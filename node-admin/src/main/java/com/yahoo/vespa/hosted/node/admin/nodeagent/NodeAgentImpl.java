// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.node.admin.nodeagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yahoo.concurrent.ThreadFactoryFactory;
import com.yahoo.log.LogLevel;
import com.yahoo.vespa.hosted.dockerapi.Container;
import com.yahoo.vespa.hosted.dockerapi.ContainerResources;
import com.yahoo.vespa.hosted.dockerapi.ContainerStats;
import com.yahoo.vespa.hosted.dockerapi.exception.ContainerNotFoundException;
import com.yahoo.vespa.hosted.dockerapi.exception.DockerException;
import com.yahoo.vespa.hosted.dockerapi.exception.DockerExecTimeoutException;
import com.yahoo.vespa.hosted.dockerapi.DockerImage;
import com.yahoo.vespa.hosted.dockerapi.ProcessResult;
import com.yahoo.vespa.hosted.dockerapi.metrics.DimensionMetrics;
import com.yahoo.vespa.hosted.dockerapi.metrics.Dimensions;
import com.yahoo.vespa.hosted.dockerapi.metrics.MetricReceiverWrapper;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeSpec;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeAttributes;
import com.yahoo.vespa.hosted.node.admin.docker.DockerOperations;
import com.yahoo.vespa.hosted.node.admin.maintenance.StorageMaintainer;
import com.yahoo.vespa.hosted.node.admin.configserver.noderepository.NodeRepository;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.Orchestrator;
import com.yahoo.vespa.hosted.node.admin.configserver.orchestrator.OrchestratorException;
import com.yahoo.vespa.hosted.node.admin.maintenance.acl.AclMaintainer;
import com.yahoo.vespa.hosted.node.admin.maintenance.identity.AthenzCredentialsMaintainer;
import com.yahoo.vespa.hosted.node.admin.nodeadmin.ConvergenceException;
import com.yahoo.vespa.hosted.node.admin.util.SecretAgentCheckConfig;
import com.yahoo.vespa.hosted.provision.Node;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.ABSENT;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.STARTING;
import static com.yahoo.vespa.hosted.node.admin.nodeagent.NodeAgentImpl.ContainerState.UNKNOWN;

/**
 * @author dybis
 * @author bakksjo
 */
public class NodeAgentImpl implements NodeAgent {
    // This is used as a definition of 1 GB when comparing flavor specs in node-repo
    private static final long BYTES_IN_GB = 1_000_000_000L;

    private static final Logger logger = Logger.getLogger(NodeAgentImpl.class.getName());

    private final Object monitor = new Object();
    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private boolean isFrozen = true;
    private boolean wantFrozen = false;
    private boolean workToDoNow = true;
    private boolean expectNodeNotInNodeRepo = false;
    private boolean hasResumedNode = false;
    private boolean hasStartedServices = true;

    private final NodeAgentContext context;
    private final NodeRepository nodeRepository;
    private final Orchestrator orchestrator;
    private final DockerOperations dockerOperations;
    private final StorageMaintainer storageMaintainer;
    private final Clock clock;
    private final Duration timeBetweenEachConverge;
    private final Optional<AthenzCredentialsMaintainer> athenzCredentialsMaintainer;
    private final Optional<AclMaintainer> aclMaintainer;
    private final Optional<HealthChecker> healthChecker;

    private int numberOfUnhandledException = 0;
    private DockerImage imageBeingDownloaded = null;
    private Instant lastConverge;

    private long currentRebootGeneration = 0;
    private Optional<Long> currentRestartGeneration = Optional.empty();

    private final Thread loopThread;
    private final ScheduledExecutorService filebeatRestarter =
            Executors.newScheduledThreadPool(1, ThreadFactoryFactory.getDaemonThreadFactory("filebeatrestarter"));
    private final Consumer<String> serviceRestarter;
    private Optional<Future<?>> currentFilebeatRestarter = Optional.empty();


