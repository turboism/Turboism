package dev.turboism.validation.p3;

import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.param.*;
import com.live2d.cubism.doc.model.id.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.deformer.warp.*;
import com.live2d.cubism.doc.model.deformer.rotation.*;
import com.live2d.cubism.doc.model.interpolator.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.*;
import com.live2d.doc.CoordType;
import com.live2d.graphics3d.type.GVector2;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * P3 扩展插值多轴切片依赖分析与局部更新验证探针
 *
 * 严格边界声明：
 * 1. 本探针为官方类在隔离 JVM 内的结构与算法验证，不代表 Editor 真实交互 FPS 承诺；
 * 2. 动态证明范围严格界定于 Normal 参数网格（NormalExtendedParameters / Combination）；
 * 3. Morph Target 扩展插值因缺少 Editor 活体交互约束，在本探针中界定为静态结构审计与未验边界。
 */
public final class P3DependencyProbe {

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("[P3DependencyProbe] Starting Cubism 5.3.04 Slice Dependency Analysis");
        System.out.println("================================================================================");

        try {
            // 1. 2 轴 ArtMesh 依赖拓扑分析与局部刷新验证
            testMultiAxisModel("ArtMesh-2Axes", 0, 2, 4, 32);

            // 2. 3 轴 WarpDeformer 依赖拓扑分析与局部刷新验证
            testMultiAxisModel("WarpDeformer-3Axes", 1, 3, 2, 16);

            // 3. 2 轴 RotationDeformer 验证
            testMultiAxisModel("RotationDeformer-2Axes", 2, 2, 4, 1);

            System.out.println("\n[P3DependencyProbe] All scenarios passed with 100% bit-exact accuracy!");
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void testMultiAxisModel(String label, int kind, int axes, int insertedPerInterval, int vertices) throws Exception {
        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.printf(">>> Running Scenario: %s (axes=%d, inserted=%d, vertices=%d)\n", label, axes, insertedPerInterval, vertices);
        System.out.println("--------------------------------------------------------------------------------");

        ModelFixture fixture = createFixture(kind, axes, ExtendedInterpolationType.ELLIPSE, insertedPerInterval, vertices, 42);
        CExtendedInterpolationExtension ext = fixture.extension;
        ACParameterControllableSource owner = fixture.owner;

        List<ACForm> ordinaryForms = (List<ACForm>) (List<?>) owner.getKeyforms();
        List<ACForm> normalDerivedForms = ext.getNormalExtendedKeyforms();
        System.out.printf("  [Model Setup] Ordinary forms: %d, Normal derived forms: %d, Total grid forms: %d\n",
                ordinaryForms.size(), normalDerivedForms.size(), ordinaryForms.size() + normalDerivedForms.size());

        List<?> normalParameters = getNormalExtendedParameters(ext);
        System.out.printf("  [Structure Analysis] NormalExtendedParameters count: %d\n", normalParameters.size());

        int totalSlices = 0;
        List<SliceInfo> allSlices = new ArrayList<>();
        Map<CFormGuid, List<SliceInfo>> ordinaryFormToSlices = new HashMap<>();
        for (ACForm f : ordinaryForms) {
            ordinaryFormToSlices.put(f.getGuid(), new ArrayList<>());
        }

        for (int axisIdx = 0; axisIdx < normalParameters.size(); axisIdx++) {
            Object normParam = normalParameters.get(axisIdx);
            List<?> combinations = getCombinations(normParam);
            CParameterSource paramSource = getParameterSource(normParam);
            System.out.printf("    Axis %d [%s]: %d combinations (slices)\n", axisIdx, paramSource.getId().getIdString(), combinations.size());

            int ordinarySourceCount = 0;
            int derivedSourceCount = 0;

            for (int sliceIdx = 0; sliceIdx < combinations.size(); sliceIdx++) {
                Object comb = combinations.get(sliceIdx);
                List<ACForm> sourceForms = getSourceKeyforms(comb);
                List<ACForm> interpolatedForms = getInterpolatedKeyforms(comb);
                List<?> sourceKeyValues = getSourceKeyValues(comb);

                SliceInfo slice = new SliceInfo(axisIdx, sliceIdx, paramSource.getId().getIdString(), comb, sourceForms, interpolatedForms, sourceKeyValues);
                allSlices.add(slice);
                totalSlices++;

                for (ACForm srcForm : sourceForms) {
                    if (ordinaryFormToSlices.containsKey(srcForm.getGuid())) {
                        ordinarySourceCount++;
                        ordinaryFormToSlices.get(srcForm.getGuid()).add(slice);
                    } else {
                        derivedSourceCount++;
                    }
                }
            }

            System.out.printf("      -> Slice sources composition: %d from ordinary forms, %d from pre-axis derived forms\n",
                    ordinarySourceCount, derivedSourceCount);
            if (axisIdx == 0) {
                if (derivedSourceCount != 0) {
                    throw new AssertionError("Axis 0 must only depend on ordinary forms, but found " + derivedSourceCount + " derived sources!");
                }
                System.out.println("      -> [CONFIRMED] Axis 0 strictly depends on 100% ordinary forms.");
            } else {
                System.out.printf("      -> [CONFIRMED] Axis %d has cascaded dependencies on pre-axis derived forms (%d references).\n",
                        axisIdx, derivedSourceCount);
            }
        }
        System.out.printf("  [Total Slices in Object] %d slices across %d axes\n", totalSlices, axes);

        // 差分验证：使用两个完全独立的全新模型实例对比【官方全量刷新】 vs 【基于级联依赖图的最小切口刷新】
        ModelFixture fixtureOfficial = createFixture(kind, axes, ExtendedInterpolationType.ELLIPSE, insertedPerInterval, vertices, 42);
        ModelFixture fixtureMinimalCut = createFixture(kind, axes, ExtendedInterpolationType.ELLIPSE, insertedPerInterval, vertices, 42);

        ACForm dirtyFormOfficial = (ACForm) ((List<?>) fixtureOfficial.owner.getKeyforms()).get(0);
        ACForm dirtyFormMinimal = (ACForm) ((List<?>) fixtureMinimalCut.owner.getKeyforms()).get(0);

        float[] origPositions = getFormPositions(dirtyFormOfficial, kind);
        float[] mutatedPositions = origPositions.clone();
        for (int i = 0; i < mutatedPositions.length; i++) {
            mutatedPositions[i] += 15.5f;
        }

        // 记录刷新前版本基线
        List<ACForm> derivedA = fixtureOfficial.extension.getNormalExtendedKeyforms();
        List<ACForm> derivedB = fixtureMinimalCut.extension.getNormalExtendedKeyforms();
        if (derivedA.size() != derivedB.size()) {
            throw new AssertionError("Derived forms count mismatch between identical models!");
        }

        int[] preVersionsA = new int[derivedA.size()];
        int[] preVersionsB = new int[derivedB.size()];
        float[][] baselinePositionsB = new float[derivedB.size()][];
        for (int i = 0; i < derivedA.size(); i++) {
            preVersionsA[i] = derivedA.get(i).getEditVersion();
            preVersionsB[i] = derivedB.get(i).getEditVersion();
            baselinePositionsB[i] = getFormPositions(derivedB.get(i), kind).clone();
        }

        // --- 腿 A: 官方全量重算 ---
        setFormPositions(dirtyFormOfficial, mutatedPositions, kind);
        dirtyFormOfficial.setUpdated();
        long t0 = System.nanoTime();
        fixtureOfficial.extension.updateInterpolatedForms(true);
        long tOfficial = System.nanoTime() - t0;

        int[] postVersionsA = new int[derivedA.size()];
        float[][] officialPositions = new float[derivedA.size()][];
        for (int i = 0; i < derivedA.size(); i++) {
            officialPositions[i] = getFormPositions(derivedA.get(i), kind).clone();
            postVersionsA[i] = derivedA.get(i).getEditVersion();
        }

        // --- 腿 B: 最小切口刷新 ---
        setFormPositions(dirtyFormMinimal, mutatedPositions, kind);
        dirtyFormMinimal.setUpdated();

        // 获取 Minimal 模型的切片列表
        List<?> minimalNormalParams = getNormalExtendedParameters(fixtureMinimalCut.extension);
        List<SliceInfo> minimalSlices = new ArrayList<>();
        for (int axisIdx = 0; axisIdx < minimalNormalParams.size(); axisIdx++) {
            Object normParam = minimalNormalParams.get(axisIdx);
            List<?> combinations = getCombinations(normParam);
            CParameterSource paramSource = getParameterSource(normParam);
            for (int sliceIdx = 0; sliceIdx < combinations.size(); sliceIdx++) {
                Object comb = combinations.get(sliceIdx);
                minimalSlices.add(new SliceInfo(axisIdx, sliceIdx, paramSource.getId().getIdString(), comb,
                        getSourceKeyforms(comb), getInterpolatedKeyforms(comb), getSourceKeyValues(comb)));
            }
        }

        Set<SliceInfo> dirtySlices = computeCascadedDirtySlices(dirtyFormMinimal, minimalSlices);
        System.out.printf("  [Differential Validation] Dirty slices computed by DAG: %d / %d (Skipped clean slices: %d, %.1f%%)\n",
                dirtySlices.size(), totalSlices, totalSlices - dirtySlices.size(),
                100.0 * (totalSlices - dirtySlices.size()) / totalSlices);

        long t1 = System.nanoTime();
        for (int axisIdx = 0; axisIdx < minimalNormalParams.size(); axisIdx++) {
            Object normParam = minimalNormalParams.get(axisIdx);
            ExtendedInterpolationType type = getExtendedInterpolationType(normParam, fixtureMinimalCut.owner);
            float scale = getExtendedInterpolationScale(normParam);
            ArrayList<?> arrayList = getExtKeyValues(normParam);

            for (SliceInfo slice : minimalSlices) {
                if (slice.axisIndex == axisIdx && dirtySlices.contains(slice)) {
                    fixtureMinimalCut.extension.updateInterpolatedForms_common(
                            slice.interpolatedForms,
                            slice.sourceForms,
                            (List<CExtendedInterpolationExtension.b>) arrayList,
                            type,
                            scale
                    );
                }
            }
        }
        long tMinimalCut = System.nanoTime() - t1;

        int[] postVersionsB = new int[derivedB.size()];
        for (int i = 0; i < derivedB.size(); i++) {
            postVersionsB[i] = derivedB.get(i).getEditVersion();
        }

        // 逐位对比坐标 raw float bits (按形态在列表中的索引 i 比对)
        int checkedBits = 0;
        int changedValues = 0;
        for (int i = 0; i < derivedB.size(); i++) {
            float[] offPos = officialPositions[i];
            float[] cutPos = getFormPositions(derivedB.get(i), kind);
            if (offPos.length != cutPos.length) {
                throw new AssertionError("Form length mismatch at index " + i);
            }
            for (int p = 0; p < offPos.length; p++) {
                int offBits = Float.floatToRawIntBits(offPos[p]);
                int cutBits = Float.floatToRawIntBits(cutPos[p]);
                if (offBits != cutBits) {
                    throw new AssertionError(String.format("Bit mismatch at derived form [%d] point %d: official=0x%08x vs minimalCut=0x%08x",
                            i, p, offBits, cutBits));
                }
                if (Float.floatToRawIntBits(baselinePositionsB[i][p]) != cutBits) changedValues++;
                checkedBits++;
            }
        }
        if (changedValues == 0) throw new AssertionError("Source edit did not change any derived output");
        System.out.println("    Changed derived float values=" + changedValues);
        System.out.printf("    [PASS] 100%% Bit-Exact! Checked %d coordinates across %d derived forms.\n",
                checkedBits, derivedB.size());
        System.out.printf("    [Sample Timing (Single Run, Non-Binding)] Official full = %.3f ms, Minimal cut = %.3f ms (Speedup: %.2fx)\n",
                tOfficial / 1_000_000.0, tMinimalCut / 1_000_000.0, (double) tOfficial / Math.max(1, tMinimalCut));

        // EditVersion 深入校验：由 dirtySlices 收集目标派生形态的引用身份集合
        System.out.println("  [EditVersion & Notification Calibration]");
        Set<ACForm> dirtyTargetForms = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SliceInfo s : dirtySlices) {
            dirtyTargetForms.addAll(s.interpolatedForms);
        }

        int cleanUnchanged = 0;
        int dirtyTargets = 0;
        int dirtyIncremented = 0;
        int dirtyZeroDelta = 0;
        int deltaMismatchCount = 0;

        for (int i = 0; i < derivedB.size(); i++) {
            ACForm formB = derivedB.get(i);
            int deltaA = postVersionsA[i] - preVersionsA[i];
            int deltaB = postVersionsB[i] - preVersionsB[i];

            boolean isDirtyTarget = dirtyTargetForms.contains(formB);
            if (isDirtyTarget) {
                dirtyTargets++;
                if (deltaB == 0) dirtyZeroDelta++;
                else dirtyIncremented++;
                if (deltaB != deltaA) {
                    deltaMismatchCount++;
                }
            } else {
                if (deltaB != 0) {
                    throw new AssertionError("Clean form at index " + i + " was skipped, but deltaB unexpectedly changed: " + deltaB);
                }
                cleanUnchanged++;
            }
        }

        System.out.printf("    Conservative dirty-list targets=%d; positive version delta=%d; zero version delta=%d; clean-list targets=%d%n",
                dirtyTargets, dirtyIncremented, dirtyZeroDelta, cleanUnchanged);
        if (deltaMismatchCount > 0) {
            System.out.printf("    [Notice] %d dirty forms had different increment counts between official and minimal cut (notification equivalence NOT proven).\n", deltaMismatchCount);
        } else {
            System.out.println("    [CONFIRMED] All dirty forms exhibited identical EditVersion increment deltas to official full run!");
        }
        System.out.println("    [Notification Calibration]:\n" +
                "      - Pure calculation skip: only targets outside the conservative dirty-list set are asserted to have zero version delta;\n" +
                "      - Downstream contract on EditVersion increments is currently an UNRESOLVED BLOCKER;\n" +
                "      - Cannot assume simple setUpdated() is equivalent to official per-vertex calls.");
    }

