package dev.turboism.core.plugin.context;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;

import java.util.Objects;

record CubismContextServices(
    CubismFacade cubismFacade,
    ParameterQueryService parameterQueryService,
    SelectionQueryService selectionQueryService,
    ModelHierarchyQueryService modelHierarchyQueryService,
    CubismReadCapabilityService cubismReadCapabilityService
) {

    CubismContextServices {
        cubismFacade = Objects.requireNonNull(cubismFacade, "cubismFacade");
        parameterQueryService = Objects.requireNonNull(parameterQueryService, "parameterQueryService");
        selectionQueryService = Objects.requireNonNull(selectionQueryService, "selectionQueryService");
        modelHierarchyQueryService = Objects.requireNonNull(modelHierarchyQueryService, "modelHierarchyQueryService");
        cubismReadCapabilityService = Objects.requireNonNull(cubismReadCapabilityService, "cubismReadCapabilityService");
    }
}