    /**
     * ABSENT means container is definitely absent - A container that was absent will not suddenly appear without
     * NodeAgent explicitly starting it.
     * STARTING state is set just before we attempt to start a container, if successful we move to the next state.
     * Otherwise we can't be certain. A container that was running a minute ago may no longer be running without
     * NodeAgent doing anything (container could have crashed). Therefore we always have to ask docker daemon
     * to get updated state of the container.
     */
    enum ContainerState {
        ABSENT,
        STARTING,
        UNKNOWN
    }

    private ContainerState containerState = UNKNOWN;

    private NodeSpec lastNode = null;
    private CpuUsageReporter lastCpuMetric = new CpuUsageReporter();

    // Created in NodeAdminImpl
    public NodeAgentImpl(
            final NodeAgentContext context,
            final NodeRepository nodeRepository,
            final Orchestrator orchestrator,
            final DockerOperations dockerOperations,
            final StorageMaintainer storageMaintainer,
            final Clock clock,
            final Duration timeBetweenEachConverge,
            final Optional<AthenzCredentialsMaintainer> athenzCredentialsMaintainer,
            final Optional<AclMaintainer> aclMaintainer,
            final Optional<HealthChecker> healthChecker) {
        this.context = context;
        this.nodeRepository = nodeRepository;
        this.orchestrator = orchestrator;
        this.dockerOperations = dockerOperations;
        this.storageMaintainer = storageMaintainer;
        this.clock = clock;
        this.timeBetweenEachConverge = timeBetweenEachConverge;
        this.lastConverge = clock.instant();
        this.athenzCredentialsMaintainer = athenzCredentialsMaintainer;
        this.aclMaintainer = aclMaintainer;
        this.healthChecker = healthChecker;

        this.loopThread = new Thread(() -> {
            try {
                while (!terminated.get()) tick();
            } catch (Throwable t) {
                numberOfUnhandledException++;
                context.log(logger, LogLevel.ERROR, "Unhandled throwable, ignoring", t);
            }
        });
        this.loopThread.setName("tick-" + context.hostname());

        this.serviceRestarter = service -> {
            try {
                ProcessResult processResult = dockerOperations.executeCommandInContainerAsRoot(
                        context, "service", service, "restart");

                if (!processResult.isSuccess()) {
                    context.log(logger, LogLevel.ERROR, "Failed to restart service " + service + ": " + processResult);
                }
            } catch (Exception e) {
                context.log(logger, LogLevel.ERROR, "Failed to restart service " + service, e);
            }
        };
    }

    @Override
    public boolean setFrozen(boolean frozen) {
        synchronized (monitor) {
            if (wantFrozen != frozen) {
                wantFrozen = frozen;
                context.log(logger, LogLevel.DEBUG, wantFrozen ? "Freezing" : "Unfreezing");
                signalWorkToBeDone();
            }

            return isFrozen == frozen;
        }
    }

    @Override
    public void start() {
        context.log(logger, "Starting with interval " + timeBetweenEachConverge.toMillis() + " ms");
        loopThread.start();
    }

    @Override
    public void stop() {
        filebeatRestarter.shutdown();
        if (!terminated.compareAndSet(false, true)) {
            throw new RuntimeException("Can not re-stop a node agent.");
        }
        signalWorkToBeDone();

        do {
            try {
                loopThread.join();
                filebeatRestarter.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                context.log(logger, LogLevel.ERROR,
                        "Interrupted while waiting for converge thread and filebeatRestarter scheduler to shutdown");
            }
        } while (loopThread.isAlive() || !filebeatRestarter.isTerminated());

        context.log(logger, "Stopped");
    }

    void startServicesIfNeeded() {
        if (!hasStartedServices) {
            context.log(logger, "Starting services");
            dockerOperations.startServices(context);
            hasStartedServices = true;
        }
    }

    void resumeNodeIfNeeded(NodeSpec node) {
        if (!hasResumedNode) {
            if (!currentFilebeatRestarter.isPresent()) {
                storageMaintainer.writeMetricsConfig(context, node);
                currentFilebeatRestarter = Optional.of(filebeatRestarter.scheduleWithFixedDelay(
                        () -> serviceRestarter.accept("filebeat"), 1, 1, TimeUnit.DAYS));
            }

            context.log(logger, LogLevel.DEBUG, "Starting optional node program resume command");
            dockerOperations.resumeNode(context);
            hasResumedNode = true;
        }
    }