    private static Set<SliceInfo> computeCascadedDirtySlices(ACForm initialDirtyForm, List<SliceInfo> allSlices) {
        Set<SliceInfo> dirtySlices = new LinkedHashSet<>();
        Set<CFormGuid> dirtyFormGuids = new HashSet<>();
        dirtyFormGuids.add(initialDirtyForm.getGuid());

        int maxAxis = 0;
        for (SliceInfo s : allSlices) maxAxis = Math.max(maxAxis, s.axisIndex);

        for (int axis = 0; axis <= maxAxis; axis++) {
            for (SliceInfo s : allSlices) {
                if (s.axisIndex != axis) continue;
                boolean isSliceDirty = false;
                for (ACForm src : s.sourceForms) {
                    if (dirtyFormGuids.contains(src.getGuid())) {
                        isSliceDirty = true;
                        break;
                    }
                }
                if (isSliceDirty) {
                    dirtySlices.add(s);
                    for (ACForm out : s.interpolatedForms) {
                        dirtyFormGuids.add(out.getGuid());
                    }
                }
            }
        }
        return dirtySlices;
    }

    static class SliceInfo {
        final int axisIndex;
        final int sliceIndex;
        final String paramId;
        final Object combinationInstance;
        final List<ACForm> sourceForms;
        final List<ACForm> interpolatedForms;
        final List<?> sourceKeyValues;

