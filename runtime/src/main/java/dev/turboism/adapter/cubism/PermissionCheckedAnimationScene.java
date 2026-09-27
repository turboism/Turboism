package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link AnimationScene} view bound to one facade's animation graph. */
final class PermissionCheckedAnimationScene implements AnimationScene {
    private final CubismFacadeImpl facade;
    final Object owner;
    private final AnimationScene delegate;

    PermissionCheckedAnimationScene(
        final CubismFacadeImpl facade,
        final AnimationScene delegate
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.owner = facade.animationGraphOwner;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String name() {
        facade.requireModelRead("model.animationScene.name");
        return delegate.name();
    }
    @Override public String guid() {
        facade.requireModelRead("model.animationScene.guid");
        return delegate.guid();
    }
    @Override public Optional<String> tag() {
        facade.requireModelRead("model.animationScene.tag");
        return delegate.tag();
    }
    @Override public Map<Integer, String> markers() {
        facade.requireModelRead("model.animationScene.markers");
        return delegate.markers();
    }
    @Override public int startFrame() {
        facade.requireModelRead("model.animationScene.startFrame");
        return delegate.startFrame();
    }
    @Override public int durationFrames() {
        facade.requireModelRead("model.animationScene.durationFrames");
        return delegate.durationFrames();
    }
    @Override public double framesPerSecond() {
        facade.requireModelRead("model.animationScene.framesPerSecond");
        return delegate.framesPerSecond();
    }
    @Override public int width() {
        facade.requireModelRead("model.animationScene.width");
        return delegate.width();
    }
    @Override public int height() {
        facade.requireModelRead("model.animationScene.height");
        return delegate.height();
    }
    @Override public boolean loopMotion() {
        facade.requireModelRead("model.animationScene.loopMotion");
        return delegate.loopMotion();
    }
    @Override public int workspaceStartFrame() {
        facade.requireModelRead("model.animationScene.workspaceStartFrame");
        return delegate.workspaceStartFrame();
    }
    @Override public int workspaceEndFrame() {
        facade.requireModelRead("model.animationScene.workspaceEndFrame");
        return delegate.workspaceEndFrame();
    }
    @Override public List<AnimationTrack> tracks() {
        facade.requireModelRead("model.animationScene.tracks");
        return delegate.tracks().stream()
            .map(track -> (AnimationTrack) new PermissionCheckedAnimationTrack(facade, track))
            .toList();
    }
    @Override public int playheadFrame() {
        facade.requireModelRead("model.animationScene.playheadFrame");
        return delegate.playheadFrame();
    }
    @Override public void seekTo(final int frame) {
        facade.requireModelWrite("model.animationScene.seekTo");
        delegate.seekTo(frame);
    }
    @Override public boolean current() {
        facade.requireModelRead("model.animationScene.current");
        return delegate.current();
    }
    @Override public void activate() {
        facade.requireModelWrite("model.animationScene.activate");
        delegate.activate();
    }
    @Override public AnimationCurveType defaultCurveType() {
        facade.requireModelRead("model.animationScene.defaultCurveType");
        return delegate.defaultCurveType();
    }
    @Override public void setDefaultCurveType(final AnimationCurveType curveType) {
        facade.requireModelWrite("model.animationScene.setDefaultCurveType");
        delegate.setDefaultCurveType(curveType);
    }
    @Override public void rename(final String name) {
        facade.requireModelWrite("model.animationScene.rename");
        delegate.rename(name);
    }
}