    private void updateNodeRepoWithCurrentAttributes(final NodeSpec node) {
        final NodeAttributes currentNodeAttributes = new NodeAttributes();
        final NodeAttributes newNodeAttributes = new NodeAttributes();

        if (node.getWantedRestartGeneration().isPresent() &&
                !Objects.equals(node.getCurrentRestartGeneration(), currentRestartGeneration)) {
            currentNodeAttributes.withRestartGeneration(node.getCurrentRestartGeneration());
            newNodeAttributes.withRestartGeneration(currentRestartGeneration);
        }

        if (!Objects.equals(node.getCurrentRebootGeneration(), currentRebootGeneration)) {
            currentNodeAttributes.withRebootGeneration(node.getCurrentRebootGeneration());
            newNodeAttributes.withRebootGeneration(currentRebootGeneration);
        }

        Optional<DockerImage> actualDockerImage = node.getWantedDockerImage().filter(n -> containerState == UNKNOWN);
        if (!Objects.equals(node.getCurrentDockerImage(), actualDockerImage)) {
            currentNodeAttributes.withDockerImage(node.getCurrentDockerImage().orElse(new DockerImage("")));
            newNodeAttributes.withDockerImage(actualDockerImage.orElse(new DockerImage("")));
        }

        publishStateToNodeRepoIfChanged(currentNodeAttributes, newNodeAttributes);
    }

    private void publishStateToNodeRepoIfChanged(NodeAttributes currentAttributes, NodeAttributes newAttributes) {
        if (!currentAttributes.equals(newAttributes)) {
            context.log(logger, "Publishing new set of attributes to node repo: %s -> %s",
                    currentAttributes, newAttributes);
            nodeRepository.updateNodeAttributes(context.hostname().value(), newAttributes);
        }
    }

    private void startContainer(NodeSpec node) {
        ContainerData containerData = createContainerData(context, node);
        dockerOperations.createContainer(context, node, containerData);
        dockerOperations.startContainer(context);
        lastCpuMetric = new CpuUsageReporter();

        hasStartedServices = true; // Automatically started with the container
        hasResumedNode = false;
        context.log(logger, "Container successfully started, new containerState is " + containerState);
    }

    private Optional<Container> removeContainerIfNeededUpdateContainerState(NodeSpec node, Optional<Container> existingContainer) {
        return existingContainer
                .flatMap(container -> removeContainerIfNeeded(node, container))
                .map(container -> {
                        shouldRestartServices(node).ifPresent(restartReason -> {
                            context.log(logger, "Will restart services: " + restartReason);
                            restartServices(node, container);
                            currentRestartGeneration = node.getWantedRestartGeneration();
                        });
                        return container;
                });
    }

    private Optional<String> shouldRestartServices(NodeSpec node) {
        if (!node.getWantedRestartGeneration().isPresent()) return Optional.empty();

        // Restart generation is only optional because it does not exist for unallocated nodes
        if (currentRestartGeneration.get() < node.getWantedRestartGeneration().get()) {
            return Optional.of("Restart requested - wanted restart generation has been bumped: "
                    + currentRestartGeneration.get() + " -> " + node.getWantedRestartGeneration().get());
        }
        return Optional.empty();
    }

    private void restartServices(NodeSpec node, Container existingContainer) {
        if (existingContainer.state.isRunning() && node.getState() == Node.State.active) {
            context.log(logger, "Restarting services");
            // Since we are restarting the services we need to suspend the node.
            orchestratorSuspendNode();
            dockerOperations.restartVespa(context);
        }
    }

    @Override
    public void stopServices() {
        context.log(logger, "Stopping services");
        if (containerState == ABSENT) return;
        try {
            hasStartedServices = hasResumedNode = false;
            dockerOperations.stopServices(context);
        } catch (ContainerNotFoundException e) {
            containerState = ABSENT;
        }
    }

