package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;

/** Override-based lifecycle hooks for Cubism animation file content. */
@CubismEditor
public interface AnimationFileHooks {

    /** Runs before the host creates an animation file; {@code operation} describes the request. */
    @CubismEditor(from = "5.0.00")
    default void beforeCreateAnimation(final ProjectFileOperation operation) {}

    /** Runs when an animation file was actually created; {@code animation} is its content entry. */
    @CubismEditor(from = "5.0.00")
    default void onAnimationCreated(final ProjectContentSnapshot animation) {}

    /** Runs after the create invocation completed; {@code result} reports how it finished. */
    @CubismEditor(from = "5.0.00")
    default void afterCreateAnimation(final ProjectFileOperationResult result) {}

    /** Runs before the host opens an animation file; {@code operation} describes the request. */
    @CubismEditor(from = "5.0.00")
    default void beforeOpenAnimation(final ProjectFileOperation operation) {}

    /** Runs when an animation file was actually opened; {@code animation} is its content entry. */
    @CubismEditor(from = "5.0.00")
    default void onAnimationOpened(final ProjectContentSnapshot animation) {}

    /** Runs after the open invocation completed; {@code result} reports how it finished. */
    @CubismEditor(from = "5.0.00")
    default void afterOpenAnimation(final ProjectFileOperationResult result) {}

    /** Runs before the host saves an animation file; {@code operation} describes the request. */
    @CubismEditor(from = "5.0.00")
    default void beforeSaveAnimation(final ProjectFileOperation operation) {}

    /** Runs when an animation file was actually saved; {@code animation} is its content entry. */
    @CubismEditor(from = "5.0.00")
    default void onAnimationSaved(final ProjectContentSnapshot animation) {}

    /** Runs after the save invocation completed; {@code result} reports how it finished. */
    @CubismEditor(from = "5.0.00")
    default void afterSaveAnimation(final ProjectFileOperationResult result) {}

    /** Runs before the host closes an animation file; {@code operation} describes the request. */
    @CubismEditor(from = "5.0.00")
    default void beforeCloseAnimation(final ProjectFileOperation operation) {}

    /** Runs when an animation file was actually closed; {@code animation} is its content entry. */
    @CubismEditor(from = "5.0.00")
    default void onAnimationClosed(final ProjectContentSnapshot animation) {}

    /** Runs after the close invocation completed; {@code result} reports how it finished. */
    @CubismEditor(from = "5.0.00")
    default void afterCloseAnimation(final ProjectFileOperationResult result) {}
}
