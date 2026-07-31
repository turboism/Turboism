package dev.turboism.sdk.cubism.physics;

/** Preview workflow contribution for the native Physics Settings group list. */
public record PhysicsEditorContribution(
    boolean headerSelectAll,
    boolean retainEnabledGroupsOnReopen
) {
    public PhysicsEditorContribution {
        if (!headerSelectAll && !retainEnabledGroupsOnReopen) {
            throw new IllegalArgumentException("physics editor contribution must enable at least one behavior");
        }
    }
}
