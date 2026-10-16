package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompleteCubismModelReadApiContractTest {

    @Test
    void modelAndChildrenExposeTheCompleteSupportedReadShapeWithoutRawHostTypes() throws Exception {
        assertMethods(
                CubismModel.class,
                "animationDocuments",
                "autoYure",
                "canvas",
                "currentModelInstance",
                "defaultKeyformLocked",
                "deformers",
                "drawables",
                "editLevel",
                "exportMoc",
                "glues",
                "id",
                "mocInfo",
                "modelEditing",
                "modelInstances",
                "name",
                "parameterBindingBatch",
                "parameterBindings",
                "parameterDefinitions",
                "parameterGroups",
                "parameters",
                "parts",
                "physicsSettings",
                "profile",
                "psdDocuments",
                "renderPixels",
                "replaceArtMeshClipMasks",
                "rotationDeformers",
                "setDefaultKeyformLocked",
                "setEditLevel",
                "setName",
                "statistics",
                "textures",
                "update",
                "warpDeformers");
        assertMethods(
                CubismRenderedPixels.class, "argb", "argbAt", "equals", "hashCode", "height", "toString", "width");
        assertMethods(Canvas.class, "heightPixels", "originXPixels", "originYPixels", "pixelsPerUnit", "widthPixels");
        assertMethods(
                Part.class,
                "alphaComposition",
                "childIds",
                "defaultOrder",
                "editColor",
                "getOpacity",
                "id",
                "index",
                "locked",
                "lockedInHierarchy",
                "maskIds",
                "morphTargets",
                "name",
                "parentId",
                "parentIndex",
                "setAlphaComposition",
                "setDefaultOrder",
                "setEditColor",
                "setId",
                "setLocked",
                "setMaskIds",
                "setName",
                "setOpacity",
                "setParent",
                "setShortName",
                "setSketch",
                "setVisible",
                "shortName",
                "sketch",
                "ui",
                "visible",
                "visibleInHierarchy");
        assertMethods(
                Drawable.class,
                "authoringSnapshot",
                "blendMode",
                "constantFlag",
                "createMorphKeyform",
                "culling",
                "doubleSided",
                "drawOrder",
                "dynamicFlag",
                "evaluationSnapshot",
                "evaluationState",
                "geometry",
                "getCombinedParameterBindings",
                "getMorphParameterBindings",
                "getNormalParameterBindings",
                "getOpacity",
                "getParameterBindings",
                "guid",
                "id",
                "index",
                "indices",
                "invertedMask",
                "locked",
                "lockedInHierarchy",
                "maskIds",
                "masks",
                "morphKeyform",
                "morphTargets",
                "multiplyColor",
                "name",
                "ordinaryKeyform",
                "parameterIds",
                "parameters",
                "parentDeformerId",
                "parentDeformerIndex",
                "parentPartId",
                "parentPartIndex",
                "renderOrder",
                "replaceGeometry",
                "screenColor",
                "setAlphaComposition",
                "setClippingMaskIds",
                "setColorComposition",
                "setCulling",
                "setDrawOrder",
                "setId",
                "setInvertedMask",
                "setLocked",
                "setMultiplyColor",
                "setName",
                "setOpacity",
                "setParent",
                "setScreenColor",
                "setTargetDeformer",
                "setUserData",
                "setVisible",
                "textureIndex",
                "ui",
                "userData",
                "vertexPositions",
                "vertexUvs",
                "visible",
                "visibleInHierarchy",
                "setOrdinaryKeyformPositions",
                "setOrdinaryKeyformDrawOrders");
        assertMethods(
                Deformer.class,
                "getCombinedParameterBindings",
                "getMorphParameterBindings",
                "getNormalParameterBindings",
                "getOpacity",
                "getParameterBindings",
                "id",
                "index",
                "locked",
                "lockedInHierarchy",
                "multiplyColor",
                "name",
                "parameterIds",
                "parameters",
                "parentDeformerId",
                "parentDeformerIndex",
                "parentPartId",
                "parentPartIndex",
                "screenColor",
                "setId",
                "setLocked",
                "setMultiplyColor",
                "setName",
                "setOpacity",
                "setParent",
                "setScreenColor",
                "setTargetDeformer",
                "setVisible",
                "ui",
                "visible",
                "visibleInHierarchy");
        assertMethods(
                Parameter.class,
                "combined",
                "combinedWith",
                "combineWith",
                "getDefaultValue",
                "getMaximumValue",
                "getMinimumValue",
                "getParameterBindings",
                "getValue",
                "id",
                "index",
                "isBlendShape",
                "keyValues",
                "name",
                "repeat",
                "resetToDefault",
                "setValue",
                "type",
                "ui",
                "uncombine",
                "updateDefinition");
        assertMethods(ParameterGroup.class, "childGroupIds", "id", "name", "parameterIds", "parentId", "rename", "ui");
        assertDeclaredMethods(
                WarpDeformer.class,
                "grid",
                "replaceGrid",
                "authoringSnapshot",
                "setOrdinaryKeyformPositions",
                "morphSnapshot",
                "createOpacityMorphKeyform",
                "createMorphKeyform");
        assertDeclaredMethods(
                RotationDeformer.class,
                "baseAngle",
                "form",
                "replaceForm",
                "setBaseAngle",
                "authoringSnapshot",
                "setOrdinaryKeyformForms");
        assertMethods(
                Glue.class,
                "drawableA",
                "drawableAId",
                "drawableB",
                "drawableBId",
                "id",
                "index",
                "intensity",
                "name",
                "parameterIds",
                "parameters",
                "setDrawableA",
                "setDrawableB",
                "setId",
                "setIntensity",
                "setName");
        assertMethods(Parts.class, "add", "all", "copy", "create", "createMany", "find", "remove");
        assertMethods(Deformers.class, "all", "applyToChildren", "createRotation", "createWarp", "find", "remove");
        assertMethods(
                Drawables.class,
                "all",
                "create",
                "createWithSourceGeometry",
                "createManyWithSourceGeometry",
                "find",
                "previewSourceTriangulation",
                "remove",
                "select");
        assertMethods(
                ModelTextures.class,
                "addModelImageGroup",
                "addTextureAtlas",
                "appendTextureAtlasPages",
                "bindModelImage",
                "exportRawImagePsd",
                "modelImageGroups",
                "rawImages",
                "registerImageAndBind",
                "relations",
                "removeModelImage",
                "removeRawImage",
                "removeTextureAtlas",
                "replaceRawImagePsd",
                "sources",
                "supportsAppendTextureAtlasPages",
                "textureAtlases",
                "textureInput");
        assertMethods(
                ArtMeshTextureInput.class,
                "artMeshId",
                "equals",
                "hashCode",
                "kind",
                "modelGeneration",
                "modelId",
                "modelImageId",
                "textureAtlasId",
                "toString");
        assertEquals(
                java.util.Optional.class,
                ModelTextures.class.getMethod("textureInput", ArtMeshId.class).getReturnType());
        assertEquals(
                java.util.Set.of("MODEL_IMAGE", "TEXTURE_ATLAS_REGION"),
                Arrays.stream(ArtMeshTextureInput.Kind.class.getEnumConstants())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(ArtMeshId.class, returnType(ArtMeshTextureInput.class, "artMeshId"));
        assertEquals(ModelId.class, returnType(ArtMeshTextureInput.class, "modelId"));
        assertEquals(long.class, returnType(ArtMeshTextureInput.class, "modelGeneration"));
        assertEquals(ArtMeshTextureInput.Kind.class, returnType(ArtMeshTextureInput.class, "kind"));
        assertEquals(java.util.Optional.class, returnType(ArtMeshTextureInput.class, "modelImageId"));
        assertEquals(java.util.Optional.class, returnType(ArtMeshTextureInput.class, "textureAtlasId"));

        assertEquals(ArtMeshId.class, returnType(Drawable.class, "id"));
        assertEquals(ArtMeshAuthoringSnapshot.class, returnType(Drawable.class, "authoringSnapshot"));
        assertEquals(ArtMeshEvaluationSnapshot.class, returnType(Drawable.class, "evaluationSnapshot"));
        assertEquals(
                ArtMeshKeyform.class,
                Drawable.class
                        .getMethod("ordinaryKeyform", java.util.List.class)
                        .getReturnType());
        assertEquals(
                void.class,
                Drawable.class
                        .getMethod("setOrdinaryKeyformPositions", ArtMeshAuthoringSnapshot.class, java.util.List.class)
                        .getReturnType());
        assertMethods(
                ArtMeshKeyform.class, "coordinates", "snapshot", "defaultKeyform", "setPositions", "setDrawOrder");
        assertEquals(
                ArtMeshMorphKeyform.class,
                Drawable.class
                        .getMethod("morphKeyform", String.class, float.class)
                        .getReturnType());
        assertEquals(
                ArtMeshMorphKeyform.class,
                Drawable.class
                        .getMethod("createMorphKeyform", String.class, float.class)
                        .getReturnType());
        assertMethods(
                ArtMeshMorphKeyform.class,
                "parameterGuid",
                "keyValue",
                "snapshot",
                "baseSnapshot",
                "setPositions",
                "setDrawOrder");
        assertEquals(DeformerId.class, returnType(Deformer.class, "id"));
        assertEquals(int.class, IntSequence.class.getMethod("get", int.class).getReturnType());
    }

    private static void assertMethods(final Class<?> type, final String... names) {
        assertMethodSet(type, type.getMethods(), names);
    }

    private static void assertDeclaredMethods(final Class<?> type, final String... names) {
        assertMethodSet(type, type.getDeclaredMethods(), names);
    }

    private static void assertMethodSet(final Class<?> type, final Method[] methods, final String... names) {
        assertEquals(
                Set.copyOf(Arrays.asList(names)),
                Arrays.stream(methods)
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .filter(method -> method.getDeclaringClass() != Object.class)
                        .map(Method::getName)
                        .collect(java.util.stream.Collectors.toSet()),
                type.getName());
        Arrays.stream(methods)
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getDeclaringClass() != Object.class)
                .forEach(method -> {
                    assertNoRawHostType(method.getReturnType());
                    Arrays.stream(method.getParameterTypes())
                            .forEach(CompleteCubismModelReadApiContractTest::assertNoRawHostType);
                });
    }

    private static Class<?> returnType(final Class<?> type, final String method) throws Exception {
        return type.getMethod(method).getReturnType();
    }

    private static void assertNoRawHostType(final Class<?> type) {
        final String name = type.getName();
        if (name.startsWith("com.live2d.")) {
            throw new AssertionError("raw host type leaked: " + name);
        }
    }
}
