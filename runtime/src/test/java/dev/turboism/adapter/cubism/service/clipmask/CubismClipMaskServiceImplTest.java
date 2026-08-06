package dev.turboism.adapter.cubism.service.clipmask;

import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismClipMaskServiceImplTest {

    @Test
    void mapsSnapshotsWithJoinedNamesAndFieldSemantics() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(
                new ClipMaskSnapshot("guid-aaaa-1", List.of("guid-bbbb-1", "guid-bbbb-2"), false),
                new ClipMaskSnapshot("guid-cccc-1", List.of("guid-bbbb-1"), true)
            ),
            List.of(
                new ArtMeshSnapshot("guid-aaaa-1", "Face", Optional.empty(), true, true),
                new ArtMeshSnapshot("guid-bbbb-1", "EyeL", Optional.empty(), true, true),
                new ArtMeshSnapshot("guid-bbbb-2", "EyeR", Optional.empty(), true, true)
            )
        );
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess());

        final List<CubismClipMaskService.ClipMaskRecord> records = service.collectClipMaskRecords();

        assertEquals(2, records.size());
        final CubismClipMaskService.ClipMaskRecord first = records.get(0);
        assertEquals("guid-aaaa-1", first.guid());
        assertEquals("guid-aaaa-1", first.id());
        assertEquals("Face", first.displayName());
        assertEquals(List.of("guid-bbbb-1", "guid-bbbb-2"), first.orderedMaskGuids());
        assertTrue(first.hasMasks());
        assertEquals(false, first.inverted());

        final CubismClipMaskService.ClipMaskRecord second = records.get(1);
        assertEquals("guid-cccc-1", second.guid());
        assertTrue(second.inverted());
        assertEquals("guid-ccc", second.displayName());
        assertEquals("", second.id());
        assertEquals("guid-bbbb-1", second.orderedMaskGuids().get(0));
    }

    @Test
    void fallsBackToShortGuidWhenMeshNameIsMissing() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("01234567-89ab-cdef", List.of(), false)),
            List.of()
        );
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess());

        final List<CubismClipMaskService.ClipMaskRecord> records = service.collectClipMaskRecords();

        assertEquals(1, records.size());
        assertEquals("01234567", records.get(0).displayName());
        assertEquals("", records.get(0).id());
    }

    @Test
    void fallsBackToShortGuidWhenMeshReadFails() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("01234567-89ab-cdef", List.of(), false)),
            List.of()
        );
        read.failMeshRead = true;
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess());

        final List<CubismClipMaskService.ClipMaskRecord> records = service.collectClipMaskRecords();

        assertEquals(1, records.size());
        assertEquals("01234567", records.get(0).displayName());
    }

    @Test
    void deduplicatesByGuidKeepingFirstOccurrence() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(
                new ClipMaskSnapshot("guid-aaaa-1", List.of("guid-bbbb-1"), false),
                new ClipMaskSnapshot("guid-aaaa-1", List.of("guid-bbbb-2"), true)
            ),
            List.of(
                new ArtMeshSnapshot("guid-aaaa-1", "First Name", Optional.empty(), true, true)
            )
        );
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess());

        final List<CubismClipMaskService.ClipMaskRecord> records = service.collectClipMaskRecords();

        assertEquals(1, records.size());
        assertEquals("First Name", records.get(0).displayName());
        assertEquals(List.of("guid-bbbb-1"), records.get(0).orderedMaskGuids());
        assertEquals(false, records.get(0).inverted());
    }

    @Test
    void emptyClipMaskListReturnsEmptyRecords() {
        final FakeCubismRead read = new FakeCubismRead(List.of(), List.of());
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess());

        assertEquals(List.of(), service.collectClipMaskRecords());
    }

    @Test
    void drawableIndexProvidesDisplayNameBeforeMeshJoin() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("guid-aaaa-1", List.of(), false)),
            List.of(new ArtMeshSnapshot("guid-aaaa-1", "Face", Optional.empty(), true, true)));
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(
            read, modelAccess(new FakeDrawable("guid-aaaa-1", "网格名")));

        final CubismClipMaskService.ClipMaskRecord record = service.collectClipMaskRecords().get(0);
        assertEquals("网格名", record.displayName());
        assertEquals("guid-aaaa-1", record.id());
    }

    @Test
    void drawableGuidFailureFallsBackToMeshJoin() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("guid-aaaa-1", List.of(), false)),
            List.of(new ArtMeshSnapshot("guid-aaaa-1", "Face", Optional.empty(), true, true)));
        final FakeDrawable drawable = new FakeDrawable("guid-aaaa-1", "网格名");
        drawable.failGuid = true;
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(
            read, modelAccess(drawable));

        assertEquals("Face", service.collectClipMaskRecords().get(0).displayName());
    }

    @Test
    void drawableNameFailureFallsBackToShortGuid() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("guid-aaaa-1", List.of(), false)),
            List.of());
        final FakeDrawable drawable = new FakeDrawable("guid-aaaa-1", "网格名");
        drawable.failName = true;
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(
            read, modelAccess(drawable));

        assertEquals("guid-aaa", service.collectClipMaskRecords().get(0).displayName());
    }

    @Test
    void modelAccessFailureFallsBackToMeshJoin() {
        final FakeCubismRead read = new FakeCubismRead(
            List.of(new ClipMaskSnapshot("guid-aaaa-1", List.of(), false)),
            List.of(new ArtMeshSnapshot("guid-aaaa-1", "Face", Optional.empty(), true, true)));
        final FakeCubismModelAccess modelAccess = modelAccess();
        modelAccess.failActive = true;
        final CubismClipMaskServiceImpl service = new CubismClipMaskServiceImpl(read, modelAccess);

        assertEquals("Face", service.collectClipMaskRecords().get(0).displayName());
    }


    /** Fake read service: clip-mask and mesh data are fully controlled. */
    static final class FakeCubismRead implements CubismReadCapabilityService {
        private final List<ClipMaskSnapshot> clipMasks;
        private final List<ArtMeshSnapshot> meshes;
        private boolean failMeshRead;

        FakeCubismRead(final List<ClipMaskSnapshot> clipMasks, final List<ArtMeshSnapshot> meshes) {
            this.clipMasks = clipMasks;
            this.meshes = meshes;
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.empty();
        }

        @Override
        public Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

        @Override
        public Optional<ModelSnapshot> activeModel() {
            return Optional.empty();
        }

        @Override
        public SelectionSnapshot selection() {
            return new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Override
        public List<ParameterSnapshot> parameters() {
            return List.of();
        }

        @Override
        public List<ModelObjectSnapshot> modelObjects() {
            return List.of();
        }

        @Override
        public List<ArtMeshSnapshot> meshes() {
            if (failMeshRead) {
                throw new IllegalStateException("mesh read unavailable");
            }
            return meshes;
        }

        @Override
        public List<DeformerSnapshot> deformers() {
            return List.of();
        }

        @Override
        public List<PsdDocumentSnapshot> psdDocuments() {
            return List.of();
        }

        @Override
        public List<ClipMaskSnapshot> clipMasks() {
            return clipMasks;
        }

        @Override
        public List<TextureAtlasSnapshot> textureAtlases() {
            return List.of();
        }

        @Override
        public Optional<RenderStatusSnapshot> renderStatus() {
            return Optional.empty();
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.empty();
        }

        @Override
        public Optional<ThemeStatusSnapshot> themeStatus() {
            return Optional.empty();
        }
        }

    private static FakeCubismModelAccess modelAccess(final FakeDrawable... drawables) {
        return new FakeCubismModelAccess(List.of(drawables));
    }

    /** Fake model access: drawables fully controlled; failure knobs for the active-model read. */
    static final class FakeCubismModelAccess implements CubismModelAccess {
        private final List<Drawable> drawables;
        boolean failActive;

        FakeCubismModelAccess(final List<Drawable> drawables) {
            this.drawables = drawables;
        }

        @Override
        public CubismModel active() {
            if (failActive) {
                throw new IllegalStateException("no active model");
            }
            return new CubismModel() {
                @Override public ModelId id() { throw new UnsupportedOperationException(); }
                @Override public Parameters parameters() { throw new UnsupportedOperationException(); }
                @Override public Parts parts() { throw new UnsupportedOperationException(); }
                @Override public Drawables drawables() {
                    return new Drawables() {
                        @Override public List<Drawable> all() { return drawables; }
                        @Override public Drawable find(final ArtMeshId id) { throw new NoSuchElementException(); }
                    };
                }
                @Override public Deformers deformers() { throw new UnsupportedOperationException(); }
                @Override public Glues glues() { throw new UnsupportedOperationException(); }
                @Override public void update() { }
            };
        }
    }

    /** Fake drawable with knobs to fail guid()/name() per test. */
    static final class FakeDrawable implements Drawable {
        private final String guid;
        private final String name;
        boolean failGuid;
        boolean failName;

        FakeDrawable(final String guid, final String name) {
            this.guid = guid;
            this.name = name;
        }

        @Override public ArtMeshId id() { return new ArtMeshId(guid); }
        @Override public String guid() {
            if (failGuid) {
                throw new UnsupportedOperationException("guid unavailable");
            }
            return guid;
        }
        @Override public String name() {
            if (failName) {
                throw new UnsupportedOperationException("name unavailable");
            }
            return name;
        }
        @Override public byte constantFlag() { throw new UnsupportedOperationException(); }
        @Override public byte dynamicFlag() { throw new UnsupportedOperationException(); }
        @Override public BlendMode blendMode() { throw new UnsupportedOperationException(); }
        @Override public int textureIndex() { throw new UnsupportedOperationException(); }
        @Override public int drawOrder() { throw new UnsupportedOperationException(); }
        @Override public int renderOrder() { throw new UnsupportedOperationException(); }
        @Override public float getOpacity() { throw new UnsupportedOperationException(); }
        @Override public IntSequence masks() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexPositions() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexUvs() { throw new UnsupportedOperationException(); }
        @Override public IntSequence indices() { throw new UnsupportedOperationException(); }
        @Override public Color multiplyColor() { throw new UnsupportedOperationException(); }
        @Override public Color screenColor() { throw new UnsupportedOperationException(); }
        @Override public int parentPartIndex() { throw new UnsupportedOperationException(); }
        @Override public int parentDeformerIndex() { throw new UnsupportedOperationException(); }
        @Override public IntSequence parameters() { throw new UnsupportedOperationException(); }
    }
}
