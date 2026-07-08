package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.event.cubism.CubismSelectionChangedEvent;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;

public interface SelectionQueryService {

    SelectionSummary currentSelection() throws CubismServiceException;

    List<ModelObjectId> selectedIds(HierarchyNode.Kind kind) throws CubismServiceException;

    Registration onSelectionChanged(SelectionChangedListener listener) throws CubismServiceException;

    @FunctionalInterface
    interface SelectionChangedListener {
        void selectionChanged(CubismSelectionChangedEvent event);
    }
}
