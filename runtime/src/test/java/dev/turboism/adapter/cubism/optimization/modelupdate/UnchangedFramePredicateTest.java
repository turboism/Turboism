package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.adapter.cubism.optimization.modelupdate.UnchangedFramePredicate.Frame;
import dev.turboism.adapter.cubism.optimization.modelupdate.UnchangedFramePredicate.ParamSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UnchangedFramePredicateTest {

    private record Values(List<Object> ids, float[] values) implements ParamSet {
        @Override public int size() { return ids == null ? -1 : ids.size(); }
        @Override public Object idAt(int index) { return ids.get(index); }
        @Override public float valueAt(int index) { return values[index]; }
    }

    private static final Object MODEL = new Object();
    private static final Object VIEW = new Object();
    private static final Object DOC = new Object();
    private static final Object VIEW_MODE = new Object();
    private static final Object EDIT_MODE = new Object();

    private static Values params(final float... values) {
        final List<Object> ids = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i++) ids.add("p" + i);
        return new Values(ids, values);
    }

    private static Frame clean() {
        return new Frame(MODEL, VIEW, DOC, 42L, 7,
            VIEW_MODE, EDIT_MODE, 0.5f,
            false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false,
            false, false,
            true, false, false, 0f, false, false, false,
            VIEW, EDIT_MODE, List.of(), null, true, false, null, false, false);
    }

    private static Frame cleanCopy(final Frame f) {
        return new Frame(f.model(), f.modelingView(), f.document(), f.documentLastModified(),
            f.parameterSetUpdateVersion(), f.viewMode(), f.editMode(), f.appearanceSettingD(),
            f.optimizeArtMesh(), f.optimizeDeformer(), f.optimizeDrawOrder(), f.optimizeHierarchy(),
            f.maskWarningHint(), f.blendModeWarningHint(), f.hideSelectedState(),
            f.highLightDeformerChild(),
            f.randomPoseAnimation(), f.externalAppAnimation(), f.recording(),
            f.developSettingH(), f.developSettingK(), f.formAnimationGate(), f.modelEditing(),
            f.updaterFlagA(), f.updaterFlagB(),
            f.updateContextPresent(), f.updateContextA(), f.updateContextB(), f.updateContextC(),
            f.updateContextD(), f.updateContextE(), f.updateContextF(),
            f.updateContextView(), f.updateContextEditMode(), f.updateContextSelection(),
            f.axRenderHash(), f.axStacksEmpty(), f.conflictPolygon(), f.contextParam(),
            f.argAllowAnimation(), f.argFormAnimation());
    }

    @Test void firstFrameNeverSkips() {
        assertFalse(UnchangedFramePredicate.test(clean(), null, params(1f), params(1f)));
    }

    @Test void unchangedFrameSkips() {
        final Frame frame = clean();
        assertTrue(UnchangedFramePredicate.test(frame, cleanCopy(frame),
            params(1f, 2f, 3f), params(1f, 2f, 3f)));
    }

    @Test void anyDifferenceForcesFull() {
        final Frame base = clean();
        final Frame f = clean();
        // Every mutable input flips the answer.
        assertFalse(UnchangedFramePredicate.test(withDoc(f, 43L), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withVersion(f, 8), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withField(f, "viewMode", new Object()), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withField(f, "editMode", new Object()), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withAppearance(f, 0.6f), cleanCopy(base),
            params(1f), params(1f)));
    }

    @Test void everyGuardFlagForcesFull() {
        final Frame base = clean();
        final String[] flags = {
            "optimizeArtMesh", "optimizeDeformer", "optimizeDrawOrder", "optimizeHierarchy",
            "maskWarningHint", "blendModeWarningHint", "hideSelectedState", "highLightDeformerChild",
            "randomPoseAnimation", "externalAppAnimation", "recording",
            "developSettingH", "developSettingK", "formAnimationGate", "modelEditing",
            "updaterFlagA", "updaterFlagB",
            "updateContextA", "updateContextB", "updateContextD", "updateContextE", "updateContextF",
            "conflictPolygon", "argAllowAnimation", "argFormAnimation"
        };
        for (final String flag : flags) {
            assertFalse(UnchangedFramePredicate.test(withFlag(clean(), flag), cleanCopy(base),
                params(1f), params(1f)), "flag " + flag);
        }
    }

    @Test void previousUnsafeStateForcesFull() {
        final Frame dirty = withFlag(clean(), "recording");
        assertFalse(UnchangedFramePredicate.test(clean(), dirty, params(1f), params(1f)));
    }

    @Test void unknownOrMissingStateForcesFull() {
        final Frame base = clean();
        assertFalse(UnchangedFramePredicate.test(withField(clean(), "model", null), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withField(clean(), "modelingView", null), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withField(clean(), "document", null), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withUcAbsent(clean()), cleanCopy(base),
            params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(clean(), cleanCopy(base), null, params(1f)));
        assertFalse(UnchangedFramePredicate.test(clean(), cleanCopy(base), params(1f), null));
        assertFalse(UnchangedFramePredicate.test(withField(clean(), "contextParam", new Object()),
            cleanCopy(base), params(1f), params(1f)));
        assertFalse(UnchangedFramePredicate.test(withStacks(clean()), cleanCopy(base),
            params(1f), params(1f)));
    }

    @Test void parameterDriftForcesFull() {
        final Frame frame = clean();
        assertFalse(UnchangedFramePredicate.test(frame, cleanCopy(frame),
            params(1f, 9f), params(1f, 2f)), "value changed");
        assertFalse(UnchangedFramePredicate.test(frame, cleanCopy(frame),
            params(1f), params(1f, 2f)), "size changed");
        final Values renamed = new Values(List.of("other"), new float[]{1f});
        assertFalse(UnchangedFramePredicate.test(frame, cleanCopy(frame), renamed, params(1f)),
            "identity changed");
        assertFalse(UnchangedFramePredicate.test(frame, cleanCopy(frame),
            new Values(null, new float[0]), params(1f)), "set absent");
    }

    @Test void selectionDriftForcesFull() {
        final Frame frame = clean();
        final Frame changed = cleanCopy(frame);
        final Object a = new Object(), b = new Object();
        final Frame sel1 = withSelection(frame, List.of(a, b));
        final Frame sel2 = withSelection(changed, List.of(a));
        assertFalse(UnchangedFramePredicate.test(sel2, sel1, params(1f), params(1f)));
        assertTrue(UnchangedFramePredicate.test(withSelection(frame, List.of(a, b)),
            withSelection(changed, List.of(a, b)), params(1f), params(1f)));
    }

    @Test void modelIdentityForcesFull() {
        assertFalse(UnchangedFramePredicate.test(clean(), withField(clean(), "model", new Object()),
            params(1f), params(1f)));
    }

    private static Frame withDoc(final Frame f, final long v) {
        return new Frame(f.model(), f.modelingView(), f.document(), v, f.parameterSetUpdateVersion(),
            f.viewMode(), f.editMode(), f.appearanceSettingD(), f.optimizeArtMesh(), f.optimizeDeformer(),
            f.optimizeDrawOrder(), f.optimizeHierarchy(), f.maskWarningHint(), f.blendModeWarningHint(),
            f.hideSelectedState(), f.highLightDeformerChild(), f.randomPoseAnimation(),
            f.externalAppAnimation(), f.recording(), f.developSettingH(), f.developSettingK(),
            f.formAnimationGate(), f.modelEditing(), f.updaterFlagA(), f.updaterFlagB(),
            f.updateContextPresent(), f.updateContextA(), f.updateContextB(), f.updateContextC(),
            f.updateContextD(), f.updateContextE(), f.updateContextF(), f.updateContextView(),
            f.updateContextEditMode(), f.updateContextSelection(), f.axRenderHash(), f.axStacksEmpty(),
            f.conflictPolygon(), f.contextParam(), f.argAllowAnimation(), f.argFormAnimation());
    }

    private static Frame withVersion(final Frame f, final int v) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(), v,
            c.viewMode(), c.editMode(), c.appearanceSettingD(), c.optimizeArtMesh(), c.optimizeDeformer(),
            c.optimizeDrawOrder(), c.optimizeHierarchy(), c.maskWarningHint(), c.blendModeWarningHint(),
            c.hideSelectedState(), c.highLightDeformerChild(), c.randomPoseAnimation(),
            c.externalAppAnimation(), c.recording(), c.developSettingH(), c.developSettingK(),
            c.formAnimationGate(), c.modelEditing(), c.updaterFlagA(), c.updaterFlagB(),
            c.updateContextPresent(), c.updateContextA(), c.updateContextB(), c.updateContextC(),
            c.updateContextD(), c.updateContextE(), c.updateContextF(), c.updateContextView(),
            c.updateContextEditMode(), c.updateContextSelection(), c.axRenderHash(), c.axStacksEmpty(),
            c.conflictPolygon(), c.contextParam(), c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withAppearance(final Frame f, final float v) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(),
            c.parameterSetUpdateVersion(), c.viewMode(), c.editMode(), v, c.optimizeArtMesh(),
            c.optimizeDeformer(), c.optimizeDrawOrder(), c.optimizeHierarchy(), c.maskWarningHint(),
            c.blendModeWarningHint(), c.hideSelectedState(), c.highLightDeformerChild(),
            c.randomPoseAnimation(), c.externalAppAnimation(), c.recording(), c.developSettingH(),
            c.developSettingK(), c.formAnimationGate(), c.modelEditing(), c.updaterFlagA(),
            c.updaterFlagB(), c.updateContextPresent(), c.updateContextA(), c.updateContextB(),
            c.updateContextC(), c.updateContextD(), c.updateContextE(), c.updateContextF(),
            c.updateContextView(), c.updateContextEditMode(), c.updateContextSelection(),
            c.axRenderHash(), c.axStacksEmpty(), c.conflictPolygon(), c.contextParam(),
            c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withSelection(final Frame f, final List<Object> selection) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(),
            c.parameterSetUpdateVersion(), c.viewMode(), c.editMode(), c.appearanceSettingD(),
            c.optimizeArtMesh(), c.optimizeDeformer(), c.optimizeDrawOrder(), c.optimizeHierarchy(),
            c.maskWarningHint(), c.blendModeWarningHint(), c.hideSelectedState(),
            c.highLightDeformerChild(), c.randomPoseAnimation(), c.externalAppAnimation(), c.recording(),
            c.developSettingH(), c.developSettingK(), c.formAnimationGate(), c.modelEditing(),
            c.updaterFlagA(), c.updaterFlagB(), c.updateContextPresent(), c.updateContextA(),
            c.updateContextB(), c.updateContextC(), c.updateContextD(), c.updateContextE(),
            c.updateContextF(), c.updateContextView(), c.updateContextEditMode(), selection,
            c.axRenderHash(), c.axStacksEmpty(), c.conflictPolygon(), c.contextParam(),
            c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withUcAbsent(final Frame f) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(),
            c.parameterSetUpdateVersion(), c.viewMode(), c.editMode(), c.appearanceSettingD(),
            c.optimizeArtMesh(), c.optimizeDeformer(), c.optimizeDrawOrder(), c.optimizeHierarchy(),
            c.maskWarningHint(), c.blendModeWarningHint(), c.hideSelectedState(),
            c.highLightDeformerChild(), c.randomPoseAnimation(), c.externalAppAnimation(), c.recording(),
            c.developSettingH(), c.developSettingK(), c.formAnimationGate(), c.modelEditing(),
            c.updaterFlagA(), c.updaterFlagB(), false, c.updateContextA(), c.updateContextB(),
            c.updateContextC(), c.updateContextD(), c.updateContextE(), c.updateContextF(),
            c.updateContextView(), c.updateContextEditMode(), c.updateContextSelection(),
            c.axRenderHash(), c.axStacksEmpty(), c.conflictPolygon(), c.contextParam(),
            c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withStacks(final Frame f) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(),
            c.parameterSetUpdateVersion(), c.viewMode(), c.editMode(), c.appearanceSettingD(),
            c.optimizeArtMesh(), c.optimizeDeformer(), c.optimizeDrawOrder(), c.optimizeHierarchy(),
            c.maskWarningHint(), c.blendModeWarningHint(), c.hideSelectedState(),
            c.highLightDeformerChild(), c.randomPoseAnimation(), c.externalAppAnimation(), c.recording(),
            c.developSettingH(), c.developSettingK(), c.formAnimationGate(), c.modelEditing(),
            c.updaterFlagA(), c.updaterFlagB(), c.updateContextPresent(), c.updateContextA(),
            c.updateContextB(), c.updateContextC(), c.updateContextD(), c.updateContextE(),
            c.updateContextF(), c.updateContextView(), c.updateContextEditMode(),
            c.updateContextSelection(), c.axRenderHash(), false, c.conflictPolygon(), c.contextParam(),
            c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withField(final Frame f, final String field, final Object value) {
        final Frame c = cleanCopy(f);
        return new Frame(
            "model".equals(field) ? value : c.model(),
            "modelingView".equals(field) ? value : c.modelingView(),
            "document".equals(field) ? value : c.document(),
            c.documentLastModified(), c.parameterSetUpdateVersion(),
            "viewMode".equals(field) ? value : c.viewMode(),
            "editMode".equals(field) ? value : c.editMode(),
            c.appearanceSettingD(), c.optimizeArtMesh(), c.optimizeDeformer(), c.optimizeDrawOrder(),
            c.optimizeHierarchy(), c.maskWarningHint(), c.blendModeWarningHint(), c.hideSelectedState(),
            c.highLightDeformerChild(), c.randomPoseAnimation(), c.externalAppAnimation(), c.recording(),
            c.developSettingH(), c.developSettingK(), c.formAnimationGate(), c.modelEditing(),
            c.updaterFlagA(), c.updaterFlagB(), c.updateContextPresent(), c.updateContextA(),
            c.updateContextB(), c.updateContextC(), c.updateContextD(), c.updateContextE(),
            c.updateContextF(), c.updateContextView(), c.updateContextEditMode(),
            c.updateContextSelection(), c.axRenderHash(), c.axStacksEmpty(), c.conflictPolygon(),
            "contextParam".equals(field) ? value : c.contextParam(),
            c.argAllowAnimation(), c.argFormAnimation());
    }

    private static Frame withFlag(final Frame f, final String flag) {
        final Frame c = cleanCopy(f);
        return new Frame(c.model(), c.modelingView(), c.document(), c.documentLastModified(),
            c.parameterSetUpdateVersion(), c.viewMode(), c.editMode(), c.appearanceSettingD(),
            "optimizeArtMesh".equals(flag) || c.optimizeArtMesh(),
            "optimizeDeformer".equals(flag) || c.optimizeDeformer(),
            "optimizeDrawOrder".equals(flag) || c.optimizeDrawOrder(),
            "optimizeHierarchy".equals(flag) || c.optimizeHierarchy(),
            "maskWarningHint".equals(flag) || c.maskWarningHint(),
            "blendModeWarningHint".equals(flag) || c.blendModeWarningHint(),
            "hideSelectedState".equals(flag) || c.hideSelectedState(),
            "highLightDeformerChild".equals(flag) || c.highLightDeformerChild(),
            "randomPoseAnimation".equals(flag) || c.randomPoseAnimation(),
            "externalAppAnimation".equals(flag) || c.externalAppAnimation(),
            "recording".equals(flag) || c.recording(),
            "developSettingH".equals(flag) || c.developSettingH(),
            "developSettingK".equals(flag) || c.developSettingK(),
            "formAnimationGate".equals(flag) || c.formAnimationGate(),
            "modelEditing".equals(flag) || c.modelEditing(),
            "updaterFlagA".equals(flag) || c.updaterFlagA(),
            "updaterFlagB".equals(flag) || c.updaterFlagB(),
            c.updateContextPresent(),
            "updateContextA".equals(flag) || c.updateContextA(),
            "updateContextB".equals(flag) || c.updateContextB(),
            c.updateContextC(),
            "updateContextD".equals(flag) || c.updateContextD(),
            "updateContextE".equals(flag) || c.updateContextE(),
            "updateContextF".equals(flag) || c.updateContextF(),
            c.updateContextView(), c.updateContextEditMode(), c.updateContextSelection(),
            c.axRenderHash(), c.axStacksEmpty(),
            "conflictPolygon".equals(flag) || c.conflictPolygon(),
            c.contextParam(),
            "argAllowAnimation".equals(flag) || c.argAllowAnimation(),
            "argFormAnimation".equals(flag) || c.argFormAnimation());
    }
}
