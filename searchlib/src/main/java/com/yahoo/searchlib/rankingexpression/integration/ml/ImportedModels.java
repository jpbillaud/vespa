// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression.integration.ml;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.yahoo.path.Path;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * All models imported from the models/ directory in the application package.
 * If this is empty it may be due to either not having any models in the application package,
 * or this being created for a ZooKeeper application package, which does not have imported models.
 *
 * @author bratseth
 */
public class ImportedModels {

    /** All imported models, indexed by their names */
    private final ImmutableMap<String, ImportedModel> importedModels;

    private static final ImmutableList<ModelImporter> importers =
            ImmutableList.of(new TensorFlowImporter(), new OnnxImporter(), new XGBoostImporter());

    /** Create a null imported models */
    public ImportedModels() {
        importedModels = ImmutableMap.of();
    }

    public ImportedModels(File modelsDirectory) {
        Map<String, ImportedModel> models = new HashMap<>();

        // Find all subdirectories recursively which contains a model we can read
        importRecursively(modelsDirectory, models);
        importedModels = ImmutableMap.copyOf(models);
    }

    private static void importRecursively(File dir, Map<String, ImportedModel> models) {
        if ( ! dir.isDirectory()) return;

        Arrays.stream(dir.listFiles()).sorted().forEach(child -> {
            Optional<ModelImporter> importer = findImporterOf(child);
            if (importer.isPresent()) {
                String name = toName(child);
                ImportedModel existing = models.get(name);
                if (existing != null)
                    throw new IllegalArgumentException("The models in " + child + " and " + existing.source() +
                                                       " both resolve to the model name '" + name + "'");
                models.put(name, importer.get().importModel(name, child));
            }
            else {
                importRecursively(child, models);
            }
        });
    }

    private static Optional<ModelImporter> findImporterOf(File path) {
        return importers.stream().filter(item -> item.canImport(path.toString())).findFirst();
    }

    /**
     * Returns the model at the given location in the application package.
     *
     * @param modelPath the path to this model (file or directory, depending on model type)
     *                  under the application package, both from the root or relative to the
     *                  models directory works
     * @return the model at this path or null if none
     */
    public ImportedModel get(File modelPath) {
        return importedModels.get(toName(modelPath));
    }

    public ImportedModel get(String modelName) {
        return importedModels.get(modelName);
    }

    /** Returns an immutable collection of all the imported models */
    public Collection<ImportedModel> all() {
        return importedModels.values();
    }

    private static String toName(File modelFile) {
        Path modelPath = Path.fromString(modelFile.toString());
        if (modelFile.isFile())
            modelPath = stripFileEnding(modelPath);
        String localPath = concatenateAfterModelsDirectory(modelPath);
        return localPath.replace('.', '_');
    }

    private static Path stripFileEnding(Path path) {
        int dotIndex = path.last().lastIndexOf(".");
        if (dotIndex <= 0) return path;
        return path.withLast(path.last().substring(0, dotIndex));
    }

    private static String concatenateAfterModelsDirectory(Path path) {
        boolean afterModels = false;
        StringBuilder result = new StringBuilder();
        for (String element : path.elements()) {
            if (afterModels) result.append(element).append("_");
            if (element.equals("models")) afterModels = true;
        }
        return result.substring(0, result.length()-1);
    }

}
