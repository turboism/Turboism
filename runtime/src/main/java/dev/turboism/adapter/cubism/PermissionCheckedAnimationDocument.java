package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationScene;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Permission-checked {@link AnimationDocument} view bound to one facade's animation graph. */
final class PermissionCheckedAnimationDocument implements AnimationDocument {
    private final CubismFacadeImpl facade;
    final Object owner;
    private final AnimationDocument delegate;

    PermissionCheckedAnimationDocument(
        final CubismFacadeImpl facade,
        final AnimationDocument delegate
    ) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.owner = facade.animationGraphOwner;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public String animationName() {
        facade.requireModelRead("model.animationDocument.name");
        return delegate.animationName();
    }
    @Override public int sceneCount() {
        facade.requireModelRead("model.animationDocument.sceneCount");
        return delegate.sceneCount();
    }
    @Override public Optional<String> currentSceneName() {
        facade.requireModelRead("model.animationDocument.currentSceneName");
        return delegate.currentSceneName();
    }
    @Override public List<String> sceneNames() {
        facade.requireModelRead("model.animationDocument.sceneNames");
        return delegate.sceneNames();
    }
    @Override public List<AnimationScene> scenes() {
        facade.requireModelRead("model.animationDocument.scenes");
        return delegate.scenes().stream()
            .map(scene -> (AnimationScene) new PermissionCheckedAnimationScene(facade, scene))
            .toList();
    }
}