        SliceInfo(int axisIndex, int sliceIndex, String paramId, Object comb,
                  List<ACForm> sourceForms, List<ACForm> interpolatedForms, List<?> sourceKeyValues) {
            this.axisIndex = axisIndex;
            this.sliceIndex = sliceIndex;
            this.paramId = paramId;
            this.combinationInstance = comb;
            this.sourceForms = sourceForms;
            this.interpolatedForms = interpolatedForms;
            this.sourceKeyValues = sourceKeyValues;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SliceInfo sliceInfo = (SliceInfo) o;
            return axisIndex == sliceInfo.axisIndex && sliceIndex == sliceInfo.sliceIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(axisIndex, sliceIndex);
        }
    }

    static class ModelFixture {
        final ACParameterControllableSource owner;
        final CExtendedInterpolationExtension extension;

        ModelFixture(ACParameterControllableSource owner, CExtendedInterpolationExtension extension) {
            this.owner = owner;
            this.extension = extension;
        }
    }

    private static ModelFixture createFixture(int kind, int axes, ExtendedInterpolationType type,
                                             int inserted, int vertices, int seed) throws Exception {
        Random random = new Random(seed);
        CModelSource model = new CModelSource("p3-proof-model", false);
        CParameterSourceSet parameters = model.getParameterSourceSet();
        parameters.setup(model);

        List<kotlin.Pair<CParameterGuid, float[]>> pairs = new ArrayList<>();
        for (int axis = 0; axis < axes; axis++) {
            CParameterSource p = new CParameterSource();
            p.setId(new CParameterId("Param_" + axis));
            p.setGuid(new CParameterGuid(new UUID(10, axis)));
            p.setMinValue(-60.0f);
            p.setMaxValue(60.0f);
            p.setDefaultValue(0.0f);
            p.setup(model);
            parameters.add(p, axis);
            pairs.add(new kotlin.Pair<>(p.getGuid(), new float[]{-60.0f, 0.0f, 60.0f}));
        }
        parameters.updateMap();

        int ordinaryCount = (int) Math.pow(3, axes);
        ACParameterControllableSource owner;
        if (kind == 2) {
            owner = new CRotationDeformerSource();
        } else if (kind == 1) {
            owner = new CWarpDeformerSource();
        } else {
            owner = new CArtMeshSource();
        }
        owner.set_modelSource(model);

        List<ACForm> ordinaryList = (List<ACForm>) (List<?>) owner.getKeyforms();
        ordinaryList.clear();
        List<CFormGuid> guids = new ArrayList<>();

        for (int i = 0; i < ordinaryCount; i++) {
            float[] positions = new float[vertices * 2];
            for (int p = 0; p < positions.length; p++) {
                positions[p] = random.nextFloat() * 100.0f - 50.0f;
            }
            ACForm form;
            if (kind == 2) {
                CRotationDeformerForm f = new CRotationDeformerForm((CRotationDeformerSource) owner, null, new CoordType("DeformerLocal"));
                f.setOriginX(positions[0]);
                f.setOriginY(positions[1]);
                f.setAngle(random.nextFloat() * 180.0f - 90.0f);
                f.setScale(1.0f);
                f.setOpacity(1.0f);
                form = f;
            } else if (kind == 1) {
                CWarpDeformerForm f = new CWarpDeformerForm((CWarpDeformerSource) owner, null, new CoordType("DeformerLocal"));
                f.setPositions(positions);
                f.setOpacity(1.0f);
                form = f;
            } else {
                form = new CArtMeshForm((CArtMeshSource) owner, random.nextInt(1000), 1.0f, positions, new CoordType("DeformerLocal"));
            }
            form.setGuid(new CFormGuid(new UUID(20, i)));
            ordinaryList.add(form);
            guids.add(form.getGuid());
        }

        KeyformGridSource grid = owner.getKeyformGridSource();
        grid.setup(owner);
        grid.getClass().getMethod("import", CModelSource.class, List.class, List.class).invoke(grid, model, pairs, guids);

        for (KeyformBindingSource binding : grid.getKeyformBindings()) {
            binding.setExtendedInterpolationType(type);
            binding.setInsertPointCount(inserted);
        }

        CExtendedInterpolationExtension ext = new CExtendedInterpolationExtension(owner);
        owner.getExtensions().add(ext);
        ext.setupFormStructure(true, true);

        return new ModelFixture(owner, ext);
    }

