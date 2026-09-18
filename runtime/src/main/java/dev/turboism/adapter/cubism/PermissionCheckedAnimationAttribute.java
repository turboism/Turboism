package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link AnimationAttribute} view bound to one facade's animation graph. */
final class PermissionCheckedAnimationAttribute implements AnimationAttribute {
    private final CubismFacadeImpl facade;
    final Object owner;
    final AnimationAttribute delegate;

    PermissionCheckedAnimationAttribute(
        final CubismFacadeImpl facade,
        final AnimationAttribute delegate
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.owner = facade.animationGraphOwner;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String id() {
        facade.requireModelRead("model.animationAttribute.id");
        return delegate.id();
    }
    @Override public String name() {
        facade.requireModelRead("model.animationAttribute.name");
        return delegate.name();
    }
    @Override public String guid() {
        facade.requireModelRead("model.animationAttribute.guid");
        return delegate.guid();
    }
    @Override public String effectId() {
        facade.requireModelRead("model.animationAttribute.effectId");
        return delegate.effectId();
    }
    @Override public Optional<ParameterId> parameterId() {
        facade.requireModelRead("model.animationAttribute.parameterId");
        return delegate.parameterId();
    }
    @Override public AnimationAttributeKind kind() {
        facade.requireModelRead("model.animationAttribute.kind");
        return delegate.kind();
    }
    @Override public boolean active() {
        facade.requireModelRead("model.animationAttribute.active");
        return delegate.active();
    }
    @Override public boolean editable() {
        facade.requireModelRead("model.animationAttribute.editable");
        return delegate.editable();
    }
    @Override public List<AnimationKeyframe> keyframes() {
        facade.requireModelRead("model.animationAttribute.keyframes");
        return delegate.keyframes();
    }
    @Override public void setKeyframe(final int frame, final double value) {
        facade.requireModelWrite("model.animationAttribute.setKeyframe");
        delegate.setKeyframe(frame, value);
    }
    @Override public void setKeyframe(
        final int frame,
        final double value,
        final AnimationCurveType curveType
    ) {
        facade.requireModelWrite("model.animationAttribute.setKeyframeCurve");
        delegate.setKeyframe(frame, value, curveType);
    }
    @Override public void setKeyframe(final int frame, final float x, final float y) {
        facade.requireModelWrite("model.animationAttribute.setKeyframePoint");
        delegate.setKeyframe(frame, x, y);
    }
    @Override public void removeKeyframe(final int frame) {
        facade.requireModelWrite("model.animationAttribute.removeKeyframe");
        delegate.removeKeyframe(frame);
    }
    @Override public int offsetKeyframes(final int frameDelta) {
        facade.requireModelWrite("model.animationAttribute.offsetKeyframes");
        return delegate.offsetKeyframes(frameDelta);
    }
    @Override public int scaleKeyframeTimes(final double factor, final int originFrame) {
        facade.requireModelWrite("model.animationAttribute.scaleKeyframeTimes");
        return delegate.scaleKeyframeTimes(factor, originFrame);
    }
    @Override public int quantizeKeyframes(final int stepFrames) {
        facade.requireModelWrite("model.animationAttribute.quantizeKeyframes");
        return delegate.quantizeKeyframes(stepFrames);
    }
    @Override public int copyKeyframesFrom(
        final AnimationAttribute source,
        final boolean replace
    ) {
        facade.requireModelWrite("model.animationAttribute.copyKeyframesFrom");
        Objects.requireNonNull(source, "source");
        return delegate.copyKeyframesFrom(
            facade.unwrapAnimationAttribute(owner, source), replace
        );
    }
    @Override public int applyCurveType(final AnimationCurveType curveType) {
        facade.requireModelWrite("model.animationAttribute.applyCurveType");
        return delegate.applyCurveType(curveType);
    }
    @Override public int applyCurveType(
        final AnimationCurveType curveType,
        final int fromFrame,
        final int toFrame
    ) {
        facade.requireModelWrite("model.animationAttribute.applyCurveTypeRange");
        return delegate.applyCurveType(curveType, fromFrame, toFrame);
    }
    @Override public void recordKeyframe(
        final int frame,
        final AnimationCurveType curveType
    ) {
        facade.requireModelWrite("model.animationAttribute.recordKeyframe");
        delegate.recordKeyframe(frame, curveType);
    }
    @Override public int bakeEvaluated(
        final int fromFrame,
        final int toFrame,
        final int stepFrames,
        final AnimationCurveType curveType
    ) {
        facade.requireModelWrite("model.animationAttribute.bakeEvaluated");
        return delegate.bakeEvaluated(fromFrame, toFrame, stepFrames, curveType);
    }
}