    @Override
    public void suspend() {
        context.log(logger, "Suspending services on node");
        if (containerState == ABSENT) return;
        try {
            hasResumedNode = false;
            dockerOperations.suspendNode(context);
        } catch (ContainerNotFoundException e) {
            containerState = ABSENT;
        } catch (RuntimeException e) {
            // It's bad to continue as-if nothing happened, but on the other hand if we do not proceed to
            // remove container, we will not be able to upgrade to fix any problems in the suspend logic!
            context.log(logger, LogLevel.WARNING, "Failed trying to suspend container", e);
        }
    }

    private Optional<String> shouldRemoveContainer(NodeSpec node, Container existingContainer) {
        final Node.State nodeState = node.getState();
        if (nodeState == Node.State.dirty || nodeState == Node.State.provisioned) {
            return Optional.of("Node in state " + nodeState + ", container should no longer be running");
        }
        if (node.getWantedDockerImage().isPresent() && !node.getWantedDockerImage().get().equals(existingContainer.image)) {
            return Optional.of("The node is supposed to run a new Docker image: "
                    + existingContainer.image.asString() + " -> " + node.getWantedDockerImage().get().asString());
        }
        if (!existingContainer.state.isRunning()) {
            return Optional.of("Container no longer running");
        }

        ContainerResources wantedContainerResources = ContainerResources.from(
                node.getMinCpuCores(), node.getMinMainMemoryAvailableGb());
        if (!wantedContainerResources.equals(existingContainer.resources)) {
            return Optional.of("Container should be running with different resource allocation, wanted: " +
                    wantedContainerResources + ", actual: " + existingContainer.resources);
        }

        if (currentRebootGeneration < node.getWantedRebootGeneration()) {
            return Optional.of(String.format("Container reboot wanted. Current: %d, Wanted: %d",
                    currentRebootGeneration, node.getWantedRebootGeneration()));
        }

        if (containerState == STARTING) return Optional.of("Container failed to start");
        return Optional.empty();
    }

    private Optional<Container> removeContainerIfNeeded(NodeSpec node, Container existingContainer) {
        Optional<String> removeReason = shouldRemoveContainer(node, existingContainer);
        if (removeReason.isPresent()) {
            context.log(logger, "Will remove container: " + removeReason.get());

            if (existingContainer.state.isRunning()) {
                if (node.getState() == Node.State.active) {
                    orchestratorSuspendNode();
                }

                try {
                    if (node.getState() != Node.State.dirty) {
                        suspend();
                    }
                    stopServices();
                } catch (Exception e) {
                    context.log(logger, LogLevel.WARNING, "Failed stopping services, ignoring", e);
                }
            }
            stopFilebeatSchedulerIfNeeded();
            storageMaintainer.handleCoreDumpsForContainer(context, node, Optional.of(existingContainer));
            dockerOperations.removeContainer(context, existingContainer);
            currentRebootGeneration = node.getWantedRebootGeneration();
            containerState = ABSENT;
            context.log(logger, "Container successfully removed, new containerState is " + containerState);
            return Optional.empty();
        }
        return Optional.of(existingContainer);
    }


    private void scheduleDownLoadIfNeeded(NodeSpec node) {
        if (node.getCurrentDockerImage().equals(node.getWantedDockerImage())) return;

        if (dockerOperations.pullImageAsyncIfNeeded(node.getWantedDockerImage().get())) {
            imageBeingDownloaded = node.getWantedDockerImage().get();
        } else if (imageBeingDownloaded != null) { // Image was downloading, but now it's ready
            imageBeingDownloaded = null;
        }
    }

    private void signalWorkToBeDone() {
        synchronized (monitor) {
            if (!workToDoNow) {
                workToDoNow = true;
                context.log(logger, LogLevel.DEBUG, "Signaling work to be done");
                monitor.notifyAll();
            }
        }
    }