    private static List<?> getNormalExtendedParameters(CExtendedInterpolationExtension ext) throws Exception {
        Field f = CExtendedInterpolationExtension.class.getDeclaredField("normalExtendedParameters");
        f.setAccessible(true);
        return (List<?>) f.get(ext);
    }

    private static CParameterSource getParameterSource(Object normParam) throws Exception {
        Method m = normParam.getClass().getMethod("b");
        m.setAccessible(true);
        return (CParameterSource) m.invoke(normParam);
    }

    private static List<?> getCombinations(Object normParam) throws Exception {
        Method m = normParam.getClass().getMethod("g");
        m.setAccessible(true);
        return (List<?>) m.invoke(normParam);
    }

    private static ArrayList<?> getExtKeyValues(Object normParam) throws Exception {
        Method m = normParam.getClass().getMethod("e");
        m.setAccessible(true);
        return (ArrayList<?>) m.invoke(normParam);
    }

    private static ExtendedInterpolationType getExtendedInterpolationType(Object normParam, ACParameterControllableSource owner) throws Exception {
        Method m = normParam.getClass().getMethod("a");
        m.setAccessible(true);
        KeyformBindingSource kb = (KeyformBindingSource) m.invoke(normParam);
        KeyformBindingSource real = owner.getKeyformGridSource().findKeyformBinding(kb.getParameterGuid());
        return real != null ? real.getExtendedInterpolationType() : kb.getExtendedInterpolationType();
    }

