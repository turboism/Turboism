package dev.turboism.adapter.cubism.model;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeModelObjectServiceTest {

    @Test
    void mapsTypedAutomationToTheNaturalHierarchyApi() {
        final MutableModel model = new MutableModel();
        final MutablePart root = model.addPart("PartRoot", "Root", null);
        final MutableDrawable face = model.addDrawable("ArtMeshFace", "Face", root);
        final MutableWarp warp = model.addWarp("WarpFace", "Face Warp", root, grid(1, 1));
        final MutableRotation rotation = model.addRotation(
            "RotationHead", "Head Rotation", root,
            new RotationDeformerForm(0F, 0F, 0F, 1F, false, false)
        );
        final RuntimeModelObjectService service = service(model, () -> true);

        assertEquals(4, service.list().size());
        assertEquals(
            List.of(
                ModelObjectKind.PART,
                ModelObjectKind.ART_MESH,
                ModelObjectKind.WARP_DEFORMER,
                ModelObjectKind.ROTATION_DEFORMER
            ),
            service.list().stream().map(value -> value.reference().kind()).toList()
        );

        service.rename(
            new ModelObjectReference(ModelObjectKind.ART_MESH, face.id().value()),
            "Face Renamed"
        );
        assertEquals("Face Renamed", face.name());

        final var reparentedFace = service.reparent(
            new ModelObjectReference(ModelObjectKind.ART_MESH, face.id().value()),
            new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, warp.id().value()),
            -1
        );
        assertEquals(
            Optional.of(new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, warp.id().value())),
            reparentedFace.parent()
        );
        final var reparentedWarp = service.reparent(
            new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, warp.id().value()),
            new ModelObjectReference(ModelObjectKind.ROTATION_DEFORMER, rotation.id().value()),
            0
        );
        assertEquals(
            Optional.of(new ModelObjectReference(
                ModelObjectKind.ROTATION_DEFORMER,
                rotation.id().value()
            )),
            reparentedWarp.parent()
        );

        final var createdPart = service.create(new ModelObjectCreateRequest.Part(
            "Accessory",
            Optional.of(new ModelObjectReference(ModelObjectKind.PART, root.id().value()))
        ));
        assertEquals(ModelObjectKind.PART, createdPart.reference().kind());
        assertEquals(Optional.of(root.id()), model.parts.find(
            new PartId(createdPart.reference().id())
        ).parentId());

        final MutablePart accessoryGroup = model.addPart("PartAccessoryGroup", "Accessory Group", root);
        final var reparentedPart = service.reparent(
            createdPart.reference(),
            new ModelObjectReference(ModelObjectKind.PART, accessoryGroup.id().value()),
            1
        );
        assertEquals(
            Optional.of(new ModelObjectReference(ModelObjectKind.PART, accessoryGroup.id().value())),
            reparentedPart.parent()
        );
        final ModelObjectOperationException invalidPartParent = assertThrows(
            ModelObjectOperationException.class,
            () -> service.reparent(
                createdPart.reference(),
                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, warp.id().value()),
                -1
            )
        );
        assertEquals(ModelObjectOperationException.Code.INVALID_REQUEST, invalidPartParent.code());

        final ArtMeshGeometry geometry = triangle();
        final var createdMesh = service.create(new ModelObjectCreateRequest.ArtMesh(
            "Accessory Mesh",
            Optional.of(createdPart.reference()),
            geometry
        ));
        assertEquals(
            geometry,
            model.drawables.find(new ArtMeshId(createdMesh.reference().id())).geometry()
        );

        final WarpGrid requestedGrid = grid(2, 3);
        final var createdWarp = service.create(new ModelObjectCreateRequest.WarpDeformer(
            "Accessory Warp",
            Optional.of(createdPart.reference()),
            requestedGrid
        ));
        assertEquals(
            requestedGrid,
            model.warps.find(new DeformerId(createdWarp.reference().id())).grid()
        );

        final RotationDeformerForm requestedForm = new RotationDeformerForm(
            15F, 2F, 3F, 1.25F, true, false
        );
        final var createdRotation = service.create(
            new ModelObjectCreateRequest.RotationDeformer(
                "Accessory Rotation",
                Optional.of(createdPart.reference()),
                requestedForm
            )
        );
        assertEquals(
            requestedForm,
            model.rotations.find(new DeformerId(createdRotation.reference().id())).form()
        );

        assertThrows(
            ModelObjectOperationException.class,
            () -> service.delete(createdPart.reference(), ModelObjectDeletePolicy.REJECT_REFERENCED)
        );
        service.delete(createdMesh.reference(), ModelObjectDeletePolicy.REJECT_REFERENCED);
        assertFalse(model.contains(createdMesh.reference()));
        service.delete(createdPart.reference(), ModelObjectDeletePolicy.CASCADE);
        assertFalse(model.contains(createdPart.reference()));

        assertEquals(warp, model.deformers.find(warp.id()));
        assertEquals(rotation, model.deformers.find(rotation.id()));
    }

    @Test
    void rejectsUnavailableCreateProviderBeforeReadingTheActiveModelOrParent() {
        final AtomicBoolean activeRead = new AtomicBoolean();
        final RuntimeModelObjectService service = new RuntimeModelObjectService(
            new UnavailableCreateAccess(activeRead),
            PermissionChecker.allowAll(),
            () -> true
        );

        final ModelObjectOperationException failure = assertThrows(
            ModelObjectOperationException.class,
            () -> service.create(new ModelObjectCreateRequest.Part(
                "Blocked Part",
                Optional.of(new ModelObjectReference(ModelObjectKind.PART, "Missing"))
            ))
        );

        assertEquals(ModelObjectOperationException.Code.UNAVAILABLE, failure.code());
        assertFalse(activeRead.get(), "capability rejection must precede active-model and parent reads");
    }

    @Test
    void reportsCommittedCreationWhenDescriptorReadbackFails() {
        final MutableModel model = new MutableModel();
        model.failCreatedDescriptorReads = true;
        final RuntimeModelObjectService service = service(model, () -> true);

        final ModelObjectOperationException failure = assertThrows(
            ModelObjectOperationException.class,
            () -> service.create(new ModelObjectCreateRequest.Part(
                "Committed Part",
                Optional.empty()
            ))
        );

        assertEquals(ModelObjectOperationException.Code.COMMITTED, failure.code());
        assertEquals(
            Optional.of(new ModelObjectReference(ModelObjectKind.PART, "Part1")),
            failure.committedReference()
        );
        assertTrue(model.contains(new ModelObjectReference(ModelObjectKind.PART, "Part1")));
    }

    @Test
    void rejectsCallsAfterTheOwningPluginBecomesStale() {
        final MutableModel model = new MutableModel();
        final AtomicBoolean active = new AtomicBoolean(true);
        final RuntimeModelObjectService service = service(model, active::get);

        active.set(false);
        final ModelObjectOperationException failure = assertThrows(
            ModelObjectOperationException.class,
            service::list
        );
        assertEquals(ModelObjectOperationException.Code.STALE, failure.code());
    }

    private static RuntimeModelObjectService service(
        final MutableModel model,
        final java.util.function.BooleanSupplier active
    ) {
        return new RuntimeModelObjectService(
            () -> model,
            PermissionChecker.allowAll(),
            active
        );
    }

    private static ArtMeshGeometry triangle() {
        return new ArtMeshGeometry(
            List.of(new Point2(0F, 0F), new Point2(1F, 0F), new Point2(0F, 1F)),
            List.of(new Point2(0F, 0F), new Point2(1F, 0F), new Point2(0F, 1F)),
            List.of(0, 1, 2)
        );
    }

    private static WarpGrid grid(final int rows, final int columns) {
        final ArrayList<Point2> points = new ArrayList<>((rows + 1) * (columns + 1));
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column <= columns; column++) {
                points.add(new Point2(column, row));
            }
        }
        return new WarpGrid(rows, columns, false, points);
    }

    private static final class UnavailableCreateAccess implements CubismModelAccess,
        RuntimeModelObjectCreateProvider {
        private final AtomicBoolean activeRead;

        private UnavailableCreateAccess(final AtomicBoolean activeRead) {
            this.activeRead = activeRead;
        }

        @Override public CubismModel active() {
            activeRead.set(true);
            throw new AssertionError("active model must not be read");
        }

        @Override public void requireCreateSupported(final ModelObjectCreateRequest request) {
            throw new ModelObjectProviderUnavailableException(
                "Model-object creation provider is unavailable"
            );
        }

        @Override public ModelObjectReference createModelObject(
            final CubismModel model,
            final ModelObjectCreateRequest request
        ) {
            throw new AssertionError("create must not be invoked");
        }
    }

    private static final class MutableModel implements CubismModel {
        private final MutableParts parts = new MutableParts();
        private final MutableDrawables drawables = new MutableDrawables();
        private final MutableDeformers deformers = new MutableDeformers();
        private final MutableWarpDeformers warps = new MutableWarpDeformers();
        private final MutableRotationDeformers rotations = new MutableRotationDeformers();
        private int sequence;
        private boolean failCreatedDescriptorReads;

        MutablePart addPart(final String id, final String name, final MutablePart parent) {
            final MutablePart value = new MutablePart(id, name, parent);
            parts.values.add(value);
            return value;
        }

        MutableDrawable addDrawable(
            final String id,
            final String name,
            final MutablePart parent
        ) {
            final MutableDrawable value = new MutableDrawable(id, name, parent, triangle());
            drawables.values.add(value);
            return value;
        }

        MutableWarp addWarp(
            final String id,
            final String name,
            final MutablePart parent,
            final WarpGrid grid
        ) {
            final MutableWarp value = new MutableWarp(id, name, parent, grid);
            deformers.values.add(value);
            warps.values.add(value);
            return value;
        }

        MutableRotation addRotation(
            final String id,
            final String name,
            final MutablePart parent,
            final RotationDeformerForm form
        ) {
            final MutableRotation value = new MutableRotation(id, name, parent, form);
            deformers.values.add(value);
            rotations.values.add(value);
            return value;
        }

        boolean contains(final ModelObjectReference reference) {
            return switch (reference.kind()) {
                case PART -> parts.values.stream().anyMatch(value -> value.id().value().equals(reference.id()));
                case ART_MESH -> drawables.values.stream().anyMatch(value -> value.id().value().equals(reference.id()));
                case WARP_DEFORMER -> warps.values.stream().anyMatch(value -> value.id().value().equals(reference.id()));
                case ROTATION_DEFORMER -> rotations.values.stream().anyMatch(value -> value.id().value().equals(reference.id()));
            };
        }

        String next(final String prefix) {
            return prefix + (++sequence);
        }

        @Override public ModelId id() { return new ModelId("Model"); }
        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return List.of(); }
                @Override public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                    throw new NoSuchElementException(id.value());
                }
            };
        }
        @Override public Parts parts() { return parts; }
        @Override public Drawables drawables() { return drawables; }
        @Override public Deformers deformers() { return deformers; }
        @Override public WarpDeformers warpDeformers() { return warps; }
        @Override public RotationDeformers rotationDeformers() { return rotations; }
        @Override public Glues glues() {
            return new Glues() {
                @Override public List<dev.turboism.sdk.cubism.model.Glue> all() { return List.of(); }
                @Override public dev.turboism.sdk.cubism.model.Glue find(
                    final dev.turboism.sdk.cubism.model.GlueId id
                ) {
                    throw new NoSuchElementException(id.value());
                }
            };
        }
        @Override public void update() { }

        private final class MutableParts implements Parts {
            final List<MutablePart> values = new ArrayList<>();
            @Override public List<Part> all() { return List.copyOf(values); }
            @Override public Part find(final PartId id) {
                return values.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
            @Override public Part create(final String name, final Part parent, final int index) {
                final MutablePart created = addPart(next("Part"), name, (MutablePart) parent);
                created.failDescriptorReads = failCreatedDescriptorReads;
                return created;
            }
            @Override public void remove(final Part part) { values.remove(part); }
        }

        private final class MutableDrawables implements Drawables {
            final List<MutableDrawable> values = new ArrayList<>();
            @Override public List<Drawable> all() { return List.copyOf(values); }
            @Override public Drawable find(final ArtMeshId id) {
                return values.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
            @Override public Drawable create(
                final String name,
                final Part parent,
                final int index,
                final ArtMeshGeometry geometry
            ) {
                final MutableDrawable value = new MutableDrawable(
                    next("ArtMesh"), name, (MutablePart) parent, geometry
                );
                values.add(value);
                return value;
            }
            @Override public void remove(final Drawable drawable) { values.remove(drawable); }
        }

        private final class MutableDeformers implements Deformers {
            final List<Deformer> values = new ArrayList<>();
            @Override public List<Deformer> all() { return List.copyOf(values); }
            @Override public Deformer find(final DeformerId id) {
                return values.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
            @Override public WarpDeformer createWarp(
                final String name,
                final Part parent,
                final int index,
                final int rows,
                final int columns
            ) {
                return addWarp(next("Warp"), name, (MutablePart) parent, grid(rows, columns));
            }
            @Override public RotationDeformer createRotation(
                final String name,
                final Part parent,
                final int index
            ) {
                return addRotation(
                    next("Rotation"), name, (MutablePart) parent,
                    new RotationDeformerForm(0F, 0F, 0F, 1F, false, false)
                );
            }
            @Override public void remove(final Deformer deformer) {
                values.remove(deformer);
                warps.values.remove(deformer);
                rotations.values.remove(deformer);
            }
        }

        private final class MutableWarpDeformers implements WarpDeformers {
            final List<MutableWarp> values = new ArrayList<>();
            @Override public List<WarpDeformer> all() { return List.copyOf(values); }
            @Override public WarpDeformer find(final DeformerId id) {
                return values.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
        }

        private final class MutableRotationDeformers implements RotationDeformers {
            final List<MutableRotation> values = new ArrayList<>();
            @Override public List<RotationDeformer> all() { return List.copyOf(values); }
            @Override public RotationDeformer find(final DeformerId id) {
                return values.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException(id.value()));
            }
        }
    }

    private static final class MutablePart implements Part {
        private final PartId id;
        private String name;
        private MutablePart parent;
        private boolean failDescriptorReads;
        MutablePart(final String id, final String name, final MutablePart parent) {
            this.id = new PartId(id);
            this.name = name;
            this.parent = parent;
        }
        @Override public PartId id() { return id; }
        @Override public String name() {
            if (failDescriptorReads) throw new IllegalStateException("descriptor readback failed");
            return name;
        }
        @Override public void setName(final String value) { name = value; }
        @Override public void setParent(final Part value, final int index) {
            parent = (MutablePart) value;
        }
        @Override public Optional<PartId> parentId() {
            return Optional.ofNullable(parent).map(MutablePart::id);
        }
        @Override public List<PartId> childIds() { return List.of(); }
        @Override public float getOpacity() { return 1F; }
        @Override public int parentIndex() { return parent == null ? -1 : 0; }
        @Override public void setOpacity(final float opacity) { }
    }

    private static final class MutableDrawable implements Drawable {
        private final ArtMeshId id;
        private String name;
        private MutablePart parentPart;
        private Deformer parentDeformer;
        private ArtMeshGeometry geometry;
        MutableDrawable(
            final String id,
            final String name,
            final MutablePart parent,
            final ArtMeshGeometry geometry
        ) {
            this.id = new ArtMeshId(id);
            this.name = name;
            this.parentPart = parent;
            this.geometry = geometry;
        }
        @Override public ArtMeshId id() { return id; }
        @Override public String name() { return name; }
        @Override public void setName(final String value) { name = value; }
        @Override public void setParent(final Part parent, final int index) {
            parentPart = (MutablePart) parent;
            parentDeformer = null;
        }
        @Override public void setParent(final Deformer parent, final int index) {
            parentDeformer = parent;
        }
        @Override public Optional<PartId> parentPartId() {
            return Optional.ofNullable(parentPart).map(MutablePart::id);
        }
        @Override public Optional<DeformerId> parentDeformerId() {
            return Optional.ofNullable(parentDeformer).map(Deformer::id);
        }
        @Override public List<ArtMeshId> maskIds() { return List.of(); }
        @Override public ArtMeshGeometry geometry() { return geometry; }
        @Override public void replaceGeometry(final ArtMeshGeometry value) { geometry = value; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1F; }
        @Override public IntSequence masks() { return emptyInts(); }
        @Override public FloatSequence vertexPositions() { return emptyFloats(); }
        @Override public FloatSequence vertexUvs() { return emptyFloats(); }
        @Override public IntSequence indices() { return emptyInts(); }
        @Override public Color multiplyColor() { return new Color(1F, 1F, 1F, 1F); }
        @Override public Color screenColor() { return new Color(0F, 0F, 0F, 1F); }
        @Override public int parentPartIndex() { return parentPart == null ? -1 : 0; }
        @Override public int parentDeformerIndex() { return parentDeformer == null ? -1 : 0; }
        @Override public IntSequence parameters() { return emptyInts(); }
    }

    private abstract static class MutableDeformer implements Deformer {
        private final DeformerId id;
        private String name;
        private MutablePart parentPart;
        private Deformer parentDeformer;
        MutableDeformer(final String id, final String name, final MutablePart parent) {
            this.id = new DeformerId(id);
            this.name = name;
            this.parentPart = parent;
        }
        @Override public DeformerId id() { return id; }
        @Override public String name() { return name; }
        @Override public void setName(final String value) { name = value; }
        @Override public void setParent(final Part parent, final int index) {
            parentPart = (MutablePart) parent;
            parentDeformer = null;
        }
        @Override public void setParent(final Deformer parent, final int index) {
            parentDeformer = parent;
        }
        @Override public Optional<PartId> parentPartId() {
            return Optional.ofNullable(parentPart).map(MutablePart::id);
        }
        @Override public Optional<DeformerId> parentDeformerId() {
            return Optional.ofNullable(parentDeformer).map(Deformer::id);
        }
        @Override public int parentDeformerIndex() { return parentDeformer == null ? -1 : 0; }
        @Override public IntSequence parameters() { return emptyInts(); }
    }

    private static final class MutableWarp extends MutableDeformer implements WarpDeformer {
        private WarpGrid grid;
        MutableWarp(
            final String id,
            final String name,
            final MutablePart parent,
            final WarpGrid grid
        ) {
            super(id, name, parent);
            this.grid = grid;
        }
        @Override public WarpGrid grid() { return grid; }
        @Override public void replaceGrid(final WarpGrid value) { grid = value; }
    }

    private static final class MutableRotation extends MutableDeformer
        implements RotationDeformer {
        private RotationDeformerForm form;
        MutableRotation(
            final String id,
            final String name,
            final MutablePart parent,
            final RotationDeformerForm form
        ) {
            super(id, name, parent);
            this.form = form;
        }
        @Override public float baseAngle() { return 0F; }
        @Override public void setBaseAngle(final float angle) { }
        @Override public RotationDeformerForm form() { return form; }
        @Override public void replaceForm(final RotationDeformerForm value) { form = value; }
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
