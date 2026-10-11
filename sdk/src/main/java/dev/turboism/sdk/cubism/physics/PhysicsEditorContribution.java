package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.CubismEditor;

/** Preview workflow contribution for the native Physics Settings group list. */
@CubismEditor
public record PhysicsEditorContribution(boolean headerSelectAll, boolean retainEnabledGroupsOnReopen) {
    public PhysicsEditorContribution {
        if (!headerSelectAll && !retainEnabledGroupsOnReopen) {
            throw new IllegalArgumentException("physics editor contribution must enable at least one behavior");
        }
    }
}