    void tick() {
        boolean isFrozenCopy;
        synchronized (monitor) {
            while (!workToDoNow) {
                long remainder = timeBetweenEachConverge
                        .minus(Duration.between(lastConverge, clock.instant()))
                        .toMillis();
                if (remainder > 0) {
                    try {
                        monitor.wait(remainder);
                    } catch (InterruptedException e) {
                        context.log(logger, LogLevel.ERROR, "Interrupted while sleeping before tick, ignoring");
                    }
                } else break;
            }
            lastConverge = clock.instant();
            workToDoNow = false;

            if (isFrozen != wantFrozen) {
                isFrozen = wantFrozen;
                context.log(logger, "Updated NodeAgent's frozen state, new value: isFrozen: " + isFrozen);
            }
            isFrozenCopy = isFrozen;
        }

        if (isFrozenCopy) {
            context.log(logger, LogLevel.DEBUG, "tick: isFrozen");
        } else {
            try {
                converge();
            } catch (OrchestratorException | ConvergenceException e) {
                context.log(logger, e.getMessage());
            } catch (ContainerNotFoundException e) {
                containerState = ABSENT;
                context.log(logger, LogLevel.WARNING, "Container unexpectedly gone, resetting containerState to " + containerState);
            } catch (DockerException e) {
                numberOfUnhandledException++;
                context.log(logger, LogLevel.ERROR, "Caught a DockerException", e);
            } catch (Exception e) {
                numberOfUnhandledException++;
                context.log(logger, LogLevel.ERROR, "Unhandled exception, ignoring.", e);
            }
        }
    }

    // Public for testing
    void converge() {
        final Optional<NodeSpec> optionalNode = nodeRepository.getOptionalNode(context.hostname().value());

        // We just removed the node from node repo, so this is expected until NodeAdmin stop this NodeAgent
        if (!optionalNode.isPresent() && expectNodeNotInNodeRepo) {
            context.log(logger, LogLevel.INFO, "Node removed from node repo (as expected)");
            return;
        }

        final NodeSpec node = optionalNode.orElseThrow(() ->
                new IllegalStateException(String.format("Node '%s' missing from node repository", context.hostname())));
        expectNodeNotInNodeRepo = false;

        Optional<Container> container = getContainer();
        if (!node.equals(lastNode)) {
            logChangesToNodeSpec(lastNode, node);

            // Current reboot generation uninitialized or incremented from outside to cancel reboot
            if (currentRebootGeneration < node.getCurrentRebootGeneration())
                currentRebootGeneration = node.getCurrentRebootGeneration();

            // Either we have changed allocation status (restart gen. only available to allocated nodes), or
            // restart generation has been incremented from outside to cancel restart
            if (currentRestartGeneration.isPresent() != node.getCurrentRestartGeneration().isPresent() ||
                    currentRestartGeneration.map(current -> current < node.getCurrentRestartGeneration().get()).orElse(false))
                currentRestartGeneration = node.getCurrentRestartGeneration();

            // Every time the node spec changes, we should clear the metrics for this container as the dimensions
            // will change and we will be reporting duplicate metrics.
            if (container.map(c -> c.state.isRunning()).orElse(false)) {
                storageMaintainer.writeMetricsConfig(context, node);
            }

            lastNode = node;
        }

        switch (node.getState()) {
            case ready:
            case reserved:
            case parked:
            case failed:
                removeContainerIfNeededUpdateContainerState(node, container);
                updateNodeRepoWithCurrentAttributes(node);
                break;
            case active:
                storageMaintainer.handleCoreDumpsForContainer(context, node, container);

                storageMaintainer.getDiskUsageFor(context)
                        .map(diskUsage -> (double) diskUsage / BYTES_IN_GB / node.getMinDiskAvailableGb())
                        .filter(diskUtil -> diskUtil >= 0.8)
                        .ifPresent(diskUtil -> storageMaintainer.removeOldFilesFromNode(context));

                scheduleDownLoadIfNeeded(node);
                if (isDownloadingImage()) {
                    context.log(logger, LogLevel.DEBUG, "Waiting for image to download " + imageBeingDownloaded.asString());
                    return;
                }
                container = removeContainerIfNeededUpdateContainerState(node, container);
                athenzCredentialsMaintainer.ifPresent(maintainer -> maintainer.converge(context));
                if (! container.isPresent()) {
                    containerState = STARTING;
                    startContainer(node);
                    containerState = UNKNOWN;
                    aclMaintainer.ifPresent(AclMaintainer::converge);
                }

                startServicesIfNeeded();
                resumeNodeIfNeeded(node);
                healthChecker.ifPresent(checker -> checker.verifyHealth(context));

                // Because it's more important to stop a bad release from rolling out in prod,
                // we put the resume call last. So if we fail after updating the node repo attributes
                // but before resume, the app may go through the tenant pipeline but will halt in prod.
                //
                // Note that this problem exists only because there are 2 different mechanisms
                // that should really be parts of a single mechanism:
                //  - The content of node repo is used to determine whether a new Vespa+application
                //    has been successfully rolled out.
                //  - Slobrok and internal orchestrator state is used to determine whether
                //    to allow upgrade (suspend).
                updateNodeRepoWithCurrentAttributes(node);
                context.log(logger, "Call resume against Orchestrator");
                orchestrator.resume(context.hostname().value());
                break;
            case inactive:
                removeContainerIfNeededUpdateContainerState(node, container);
                updateNodeRepoWithCurrentAttributes(node);
                break;
            case provisioned:
                nodeRepository.setNodeState(context.hostname().value(), Node.State.dirty);
                break;
            case dirty:
                removeContainerIfNeededUpdateContainerState(node, container);
                context.log(logger, "State is " + node.getState() + ", will delete application storage and mark node as ready");
                athenzCredentialsMaintainer.ifPresent(maintainer -> maintainer.clearCredentials(context));
                storageMaintainer.archiveNodeStorage(context);
                updateNodeRepoWithCurrentAttributes(node);
                nodeRepository.setNodeState(context.hostname().value(), Node.State.ready);
                expectNodeNotInNodeRepo = true;
                break;
            default:
                throw new RuntimeException("UNKNOWN STATE " + node.getState().name());
        }
    }

