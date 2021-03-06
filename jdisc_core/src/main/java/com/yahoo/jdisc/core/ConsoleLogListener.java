// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.core;

import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import com.yahoo.net.HostName;

/**
 * @author <a href="mailto:vikasp@yahoo-inc.com">Vikas Panwar</a>
 */
class ConsoleLogListener implements LogListener {

    public static final int DEFAULT_LOG_LEVEL = Integer.MAX_VALUE;
    private final ConsoleLogFormatter formatter;
    private final PrintStream out;
    private final int maxLevel;

    ConsoleLogListener(PrintStream out, String serviceName, String logLevel) {
        this.out = out;
        this.formatter = new ConsoleLogFormatter(getHostname(), getProcessId(), serviceName);
        this.maxLevel = parseLogLevel(logLevel);
    }

    @Override
    public void logged(LogEntry entry) {
        if (entry.getLevel() > maxLevel) {
            return;
        }
        out.println(formatter.formatEntry(entry));
    }

    public static int parseLogLevel(String logLevel) {
        if (logLevel == null || logLevel.isEmpty()) {
            return DEFAULT_LOG_LEVEL;
        }
        if (logLevel.equalsIgnoreCase("OFF")) {
            return Integer.MIN_VALUE;
        }
        if (logLevel.equalsIgnoreCase("ERROR")) {
            return 1;
        }
        if (logLevel.equalsIgnoreCase("WARNING")) {
            return 2;
        }
        if (logLevel.equalsIgnoreCase("INFO")) {
            return 3;
        }
        if (logLevel.equalsIgnoreCase("DEBUG")) {
            return 4;
        }
        if (logLevel.equalsIgnoreCase("ALL")) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.valueOf(logLevel);
        } catch (NumberFormatException e) {
            // fall through
        }
        return DEFAULT_LOG_LEVEL;
    }

    public static ConsoleLogListener newInstance() {
        return new ConsoleLogListener(System.out,
                                      System.getProperty("jdisc.logger.tag"),
                                      System.getProperty("jdisc.logger.level"));
    }

    static String getProcessId() {
        // platform independent
        String jvmName = ManagementFactory.getRuntimeMXBean().getName();
        if (jvmName != null) {
            int idx = jvmName.indexOf('@');
            if (idx > 0) {
                try {
                    return Long.toString(Long.valueOf(jvmName.substring(0, jvmName.indexOf('@'))));
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        // linux specific
        File file = new File("/proc/self");
        if (file.exists()) {
            try {
                return file.getCanonicalFile().getName();
            } catch (IOException e) {
                return null;
            }
        }

        // fallback
        return null;
    }

    static String getHostname() {
        return HostName.getLocalhost();
    }
}
