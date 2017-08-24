package com.yahoo.vespa.model.content;

import com.yahoo.documentmodel.NewDocumentType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class that sorts a list of document types in topological order
 * according to the document references between the types.
 *
 * Document types without any outgoing document references are considered
 * to be first in the topological order.
 *
 * @author geirst
 */
public class TopologicalDocumentTypeSorter {

    private final Map<String, NewDocumentType> unsortedTypes = new LinkedHashMap<>();
    private final Map<String, NewDocumentType> sortedTypes = new LinkedHashMap<>();

    private TopologicalDocumentTypeSorter(Collection<NewDocumentType> documentTypes) {
        documentTypes.stream().forEach(docType -> unsortedTypes.put(docType.getName(), docType));
        unsortedTypes.values().stream().forEach(docType -> depthFirstTraverse(docType));
    }

    private void depthFirstTraverse(NewDocumentType docType) {
        // Note that cycles are not allowed and detected earlier in DocumentGraphValidator.
        if (sortedTypes.containsKey(docType.getName())) {
            return;
        }
        for (NewDocumentType.Name referenceDocTypeName : docType.getDocumentReferences()) {
            NewDocumentType referenceDocType = unsortedTypes.get(referenceDocTypeName.getName());
            assert (referenceDocType != null);
            depthFirstTraverse(referenceDocType);
        }
        sortedTypes.put(docType.getName(), docType);
    }

    public static List<NewDocumentType> sort(Collection<NewDocumentType> documentTypes) {
        TopologicalDocumentTypeSorter sorter = new TopologicalDocumentTypeSorter(documentTypes);
        return sorter.sortedTypes.values().stream().collect(Collectors.toList());
    }
}
