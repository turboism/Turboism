package dev.turboism.preview;

import dev.turboism.adapter.cubism.HostSnapshotSource;

import java.util.List;
import java.util.Optional;

enum EmptyHostSnapshotSource implements HostSnapshotSource {
    INSTANCE;

    private static final HostSelection EMPTY_SELECTION = new HostSelection(
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty()
    );

    @Override
    public Optional<HostProject> activeProject() {
        return Optional.empty();
    }

    @Override
    public Optional<HostDocument> activeDocument() {
        return Optional.empty();
    }

    @Override
    public Optional<HostModel> activeModel() {
        return Optional.empty();
    }

    @Override
    public HostSelection selection() {
        return EMPTY_SELECTION;
    }

    @Override
    public boolean isHostPresent() {
        return false;
    }

    @Override
    public long invalidationToken() {
        return 0L;
    }
}
