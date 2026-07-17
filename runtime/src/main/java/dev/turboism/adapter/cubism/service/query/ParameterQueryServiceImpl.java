package dev.turboism.adapter.cubism.service.query;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.adapter.cubism.SnapshotWithVersion;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.CubismRuntimeSnapshot;
import dev.turboism.sdk.cubism.CubismServiceException;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.service.query.ParameterBounds;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ParameterQueryServiceImpl implements ParameterQueryService {

    public static final String PARAMETER_READ_PERMISSION = "turboism.cubism.parameter.read";
    public static final String PARAMETER_READ_CAPABILITY = "cubism.parameter.read";

    private final CubismFacadeImpl facade;
    private final CubismPermissionGate permissionGate;
    private volatile ParameterIndex cachedIndex = new ParameterIndex(Long.MIN_VALUE, List.of(), Map.of());

    public ParameterQueryServiceImpl(final CubismFacadeImpl facade, final CubismPermissionGate permissionGate) {
        this.facade = Objects.requireNonNull(facade, "facade");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
    }

    @Override
    public Optional<ParameterSummary> findById(final ParameterId id) throws CubismServiceException {
        Objects.requireNonNull(id, "id");
        permissionGate.require(
            PARAMETER_READ_PERMISSION,
            "parameterQuery.findById",
            PARAMETER_READ_CAPABILITY
        );
        return Optional.ofNullable(index().parametersById().get(id));
    }

    @Override
    public List<ParameterSummary> listAll() throws CubismServiceException {
        permissionGate.require(
            PARAMETER_READ_PERMISSION,
            "parameterQuery.listAll",
            PARAMETER_READ_CAPABILITY
        );
        return index().parameters();
    }

    @Override
    public boolean exists(final ParameterId id) throws CubismServiceException {
        Objects.requireNonNull(id, "id");
        permissionGate.require(
            PARAMETER_READ_PERMISSION,
            "parameterQuery.exists",
            PARAMETER_READ_CAPABILITY
        );
        return index().parametersById().containsKey(id);
    }

    private ParameterIndex index() throws CubismServiceException {
        final SnapshotWithVersion versioned = runtimeWithServiceError();
        final ParameterIndex currentIndex = cachedIndex;
        if (currentIndex.version() == versioned.version()) {
            return currentIndex;
        }
        final ParameterIndex nextIndex = buildIndex(versioned.version(), versioned.snapshot());
        cachedIndex = nextIndex;
        return nextIndex;
    }

    private SnapshotWithVersion runtimeWithServiceError() throws CubismServiceException {
        try {
            return facade.runtimeWithVersion();
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new CubismServiceException(ServiceError.INVALID_SNAPSHOT, "Cubism runtime snapshot is invalid.", error);
        }
    }

    private ParameterIndex buildIndex(final long version, final CubismRuntimeSnapshot snapshot) throws CubismServiceException {
        final Map<ParameterId, ParameterSummary> parametersById = new LinkedHashMap<>();
        for (ParameterSnapshot parameter : snapshot.parameters()) {
            final ParameterSummary summary = summary(parameter);
            if (parametersById.put(summary.id(), summary) != null) {
                throw invalidSnapshot("Duplicate parameter id " + summary.id().value());
            }
        }
        return new ParameterIndex(version, List.copyOf(parametersById.values()), Map.copyOf(parametersById));
    }

    private ParameterSummary summary(final ParameterSnapshot parameter) throws CubismServiceException {
        if (parameter.minValue() > parameter.maxValue()) {
            throw invalidSnapshot("Invalid parameter bounds for parameter " + parameter.id());
        }
        if (parameter.defaultValue() < parameter.minValue() || parameter.defaultValue() > parameter.maxValue()) {
            throw invalidSnapshot("Invalid default value for parameter " + parameter.id());
        }
        return new ParameterSummary(
            new ParameterId(parameter.id()),
            parameter.name(),
            parameter.value(),
            new ParameterBounds(parameter.minValue(), parameter.maxValue(), parameter.defaultValue()),
            parameter.visible(),
            parameter.editable()
        );
    }

    private CubismServiceException invalidSnapshot(final String message) {
        return new CubismServiceException(ServiceError.INVALID_SNAPSHOT, message);
    }

    private record ParameterIndex(
        long version,
        List<ParameterSummary> parameters,
        Map<ParameterId, ParameterSummary> parametersById
    ) {
        private ParameterIndex {
            parameters = List.copyOf(parameters);
            parametersById = Map.copyOf(parametersById);
        }
    }
}
