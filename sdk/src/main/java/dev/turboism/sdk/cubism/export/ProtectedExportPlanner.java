package dev.turboism.sdk.cubism.export;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * Single protected-export planner owned by the contributing plugin.
 *
 * <p>The runtime calls the planner on its export worker thread with a read-only
 * {@link ProtectedExportCensus} of the staging copy. A planner must be pure and fast:
 * it never mutates the model, opens a file, performs an export or touches the host, and
 * it must answer the same census with the same plan. The runtime verifies the returned
 * plan against the census and refuses the export when any entry disagrees, so a planner
 * can never widen its own authority.</p>
 */
@Incubating
@CubismEditor({"5.2.03", "5.3.02", "5.3.03", "5.3.04"})
@FunctionalInterface
public interface ProtectedExportPlanner {

    /**
     * Plans the rewrite and flatten targets for one censused staging copy.
     *
     * @param census immutable census of the copy; never {@code null} and never mutated
     * @return an admitted plan for the runtime to verify, or a bounded rejection
     */
    ProtectedExportPlanResult plan(ProtectedExportCensus census);
}
