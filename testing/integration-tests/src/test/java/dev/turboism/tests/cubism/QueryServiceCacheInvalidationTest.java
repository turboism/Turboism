package dev.turboism.tests.cubism;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.testkit.RecordingHostSnapshotSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryServiceCacheInvalidationTest {

    @Test
    void eachReadObservesTheCurrentHostData() {
        final RecordingHostSnapshotSource source = CubismQueryIntegrationSupport.versionedSource(
                List.of(CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X")));
        final CubismQueryIntegrationSupport.QueryEnvironment environment =
                CubismQueryIntegrationSupport.environment(source, MODEL_READ_PERMISSION);

        final List<ParameterSnapshot> first = environment.context().cubismRead().parameters();
        CubismQueryIntegrationSupport.replaceParameters(
                source, List.of(CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")));
        final List<ParameterSnapshot> reread =
                environment.context().cubismRead().parameters();

        assertEquals(
                List.of("param-angle-x"),
                first.stream().map(ParameterSnapshot::id).toList());
        assertEquals(
                List.of("param-opacity"),
                reread.stream().map(ParameterSnapshot::id).toList());
    }

    @Test
    void hostInvalidationAdvancesTokenAndRefreshesDerivedData() {
        final RecordingHostSnapshotSource source = CubismQueryIntegrationSupport.versionedSource(
                List.of(CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X")));
        final CubismQueryIntegrationSupport.QueryEnvironment environment =
                CubismQueryIntegrationSupport.environment(source, MODEL_READ_PERMISSION);

        environment.context().cubismRead().parameters();
        CubismQueryIntegrationSupport.replaceParameters(
                source, List.of(CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")));
        source.advanceInvalidationToken();
        final List<ParameterSnapshot> refreshed =
                environment.context().cubismRead().parameters();

        assertEquals(
                List.of("param-opacity"),
                refreshed.stream().map(ParameterSnapshot::id).toList());
    }

    @Test
    void staleSnapshotIsNotMergedWithNewHostData() {
        final RecordingHostSnapshotSource source = CubismQueryIntegrationSupport.versionedSource(List.of(
                CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X"),
                CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")));
        final CubismQueryIntegrationSupport.QueryEnvironment environment =
                CubismQueryIntegrationSupport.environment(source, MODEL_READ_PERMISSION);

        environment.context().cubismRead().parameters();
        CubismQueryIntegrationSupport.replaceParameters(
                source,
                List.of(new HostSnapshotSource.HostParameter(
                        "param-angle-y", "Angle Y", 0.0, 0.0, -30.0, 30.0, true, true)));
        source.advanceInvalidationToken();
        final List<ParameterSnapshot> refreshed =
                environment.context().cubismRead().parameters();

        assertEquals(
                List.of("param-angle-y"),
                refreshed.stream().map(ParameterSnapshot::id).toList());
        assertTrue(refreshed.stream().noneMatch(parameter -> parameter.id().equals("param-opacity")));
    }
}
