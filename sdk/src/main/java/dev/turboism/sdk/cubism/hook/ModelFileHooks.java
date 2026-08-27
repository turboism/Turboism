package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
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

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeOpenModel(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelOpened(final ProjectContentSnapshot model) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterOpenModel(final ProjectFileOperationResult result) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeSaveModel(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelSaved(final ProjectContentSnapshot model) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterSaveModel(final ProjectFileOperationResult result) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void beforeCloseModel(final ProjectFileOperation operation) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onModelClosed(final ProjectContentSnapshot model) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterCloseModel(final ProjectFileOperationResult result) {
    }
}
