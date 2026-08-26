package dev.turboism.tests.cubism;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.MODEL_READ_PERMISSION;
import static dev.turboism.tests.cubism.CubismQueryIntegrationSupport.PARAMETER_READ_PERMISSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryServiceCacheInvalidationTest {

    @Test
    void sameVersionReturnsCachedSnapshotDerivedResult() throws CubismServiceException {
        final CubismQueryIntegrationSupport.VersionedSource source = CubismQueryIntegrationSupport.versionedSource(List.of(
            CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X")
        ));
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            source,
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        final List<ParameterSummary> first = environment.context().parameterQuery().listAll();
        source.replaceParametersWithoutInvalidation(List.of(CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")));
        final List<ParameterSummary> cached = environment.context().parameterQuery().listAll();

        assertSame(first, cached);
        assertEquals(List.of(new ParameterId("param-angle-x")), cached.stream().map(ParameterSummary::id).toList());
    }

    @Test
    void hostInvalidationAdvancesTokenAndRefreshesDerivedData() throws CubismServiceException {
        final CubismQueryIntegrationSupport.VersionedSource source = CubismQueryIntegrationSupport.versionedSource(List.of(
            CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X")
        ));
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            source,
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        environment.context().parameterQuery().listAll();
        source.replaceParametersWithoutInvalidation(List.of(CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")));
        source.advanceInvalidationToken();
        final List<ParameterSummary> refreshed = environment.context().parameterQuery().listAll();

        assertEquals(List.of(new ParameterId("param-opacity")), refreshed.stream().map(ParameterSummary::id).toList());
    }

    @Test
    void staleSnapshotIsNotMergedWithNewHostData() throws CubismServiceException {
        final CubismQueryIntegrationSupport.VersionedSource source = CubismQueryIntegrationSupport.versionedSource(List.of(
            CubismQueryIntegrationSupport.hostParameter("param-angle-x", "Angle X"),
            CubismQueryIntegrationSupport.hostParameter("param-opacity", "Opacity")
        ));
        final CubismQueryIntegrationSupport.QueryEnvironment environment = CubismQueryIntegrationSupport.environment(
            source,
            MODEL_READ_PERMISSION,
            PARAMETER_READ_PERMISSION
        );

        environment.context().parameterQuery().listAll();
        source.replaceParametersWithoutInvalidation(List.of(new HostSnapshotSource.HostParameter(
            "param-angle-y",
            "Angle Y",
            0.0,
            0.0,
            -30.0,
            30.0,
            true,
            true
        )));
        source.advanceInvalidationToken();
        final List<ParameterSummary> refreshed = environment.context().parameterQuery().listAll();

        assertEquals(List.of(new ParameterId("param-angle-y")), refreshed.stream().map(ParameterSummary::id).toList());
        assertTrue(refreshed.stream().noneMatch(parameter -> parameter.id().equals(new ParameterId("param-opacity"))));
    }
}
