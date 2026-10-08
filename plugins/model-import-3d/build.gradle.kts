plugins { `java-library` }
dependencies { compileOnly(project(":sdk")); testImplementation(project(":sdk")) }

// Compile the canonical SDK-only importer; no copied engine and no HostProbe classes.
val engine = listOf("StrictJson", "GlbSurfaceReader", "EmbeddedBaseColorTexture", "ClosedConvexSurfaceProof", "PlanarQuadProof", "SurfacePatchPlan", "SurfaceEquivalentSlotPlan", "SurfaceEditableTopologyPlan", "SurfaceDerivedPlanePlan", "SurfaceDerivedTexturePlan",
    "SurfaceProjection", "SurfaceVertexPlan", "SurfaceNativeSourceWindingPlan", "SurfaceVertexAuthoring", "SurfaceAutoCamera", "SurfaceSourceSplit", "SurfaceBspPlan", "SurfaceBspMerge", "SurfaceFixedIndexPlan", "SurfaceKeyPlan", "SurfaceKeyformBake", "SurfaceWarpKeyformBake", "SurfaceWarpSpatialFit", "SurfaceQuadChartDiscovery", "SurfaceWarpChartPlan", "SurfaceWarpImagePlan", "SurfaceWarpAuthoring", "SurfaceRotationAuthoring", "SurfacePlacementAuthoring", "SurfaceWarpImportCandidate", "SurfaceWarpLayerPlan", "SurfaceWarpLayerAuthoring", "SurfaceImportPlan",
    "SurfaceImportGeometry", "SurfaceBaseColorPlan", "SurfaceImportAppearance", "ParallelSheetOrderPlan",
    "SurfaceSheetOrderBake", "SurfaceImport")
sourceSets.main {
    java.srcDir(rootProject.file("validation/model-import-3d-host-probe/input-src"))
    java.srcDir(rootProject.file("validation/model-import-3d-host-probe/native-src"))
    java.setIncludes((engine + listOf("ModelImportPlugin", "ModelInputNormalizer", "ModelInputFiles", "SurfaceSimplifier", "SurfacePatchCollapseProof", "SurfaceImportCostPlan", "ObjModelInput", "ModelTextureNormalizer")).map { "**/$it.java" }.toSet())
}

tasks.test { maxHeapSize = "2g"; systemProperty("mi3d.repo", rootProject.projectDir.absolutePath) }
