package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.AnimationTrack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link AnimationTrack} view bound to one facade's animation graph. */
final class PermissionCheckedAnimationTrack implements AnimationTrack {
    private final CubismFacadeImpl facade;
    final Object owner;
    private final AnimationTrack delegate;

    PermissionCheckedAnimationTrack(
        final CubismFacadeImpl facade,
        final AnimationTrack delegate
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.owner = facade.animationGraphOwner;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String guid() {
        facade.requireModelRead("model.animationTrack.guid");
        return delegate.guid();
    }
    @Override public String name() {
        facade.requireModelRead("model.animationTrack.name");
        return delegate.name();
    }
    @Override public AnimationTrackKind kind() {
        facade.requireModelRead("model.animationTrack.kind");
        return delegate.kind();
    }
    @Override public int startFrame() {
        facade.requireModelRead("model.animationTrack.startFrame");
        return delegate.startFrame();
    }
    @Override public int durationFrames() {
        facade.requireModelRead("model.animationTrack.durationFrames");
        return delegate.durationFrames();
    }
    @Override public List<Integer> keyframeFrames() {
        facade.requireModelRead("model.animationTrack.keyframeFrames");
        return delegate.keyframeFrames();
    }
    @Override public boolean visible() {
        facade.requireModelRead("model.animationTrack.visible");
        return delegate.visible();
    }
    @Override public boolean editable() {
        facade.requireModelRead("model.animationTrack.editable");
        return delegate.editable();
    }
    @Override public boolean muted() {
        facade.requireModelRead("model.animationTrack.muted");
        return delegate.muted();
    }
    @Override public boolean repeat() {
        facade.requireModelRead("model.animationTrack.repeat");
        return delegate.repeat();
    }
    @Override public List<AnimationTrack> children() {
        facade.requireModelRead("model.animationTrack.children");
        return delegate.children().stream()
            .map(child -> (AnimationTrack) new PermissionCheckedAnimationTrack(facade, child))
            .toList();
    }
    @Override public List<AnimationAttribute> attributes() {
        facade.requireModelRead("model.animationTrack.attributes");
        return delegate.attributes().stream()
            .map(attribute -> (AnimationAttribute)
                new PermissionCheckedAnimationAttribute(facade, attribute))
            .toList();
    }
    @Override public Optional<String> linkedModelGuid() {
        facade.requireModelRead("model.animationTrack.linkedModelGuid");
        return delegate.linkedModelGuid();
    }
    @Override public Optional<String> linkedSceneGuid() {
        facade.requireModelRead("model.animationTrack.linkedSceneGuid");
        return delegate.linkedSceneGuid();
    }
}
