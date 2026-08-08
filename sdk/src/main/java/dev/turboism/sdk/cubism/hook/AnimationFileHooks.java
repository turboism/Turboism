package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;

/** Override-based lifecycle hooks for Cubism animation file content. */
@PreviewApi
public interface AnimationFileHooks {

    default void beforeCreateAnimation(final ProjectFileOperation operation) {
    }

    default void onAnimationCreated(final ProjectContentSnapshot animation) {
    }

    default void afterCreateAnimation(final ProjectFileOperationResult result) {
    }

    default void beforeOpenAnimation(final ProjectFileOperation operation) {
    }

    default void onAnimationOpened(final ProjectContentSnapshot animation) {
    }

    default void afterOpenAnimation(final ProjectFileOperationResult result) {
    }

    default void beforeSaveAnimation(final ProjectFileOperation operation) {
    }

    default void onAnimationSaved(final ProjectContentSnapshot animation) {
    }

    default void afterSaveAnimation(final ProjectFileOperationResult result) {
    }

    default void beforeCloseAnimation(final ProjectFileOperation operation) {
    }

    default void onAnimationClosed(final ProjectContentSnapshot animation) {
    }

    default void afterCloseAnimation(final ProjectFileOperationResult result) {
    }
}
