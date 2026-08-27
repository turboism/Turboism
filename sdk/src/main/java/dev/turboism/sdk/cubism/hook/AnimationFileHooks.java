package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;

/** Override-based lifecycle hooks for Cubism animation file content. */
public interface AnimationFileHooks {

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeCreateAnimation(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onAnimationCreated(final ProjectContentSnapshot animation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterCreateAnimation(final ProjectFileOperationResult result) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeOpenAnimation(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onAnimationOpened(final ProjectContentSnapshot animation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterOpenAnimation(final ProjectFileOperationResult result) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeSaveAnimation(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onAnimationSaved(final ProjectContentSnapshot animation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterSaveAnimation(final ProjectFileOperationResult result) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeCloseAnimation(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onAnimationClosed(final ProjectContentSnapshot animation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterCloseAnimation(final ProjectFileOperationResult result) {
    }
}