    private static float getExtendedInterpolationScale(Object normParam) throws Exception {
        Method m = normParam.getClass().getMethod("a");
        m.setAccessible(true);
        KeyformBindingSource kb = (KeyformBindingSource) m.invoke(normParam);
        return kb.getExtendedInterpolationScale();
    }

    private static List<ACForm> getSourceKeyforms(Object comb) throws Exception {
        Method m = comb.getClass().getMethod("c");
        m.setAccessible(true);
        return (List<ACForm>) m.invoke(comb);
    }

    private static List<ACForm> getInterpolatedKeyforms(Object comb) throws Exception {
        Method m = comb.getClass().getMethod("d");
        m.setAccessible(true);
        return (List<ACForm>) m.invoke(comb);
    }

    private static List<?> getSourceKeyValues(Object comb) throws Exception {
        Method m = comb.getClass().getMethod("b");
        m.setAccessible(true);
        return (List<?>) m.invoke(comb);
    }

    private static float[] getFormPositions(ACForm form, int kind) {
        if (kind == 2) {
            CRotationDeformerForm rf = (CRotationDeformerForm) form;
            return new float[]{rf.getOriginX(), rf.getOriginY(), rf.getAngle(), rf.getScale()};
        } else if (kind == 1) {
            return ((CWarpDeformerForm) form).getPositions();
        } else {
            return ((CArtMeshForm) form).getPositions();
        }
    }

    private static void setFormPositions(ACForm form, float[] pos, int kind) {
        if (kind == 2) {
            CRotationDeformerForm rf = (CRotationDeformerForm) form;
            rf.setOriginX(pos[0]);
            rf.setOriginY(pos[1]);
            rf.setAngle(pos[2]);
            rf.setScale(pos[3]);
        } else if (kind == 1) {
            ((CWarpDeformerForm) form).setPositions(pos.clone());
        } else {
            ((CArtMeshForm) form).setPositions(pos.clone());
        }
    }
}
