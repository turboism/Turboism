package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites reviewed warp-deformer, Bezier grid, overlay draw, and deform-brush classes across Cubism 5.0–5.3.
 *
 * <p>Every rewritten class verifies exact loader, code-source archive, and {@link ReviewedMethodShape}
 * equality against reviewed reference bytes before transforming any method. Never touches
 * {@code WarpDeformer_Draw.drawNormalPoint} so it composes cleanly with normal-point elision.</p>
 */
public final class WarpDeformEditTransformer implements ClassFileTransformer {

    private static final int EXPECTED_SELECTED_BUFFER_UPLOADS = 2;
    private static final int EXPECTED_WARP_SOURCE_COPY_OF_SITES = 1;
    private static final int EXPECTED_ROT_TRANSFORM_SCRATCH_SITES = 6;
    private static final int EXPECTED_ROT_HELPER_SCRATCH_SITES = 3;
    private static final int EXPECTED_SELECTION_MESH_UPDATE_SITES = 1;
    private static final int LOCAL_SAFETY_MARGIN = 4;
    private static final int[] ROT_TRANSFORM_SCRATCH_SLOTS = {0, 1, 2, 2, 3, 4};
    private static final int[] ROT_HELPER_SCRATCH_SLOTS = {5, 6, 7};

    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GET_PROPERTIES_METHOD = "getProperties";
    private static final String GET_PROPERTIES_DESCRIPTOR = "()Ljava/util/Properties;";
    private static final String PROPERTIES_OWNER = "java/util/Properties";
    private static final String PROPERTIES_GET_METHOD = "get";
    private static final String PROPERTIES_GET_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String ATOMIC_BOOLEAN_OWNER = "java/util/concurrent/atomic/AtomicBoolean";
    private static final String ATOMIC_GET_METHOD = "get";
    private static final String ATOMIC_GET_DESCRIPTOR = "()Z";
    private static final String BOOLEAN_OWNER = "java/lang/Boolean";
    private static final String BOOLEAN_FALSE_FIELD = "FALSE";
    private static final String BOOLEAN_DESCRIPTOR = "Ljava/lang/Boolean;";
    private static final String THROWABLE_OWNER = "java/lang/Throwable";
    private static final String METHOD_HANDLE_OWNER = "java/lang/invoke/MethodHandle";
    private static final String INVOKE_EXACT_METHOD = "invokeExact";
    private static final String ARRAYS_OWNER = "java/util/Arrays";
    private static final String ARRAYS_COPY_OF_METHOD = "copyOf";
    private static final String ARRAYS_COPY_OF_DESCRIPTOR = "([FI)[F";
    private static final String SYSTEM_ARRAYCOPY_METHOD = "arraycopy";
    private static final String SYSTEM_ARRAYCOPY_DESCRIPTOR = "(Ljava/lang/Object;ILjava/lang/Object;II)V";
    private static final String LIST_OWNER = "java/util/List";
    private static final String LIST_SIZE_METHOD = "size";
    private static final String LIST_SIZE_DESCRIPTOR = "()I";
    private static final String LIST_GET_METHOD = "get";
    private static final String LIST_GET_DESCRIPTOR = "(I)Ljava/lang/Object;";
    private static final String RANDOM_ACCESS_OWNER = "java/util/RandomAccess";
    private static final String FLOAT_GETTER_DESCRIPTOR = "()F";
    private static final String POSITIONS_FIELD_NAME = "positions";
    private static final String POSITIONS_FIELD_DESCRIPTOR = "[F";
    private static final String WEIGHT_MAP_FIELD = "eWarpDeformerWeightMap";
    private static final String SET_ENABLED_METHOD = "setEnabled";
    private static final String SET_ENABLED_DESCRIPTOR = "(Z)V";
    private static final String GET_SELECTION_METHOD = "getSelection";
    private static final String BUFFER_UPLOAD_METHOD = "l";
    private static final String BUFFER_UPLOAD_DESCRIPTOR = "()V";
    private static final String ACTION_PACK_OWNER = "com/live2d/cubism/view/context/actionManager/aG";
    private static final String ACTION_PACK_DEFORMERS_METHOD = "V";
    private static final String ACTION_PACK_DEFORMERS_DESC = "()Ljava/util/List;";
    private static final String CANVAS_SETTING_OWNER = "com/live2d/cubism/setting/AppSetting$CanvasSetting";
    private static final String HIDE_SELECTED_METHOD = "getHideSelectedState";
    private static final String HIDE_SELECTED_DESC = "()Z";
    private static final String GET_POSITIONS_BUFFER_METHOD = "getPositionsBuffer";
    private static final String GET_COLORS_BUFFER_METHOD = "getColorsBuffer";
    private static final String BUFFER_WRAPPER_DESCRIPTOR = "()L" + WarpDeformEditTarget.FLOAT_BUFFER_OWNER + ";";