    private void logChangesToNodeSpec(NodeSpec lastNode, NodeSpec node) {
        StringBuilder builder = new StringBuilder();
        appendIfDifferent(builder, "state", lastNode, node, NodeSpec::getState);
        if (builder.length() > 0) {
            context.log(logger, LogLevel.INFO, "Changes to node: " + builder.toString());
        }
    }

    private static <T> String fieldDescription(T value) {
        return value == null ? "[absent]" : value.toString();
    }

    private <T> void appendIfDifferent(StringBuilder builder, String name, NodeSpec oldNode, NodeSpec newNode, Function<NodeSpec, T> getter) {
        T oldValue = oldNode == null ? null : getter.apply(oldNode);
        T newValue = getter.apply(newNode);
        if (!Objects.equals(oldValue, newValue)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(name).append(" ").append(fieldDescription(oldValue)).append(" -> ").append(fieldDescription(newValue));
        }
    }

    private void stopFilebeatSchedulerIfNeeded() {
        if (currentFilebeatRestarter.isPresent()) {
            currentFilebeatRestarter.get().cancel(true);
            currentFilebeatRestarter = Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public void updateContainerNodeMetrics() {
        final NodeSpec node = lastNode;
        if (node == null || containerState != UNKNOWN) return;

        Optional<ContainerStats> containerStats = dockerOperations.getContainerStats(context);
        if (!containerStats.isPresent()) return;

        Dimensions.Builder dimensionsBuilder = new Dimensions.Builder()
                .add("host", context.hostname().value())
                .add("role", SecretAgentCheckConfig.nodeTypeToRole(context.nodeType()))
                .add("state", node.getState().toString());
        node.getParentHostname().ifPresent(parent -> dimensionsBuilder.add("parentHostname", parent));
        node.getAllowedToBeDown().ifPresent(allowed ->
                dimensionsBuilder.add("orchestratorState", allowed ? "ALLOWED_TO_BE_DOWN" : "NO_REMARKS"));
        Dimensions dimensions = dimensionsBuilder.build();

        ContainerStats stats = containerStats.get();
        final String APP = MetricReceiverWrapper.APPLICATION_NODE;
        final int totalNumCpuCores = ((List<Number>) ((Map) stats.getCpuStats().get("cpu_usage")).get("percpu_usage")).size();
        final long cpuContainerKernelTime = ((Number) ((Map) stats.getCpuStats().get("cpu_usage")).get("usage_in_kernelmode")).longValue();
        final long cpuContainerTotalTime = ((Number) ((Map) stats.getCpuStats().get("cpu_usage")).get("total_usage")).longValue();
        final long cpuSystemTotalTime = ((Number) stats.getCpuStats().get("system_cpu_usage")).longValue();
        final long memoryTotalBytes = ((Number) stats.getMemoryStats().get("limit")).longValue();
        final long memoryTotalBytesUsage = ((Number) stats.getMemoryStats().get("usage")).longValue();
        final long memoryTotalBytesCache = ((Number) ((Map) stats.getMemoryStats().get("stats")).get("cache")).longValue();
        final long diskTotalBytes = (long) (node.getMinDiskAvailableGb() * BYTES_IN_GB);
        final Optional<Long> diskTotalBytesUsed = storageMaintainer.getDiskUsageFor(context);

        lastCpuMetric.updateCpuDeltas(cpuSystemTotalTime, cpuContainerTotalTime, cpuContainerKernelTime);

        // Ratio of CPU cores allocated to this container to total number of CPU cores on this host
        final double allocatedCpuRatio = node.getMinCpuCores() / totalNumCpuCores;
        double cpuUsageRatioOfAllocated = lastCpuMetric.getCpuUsageRatio() / allocatedCpuRatio;
        double cpuKernelUsageRatioOfAllocated = lastCpuMetric.getCpuKernelUsageRatio() / allocatedCpuRatio;

        long memoryTotalBytesUsed = memoryTotalBytesUsage - memoryTotalBytesCache;
        double memoryUsageRatio = (double) memoryTotalBytesUsed / memoryTotalBytes;
        double memoryTotalUsageRatio = (double) memoryTotalBytesUsage / memoryTotalBytes;
        Optional<Double> diskUsageRatio = diskTotalBytesUsed.map(used -> (double) used / diskTotalBytes);

        List<DimensionMetrics> metrics = new ArrayList<>();
        DimensionMetrics.Builder systemMetricsBuilder = new DimensionMetrics.Builder(APP, dimensions)
                .withMetric("mem.limit", memoryTotalBytes)
                .withMetric("mem.used", memoryTotalBytesUsed)
                .withMetric("mem.util", 100 * memoryUsageRatio)
                .withMetric("mem_total.used", memoryTotalBytesUsage)
                .withMetric("mem_total.util", 100 * memoryTotalUsageRatio)
                .withMetric("cpu.util", 100 * cpuUsageRatioOfAllocated)
                .withMetric("cpu.sys.util", 100 * cpuKernelUsageRatioOfAllocated)
                .withMetric("disk.limit", diskTotalBytes);

        diskTotalBytesUsed.ifPresent(diskUsed -> systemMetricsBuilder.withMetric("disk.used", diskUsed));
        diskUsageRatio.ifPresent(diskRatio -> systemMetricsBuilder.withMetric("disk.util", 100 * diskRatio));
        metrics.add(systemMetricsBuilder.build());

        stats.getNetworks().forEach((interfaceName, interfaceStats) -> {
            Dimensions netDims = dimensionsBuilder.add("interface", interfaceName).build();
            Map<String, Number> infStats = (Map<String, Number>) interfaceStats;
            DimensionMetrics networkMetrics = new DimensionMetrics.Builder(APP, netDims)
                    .withMetric("net.in.bytes", infStats.get("rx_bytes").longValue())
                    .withMetric("net.in.errors", infStats.get("rx_errors").longValue())
                    .withMetric("net.in.dropped", infStats.get("rx_dropped").longValue())
                    .withMetric("net.out.bytes", infStats.get("tx_bytes").longValue())
                    .withMetric("net.out.errors", infStats.get("tx_errors").longValue())
                    .withMetric("net.out.dropped", infStats.get("tx_dropped").longValue())
                    .build();
            metrics.add(networkMetrics);
        });

        pushMetricsToContainer(metrics);
    }

    private void pushMetricsToContainer(List<DimensionMetrics> metrics) {
        StringBuilder params = new StringBuilder();
        try {
            for (DimensionMetrics dimensionMetrics : metrics) {
                params.append(dimensionMetrics.toSecretAgentReport());
            }
            String wrappedMetrics = "s:" + params.toString();

            // Push metrics to the metrics proxy in each container
            String[] command = {"vespa-rpc-invoke",  "-t", "2",  "tcp/localhost:19091",  "setExtraMetrics", wrappedMetrics};
            dockerOperations.executeCommandInContainerAsRoot(context, 5L, command);
        } catch (DockerExecTimeoutException | JsonProcessingException  e) {
            context.log(logger, LogLevel.WARNING, "Failed to push metrics to container", e);
        }
    }

    private Optional<Container> getContainer() {
        if (containerState == ABSENT) return Optional.empty();
        Optional<Container> container = dockerOperations.getContainer(context);
        if (! container.isPresent()) containerState = ABSENT;
        return container;
    }

    @Override
    public boolean isDownloadingImage() {
        return imageBeingDownloaded != null;
    }

    @Override
    public int getAndResetNumberOfUnhandledExceptions() {
        int temp = numberOfUnhandledException;
        numberOfUnhandledException = 0;
        return temp;
    }

    class CpuUsageReporter {
        private long containerKernelUsage = 0;
        private long totalContainerUsage = 0;
        private long totalSystemUsage = 0;

        private long deltaContainerKernelUsage;
        private long deltaContainerUsage;
        private long deltaSystemUsage;

        private void updateCpuDeltas(long totalSystemUsage, long totalContainerUsage, long containerKernelUsage) {
            deltaSystemUsage = this.totalSystemUsage == 0 ? 0 : (totalSystemUsage - this.totalSystemUsage);
            deltaContainerUsage = totalContainerUsage - this.totalContainerUsage;
            deltaContainerKernelUsage = containerKernelUsage - this.containerKernelUsage;

            this.totalSystemUsage = totalSystemUsage;
            this.totalContainerUsage = totalContainerUsage;
            this.containerKernelUsage = containerKernelUsage;
        }

        /**
         * Returns the CPU usage ratio for the docker container that this NodeAgent is managing
         * in the time between the last two times updateCpuDeltas() was called. This is calculated
         * by dividing the CPU time used by the container with the CPU time used by the entire system.
         */
        double getCpuUsageRatio() {
            return deltaSystemUsage == 0 ? Double.NaN : (double) deltaContainerUsage / deltaSystemUsage;
        }

        double getCpuKernelUsageRatio() {
            return deltaSystemUsage == 0 ? Double.NaN : (double) deltaContainerKernelUsage / deltaSystemUsage;
        }
    }

    // TODO: Also skip orchestration if we're downgrading in test/staging
    // How to implement:
    //  - test/staging: We need to figure out whether we're in test/staging, zone is available in Environment
    //  - downgrading: Impossible to know unless we look at the hosted version, which is
    //    not available in the docker image (nor its name). Not sure how to solve this. Should
    //    the node repo return the hosted version or a downgrade bit in addition to
    //    wanted docker image etc?
    // Should the tenant pipeline instead use BCP tool to upgrade faster!?
    //
    // More generally, the node repo response should contain sufficient info on what the docker image is,
    // to allow the node admin to make decisions that depend on the docker image. Or, each docker image
    // needs to contain routines for drain and suspend. For many images, these can just be dummy routines.
    private void orchestratorSuspendNode() {
        context.log(logger, "Ask Orchestrator for permission to suspend node");
        orchestrator.suspend(context.hostname().value());
    }

    protected ContainerData createContainerData(NodeAgentContext context, NodeSpec node) {
        return (pathInContainer, data) -> {
            throw new UnsupportedOperationException("addFile not implemented");
        };
    }
}
