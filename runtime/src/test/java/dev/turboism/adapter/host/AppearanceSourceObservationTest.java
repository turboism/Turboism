package dev.turboism.adapter.host;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@code observe()} on the appearance source must cost exactly one host read: before the
 * override the default observation composed the accessors and ran {@code current()} four times.
 */
class AppearanceSourceObservationTest {

    private static CubismModel model() {
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-1"); }
            @Override public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }
            @Override public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }
            @Override public void update() { }
        };
    }

    @Test
    void oneObservationReadsTheHostOnce() {
        final CountingAdapter adapter = new CountingAdapter();
        final AtomicInteger modelReads = new AtomicInteger();
        final CubismModelAccess modelAccess = () -> {
            modelReads.incrementAndGet();
            return model();
        };
        final HostSnapshotSource source =
            PluginScopedCubismModelAccess.appearanceSource(adapter, modelAccess);

        final HostSnapshotSource.Observation observation = source.observe();

        assertEquals(1, adapter.documentReads.get(),
            "one observation must read the active document once");
        assertEquals(1, modelReads.get(),
            "one observation must resolve the active model once");
        assertTrue(observation.document().isPresent());
        assertTrue(observation.model().isPresent());
    }

    @Test
    void versionOfDoesNotReadTheHostAgain() {
        final CountingAdapter adapter = new CountingAdapter();
        final CubismModelAccess modelAccess = () -> model();
        final HostSnapshotSource source =
            PluginScopedCubismModelAccess.appearanceSource(adapter, modelAccess);

        final HostSnapshotSource.Observation observation = source.observe();
        final int readsAfterObserve = adapter.documentReads.get();
        final long version = source.versionOf(observation);

        assertEquals(readsAfterObserve, adapter.documentReads.get(),
            "versionOf must answer from the observation, not a fresh read");
        assertEquals(version, source.invalidationToken(),
            "the observation version must equal the token a fresh poll would report");
    }

    private static final class CountingAdapter implements ProjectWorkspaceAdapter {
        private final AtomicInteger documentReads = new AtomicInteger();

        @Override
        public AdapterResult<Optional<ProjectSnapshot>> activeProject() {
            return AdapterResult.available(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<DocumentSnapshot>> activeDocument() {
            documentReads.incrementAndGet();
            return AdapterResult.available(Optional.of(new DocumentSnapshot(
                "document-1",
                "Model",
                "model/model.cmo3",
                Optional.empty(),
                Optional.of(new ModelSnapshot(
                    "model-1", "Model", java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), java.util.List.of()
                )),
                DocumentKind.MODEL,
                Optional.empty(),
                Optional.empty()
            )));
        }

        @Override
        public AdapterResult<Optional<WorkspaceSnapshot>> workspace() {
            return AdapterResult.available(Optional.empty());
        }

        @Override
        public AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot() {
            return AdapterResult.available(
                new ProjectWorkspaceSnapshot(Optional.empty(), Optional.empty())
            );
        }
    }
}