    private static final String MH_FORM_IS_INSIDE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)I";
    private static final String MH_CANVAS_TO_LOCAL_DESC = "(Ljava/lang/Object;[F[FI)Z";
    private static final String MH_GET01_EX_QUAD_DESC = "(Ljava/lang/Object;FFLjava/lang/Object;[F)Ljava/lang/Object;";
    private static final String MH_INVERSE_QUAD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String MH_GRID_IS_INSIDE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Z)I";
    private static final String MH_CREATE_BEZIER_DESC = "(Ljava/lang/Object;II)[F";
    private static final String MH_CORNER_TRIANGLE_DESC = "(Ljava/lang/Object;[FIIFFLjava/lang/Object;)Z";
    private static final String MH_LINES_DESC = "(Ljava/lang/Object;[FIILjava/lang/Object;Z)Z";
    private static final String MH_SELECTED_UPLOAD_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Z)Z";
    private static final String MH_SOFT_SELECTION_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[FII)Z";

    private final ClassLoader loader;
    private final Path artifact;
    private final WarpDeformEditTarget target;
    private final Map<String, List<MethodSpec>> specsByOwner;
    private final Map<MethodKey, List<String>> referenceShapes;
    private final Map<String, Integer> matchesByOwner = new ConcurrentHashMap<>();
    private final SoftSelectionAnchor softSelectionAnchor;
    private final ActionTransformAnchors actionAnchors;
    private final String resolvedOverlayMethodName;
    private final String selectionViewOwner;
    private final String resolvedSelectionDrawMethodName;
    private final SelectionDrawScan selectionDrawScan;
    private final int warpSourceReinitNextFreeLocal;
    private final int rotTransformNextFreeLocal;
    private final int rotHelperNextFreeLocal;
    private volatile String failure;

    private record SelectionDrawScan(
            String methodName,
            int newCount,
            int updateCalls,
            int setVolatileAfterUpdateCalls,
            int meshSlot,
            int packSlot,
            int selectedSlot,
            int gluedSlot,
            int nextFreeLocal) {}

    private record MethodSpec(String name, String descriptor) {}

    private record MethodKey(String owner, String name, String descriptor) {}

    private record SoftSelectionAnchor(String descriptor, int selectionSlot, int colorSlot) {}

    private record ActionTransformAnchors(
            String helperFieldOwner,
            String helperFieldName,
            String helperFieldDesc,
            String helperCallOwner,
            String helperCallName,
            String helperCallDesc,
            String sampleMethodName,
            String sampleMethodDesc,
            String offsetOwner,
            String offsetFieldName,
            String offsetFieldDesc,
            String offsetGetterOwner,
            String offsetGetterName,
            String offsetGetterDesc) {}

    /**
     * Builds a transformer pinned to one reviewed {@link WarpDeformEditTarget} and reference class map.
     *
     * @param loader host loader permitted to receive rewrites
     * @param artifact reviewed Cubism JAR path matched via protection-domain code source
     * @param references unmodified reference bytecode keyed by internal class name
     * @param target resolved target descriptor for the artifact
     * @throws IllegalArgumentException when any reviewed method or structural anchor is absent
     */
    public WarpDeformEditTransformer(
            final ClassLoader loader,
            final Path artifact,
            final Map<String, byte[]> references,
            final WarpDeformEditTarget target) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.artifact =
                Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(references, "references");

        final Map<String, List<MethodSpec>> specs = new LinkedHashMap<>();
        final Map<MethodKey, List<String>> shapes = new LinkedHashMap<>();
        SoftSelectionAnchor resolvedSoftAnchor = null;
        ActionTransformAnchors resolvedActionAnchors = new ActionTransformAnchors(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        String detectedOverlayMethod = WarpDeformEditTarget.OVERLAY_HELPER_METHOD;
        final String expectedSelectionOwner =
                WarpDeformEditTarget.LEGACY_OVERLAY_VIEW_OWNER.equals(target.overlayOwner())
                        ? WarpDeformEditTarget.LEGACY_OVERLAY_VIEW_OWNER
                        : WarpDeformEditTarget.OVERLAY_VIEW_OWNER;
        String detectedSelectionMethod = null;
        SelectionDrawScan resolvedSelScan = null;
        int warpSourceFreeLocal = 4;
        int rotTransformFreeLocal = 16;
        int rotHelperFreeLocal = 14;

        for (final String owner : target.targetClasses()) {
            final byte[] refBytes = references.get(owner);
            if (refBytes == null) {
                throw new IllegalArgumentException("missing reference bytes for " + owner);
            }
            final List<MethodSpec> ownerMethods;
            if (WarpDeformEditTarget.WARP_DRAW_OWNER.equals(owner)) {
                resolvedSoftAnchor = inspectSoftSelection(refBytes);
                if (resolvedSoftAnchor == null) {
                    throw new IllegalArgumentException("drawSoftSelection anchor absent in " + owner);
                }
                ownerMethods = List.of(
                        new MethodSpec(
                                WarpDeformEditTarget.DRAW_CORNER_TRIANGLE_METHOD,
                                WarpDeformEditTarget.DRAW_CORNER_TRIANGLE_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.DRAW_MAJOR_LINES_METHOD,
                                WarpDeformEditTarget.DRAW_MAJOR_LINES_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.DRAW_DETAIL_LINES_METHOD,
                                WarpDeformEditTarget.DRAW_DETAIL_LINES_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.DRAW_SELECTED_POINT_METHOD,
                                WarpDeformEditTarget.DRAW_SELECTED_POINT_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.DRAW_SOFT_SELECTION_METHOD, resolvedSoftAnchor.descriptor()));
                if (countBufferUploadsInSelectedPoint(refBytes) != EXPECTED_SELECTED_BUFFER_UPLOADS) {
                    throw new IllegalArgumentException("drawSelectedPoint buffer upload count mismatch");
                }
            } else if (owner.startsWith(WarpDeformEditTarget.ACTION_PACKAGE_PREFIX)) {
                final MethodSpec actionMethod = detectActionMethod(refBytes);
                if (actionMethod == null) {
                    throw new IllegalArgumentException("action float method absent in " + owner);
                }
                resolvedActionAnchors = inspectActionAnchors(owner, actionMethod, refBytes, resolvedActionAnchors);
                ownerMethods = List.of(actionMethod);
            } else if (target.updaterOwner().equals(owner)) {
                ownerMethods = List.of(
                        new MethodSpec(WarpDeformEditTarget.UPDATE_MODEL_METHOD, target.coreUpdateDescriptor()),
                        new MethodSpec(WarpDeformEditTarget.UPDATE_MODEL_METHOD, target.artMeshAppearanceDescriptor()));
            } else if (target.overlayOwner().equals(owner) || expectedSelectionOwner.equals(owner)) {
                final java.util.ArrayList<MethodSpec> viewMethods = new java.util.ArrayList<>();
                if (target.overlayOwner().equals(owner)) {
                    detectedOverlayMethod = WarpDeformEditTarget.LEGACY_OVERLAY_VIEW_OWNER.equals(owner)
                            ? detectLegacyOverlayMethod(refBytes)
                            : WarpDeformEditTarget.OVERLAY_HELPER_METHOD;
                    if (detectedOverlayMethod == null) {
                        throw new IllegalArgumentException("legacy overlay loop method absent in " + owner);
                    }
                    viewMethods.add(new MethodSpec(detectedOverlayMethod, target.overlayMethodDescriptor()));
                }
                if (expectedSelectionOwner.equals(owner)) {
                    resolvedSelScan = inspectSelectionDrawMethod(refBytes);
                    detectedSelectionMethod = resolvedSelScan == null ? null : resolvedSelScan.methodName();
                    if (detectedSelectionMethod == null) {
                        throw new IllegalArgumentException("updateSelectionDraw method absent in " + owner);
                    }
                    if (resolvedSelScan.newCount() != EXPECTED_SELECTION_MESH_UPDATE_SITES
                            || resolvedSelScan.updateCalls() != EXPECTED_SELECTION_MESH_UPDATE_SITES
                            || resolvedSelScan.setVolatileAfterUpdateCalls() != EXPECTED_SELECTION_MESH_UPDATE_SITES
                            || resolvedSelScan.meshSlot() < 0
                            || resolvedSelScan.packSlot() < 0
                            || resolvedSelScan.selectedSlot() < 0
                            || resolvedSelScan.gluedSlot() < 0) {
                        throw new IllegalArgumentException(
                                "DrawEntity_ArtMesh_Selection selection structure mismatch in "
                                        + owner
                                        + ": "
                                        + resolvedSelScan);
                    }
                    viewMethods.add(
                            new MethodSpec(detectedSelectionMethod, WarpDeformEditTarget.SELECTION_DRAW_DESCRIPTOR));
                }
                ownerMethods = List.copyOf(viewMethods);
            } else {
                ownerMethods = fixedMethodsFor(owner);
                if (WarpDeformEditTarget.WARP_SOURCE_OWNER.equals(owner)) {
                    final int[] scan = inspectCopyOfAndMaxLocal(
                            refBytes,
                            WarpDeformEditTarget.WARP_SOURCE_REINIT_METHOD,
                            WarpDeformEditTarget.WARP_SOURCE_REINIT_DESCRIPTOR);
                    if (scan[0] != EXPECTED_WARP_SOURCE_COPY_OF_SITES) {
                        throw new IllegalArgumentException(
                                "CWarpDeformerSource.reinitInstance Arrays.copyOf count mismatch: " + scan[0]);
                    }
                    warpSourceFreeLocal = scan[1];
                } else if (WarpDeformEditTarget.ROT_FORM_OWNER.equals(owner)) {
                    final int[] scan = inspectFloat2AllocsAndMaxLocal(
                            refBytes,
                            WarpDeformEditTarget.ROT_TRANSFORM_EXE_METHOD,
                            WarpDeformEditTarget.ROT_TRANSFORM_EXE_DESCRIPTOR);
                    if (scan[0] != EXPECTED_ROT_TRANSFORM_SCRATCH_SITES) {
                        throw new IllegalArgumentException(
                                "CRotationDeformerForm.transform_exe float[2] count mismatch: " + scan[0]);
                    }
                    rotTransformFreeLocal = scan[1];
                } else if (WarpDeformEditTarget.ROT_HELPER_OWNER.equals(owner)) {
                    final int[] scan = inspectFloat2AllocsAndMaxLocal(
                            refBytes,
                            WarpDeformEditTarget.ROT_HELPER_METHOD,
                            WarpDeformEditTarget.ROT_HELPER_DESCRIPTOR);
                    if (scan[0] != EXPECTED_ROT_HELPER_SCRATCH_SITES) {
                        throw new IllegalArgumentException("CRotationDeformer$a.a float[2] count mismatch: " + scan[0]);
                    }
                    rotHelperFreeLocal = scan[1];
                }
            }
            specs.put(owner, ownerMethods);
            for (final MethodSpec spec : ownerMethods) {
                final List<String> shape = ReviewedMethodShape.read(refBytes, owner, spec.name(), spec.descriptor());
                if (shape == null) {
                    throw new IllegalArgumentException(
                            "reviewed method absent: " + owner + "." + spec.name() + spec.descriptor());
                }
                shapes.put(new MethodKey(owner, spec.name(), spec.descriptor()), shape);
            }
        }
        this.specsByOwner = Map.copyOf(specs);
        this.referenceShapes = Map.copyOf(shapes);
        this.softSelectionAnchor = Objects.requireNonNull(resolvedSoftAnchor);
        this.actionAnchors = resolvedActionAnchors;
        this.resolvedOverlayMethodName = detectedOverlayMethod;
        this.selectionViewOwner = expectedSelectionOwner;
        this.resolvedSelectionDrawMethodName = Objects.requireNonNull(detectedSelectionMethod, "selectionDrawMethod");
        this.selectionDrawScan = Objects.requireNonNull(resolvedSelScan, "selectionDrawScan");
        this.warpSourceReinitNextFreeLocal = warpSourceFreeLocal;
        this.rotTransformNextFreeLocal = rotTransformFreeLocal;
        this.rotHelperNextFreeLocal = rotHelperFreeLocal;
    }

    /**
     * Returns expected site match count for a reviewed owner class.
     *
     * @param ownerInternalName internal binary class name
     * @return expected match count for that owner, or {@code 0} if untargeted
     */
    public int expectedMatchesFor(final String ownerInternalName) {
        final List<MethodSpec> specs = specsByOwner.get(ownerInternalName);
        return specs == null ? 0 : specs.size();
    }

    /**
     * Returns observed matched-site count for one reviewed owner class.
     *
     * @param ownerInternalName internal binary class name
     * @return matched sites observed so far
     */
    public int matchesFor(final String ownerInternalName) {
        return matchesByOwner.getOrDefault(ownerInternalName, 0);
    }

    /** Returns total matched-site count across all rewritten target classes. */
    public int sites() {
        int sum = 0;
        for (final int count : matchesByOwner.values()) {
            sum += count;
        }
        return sum;
    }

    /** Returns expected total matched-site count across every target class in this artifact. */
    public int expectedTotalSites() {
        int sum = 0;
        for (final List<MethodSpec> specs : specsByOwner.values()) {
            sum += specs.size();
        }
        return sum;
    }

    /** Returns the most recent refusal reason, or {@code null} when every transform succeeded. */
    public String failure() {
        return failure;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (actualLoader != loader || name == null || bytes == null) {
            return null;
        }
        final List<MethodSpec> expectedMethods = specsByOwner.get(name);
        if (expectedMethods == null) {
            return null;
        }
        try {
            if (!HostCodeSource.matchesArtifact(domain, artifact)) {
                failure = "warp-deform-edit class source mismatch for " + name;
                return null;
            }
            for (final MethodSpec spec : expectedMethods) {
                final MethodKey key = new MethodKey(name, spec.name(), spec.descriptor());
                final List<String> observed = ReviewedMethodShape.read(bytes, name, spec.name(), spec.descriptor());
                if (!referenceShapes.get(key).equals(observed)) {
                    failure = "warp-deform-edit method shape mismatch: " + name + "." + spec.name() + spec.descriptor();
                    return null;
                }
            }

            final ClassReader reader = new ClassReader(bytes);
            final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
            final int[] matched = {0};
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String methodName,
                                final String desc,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor out =
                                    super.visitMethod(access, methodName, desc, signature, exceptions);
                            for (final MethodSpec spec : expectedMethods) {
                                if (spec.name().equals(methodName)
                                        && spec.descriptor().equals(desc)) {
                                    matched[0]++;
                                    return adaptMethod(name, methodName, desc, out);
                                }
                            }
                            return out;
                        }
                    },
                    ClassReader.EXPAND_FRAMES);

            if (matched[0] != expectedMethods.size()) {
                failure = "warp-deform-edit matched " + matched[0] + "/" + expectedMethods.size() + " in " + name;
                return null;
            }
            matchesByOwner.put(name, matched[0]);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError unexpected) {
            failure = unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage();
            return null;
        }
    }

    private MethodVisitor adaptMethod(
            final String owner, final String methodName, final String desc, final MethodVisitor out) {
        if (WarpDeformEditTarget.WARP_FORM_OWNER.equals(owner)) {
            if (WarpDeformEditTarget.REINIT_METHOD.equals(methodName)) {
                return new ReinitAdapter(out);
            }
            if (WarpDeformEditTarget.FORM_IS_INSIDE_METHOD.equals(methodName)) {
                return new EntrySlotIntBoolAdapter(
                        out, WarpDeformEditBridge.FORM_IS_INSIDE_PROPERTY, MH_FORM_IS_INSIDE_DESC, new int[] {0, 1});
            }
        }
        if (WarpDeformEditTarget.WARP_DEFORMER_OWNER.equals(owner)) {
            return new EntryVoidBoolGateAdapter(
                    out,
                    WarpDeformEditBridge.TRANSFORM_CANVAS_TO_LOCAL_PROPERTY,
                    MH_CANVAS_TO_LOCAL_DESC,
                    new int[] {0, 1, 2, 3},
                    new int[] {Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD});
        }
        if (WarpDeformEditTarget.SIMPLE_GRID_OWNER.equals(owner)) {
            return new Get01ExQuadAdapter(out);
        }
        if (WarpDeformEditTarget.GRID_TRANSFORM_OWNER.equals(owner)) {
            if (WarpDeformEditTarget.INVERSE_TRANSFORM_3_3_QUAD_METHOD.equals(methodName)) {
                return new InverseQuadAdapter(out);
            }
            if (WarpDeformEditTarget.GRID_TRANSFORM_IS_INSIDE_METHOD.equals(methodName)) {
                return new GridIsInsideAdapter(out);
            }
        }
        if (WarpDeformEditTarget.BEZIER_GRID_OWNER.equals(owner)) {
            return new CreatePointsOnBezierAdapter(out);
        }
        if (WarpDeformEditTarget.WARP_DRAW_OWNER.equals(owner)) {
            return switch (methodName) {
                case WarpDeformEditTarget.DRAW_CORNER_TRIANGLE_METHOD ->
                    new EntryVoidBoolGateAdapter(
                            out,
                            WarpDeformEditBridge.DRAW_CORNER_TRIANGLE_PROPERTY,
                            MH_CORNER_TRIANGLE_DESC,
                            new int[] {0, 1, 2, 3, 4, 5, 6},
                            new int[] {
                                Opcodes.ALOAD,
                                Opcodes.ALOAD,
                                Opcodes.ILOAD,
                                Opcodes.ILOAD,
                                Opcodes.FLOAD,
                                Opcodes.FLOAD,
                                Opcodes.ALOAD
                            });
                case WarpDeformEditTarget.DRAW_MAJOR_LINES_METHOD ->
                    new EntryVoidBoolGateAdapter(
                            out,
                            WarpDeformEditBridge.DRAW_MAJOR_LINES_PROPERTY,
                            MH_LINES_DESC,
                            new int[] {0, 1, 2, 3, 4, 5},
                            new int[] {
                                Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ILOAD
                            });
                case WarpDeformEditTarget.DRAW_DETAIL_LINES_METHOD ->
                    new EntryVoidBoolGateAdapter(
                            out,
                            WarpDeformEditBridge.DRAW_DETAIL_LINES_PROPERTY,
                            MH_LINES_DESC,
                            new int[] {0, 1, 2, 3, 4, 5},
                            new int[] {
                                Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.ILOAD, Opcodes.ALOAD, Opcodes.ILOAD
                            });
                case WarpDeformEditTarget.DRAW_SELECTED_POINT_METHOD -> new DrawSelectedPointAdapter(out);
                case WarpDeformEditTarget.DRAW_SOFT_SELECTION_METHOD ->
                    new DrawSoftSelectionAdapter(
                            out, softSelectionAnchor.selectionSlot(), softSelectionAnchor.colorSlot());
                default -> out;
            };
        }
        if (owner.startsWith(WarpDeformEditTarget.ACTION_PACKAGE_PREFIX)) {
            if (WarpDeformEditTarget.ACTION_DEFORM_LIST_TO_FLOATS_METHOD.equals(methodName)) {
                return new ActionListToFloatsAdapter(out);
            }
            if (WarpDeformEditTarget.ACTION_LEGACY_TRANSFORM_FLOATS_METHOD.equals(methodName)) {
                return new ActionLegacyTransformAdapter(out, owner, actionAnchors);
            }
            if (WarpDeformEditTarget.ACTION_OFFSET_FLOATS_METHOD.equals(methodName)) {
                return new ActionOffsetFloatsAdapter(out, owner, actionAnchors);
            }
        }
        if (target.updaterOwner().equals(owner) && WarpDeformEditTarget.UPDATE_MODEL_METHOD.equals(methodName)) {
            if (target.coreUpdateDescriptor().equals(desc)) {
                return EditSubtreeMethodWeavers.coreUpdateAdapter(out);
            }
            if (target.artMeshAppearanceDescriptor().equals(desc)) {
                return EditSubtreeMethodWeavers.artMeshAppearanceAdapter(out);
            }
        }
        if (WarpDeformEditTarget.WARP_SOURCE_OWNER.equals(owner)
                && WarpDeformEditTarget.WARP_SOURCE_REINIT_METHOD.equals(methodName)) {
            return EditSubtreeMethodWeavers.warpSourceReinitAdapter(out, warpSourceReinitNextFreeLocal);
        }
        if (WarpDeformEditTarget.ROT_FORM_OWNER.equals(owner)
                && WarpDeformEditTarget.ROT_TRANSFORM_EXE_METHOD.equals(methodName)) {
            return EditSubtreeMethodWeavers.rotationScratchAdapter(
                    out, ROT_TRANSFORM_SCRATCH_SLOTS, rotTransformNextFreeLocal);
        }
        if (WarpDeformEditTarget.ROT_HELPER_OWNER.equals(owner)
                && WarpDeformEditTarget.ROT_HELPER_METHOD.equals(methodName)) {
            return EditSubtreeMethodWeavers.rotationScratchAdapter(
                    out, ROT_HELPER_SCRATCH_SLOTS, rotHelperNextFreeLocal);
        }
        if (selectionViewOwner.equals(owner)
                && resolvedSelectionDrawMethodName.equals(methodName)
                && WarpDeformEditTarget.SELECTION_DRAW_DESCRIPTOR.equals(desc)) {
            return EditSubtreeMethodWeavers.selectionDrawAdapter(
                    out,
                    selectionDrawScan.meshSlot(),
                    selectionDrawScan.packSlot(),
                    selectionDrawScan.selectedSlot(),
                    selectionDrawScan.gluedSlot(),
                    selectionDrawScan.nextFreeLocal());
        }
        if (WarpDeformEditTarget.ACFORM_OWNER.equals(owner)) {
            return new EditSubtreeMethodWeavers.EpochIncrementEntryAdapter(
                    out, WarpDeformEditBridge.MESH_KEYFORM_EPOCH_PROPERTY, true);
        }
        if (WarpDeformEditTarget.KEYFORM_BINDING_SOURCE_OWNER.equals(owner)) {
            if (WarpDeformEditTarget.GET_KEY_INDICES_IN_RANGE_METHOD.equals(methodName)) {
                return new EditSubtreeMethodWeavers.KeyIndicesInRangeAdapter(out);
            }
            return new EditSubtreeMethodWeavers.EpochIncrementEntryAdapter(
                    out, WarpDeformEditBridge.GRID_MUTATION_EPOCH_PROPERTY, false);
        }
        if (WarpDeformEditTarget.KEYFORM_GRID_SOURCE_OWNER.equals(owner)) {
            if (WarpDeformEditTarget.FIND_KEYFORM_METHOD.equals(methodName)) {
                return new EditSubtreeMethodWeavers.FindKeyformAdapter(
                        out, WarpDeformEditTarget.FIND_KEYFORM_SEARCH_DESCRIPTOR.equals(desc));
            }
            return new EditSubtreeMethodWeavers.EpochIncrementEntryAdapter(
                    out, WarpDeformEditBridge.GRID_MUTATION_EPOCH_PROPERTY, false);
        }
        if (target.overlayOwner().equals(owner)
                && resolvedOverlayMethodName.equals(methodName)
                && target.overlayMethodDescriptor().equals(desc)) {
            return WarpDeformEditTarget.LEGACY_OVERLAY_VIEW_OWNER.equals(owner)
                    ? EditSubtreeMethodWeavers.legacyOverlayViewAdapter(out)
                    : EditSubtreeMethodWeavers.overlayHelperAdapter(out);
        }
        return out;
    }

    private static List<MethodSpec> buildKeyformGridMethodSpecs() {
        final List<MethodSpec> specs =
                new java.util.ArrayList<>(2 + WarpDeformEditTarget.KEYFORM_GRID_MUTATOR_DESCRIPTORS.size());
        specs.add(new MethodSpec(
                WarpDeformEditTarget.FIND_KEYFORM_METHOD, WarpDeformEditTarget.FIND_KEYFORM_DEFAULT_DESCRIPTOR));
        specs.add(new MethodSpec(
                WarpDeformEditTarget.FIND_KEYFORM_METHOD, WarpDeformEditTarget.FIND_KEYFORM_SEARCH_DESCRIPTOR));
        for (final Map.Entry<String, String> entry : WarpDeformEditTarget.KEYFORM_GRID_MUTATOR_DESCRIPTORS.entrySet()) {
            specs.add(new MethodSpec(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(specs);
    }

    private static List<MethodSpec> fixedMethodsFor(final String owner) {
        return switch (owner) {
            case WarpDeformEditTarget.ACFORM_OWNER ->
                List.of(
                        new MethodSpec(
                                WarpDeformEditTarget.SET_UPDATED_METHOD, WarpDeformEditTarget.SET_UPDATED_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.REINIT_METHOD, WarpDeformEditTarget.KEYFORM_REINIT_DESCRIPTOR));
            case WarpDeformEditTarget.KEYFORM_BINDING_SOURCE_OWNER ->
                List.of(
                        new MethodSpec(
                                WarpDeformEditTarget.GET_KEY_INDICES_IN_RANGE_METHOD,
                                WarpDeformEditTarget.GET_KEY_INDICES_IN_RANGE_DESCRIPTOR),
                        new MethodSpec(WarpDeformEditTarget.SET_KEYS_METHOD, WarpDeformEditTarget.SET_KEYS_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.REINIT_METHOD, WarpDeformEditTarget.KEYFORM_REINIT_DESCRIPTOR));
            case WarpDeformEditTarget.KEYFORM_GRID_SOURCE_OWNER -> buildKeyformGridMethodSpecs();
            case WarpDeformEditTarget.WARP_FORM_OWNER ->
                List.of(
                        new MethodSpec(WarpDeformEditTarget.REINIT_METHOD, WarpDeformEditTarget.REINIT_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.FORM_IS_INSIDE_METHOD,
                                WarpDeformEditTarget.FORM_IS_INSIDE_DESCRIPTOR));
            case WarpDeformEditTarget.WARP_SOURCE_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.WARP_SOURCE_REINIT_METHOD,
                        WarpDeformEditTarget.WARP_SOURCE_REINIT_DESCRIPTOR));
            case WarpDeformEditTarget.WARP_DEFORMER_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.TRANSFORM_CANVAS_TO_LOCAL_METHOD,
                        WarpDeformEditTarget.TRANSFORM_CANVAS_TO_LOCAL_DESCRIPTOR));
            case WarpDeformEditTarget.ROT_FORM_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.ROT_TRANSFORM_EXE_METHOD,
                        WarpDeformEditTarget.ROT_TRANSFORM_EXE_DESCRIPTOR));
            case WarpDeformEditTarget.ROT_HELPER_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.ROT_HELPER_METHOD, WarpDeformEditTarget.ROT_HELPER_DESCRIPTOR));
            case WarpDeformEditTarget.SIMPLE_GRID_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.GET01_EX_QUAD_METHOD, WarpDeformEditTarget.GET01_EX_QUAD_DESCRIPTOR));
            case WarpDeformEditTarget.GRID_TRANSFORM_OWNER ->
                List.of(
                        new MethodSpec(
                                WarpDeformEditTarget.INVERSE_TRANSFORM_3_3_QUAD_METHOD,
                                WarpDeformEditTarget.INVERSE_TRANSFORM_3_3_QUAD_DESCRIPTOR),
                        new MethodSpec(
                                WarpDeformEditTarget.GRID_TRANSFORM_IS_INSIDE_METHOD,
                                WarpDeformEditTarget.GRID_TRANSFORM_IS_INSIDE_DESCRIPTOR));
            case WarpDeformEditTarget.BEZIER_GRID_OWNER ->
                List.of(new MethodSpec(
                        WarpDeformEditTarget.CREATE_POINTS_ON_BEZIER_METHOD,
                        WarpDeformEditTarget.CREATE_POINTS_ON_BEZIER_DESCRIPTOR));
            default -> throw new IllegalArgumentException("unsupported fixed owner: " + owner);
        };
    }

    private static int[] inspectCopyOfAndMaxLocal(
            final byte[] bytes, final String methodName, final String methodDesc) {
        final int[] res = {0, LOCAL_SAFETY_MARGIN};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!methodName.equals(name) || !methodDesc.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private int maxLocal = 2;

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                    }

                                    @Override
                                    public void visitIincInsn(final int varIndex, final int increment) {
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int op,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        if (op == Opcodes.INVOKESTATIC
                                                && ARRAYS_OWNER.equals(owner)
                                                && ARRAYS_COPY_OF_METHOD.equals(mName)
                                                && ARRAYS_COPY_OF_DESCRIPTOR.equals(mDesc)) {
                                            res[0]++;
                                        }
                                    }

                                    @Override
                                    public void visitEnd() {
                                        res[1] = maxLocal + LOCAL_SAFETY_MARGIN;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return res;
    }

    private static int[] inspectFloat2AllocsAndMaxLocal(
            final byte[] bytes, final String methodName, final String methodDesc) {
        final int[] res = {0, LOCAL_SAFETY_MARGIN};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!methodName.equals(name) || !methodDesc.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private int maxLocal = 5;
                                    private boolean prevIconst2;

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        prevIconst2 = opcode == Opcodes.ICONST_2;
                                    }

                                    @Override
                                    public void visitIntInsn(final int opcode, final int operand) {
                                        if (prevIconst2 && opcode == Opcodes.NEWARRAY && operand == Opcodes.T_FLOAT) {
                                            res[0]++;
                                        }
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        prevIconst2 = false;
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                    }

                                    @Override
                                    public void visitIincInsn(final int varIndex, final int increment) {
                                        prevIconst2 = false;
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int op, final String o, final String n, final String d) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int op,
                                            final String o,
                                            final String n,
                                            final String d,
                                            final boolean itf) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitJumpInsn(final int op, final Label label) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitLabel(final Label label) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitTypeInsn(final int op, final String type) {
                                        prevIconst2 = false;
                                    }

                                    @Override
                                    public void visitEnd() {
                                        res[1] = maxLocal + LOCAL_SAFETY_MARGIN;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return res;
    }

    private static SelectionDrawScan inspectSelectionDrawMethod(final byte[] bytes) {
        final SelectionDrawScan[] res = {null};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!WarpDeformEditTarget.SELECTION_DRAW_DESCRIPTOR.equals(desc)
                                        || name.length() != 1) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private boolean sawKey;
                                    private int newCount;
                                    private int updateCalls;
                                    private int setVolatileAfterUpdateCalls;
                                    private boolean inMeshCtor;
                                    private boolean awaitingSetVolatile;
                                    private int lastAloadInCtor = -1;
                                    private int meshSlot = -1;
                                    private int packSlot = -1;
                                    private int selectedSlot = -1;
                                    private int gluedSlot = -1;
                                    private final int[] recentOps = new int[3];
                                    private final int[] recentVars = {-1, -1, -1};
                                    private int maxLocal = 1;

                                    @Override
                                    public void visitLdcInsn(final Object value) {
                                        if (WarpDeformEditTarget.SELECTION_DRAW_KEY.equals(value)) {
                                            sawKey = true;
                                        }
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        if (opcode == Opcodes.NEW
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.equals(
                                                        type)) {
                                            newCount++;
                                            inMeshCtor = true;
                                            lastAloadInCtor = -1;
                                        }
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                        if (opcode == Opcodes.ALOAD && inMeshCtor) {
                                            lastAloadInCtor = varIndex;
                                        }
                                        if (opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD) {
                                            recentOps[0] = recentOps[1];
                                            recentOps[1] = recentOps[2];
                                            recentOps[2] = opcode;
                                            recentVars[0] = recentVars[1];
                                            recentVars[1] = recentVars[2];
                                            recentVars[2] = varIndex;
                                        }
                                    }

                                    @Override
                                    public void visitIincInsn(final int varIndex, final int increment) {
                                        maxLocal = Math.max(maxLocal, varIndex + 2);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int op,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        if (op == Opcodes.INVOKESPECIAL
                                                && inMeshCtor
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.equals(
                                                        owner)
                                                && WarpDeformEditTarget.INIT_METHOD.equals(mName)
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_CTOR_DESCRIPTOR.equals(
                                                        mDesc)) {
                                            inMeshCtor = false;
                                            meshSlot = lastAloadInCtor;
                                        } else if (op == Opcodes.INVOKEVIRTUAL
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_SELECTION_OWNER.equals(
                                                        owner)
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_UPDATE_METHOD.equals(mName)
                                                && WarpDeformEditTarget.DRAW_ENTITY_ARTMESH_UPDATE_DESCRIPTOR.equals(
                                                        mDesc)) {
                                            updateCalls++;
                                            if (recentOps[0] == Opcodes.ALOAD
                                                    && recentOps[1] == Opcodes.ILOAD
                                                    && recentOps[2] == Opcodes.ILOAD) {
                                                packSlot = recentVars[0];
                                                selectedSlot = recentVars[1];
                                                gluedSlot = recentVars[2];
                                            }
                                            awaitingSetVolatile = true;
                                        } else if (awaitingSetVolatile
                                                && op == Opcodes.INVOKEVIRTUAL
                                                && WarpDeformEditTarget.SCENE_GRAPH_SET_VOLATILE_METHOD.equals(mName)
                                                && WarpDeformEditTarget.SCENE_GRAPH_SET_VOLATILE_DESCRIPTOR.equals(
                                                        mDesc)) {
                                            awaitingSetVolatile = false;
                                            setVolatileAfterUpdateCalls++;
                                        }
                                    }

                                    @Override
                                    public void visitEnd() {
                                        if (sawKey) {
                                            res[0] = new SelectionDrawScan(
                                                    name,
                                                    newCount,
                                                    updateCalls,
                                                    setVolatileAfterUpdateCalls,
                                                    meshSlot,
                                                    packSlot,
                                                    selectedSlot,
                                                    gluedSlot,
                                                    maxLocal + LOCAL_SAFETY_MARGIN);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return res[0];
    }

    private static String detectLegacyOverlayMethod(final byte[] bytes) {
        final String[] found = {null};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!WarpDeformEditTarget.LEGACY_OVERLAY_DESCRIPTOR.equals(desc)
                                        || (!WarpDeformEditTarget.LEGACY_OVERLAY_METHOD_V500.equals(name)
                                                && !WarpDeformEditTarget.LEGACY_OVERLAY_METHOD_V506.equals(name))) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private boolean readsDeformers;
                                    private boolean checksHideSelected;

                                    @Override
                                    public void visitMethodInsn(
                                            final int op,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        if (ACTION_PACK_OWNER.equals(owner)
                                                && ACTION_PACK_DEFORMERS_METHOD.equals(mName)
                                                && ACTION_PACK_DEFORMERS_DESC.equals(mDesc)) {
                                            readsDeformers = true;
                                        }
                                        if (CANVAS_SETTING_OWNER.equals(owner)
                                                && HIDE_SELECTED_METHOD.equals(mName)
                                                && HIDE_SELECTED_DESC.equals(mDesc)) {
                                            checksHideSelected = true;
                                        }
                                    }

                                    @Override
                                    public void visitEnd() {
                                        if (readsDeformers && checksHideSelected) {
                                            found[0] = name;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static MethodSpec detectActionMethod(final byte[] bytes) {
        final MethodSpec[] found = {null};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (WarpDeformEditTarget.ACTION_DEFORM_LIST_TO_FLOATS_METHOD.equals(name)
                                        && WarpDeformEditTarget.ACTION_DEFORM_LIST_TO_FLOATS_DESCRIPTOR.equals(desc)) {
                                    found[0] = new MethodSpec(name, desc);
                                } else if (WarpDeformEditTarget.ACTION_LEGACY_TRANSFORM_FLOATS_METHOD.equals(name)
                                        && WarpDeformEditTarget.ACTION_LEGACY_TRANSFORM_FLOATS_DESCRIPTOR.equals(
                                                desc)) {
                                    found[0] = new MethodSpec(name, desc);
                                } else if (WarpDeformEditTarget.ACTION_OFFSET_FLOATS_METHOD.equals(name)
                                        && WarpDeformEditTarget.ACTION_OFFSET_FLOATS_DESCRIPTOR.equals(desc)) {
                                    found[0] = new MethodSpec(name, desc);
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
        return found[0];
    }

    private static ActionTransformAnchors inspectActionAnchors(
            final String owner, final MethodSpec method, final byte[] bytes, final ActionTransformAnchors prior) {
        if (WarpDeformEditTarget.ACTION_LEGACY_TRANSFORM_FLOATS_METHOD.equals(method.name())) {
            final String[] captured = new String[8];
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String sig,
                                        final String[] ex) {
                                    if (!method.name().equals(name)
                                            || !method.descriptor().equals(desc)) {
                                        return null;
                                    }
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitFieldInsn(
                                                final int opcode,
                                                final String fOwner,
                                                final String fName,
                                                final String fDesc) {
                                            if (opcode == Opcodes.GETSTATIC && captured[0] == null) {
                                                captured[0] = fOwner;
                                                captured[1] = fName;
                                                captured[2] = fDesc;
                                            }
                                        }

                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String mOwner,
                                                final String mName,
                                                final String mDesc,
                                                final boolean itf) {
                                            if (opcode == Opcodes.INVOKEVIRTUAL && captured[3] == null) {
                                                captured[3] = mOwner;
                                                captured[4] = mName;
                                                captured[5] = mDesc;
                                            } else if (opcode == Opcodes.INVOKESPECIAL
                                                    && owner.equals(mOwner)
                                                    && captured[6] == null) {
                                                captured[6] = mName;
                                                captured[7] = mDesc;
                                            }
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_FRAMES);
            for (final String s : captured) {
                if (s == null) {
                    throw new IllegalArgumentException("missing legacy action anchor in " + owner);
                }
            }
            return new ActionTransformAnchors(
                    captured[0],
                    captured[1],
                    captured[2],
                    captured[3],
                    captured[4],
                    captured[5],
                    captured[6],
                    captured[7],
                    prior.offsetOwner(),
                    prior.offsetFieldName(),
                    prior.offsetFieldDesc(),
                    prior.offsetGetterOwner(),
                    prior.offsetGetterName(),
                    prior.offsetGetterDesc());
        }
        if (WarpDeformEditTarget.ACTION_OFFSET_FLOATS_METHOD.equals(method.name())) {
            final String[] captured = new String[6];
            new ClassReader(bytes)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String sig,
                                        final String[] ex) {
                                    if (!method.name().equals(name)
                                            || !method.descriptor().equals(desc)) {
                                        return null;
                                    }
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitFieldInsn(
                                                final int opcode,
                                                final String fOwner,
                                                final String fName,
                                                final String fDesc) {
                                            if (opcode == Opcodes.GETFIELD && captured[0] == null) {
                                                captured[0] = fOwner;
                                                captured[1] = fName;
                                                captured[2] = fDesc;
                                            }
                                        }

                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String mOwner,
                                                final String mName,
                                                final String mDesc,
                                                final boolean itf) {
                                            if (opcode == Opcodes.INVOKEVIRTUAL && captured[3] == null) {
                                                captured[3] = mOwner;
                                                captured[4] = mName;
                                                captured[5] = mDesc;
                                            }
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_FRAMES);
            for (final String s : captured) {
                if (s == null) {
                    throw new IllegalArgumentException("missing offset action anchor in " + owner);
                }
            }
            return new ActionTransformAnchors(
                    prior.helperFieldOwner(),
                    prior.helperFieldName(),
                    prior.helperFieldDesc(),
                    prior.helperCallOwner(),
                    prior.helperCallName(),
                    prior.helperCallDesc(),
                    prior.sampleMethodName(),
                    prior.sampleMethodDesc(),
                    captured[0],
                    captured[1],
                    captured[2],
                    captured[3],
                    captured[4],
                    captured[5]);
        }
        return prior;
    }

    private static SoftSelectionAnchor inspectSoftSelection(final byte[] bytes) {
        final String[] methodDesc = {null};
        final int[] slots = {-1, -1, 0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!WarpDeformEditTarget.DRAW_SOFT_SELECTION_METHOD.equals(name)
                                        || !desc.startsWith(
                                                WarpDeformEditTarget.DRAW_SOFT_SELECTION_DESCRIPTOR_PREFIX)) {
                                    return null;
                                }
                                methodDesc[0] = desc;
                                return new MethodVisitor(Opcodes.ASM9) {
                                    private boolean sawGetSelection;
                                    private int lastAstore = -1;
                                    private boolean sawWeightField;
                                    private boolean sawIcon1;

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && WarpDeformEditTarget.WARP_SOURCE_OWNER.equals(owner)
                                                && GET_SELECTION_METHOD.equals(mName)) {
                                            sawGetSelection = true;
                                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                                && WarpDeformEditTarget.SIMPLE_MESH_ENTITY_OWNER.equals(owner)
                                                && SET_ENABLED_METHOD.equals(mName)
                                                && SET_ENABLED_DESCRIPTOR.equals(mDesc)
                                                && sawWeightField
                                                && sawIcon1) {
                                            slots[1] = lastAstore;
                                            slots[2]++;
                                        }
                                        sawWeightField = false;
                                        sawIcon1 = false;
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            final int opcode,
                                            final String fOwner,
                                            final String fName,
                                            final String fDesc) {
                                        sawGetSelection = false;
                                        sawIcon1 = false;
                                        sawWeightField = opcode == Opcodes.GETFIELD
                                                && WarpDeformEditTarget.WARP_DRAW_OWNER.equals(fOwner)
                                                && WEIGHT_MAP_FIELD.equals(fName);
                                    }

                                    @Override
                                    public void visitInsn(final int opcode) {
                                        sawGetSelection = false;
                                        sawIcon1 = sawWeightField && opcode == Opcodes.ICONST_1;
                                        if (!sawIcon1) {
                                            sawWeightField = false;
                                        }
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int varIndex) {
                                        if (opcode == Opcodes.ASTORE) {
                                            if (sawGetSelection && slots[0] == -1) {
                                                slots[0] = varIndex;
                                            }
                                            lastAstore = varIndex;
                                        }
                                        sawGetSelection = false;
                                        sawWeightField = false;
                                        sawIcon1 = false;
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        if (methodDesc[0] == null || slots[0] < 0 || slots[1] < 0 || slots[2] != 1) {
            return null;
        }
        return new SoftSelectionAnchor(methodDesc[0], slots[0], slots[1]);
    }

    private static int countBufferUploadsInSelectedPoint(final byte[] bytes) {
        final int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!WarpDeformEditTarget.DRAW_SELECTED_POINT_METHOD.equals(name)
                                        || !WarpDeformEditTarget.DRAW_SELECTED_POINT_DESCRIPTOR.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        if (opcode == Opcodes.INVOKEVIRTUAL
                                                && WarpDeformEditTarget.FLOAT_BUFFER_OWNER.equals(owner)
                                                && BUFFER_UPLOAD_METHOD.equals(mName)
                                                && BUFFER_UPLOAD_DESCRIPTOR.equals(mDesc)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static void emitActiveSlotCheck(final MethodVisitor out, final Label fallback) {
        out.visitMethodInsn(
                Opcodes.INVOKESTATIC, SYSTEM_OWNER, GET_PROPERTIES_METHOD, GET_PROPERTIES_DESCRIPTOR, false);
        out.visitLdcInsn(WarpDeformEditBridge.ACTIVE_PROPERTY);
        out.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, PROPERTIES_OWNER, PROPERTIES_GET_METHOD, PROPERTIES_GET_DESCRIPTOR, false);
        out.visitInsn(Opcodes.DUP);
        out.visitTypeInsn(Opcodes.INSTANCEOF, ATOMIC_BOOLEAN_OWNER);
        final Label castOk = new Label();
        out.visitJumpInsn(Opcodes.IFNE, castOk);
        out.visitInsn(Opcodes.POP);
        out.visitJumpInsn(Opcodes.GOTO, fallback);
        out.visitLabel(castOk);
        out.visitTypeInsn(Opcodes.CHECKCAST, ATOMIC_BOOLEAN_OWNER);
        out.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, ATOMIC_BOOLEAN_OWNER, ATOMIC_GET_METHOD, ATOMIC_GET_DESCRIPTOR, false);
        out.visitJumpInsn(Opcodes.IFEQ, fallback);
    }

    private static final class ReinitAdapter extends MethodVisitor {

        private static final int SRC_LOCAL = 3;
        private static final int LEN_LOCAL = 4;
        private static final int DST_LOCAL = 5;

        private ReinitAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (opcode == Opcodes.INVOKESTATIC
                    && ARRAYS_OWNER.equals(owner)
                    && ARRAYS_COPY_OF_METHOD.equals(name)
                    && ARRAYS_COPY_OF_DESCRIPTOR.equals(desc)) {
                final Label fallback = new Label();
                final Label done = new Label();
                super.visitVarInsn(Opcodes.ISTORE, LEN_LOCAL);
                super.visitVarInsn(Opcodes.ASTORE, SRC_LOCAL);
                emitActiveSlotCheck(mv, fallback);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(
                        Opcodes.GETFIELD,
                        WarpDeformEditTarget.WARP_FORM_OWNER,
                        POSITIONS_FIELD_NAME,
                        POSITIONS_FIELD_DESCRIPTOR);
                super.visitInsn(Opcodes.DUP);
                super.visitVarInsn(Opcodes.ASTORE, DST_LOCAL);
                super.visitJumpInsn(Opcodes.IFNULL, fallback);
                super.visitVarInsn(Opcodes.ALOAD, DST_LOCAL);
                super.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
                super.visitJumpInsn(Opcodes.IF_ACMPEQ, fallback);
                super.visitVarInsn(Opcodes.ALOAD, DST_LOCAL);
                super.visitInsn(Opcodes.ARRAYLENGTH);
                super.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
                super.visitJumpInsn(Opcodes.IF_ICMPNE, fallback);
                super.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitVarInsn(Opcodes.ALOAD, DST_LOCAL);
                super.visitInsn(Opcodes.ICONST_0);
                super.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        SYSTEM_OWNER,
                        SYSTEM_ARRAYCOPY_METHOD,
                        SYSTEM_ARRAYCOPY_DESCRIPTOR,
                        false);
                super.visitVarInsn(Opcodes.ALOAD, DST_LOCAL);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(fallback);
                super.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
                super.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                super.visitLabel(done);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, DST_LOCAL + 1));
        }
    }

    private static final class EntrySlotIntBoolAdapter extends MethodVisitor {

        private final String slotKey;
        private final String mhDesc;
        private final int[] argSlots;

        private EntrySlotIntBoolAdapter(
                final MethodVisitor out, final String slotKey, final String mhDesc, final int[] argSlots) {
            super(Opcodes.ASM9, out);
            this.slotKey = slotKey;
            this.mhDesc = mhDesc;
            this.argSlots = argSlots;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label negative = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, slotKey, notHandle);
            for (final int slot : argSlots) {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, mhDesc, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFLT, negative);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(negative);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class EntryVoidBoolGateAdapter extends MethodVisitor {

        private final String slotKey;
        private final String mhDesc;
        private final int[] argSlots;
        private final int[] loadOpcodes;

        private EntryVoidBoolGateAdapter(
                final MethodVisitor out,
                final String slotKey,
                final String mhDesc,
                final int[] argSlots,
                final int[] loadOpcodes) {
            super(Opcodes.ASM9, out);
            this.slotKey = slotKey;
            this.mhDesc = mhDesc;
            this.argSlots = argSlots;
            this.loadOpcodes = loadOpcodes;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, slotKey, notHandle);
            for (int i = 0; i < argSlots.length; i++) {
                mv.visitVarInsn(loadOpcodes[i], argSlots[i]);
            }
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, mhDesc, false);
            mv.visitJumpInsn(Opcodes.IFEQ, fallback);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class Get01ExQuadAdapter extends MethodVisitor {

        private Get01ExQuadAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.GET01_EX_QUAD_PROPERTY, notHandle);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.FLOAD, 1);
            mv.visitVarInsn(Opcodes.FLOAD, 2);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_GET01_EX_QUAD_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETSTATIC, BOOLEAN_OWNER, BOOLEAN_FALSE_FIELD, BOOLEAN_DESCRIPTOR);
            mv.visitJumpInsn(Opcodes.IF_ACMPEQ, notHandle);
            mv.visitTypeInsn(Opcodes.CHECKCAST, POSITIONS_FIELD_DESCRIPTOR);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class InverseQuadAdapter extends MethodVisitor {

        private InverseQuadAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(mv, WarpDeformEditBridge.INVERSE_QUAD_PROPERTY, notHandle);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_INVERSE_QUAD_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, notHandle);
            mv.visitTypeInsn(Opcodes.CHECKCAST, WarpDeformEditTarget.GVECTOR2_OWNER);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class GridIsInsideAdapter extends MethodVisitor {

        private GridIsInsideAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label negative = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(
                    mv, WarpDeformEditBridge.GRID_TRANSFORM_IS_INSIDE_PROPERTY, notHandle);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_GRID_IS_INSIDE_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFLT, negative);
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitLabel(negative);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class CreatePointsOnBezierAdapter extends MethodVisitor {

        private CreatePointsOnBezierAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label tryStart = new Label();
            final Label tryEnd = new Label();
            final Label handler = new Label();
            final Label notHandle = new Label();
            final Label fallback = new Label();
            mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
            mv.visitLabel(tryStart);
            SlotBridgeEmitter.emitMethodHandleConsult(
                    mv, WarpDeformEditBridge.CREATE_POINTS_ON_BEZIER_PROPERTY, notHandle);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_CREATE_BEZIER_DESC, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNULL, notHandle);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notHandle);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(tryEnd);
            mv.visitJumpInsn(Opcodes.GOTO, fallback);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(fallback);
        }
    }

    private static final class DrawSelectedPointAdapter extends MethodVisitor {

        private static final int RENDERER_LOCAL = 40;
        private static final int BUFFER_LOCAL = 41;

        private boolean pendingIsPositions;
        private boolean hasPendingKind;

        private DrawSelectedPointAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && WarpDeformEditTarget.GPOINT_RENDERER_OWNER.equals(owner)
                    && BUFFER_WRAPPER_DESCRIPTOR.equals(desc)
                    && (GET_POSITIONS_BUFFER_METHOD.equals(name) || GET_COLORS_BUFFER_METHOD.equals(name))) {
                super.visitInsn(Opcodes.DUP);
                super.visitVarInsn(Opcodes.ASTORE, RENDERER_LOCAL);
                pendingIsPositions = GET_POSITIONS_BUFFER_METHOD.equals(name);
                hasPendingKind = true;
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                return;
            }
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && WarpDeformEditTarget.FLOAT_BUFFER_OWNER.equals(owner)
                    && BUFFER_UPLOAD_METHOD.equals(name)
                    && BUFFER_UPLOAD_DESCRIPTOR.equals(desc)
                    && hasPendingKind) {
                hasPendingKind = false;
                super.visitVarInsn(Opcodes.ASTORE, BUFFER_LOCAL);
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label notHandle = new Label();
                final Label doUpload = new Label();
                final Label done = new Label();
                super.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
                super.visitLabel(tryStart);
                SlotBridgeEmitter.emitMethodHandleConsult(
                        mv, WarpDeformEditBridge.DRAW_SELECTED_POINT_UPLOAD_PROPERTY, notHandle);
                super.visitVarInsn(Opcodes.ALOAD, BUFFER_LOCAL);
                super.visitVarInsn(Opcodes.ALOAD, RENDERER_LOCAL);
                super.visitInsn(pendingIsPositions ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                super.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        METHOD_HANDLE_OWNER,
                        INVOKE_EXACT_METHOD,
                        MH_SELECTED_UPLOAD_DESC,
                        false);
                super.visitJumpInsn(Opcodes.IFEQ, done);
                super.visitJumpInsn(Opcodes.GOTO, doUpload);
                super.visitLabel(notHandle);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(tryEnd);
                super.visitJumpInsn(Opcodes.GOTO, doUpload);
                super.visitLabel(handler);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(doUpload);
                super.visitVarInsn(Opcodes.ALOAD, BUFFER_LOCAL);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                super.visitLabel(done);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, BUFFER_LOCAL + 1));
        }
    }

    private static final class DrawSoftSelectionAdapter extends MethodVisitor {

        private final int selectionSlot;
        private final int colorSlot;
        private boolean sawWeightField;
        private boolean sawIcon1;
        private boolean injected;

        private DrawSoftSelectionAdapter(final MethodVisitor out, final int selectionSlot, final int colorSlot) {
            super(Opcodes.ASM9, out);
            this.selectionSlot = selectionSlot;
            this.colorSlot = colorSlot;
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            sawIcon1 = false;
            sawWeightField = opcode == Opcodes.GETFIELD
                    && WarpDeformEditTarget.WARP_DRAW_OWNER.equals(owner)
                    && WEIGHT_MAP_FIELD.equals(name);
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitInsn(final int opcode) {
            sawIcon1 = sawWeightField && opcode == Opcodes.ICONST_1;
            if (!sawIcon1) {
                sawWeightField = false;
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            super.visitMethodInsn(opcode, owner, name, desc, itf);
            if (!injected
                    && opcode == Opcodes.INVOKEVIRTUAL
                    && WarpDeformEditTarget.SIMPLE_MESH_ENTITY_OWNER.equals(owner)
                    && SET_ENABLED_METHOD.equals(name)
                    && SET_ENABLED_DESCRIPTOR.equals(desc)
                    && sawWeightField
                    && sawIcon1) {
                injected = true;
                final Label tryStart = new Label();
                final Label tryEnd = new Label();
                final Label handler = new Label();
                final Label notHandle = new Label();
                final Label fallback = new Label();
                mv.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE_OWNER);
                mv.visitLabel(tryStart);
                SlotBridgeEmitter.emitMethodHandleConsult(
                        mv, WarpDeformEditBridge.DRAW_SOFT_SELECTION_PROPERTY, notHandle);
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, selectionSlot);
                mv.visitVarInsn(Opcodes.ALOAD, colorSlot);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitVarInsn(Opcodes.ILOAD, 2);
                mv.visitVarInsn(Opcodes.ILOAD, 3);
                mv.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, METHOD_HANDLE_OWNER, INVOKE_EXACT_METHOD, MH_SOFT_SELECTION_DESC, false);
                mv.visitJumpInsn(Opcodes.IFEQ, fallback);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitLabel(notHandle);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(tryEnd);
                mv.visitJumpInsn(Opcodes.GOTO, fallback);
                mv.visitLabel(handler);
                mv.visitInsn(Opcodes.POP);
                mv.visitLabel(fallback);
            }
            sawWeightField = false;
            sawIcon1 = false;
        }
    }

    private static final class ActionListToFloatsAdapter extends MethodVisitor {

        private static final int SIZE_LOCAL = 20;
        private static final int OUT_LOCAL = 21;
        private static final int INDEX_LOCAL = 22;
        private static final int PT_LOCAL = 23;

        private ActionListToFloatsAdapter(final MethodVisitor out) {
            super(Opcodes.ASM9, out);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            final Label loopStart = new Label();
            final Label loopEnd = new Label();

            emitActiveSlotCheck(mv, fallback);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, RANDOM_ACCESS_OWNER);
            mv.visitJumpInsn(Opcodes.IFEQ, fallback);

            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
            mv.visitVarInsn(Opcodes.ISTORE, SIZE_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, SIZE_LOCAL);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            mv.visitVarInsn(Opcodes.ASTORE, OUT_LOCAL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, INDEX_LOCAL);

            mv.visitLabel(loopStart);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, SIZE_LOCAL);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_OWNER, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
            mv.visitTypeInsn(Opcodes.CHECKCAST, WarpDeformEditTarget.GVECTOR2_OWNER);
            mv.visitVarInsn(Opcodes.ASTORE, PT_LOCAL);

            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitVarInsn(Opcodes.ALOAD, PT_LOCAL);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, WarpDeformEditTarget.GVECTOR2_OWNER, "getX", FLOAT_GETTER_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.FASTORE);

            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitInsn(Opcodes.IMUL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitVarInsn(Opcodes.ALOAD, PT_LOCAL);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, WarpDeformEditTarget.GVECTOR2_OWNER, "getY", FLOAT_GETTER_DESCRIPTOR, false);
            mv.visitInsn(Opcodes.FASTORE);

            mv.visitIincInsn(INDEX_LOCAL, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loopStart);

            mv.visitLabel(loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(fallback);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, PT_LOCAL + 1));
        }
    }

    private static final class ActionLegacyTransformAdapter extends MethodVisitor {

        private static final int SRC_LOCAL = 20;
        private static final int OUT_LOCAL = 21;
        private static final int LEN_LOCAL = 22;
        private static final int INDEX_LOCAL = 23;

        private final String owner;
        private final ActionTransformAnchors anchors;

        private ActionLegacyTransformAdapter(
                final MethodVisitor out, final String owner, final ActionTransformAnchors anchors) {
            super(Opcodes.ASM9, out);
            this.owner = owner;
            this.anchors = anchors;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            final Label loopStart = new Label();
            final Label loopEnd = new Label();

            emitActiveSlotCheck(mv, fallback);
            mv.visitFieldInsn(
                    Opcodes.GETSTATIC,
                    anchors.helperFieldOwner(),
                    anchors.helperFieldName(),
                    anchors.helperFieldDesc());
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    anchors.helperCallOwner(),
                    anchors.helperCallName(),
                    anchors.helperCallDesc(),
                    false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ASTORE, SRC_LOCAL);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ISTORE, LEN_LOCAL);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            mv.visitVarInsn(Opcodes.ASTORE, OUT_LOCAL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, INDEX_LOCAL);

            mv.visitLabel(loopStart);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitInsn(Opcodes.FALOAD);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, owner, anchors.sampleMethodName(), anchors.sampleMethodDesc(), false);
            mv.visitInsn(Opcodes.FASTORE);
            mv.visitIincInsn(INDEX_LOCAL, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loopStart);

            mv.visitLabel(loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(fallback);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, INDEX_LOCAL + 1));
        }
    }

    private static final class ActionOffsetFloatsAdapter extends MethodVisitor {

        private static final int SRC_LOCAL = 20;
        private static final int LEN_LOCAL = 21;
        private static final int OUT_LOCAL = 22;
        private static final int DX_LOCAL = 23;
        private static final int DY_LOCAL = 24;
        private static final int INDEX_LOCAL = 25;

        private final String owner;
        private final ActionTransformAnchors anchors;

        private ActionOffsetFloatsAdapter(
                final MethodVisitor out, final String owner, final ActionTransformAnchors anchors) {
            super(Opcodes.ASM9, out);
            this.owner = owner;
            this.anchors = anchors;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            final Label fallback = new Label();
            final Label notNull = new Label();
            final Label loopStart = new Label();
            final Label loopEnd = new Label();

            emitActiveSlotCheck(mv, fallback);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(
                    Opcodes.GETFIELD, anchors.offsetOwner(), anchors.offsetFieldName(), anchors.offsetFieldDesc());
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    anchors.offsetGetterOwner(),
                    anchors.offsetGetterName(),
                    anchors.offsetGetterDesc(),
                    false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.ASTORE, SRC_LOCAL);
            mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ARETURN);

            mv.visitLabel(notNull);
            mv.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            mv.visitVarInsn(Opcodes.ISTORE, LEN_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            mv.visitVarInsn(Opcodes.ASTORE, OUT_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
            mv.visitJumpInsn(Opcodes.IFEQ, loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, WarpDeformEditTarget.GVECTOR2_OWNER, "getX", FLOAT_GETTER_DESCRIPTOR, false);
            mv.visitVarInsn(Opcodes.FSTORE, DX_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, WarpDeformEditTarget.GVECTOR2_OWNER, "getY", FLOAT_GETTER_DESCRIPTOR, false);
            mv.visitVarInsn(Opcodes.FSTORE, DY_LOCAL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, INDEX_LOCAL);

            mv.visitLabel(loopStart);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, LEN_LOCAL);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitVarInsn(Opcodes.ALOAD, SRC_LOCAL);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitInsn(Opcodes.FALOAD);
            mv.visitVarInsn(Opcodes.ILOAD, INDEX_LOCAL);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IAND);
            final Label useY = new Label();
            final Label addDone = new Label();
            mv.visitJumpInsn(Opcodes.IFNE, useY);
            mv.visitVarInsn(Opcodes.FLOAD, DX_LOCAL);
            mv.visitJumpInsn(Opcodes.GOTO, addDone);
            mv.visitLabel(useY);
            mv.visitVarInsn(Opcodes.FLOAD, DY_LOCAL);
            mv.visitLabel(addDone);
            mv.visitInsn(Opcodes.FADD);
            mv.visitInsn(Opcodes.FASTORE);
            mv.visitIincInsn(INDEX_LOCAL, 1);
            mv.visitJumpInsn(Opcodes.GOTO, loopStart);

            mv.visitLabel(loopEnd);
            mv.visitVarInsn(Opcodes.ALOAD, OUT_LOCAL);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(fallback);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            super.visitMaxs(maxStack, Math.max(maxLocals, INDEX_LOCAL + 1));
        }
    }
}
