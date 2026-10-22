package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditColorBlend;
import dev.turboism.sdk.cubism.edit.EditSession;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditSessionOptions;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.DocumentId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Shared scripted host, operation routing, and object graph for edit-session contracts. */
final class EditSessionTestFixtures {
    private EditSessionTestFixtures() {}

    static final DocumentId DOCUMENT = new DocumentId("document-1");

    static final class Fixture {
        final AtomicBoolean editScopeGate = new AtomicBoolean();
        final FakeOpsAccess access = new FakeOpsAccess();
        final FakeOpsHost host = new FakeOpsHost(access);
        final MeshSource mesh;
        final MeshSource other;
        final HostSource artPath;
        final PartSource rootPart;
        final PartSource subPart;
        final GlueSource glue;
        final ParamSource paramSource;
        final RuntimeEditSessionManager manager = new RuntimeEditSessionManager(
                host,
                editScopeGate,
                context -> new EditSessionUiLock() {
                    @Override
                    public void engage(final boolean silent) {}

                    @Override
                    public void log(final String message) {}

                    @Override
                    public void progress(final double value) {}

                    @Override
                    public void disengage() {}
                },
                EditSessionRecoveries.PREFER_REVERT_WHEN_VERIFIED);

        Fixture() {
            mesh = new MeshSource("mesh1", "g-mesh");
            other = new MeshSource("other", "g-other");
            artPath = new HostSource("path1", "g-path");
            rootPart = new PartSource("root-part", "g-root");
            rootPart.children.add(mesh);
            subPart = new PartSource("sub-part", "g-sub");
            subPart.parent = rootPart;
            glue = new GlueSource("glue1", "g-glue");
            paramSource = new ParamSource("AngleZ", "pg1", "Angle Z", 0.0, 0.0, 30.0);
            final Group childGroup = new Group("group-a", "Group A");
            final Group rootGroup = new Group("root-group", "Root");
            rootGroup.children.add(childGroup);
            rootGroup.children.add(paramSource);
            final WarpSource warp = new WarpSource("warp1", "g-warp");
            final RotSource rot = new RotSource("rot1", "g-rot");
            rot.targetDeformer = warp;

            rootPart.name = "RootPartName";
            rootPart.enableDrawOrderGroup = true;
            rootPart.sketch = true;
            rootPart.clipGuids = List.of(other.guid);
            rootPart.reverseMask = true;
            rootPart.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.MULTIPLY.ordinal()];
            rootPart.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.ATOP.ordinal()];
            final PartForm rootForm = new PartForm();
            rootForm.drawOrder = 7;
            rootForm.opacity = 0.5;
            rootForm.multiply = new FloatColor("#AABBCC");
            rootForm.screen = new FloatColor("#112233");
            rootPart.form = rootForm;
            subPart.name = "SubPartName";
            subPart.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.NORMAL.ordinal()];
            subPart.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OVER.ordinal()];
            subPart.form = new PartForm();

            mesh.name = "MeshName";
            mesh.parent = subPart;
            mesh.targetDeformerId = warp.id;
            mesh.clipGuids = List.of(other.guid);
            mesh.reverseMask = true;
            mesh.culling = true;
            mesh.positions = new float[] {0, 0, 1, 0, 0, 1, 1, 1};
            mesh.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.ADD.ordinal()];
            mesh.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OUT.ordinal()];
            final DrawableForm meshForm = new DrawableForm();
            meshForm.drawOrder = 3;
            meshForm.opacity = 0.8;
            meshForm.multiply = new FloatColor("#102030");
            meshForm.screen = new FloatColor("#405060");
            mesh.form = meshForm;
            other.name = "OtherName";
            other.colorBlend = COLOR_BLEND_VALUES[EditColorBlend.NORMAL.ordinal()];
            other.alphaBlend = ALPHA_BLEND_VALUES[EditAlphaBlend.OVER.ordinal()];
            other.form = new DrawableForm();

            warp.name = "WarpName";
            warp.parent = subPart;
            warp.col = 2;
            warp.row = 3;
            warp.extensions = List.of(new BezierExt(3, 9, 9), new BezierExt(2, 4, 5));
            final WarpForm warpForm = new WarpForm();
            warpForm.opacity = 0.25;
            warpForm.multiply = new FloatColor("#0A0B0C");
            warpForm.screen = new FloatColor("#0D0E0F");
            warpForm.positions = new float[] {
                10, 20, 0, 0, 50, 20,
                0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0,
                10, 60, 0, 0, 50, 60,
            };
            warp.form = warpForm;

            rot.name = "RotName";
            rot.targetDeformerId = warp.id;
            rot.baseAngle = 10.0;
            final RotForm rotForm = new RotForm();
            rotForm.angle = 45.0;
            rotForm.scale = 1.5;
            rotForm.opacity = 0.75;
            rotForm.multiply = new FloatColor("#222222");
            rotForm.screen = new FloatColor("#333333");
            rotForm.originX = 3.0;
            rotForm.originY = 4.0;
            rot.form = rotForm;

            glue.name = "GlueName";
            glue.parent = subPart;
            final GlueForm glueForm = new GlueForm();
            glueForm.intensity = 0.65f;
            glue.form = glueForm;

            access.script(mesh, other, artPath, rootPart, subPart, glue, paramSource, rootGroup, List.of(warp, rot));
        }

        EditSession open() throws EditSessionException {
            return manager.open(host.binding, DOCUMENT, EditSessionOptions.defaults());
        }
    }

    // ------------------------------------------------------------------
    // fake host object model
    // ------------------------------------------------------------------

    record HostId(String value) {}

    record HostGuid(String value) {}

    static class HostSource {
        final HostId id;
        final HostGuid guid;
        String name;
        Object handler = new Handler();
        Object grid = new Object();
        Object targetDeformer;
        Object targetDeformerId;
        Object labelColor;
        Object parent;
        Object form;
        List<Object> extensions = List.of();
        List<Object> clipGuids = List.of();
        boolean enableDrawOrderGroup;
        boolean sketch;
        boolean offscreen;
        boolean reverseMask;
        boolean culling;
        float[] positions = new float[0];
        Object colorBlend;
        Object alphaBlend;

        HostSource(final String id, final String guid) {
            this.id = new HostId(id);
            this.guid = new HostGuid(guid);
            this.name = id;
        }
    }

    static final class MeshSource extends HostSource {
        MeshSource(final String id, final String guid) {
            super(id, guid);
        }
    }

    static final class PartSource extends HostSource {
        final List<Object> children = new ArrayList<>();
        final List<Object> keyforms = new ArrayList<>();

        PartSource(final String id, final String guid) {
            super(id, guid);
            handler = new PartHandler();
        }
    }

    static final class WarpSource extends HostSource {
        int col;
        int row;

        WarpSource(final String id, final String guid) {
            super(id, guid);
        }
    }

    static final class RotSource extends HostSource {
        double baseAngle;

        RotSource(final String id, final String guid) {
            super(id, guid);
        }
    }

    static final class GlueSource extends HostSource {
        GlueSource(final String id, final String guid) {
            super(id, guid);
        }
    }

    /** Fake {@code CGlueForm}: only the verified {@code getIntensity} member is scripted. */
    static final class GlueForm {
        float intensity;
    }

    /** A live model instance: {@code *.source} resolves back to the scripted source. */
    static class HostInstance {
        final HostSource source;

        HostInstance(final HostSource source) {
            this.source = source;
        }
    }

    static final class PartInstance extends HostInstance {
        PartInstance(final HostSource source) {
            super(source);
        }
    }

    static final class MeshInstance extends HostInstance {
        MeshInstance(final HostSource source) {
            super(source);
        }
    }

    static final class WarpInstance extends HostInstance {
        WarpInstance(final HostSource source) {
            super(source);
        }
    }

    static final class RotInstance extends HostInstance {
        RotInstance(final HostSource source) {
            super(source);
        }
    }

    static final class GlueInstance extends HostInstance {
        GlueInstance(final HostSource source) {
            super(source);
        }
    }

    /** Fake {@code CFloatColor}: only the verified {@code getHexRGB} member is scripted. */
    static final class FloatColor {
        final String hex;

        FloatColor(final String hex) {
            this.hex = hex;
        }
    }

    /** Fake {@code CWarpDeformerBezierExtension}. */
    static final class BezierExt {
        final int editLevel;
        final int col;
        final int row;

        BezierExt(final int editLevel, final int col, final int row) {
            this.editLevel = editLevel;
            this.col = col;
            this.row = row;
        }
    }

    static final class PartForm {
        int drawOrder;
        double opacity;
        Object multiply;
        Object screen;
    }

    static final class DrawableForm {
        int drawOrder;
        double opacity;
        Object multiply;
        Object screen;
    }

    static class DeformerForm {
        double opacity;
        Object multiply;
        Object screen;
    }

    static final class WarpForm extends DeformerForm {
        float[] positions = new float[0];
    }

    static final class RotForm extends DeformerForm {
        double angle;
        double scale;
        double originX;
        double originY;
    }

    /** Host {@code ColorComposition} constants, in the 5.3.x declaration order. */
    static final Object[] COLOR_BLEND_VALUES;

    /** Host {@code AlphaComposition} constants, in declaration order. */
    static final Object[] ALPHA_BLEND_VALUES;

    static {
        COLOR_BLEND_VALUES = new Object[EditColorBlend.values().length];
        for (int i = 0; i < COLOR_BLEND_VALUES.length; i++) {
            COLOR_BLEND_VALUES[i] = new Object();
        }
        ALPHA_BLEND_VALUES = new Object[EditAlphaBlend.values().length];
        for (int i = 0; i < ALPHA_BLEND_VALUES.length; i++) {
            ALPHA_BLEND_VALUES[i] = new Object();
        }
    }

    static final class Handler {}

    static final class PartHandler {}

    static final class GroupHandler {}

    static final class LabelColor {}

    static final class Param {}

    static final class ParamSource {
        final HostId id;
        final HostGuid guid;
        final String name;
        final double minimum;
        final double defaultValue;
        final double maximum;

        ParamSource(
                final String id,
                final String guid,
                final String name,
                final double minimum,
                final double defaultValue,
                final double maximum) {
            this.id = new HostId(id);
            this.guid = new HostGuid(guid);
            this.name = name;
            this.minimum = minimum;
            this.defaultValue = defaultValue;
            this.maximum = maximum;
        }
    }

    static final class Group {
        final HostId id;
        final String name;
        final GroupHandler handler = new GroupHandler();
        final List<Object> children = new ArrayList<>();

        Group(final String id, final String name) {
            this.id = new HostId(id);
            this.name = name;
        }
    }

    // ------------------------------------------------------------------
    // scripted member surface
    // ------------------------------------------------------------------

    /**
     * Scripted {@link EditSessionOpsAccess}: member calls dispatch through an alias handler
     * table and every call is recorded. {@code authorizesFeature} admits everything unless a
     * capability is explicitly denied — the fail-closed tests assert no member call survives a
     * denied gate.
     */
    static final class FakeOpsAccess implements EditSessionOpsAccess {
        record Call(String alias, Object target, List<Object> arguments) {}

        final Object document = new Object();
        final Object modelSource = new Object();
        final Object model = new Object();
        final Object app = new Object();
        final Object mainFrame = new Object();
        final Object palette = new Object();
        final Object paletteView = new Object();
        final Object operation = new Object();
        final Object validator = new Object();
        final Object updateManager = new Object();
        final Object pack = new Object();
        final Object modelHandler = new Object();
        final Object paramSet = new Object();
        final Param param = new Param();
        final Object undo = new Object();
        final List<Object> objects = new ArrayList<>();
        final List<Object> glues = new ArrayList<>();
        List<Object> deformers = List.of();
        List<Object> selectionGuids = List.of();

        final Map<String, BiFunction<Object, Object[], Object>> handlers = new HashMap<>();
        final Map<String, java.util.function.Function<Object[], Object>> staticHandlers = new HashMap<>();
        final Map<String, java.util.function.Function<Object[], Object>> constructorHandlers = new HashMap<>();
        final Map<String, Predicate<Object>> instanceChecks = new HashMap<>();
        final Map<String, Object> staticFields = new HashMap<>();
        final List<Call> calls = new ArrayList<>();
        boolean denyAll;
        final Set<String> denied = new HashSet<>();
        final Set<String> deniedAliases = new HashSet<>();

        void script(
                final HostSource mesh,
                final HostSource other,
                final HostSource artPath,
                final PartSource rootPart,
                final PartSource subPart,
                final GlueSource glue,
                final ParamSource paramSource,
                final Group rootGroup,
                final List<Object> deformerList) {
            objects.add(mesh);
            objects.add(other);
            objects.add(rootPart);
            objects.add(subPart);
            objects.addAll(deformerList);
            deformers = deformerList;
            glues.add(glue);

            final List<Object> partInstances = List.of(new PartInstance(rootPart), new PartInstance(subPart));
            final List<Object> meshInstances = List.of(new MeshInstance(mesh), new MeshInstance(other));
            final ArrayList<Object> deformerInstances = new ArrayList<>();
            for (final Object deformer : deformerList) {
                deformerInstances.add(
                        deformer instanceof WarpSource
                                ? new WarpInstance((HostSource) deformer)
                                : new RotInstance((HostSource) deformer));
            }
            final Map<String, Object> instancesById = new HashMap<>();
            instancesById.put(glue.id.value(), new GlueInstance(glue));

            on("cubism.editor-model.model-source.all-objects", (t, a) -> objects);
            on("cubism.editor-model.model-source.all-deformers", (t, a) -> deformers);
            on("cubism.editor-model.model-source.all-art-meshes", (t, a) -> List.of(mesh, other));
            on("cubism.editor-model.model-source.all-glues", (t, a) -> glues);
            on("cubism.editor-model.model.get-object", (t, a) -> instancesById.get(((HostId) a[0]).value()));
            on("cubism.editor-model.model-source.parts", (t, a) -> List.of(rootPart));
            on("cubism.editor-model.model-source.root-part", (t, a) -> rootPart);
            on("cubism.editor-model.model-source.root-parameter-group", (t, a) -> rootGroup);
            on("cubism.editor-model.model-source.handler", (t, a) -> modelHandler);
            on("cubism.editor-model.model-source.update-instances", (t, a) -> null);
            on("cubism.editor-model.model.parameter-set", (t, a) -> paramSet);
            on("cubism.editor-model.parameter-set.parameters", (t, a) -> List.of(param));
            on("cubism.editor-model.parameter.source", (t, a) -> paramSource);
            on("cubism.editor-model.parameter-source.id", (t, a) -> paramSource.id);
            on("cubism.editor-model.parameter-source.guid", (t, a) -> paramSource.guid);
            on("cubism.editor-model.parameter-source.name", (t, a) -> paramSource.name);
            on("cubism.editor-model.parameter-source.minimum", (t, a) -> paramSource.minimum);
            on("cubism.editor-model.parameter-source.default", (t, a) -> paramSource.defaultValue);
            on("cubism.editor-model.parameter-source.maximum", (t, a) -> paramSource.maximum);
            on("cubism.editor-model.parameter-source.repeat", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-source.morph-target", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-controllable-source.id", (t, a) -> ((HostSource) t).id);
            on("cubism.editor-model.parameter-controllable-source.guid", (t, a) -> ((HostSource) t).guid);
            on("cubism.editor-model.parameter-controllable-source.local-name", (t, a) -> ((HostSource) t).name);
            on("cubism.editor-model.parameter-controllable-source.handler", (t, a) -> ((HostSource) t).handler);
            on(
                    "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                    (t, a) -> ((HostSource) t).targetDeformer);
            on("cubism.editor-model.parameter-controllable-source.label-color", (t, a) -> ((HostSource) t).labelColor);
            on("cubism.editor-model.parameter-controllable-source.name-or-id-string", (t, a) -> ((HostSource) t).name);
            on(
                    "cubism.editor-model.parameter-controllable-source.target-deformer-id",
                    (t, a) -> ((HostSource) t).targetDeformerId);
            on("cubism.editor-model.parameter-controllable-source.extensions", (t, a) -> ((HostSource) t).extensions);
            on(
                    "cubism.editor-model.parameter-controllable.interpolated-form",
                    (t, a) -> ((HostInstance) t).source.form);
            on("cubism.editor-model.part-source.parent", (t, a) -> ((HostSource) t).parent);
            on("cubism.editor-model.float-color.hex-rgb", (t, a) -> ((FloatColor) t).hex);
            on(
                    "cubism.editor-model.part-source.enable-draw-order-group",
                    (t, a) -> ((HostSource) t).enableDrawOrderGroup);
            on("cubism.editor-model.part-source.sketch", (t, a) -> ((HostSource) t).sketch);
            on("cubism.editor-model.part-source.use-offscreen", (t, a) -> ((HostSource) t).offscreen);
            on("cubism.editor-model.part-source.clip-guid-list", (t, a) -> ((HostSource) t).clipGuids);
            on("cubism.editor-model.part-source.invert-clipping-mask", (t, a) -> ((HostSource) t).reverseMask);
            on("cubism.editor-model.part-source.color-composition", (t, a) -> ((HostSource) t).colorBlend);
            on("cubism.editor-model.part-source.alpha-composition", (t, a) -> ((HostSource) t).alphaBlend);
            on("cubism.editor-model.part-form.draw-order", (t, a) -> ((PartForm) t).drawOrder);
            on("cubism.editor-model.part-form.opacity", (t, a) -> ((PartForm) t).opacity);
            on("cubism.editor-model.part-form.multiply-color", (t, a) -> ((PartForm) t).multiply);
            on("cubism.editor-model.part-form.screen-color", (t, a) -> ((PartForm) t).screen);
            on("cubism.editor-model.art-mesh-source.clip-guid-list", (t, a) -> ((HostSource) t).clipGuids);
            on("cubism.editor-model.art-mesh-source.inverted-mask", (t, a) -> ((HostSource) t).reverseMask);
            on("cubism.editor-model.art-mesh-source.color-composition", (t, a) -> ((HostSource) t).colorBlend);
            on("cubism.editor-model.art-mesh-source.alpha-composition", (t, a) -> ((HostSource) t).alphaBlend);
            on("cubism.editor-model.art-mesh-source.culling", (t, a) -> ((HostSource) t).culling);
            on("cubism.editor-model.art-mesh-source.positions", (t, a) -> ((HostSource) t).positions);
            on("cubism.editor-model.drawable-form.draw-order", (t, a) -> ((DrawableForm) t).drawOrder);
            on("cubism.editor-model.drawable-form.opacity", (t, a) -> ((DrawableForm) t).opacity);
            on("cubism.editor-model.drawable-form.multiply-color", (t, a) -> ((DrawableForm) t).multiply);
            on("cubism.editor-model.drawable-form.screen-color", (t, a) -> ((DrawableForm) t).screen);
            on("cubism.editor-model.warp-source.col", (t, a) -> ((WarpSource) t).col);
            on("cubism.editor-model.warp-source.row", (t, a) -> ((WarpSource) t).row);
            on("cubism.editor-model.warp-form.positions", (t, a) -> ((WarpForm) t).positions);
            on("cubism.editor-model.deformer-form.opacity", (t, a) -> ((DeformerForm) t).opacity);
            on("cubism.editor-model.deformer-form.multiply-color", (t, a) -> ((DeformerForm) t).multiply);
            on("cubism.editor-model.deformer-form.screen-color", (t, a) -> ((DeformerForm) t).screen);
            on("cubism.editor-model.warp-bezier-extension.edit-level", (t, a) -> ((BezierExt) t).editLevel);
            on("cubism.editor-model.warp-bezier-extension.bezier-col", (t, a) -> ((BezierExt) t).col);
            on("cubism.editor-model.warp-bezier-extension.bezier-row", (t, a) -> ((BezierExt) t).row);
            on("cubism.editor-model.rotation-form.angle", (t, a) -> ((RotForm) t).angle);
            on("cubism.editor-model.rotation-form.scale", (t, a) -> ((RotForm) t).scale);
            on("cubism.editor-model.rotation-form.origin-x", (t, a) -> ((RotForm) t).originX);
            on("cubism.editor-model.rotation-form.origin-y", (t, a) -> ((RotForm) t).originY);
            on("cubism.editor-model.rotation-source.base-angle", (t, a) -> ((RotSource) t).baseAngle);
            on("cubism.editor-model.model.parts", (t, a) -> partInstances);
            on("cubism.editor-model.part.source", (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.model.all-art-meshes", (t, a) -> meshInstances);
            on("cubism.editor-model.art-mesh.source", (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.model.all-deformers", (t, a) -> deformerInstances);
            on("cubism.editor-model.deformer.source", (t, a) -> ((HostInstance) t).source);
            on("cubism.editor-model.parameter-controllable-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.parameter-controllable.keyform-grid", (t, a) -> ((HostSource) t).grid);
            on("cubism.editor-model.parameter-controllable-handler" + ".create-undo-for-all-edit", (t, a) -> undo);
            on("cubism.editor-model.undo.add", (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.id.value", (t, a) -> ((HostId) t).value());
            on("cubism.editor-model.part-id.value", (t, a) -> ((HostId) t).value());
            on("cubism.editor-model.guid.value", (t, a) -> ((HostGuid) t).value());
            on("cubism.editor-model.keyform-grid.add-key", (t, a) -> null);
            on("cubism.editor-model.keyform-grid.bindings", (t, a) -> List.of());
            on("cubism.editor-model.keyform-grid-source.set-initial-keyform", (t, a) -> null);
            on("cubism.editor-model.parameter-controllable-source.set-keyform-grid-source", (t, a) -> {
                ((HostSource) t).grid = a[0];
                return null;
            });
            on("cubism.editor-model.c-array-list.add", (t, a) -> Boolean.valueOf(((List<Object>) t).add(a[0])));
            on("cubism.editor-model.part-source.keyforms", (t, a) -> ((PartSource) t).keyforms);
            on("cubism.editor-model.part-source.id", (t, a) -> ((PartSource) t).id);
            on("cubism.editor-model.part-source.children", (t, a) -> ((PartSource) t).children);
            on("cubism.editor-model.part-source.handler", (t, a) -> ((PartSource) t).handler);
            on("cubism.editor-model.part-handler.add-part-child", (t, a) -> {
                rootPart.children.add(a[0]);
                return undo;
            });
            on("cubism.editor-model.part-source.set-id", (t, a) -> null);
            on("cubism.editor-model.part-source.set-guid", (t, a) -> null);
            on("cubism.editor-model.part-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.part-source.set-default-order", (t, a) -> null);
            on("cubism.editor-model.parameter-group.children", (t, a) -> ((Group) t).children);
            on("cubism.editor-model.parameter-group.id", (t, a) -> ((Group) t).id);
            on("cubism.editor-model.parameter-group.name", (t, a) -> ((Group) t).name);
            on("cubism.editor-model.parameter-group.label-color", (t, a) -> null);
            on("cubism.editor-model.parameter-group.handler", (t, a) -> ((Group) t).handler);
            on("cubism.editor-model.deformer-source.guid", (t, a) -> ((HostSource) t).guid);
            on("cubism.editor-model.deformer-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.glue-source.set-local-name", (t, a) -> null);
            on("cubism.editor-model.glue-source.local-name", (t, a) -> ((HostSource) t).name);
            on("cubism.editor-model.glue.current-keyform", (t, a) -> ((HostInstance) t).source.form);
            on("cubism.editor-model.glue-form.intensity", (t, a) -> ((GlueForm) t).intensity);
            on("cubism.editor-model.update-manager.selection-guid-list", (t, a) -> selectionGuids);
            on("cubism.editor-model.update-manager.set-selection", (t, a) -> null);
            on("cubism.editor-model.app-controller.update-manager", (t, a) -> updateManager);
            on("cubism.editor-model.app-controller.complete-pack", (t, a) -> pack);
            on("cubism.editor-model.complete-pack.update-parameter", (t, a) -> null);
            on("cubism.editor-model.complete-pack.update-part-palette", (t, a) -> null);
            on("cubism.editor-model.complete-pack.update-deformer-palette", (t, a) -> null);
            on("cubism.editor-model.complete-pack.repaint-canvas", (t, a) -> null);
            on("cubism.editor-model.modeling-document.mark-dirty", (t, a) -> null);
            on("cubism.editor-model.model-handler.remove-objects", (t, a) -> undo);
            on("cubism.editor-model.model-handler.add-source-undo", (t, a) -> undo);
            on("cubism.editor-model.parameter-group-handler.add-parameter-child", (t, a) -> undo);
            on("cubism.editor-model.app-controller.main-frame", (t, a) -> mainFrame);
            on("cubism.editor-model.main-frame.parameter-palette", (t, a) -> palette);
            on("cubism.editor-model.parameter-palette.view", (t, a) -> paletteView);
            on("cubism.editor-model.parameter-palette-view.operation", (t, a) -> operation);
            on("cubism.editor-model.parameter-validator.valid-id", (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.parameter-validator.keys-outside-range", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.parameter-validator.allow-repeat", (t, a) -> Boolean.TRUE);
            on("cubism.editor-model.parameter-validator.default-change-affects-morph-target", (t, a) -> Boolean.FALSE);
            on("cubism.editor-model.form.set-guid", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-name", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-minimum", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-maximum", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-default", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-repeat", (t, a) -> null);
            on("cubism.editor-model.parameter-source.set-id", (t, a) -> null);

            onStatic("cubism.editor-model.app-controller.instance", a -> app);
            onStatic("cubism.editor-model.parameter-operation.validator", a -> validator);
            onStatic("cubism.editor-model.model-handler.create-free-id-default", a -> a[1]);
            onStatic("cubism.editor-model.model-source.verify", a -> null);
            onStatic("cubism.editor-model.color-composition.values", a -> COLOR_BLEND_VALUES);
            onStatic("cubism.editor-model.alpha-composition.values", a -> ALPHA_BLEND_VALUES);

            onConstruct("cubism.editor-model.parameter-id.create", a -> new HostId((String) a[0]));
            onConstruct(
                    "cubism.editor-model.parameter-source.create",
                    a -> new ParamSource("created", "g-created", (String) a[1], 0.0, 0.0, 1.0));
            onConstruct("cubism.editor-model.parameter-group-id.create", a -> new HostId((String) a[0]));
            onConstruct("cubism.editor-model.parameter-group-guid.create", a -> new HostGuid("g-new"));
            onConstruct("cubism.editor-model.part-source.create", a -> new PartSource("created", "g-created"));
            onConstruct("cubism.editor-model.part-id.create", a -> new HostId((String) a[0]));
            onConstruct("cubism.editor-model.part-guid.create", a -> new HostGuid("g-new"));
            onConstruct("cubism.editor-model.part-form.create", a -> new PartForm());
            onConstruct("cubism.editor-model.form-guid.create", a -> new HostGuid("g-form"));
            onConstruct("cubism.editor-model.keyform-grid-source.create", a -> new Object());
            onConstruct("cubism.editor-model.simple-undo.create", a -> undo);

            isInstanceOf("cubism.editor-model.part-source.class", PartSource.class);
            isInstanceOf("cubism.editor-model.art-mesh-source.class", MeshSource.class);
            isInstanceOf("cubism.editor-model.warp-source.class", WarpSource.class);
            isInstanceOf("cubism.editor-model.rotation-source.class", RotSource.class);
            isInstanceOf("cubism.editor-model.glue-source.class", GlueSource.class);
            isInstanceOf("cubism.editor-model.part.class", PartInstance.class);
            isInstanceOf("cubism.editor-model.art-mesh.class", MeshInstance.class);
            isInstanceOf("cubism.editor-model.warp.class", WarpInstance.class);
            isInstanceOf("cubism.editor-model.rotation.class", RotInstance.class);
            isInstanceOf("cubism.editor-model.warp-bezier-extension.class", BezierExt.class);
            isInstanceOf("cubism.editor-model.parameter-group.class", Group.class);
            isInstanceOf("cubism.editor-model.parameter-source.class", ParamSource.class);
            isInstanceOf("cubism.editor-model.parameter-group-handler.class", GroupHandler.class);
            isInstanceOf("cubism.editor-model.parameter-controllable-handler.class", Handler.class);
            isInstanceOf("cubism.editor-model.part-handler.class", PartHandler.class);
            isInstanceOf("cubism.editor-model.label-color.class", LabelColor.class);
        }

        void denyAll() {
            denyAll = true;
        }

        void deny(final String capabilityId) {
            denied.add(capabilityId);
        }

        void denyAlias(final String alias) {
            deniedAliases.add(alias);
        }

        @Override
        public Object document() {
            return document;
        }

        @Override
        public Object modelSource() {
            return modelSource;
        }

        @Override
        public Object model() {
            return model;
        }

        @Override
        public boolean authorizesFeature(final String capabilityId, final Set<String> aliases) {
            return !denyAll
                    && !denied.contains(capabilityId)
                    && deniedAliases.stream().noneMatch(aliases::contains);
        }

        @Override
        public Object invoke(final String alias, final Object target, final Object... arguments) {
            calls.add(new Call(alias, target, java.util.Arrays.asList(arguments)));
            final BiFunction<Object, Object[], Object> handler = handlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted member call: " + alias);
            }
            return handler.apply(target, arguments);
        }

        @Override
        public Object invokeStatic(final String alias, final Object... arguments) {
            calls.add(new Call(alias, null, java.util.Arrays.asList(arguments)));
            final var handler = staticHandlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted static call: " + alias);
            }
            return handler.apply(arguments);
        }

        @Override
        public Object construct(final String alias, final Object... arguments) {
            calls.add(new Call(alias, null, java.util.Arrays.asList(arguments)));
            final var handler = constructorHandlers.get(alias);
            if (handler == null) {
                throw new AssertionError("unscripted constructor: " + alias);
            }
            return handler.apply(arguments);
        }

        @Override
        public Object readStaticField(final String alias) {
            return staticFields.get(alias);
        }

        @Override
        public boolean isInstance(final String alias, final Object value) {
            final Predicate<Object> check = instanceChecks.get(alias);
            return check != null && check.test(value);
        }

        void on(final String alias, final BiFunction<Object, Object[], Object> handler) {
            handlers.put(alias, handler);
        }

        void onStatic(final String alias, final java.util.function.Function<Object[], Object> handler) {
            staticHandlers.put(alias, handler);
        }

        void onConstruct(final String alias, final java.util.function.Function<Object[], Object> handler) {
            constructorHandlers.put(alias, handler);
        }

        void isInstanceOf(final String alias, final Class<?> type) {
            instanceChecks.put(alias, type::isInstance);
        }

        boolean called(final String alias) {
            return calls.stream().anyMatch(call -> call.alias().equals(alias));
        }

        boolean constructed(final String alias) {
            return called(alias);
        }

        List<Call> callsOf(final String alias) {
            return calls.stream().filter(call -> call.alias().equals(alias)).toList();
        }

        int indexOf(final String alias) {
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).alias().equals(alias)) {
                    return i;
                }
            }
            return -1;
        }

        List<String> memberCalls() {
            return calls.stream().map(Call::alias).toList();
        }
    }

    /**
     * Fake session host: the lifecycle members mirror {@code RuntimeEditSessionManagerTest}'s
     * fake; {@link #opsAccess} hands out the scripted member surface so routed operations can
     * run. {@link #dispatch} records labels and runs inline.
     *
     * <p>The host models the native edit-mode/history semantics the session relies on:
     * {@code beginEdit} installs the returned token as the current undo group (a second
     * {@code beginEdit} REPLACES it without closing the first — exactly like the host's
     * deferred palette edits), {@code endEdit(false)} commits the current group as one history
     * entry, {@code endEdit(true)} discards it, {@code revert()} pops the entry at the cursor,
     * and {@code undoRedoTo} only moves the cursor. Session operations never call
     * {@code beginEdit}/{@code endEdit}, so an open session must leave the history position and
     * entry list untouched.</p>
     */
    static final class FakeOpsHost implements EditorEditSessionHost {
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                "plugin.test", "document-1", 1, "model-1", 1, Thread.currentThread());
        final Object editToken = new Object();
        final List<Object> entries = new ArrayList<>();
        final List<String> revertedLabels = new ArrayList<>();
        final List<Integer> cursorMoves = new ArrayList<>();
        int position;
        Object currentGroup;
        boolean revertVerified;
        final FakeOpsAccess access;
        final List<String> dispatchLabels = new ArrayList<>();

        FakeOpsHost(final FakeOpsAccess access) {
            this.access = access;
        }

        List<String> opsDispatchLabels() {
            return dispatchLabels.stream()
                    .filter(label -> !label.equals("edit-session.open")
                            && !label.equals("edit-session.close")
                            && !label.equals("edit-session.cancel"))
                    .toList();
        }

        /**
         * Simulates a host-side edit displacing the session's undo group: a foreign {@code
         * GroupUndo} replaces {@code currentGroup} (native {@code beginEdit} semantics); with
         * {@code commit} it is additionally pushed to history, as the foreign edit's own
         * {@code endEdit(false)} would.
         */
        void displaceSessionGroup(final boolean commit) {
            final Object foreign = new Object();
            currentGroup = foreign;
            if (commit) {
                entries.subList(position, entries.size()).clear();
                entries.add(foreign);
                position = entries.size();
                currentGroup = null;
            }
        }

        HistorySnapshot snapshot() {
            final List<HistoryEntry> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                // Labels carry the entry's identity: a foreign entry occupying a
                // pre-session slot must not compare equal to the entry it replaced.
                rows.add(new HistoryEntry(i, "entry-" + System.identityHashCode(entries.get(i)), true));
            }
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    7,
                    position,
                    rows,
                    position > 0,
                    position < entries.size());
        }

        @Override
        public Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(final String pluginId) {
            return Optional.of(binding);
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return true;
        }

        @Override
        public boolean admits(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return true;
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return snapshot();
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding expected, final String label) {
            currentGroup = editToken;
            return editToken;
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding expected, final Object edit, final boolean cancel) {
            if (!cancel && currentGroup != null) {
                entries.subList(position, entries.size()).clear();
                entries.add(currentGroup);
                position = entries.size();
            }
            currentGroup = null;
        }

        @Override
        public Object currentEditGroup(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return currentGroup;
        }

        @Override
        public void undoEditGroup(final EditorAuthoringTransactionCoordinator.Binding expected, final Object edit) {}

        @Override
        public void undoGroup(final EditorAuthoringTransactionCoordinator.Binding expected, final Object group) {}

        @Override
        public void undoRedoTo(final EditorAuthoringTransactionCoordinator.Binding expected, final int target) {
            cursorMoves.add(target);
            position = Math.min(target, entries.size());
        }

        @Override
        public boolean undoRevertVerified(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return revertVerified;
        }

        @Override
        public void revert(final EditorAuthoringTransactionCoordinator.Binding expected) {
            revertedLabels.add("revert");
            if (position > 0) {
                // CUndoManager.revert: undo the entry at the cursor, then drop it and every
                // entry above — position and the entry list both shrink.
                entries.subList(position - 1, entries.size()).clear();
                position--;
            }
        }

        @Override
        public Optional<Object> mainWindow(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return Optional.empty();
        }

        @Override
        public void refreshAfterSession(final EditorAuthoringTransactionCoordinator.Binding expected) {}

        @Override
        public <T> T dispatch(final String label, final HostTask<T> task) throws EditSessionException {
            dispatchLabels.add(label);
            return task.run();
        }

        @Override
        public EditSessionOpsAccess opsAccess(final EditorAuthoringTransactionCoordinator.Binding binding) {
            return access;
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code + ":fake";
        }
    }
}
