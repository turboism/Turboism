package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;

/** Override-based lifecycle hooks for Cubism model file content. */
public interface ModelFileHooks {

    default void beforeCreateModel(final ProjectFileOperation operation) {
    }

    default void onModelCreated(final ProjectContentSnapshot model) {
    }

    default void afterCreateModel(final ProjectFileOperationResult result) {
    }

    default void beforeOpenModel(final ProjectFileOperation operation) {
    }

    default void onModelOpened(final ProjectContentSnapshot model) {
    }

    default void afterOpenModel(final ProjectFileOperationResult result) {
    }

    default void beforeSaveModel(final ProjectFileOperation operation) {
    }

    default void onModelSaved(final ProjectContentSnapshot model) {
    }

    default void afterSaveModel(final ProjectFileOperationResult result) {
    }

    default void beforeCloseModel(final ProjectFileOperation operation) {
    }

    default void onModelClosed(final ProjectContentSnapshot model) {
    }

    default void afterCloseModel(final ProjectFileOperationResult result) {
    }
}
