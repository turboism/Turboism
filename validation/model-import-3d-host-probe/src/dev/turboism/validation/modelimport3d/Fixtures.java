package dev.turboism.validation.modelimport3d;

import dev.turboism.validation.modelimport3d.Model.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic fixture: one object, two ordinary parameters with three keys each
 * ([-1,0,1], declared default 0 = middle index, not first), a complete 3x3 cell
 * grid, and a blend-shape parameter carrying three morph records including an
 * explicit 0.0f key plus one nullable-keyValue observation record. Source and
 * every form share one 4-vertex / 2-triangle topology. All identities are
 * synthetic.
 */
final class Fixtures {

    private Fixtures() {}

    static final String PARAM_A_ID = "ParamAngleX";
    static final String PARAM_A_GUID = "syn-guid-param-a";
    static final String PARAM_B_ID = "ParamAngleY";
    static final String PARAM_B_GUID = "syn-guid-param-b";
    static final String PARAM_S_ID = "ParamBlendShapeUV";
    static final String PARAM_S_GUID = "syn-guid-param-blendshape";

    static Scope scope() {
        return new Scope("synthetic-session-t006", "synthetic-doc-t006",
                "synthetic-model-t006", 1L, "synthetic-owner-t006");
    }

    static Binding bindingA() {
        return new Binding(PARAM_A_ID, PARAM_A_GUID,
                List.of(-1.0f, 0.0f, 1.0f), -1.0f, 1.0f, 0.0f);
    }

    static Binding bindingB() {
        return new Binding(PARAM_B_ID, PARAM_B_GUID,
                List.of(-1.0f, 0.0f, 1.0f), -1.0f, 1.0f, 0.0f);
    }

    /** 4-vertex form (8 coordinates, same arity as source), offset by base. */
    private static DomainSnapshot form(String label, float base) {
        float[] p = new float[]{base, base + 0.5f, base + 1.0f, base + 1.5f,
                base + 2.0f, base + 2.5f, base + 3.0f, base + 3.5f};
        return DomainSnapshot.of(label, p, null, null,
                500 + Math.round(base));
    }

    static final List<String> FAULTS = List.of(
            "cell", "form", "morph-form", "morph-null-guid", "default-key");

    /**
     * @param fault null, or one of {@link #FAULTS}
     */
    static Fixture fixture(String fault) {
        Binding a = bindingA();
        Binding b = "default-key".equals(fault)
                ? new Binding(PARAM_B_ID, PARAM_B_GUID,
                        List.of(-1.0f, 1.0f), -1.0f, 1.0f, 0.0f)
                : bindingB();
        List<Binding> bindings = List.of(a, b);

        List<CellEntry> cells = new ArrayList<>();
        Map<String, DomainSnapshot> forms = new LinkedHashMap<>();
        float base = 0.0f;
        for (float va : a.keys()) {
            for (float vb : b.keys()) {
                CellKey k = new CellKey(List.of(
                        new KeyPoint(PARAM_A_GUID, va),
                        new KeyPoint(PARAM_B_GUID, vb)));
                String guid = "syn-form-" + (int) (va + 1) + "-"
                        + (int) (vb + 1);
                cells.add(new CellEntry(k, guid));
                forms.put(guid, form("form:" + guid, base += 10f));
            }
        }
        if ("cell".equals(fault)) {
            cells.removeIf(e -> e.key().equals(new CellKey(List.of(
                    new KeyPoint(PARAM_A_GUID, 0.0f),
                    new KeyPoint(PARAM_B_GUID, 0.0f)))));
        }

        String defaultGuid = "syn-form-1-1";
        if ("form".equals(fault)) {
            forms.remove(defaultGuid);
        }

        // Morph targets on the blend-shape parameter: explicit 0 plus two
        // non-zero keys, each with its own non-aliased form.
        String morphGuid0 = "syn-morph-form-00";
        String morphGuidA = "syn-morph-form-05";
        String morphGuidB = "syn-morph-form-10";
        forms.put(morphGuid0, form("morph:" + morphGuid0, 400f));
        forms.put(morphGuidA, form("morph:" + morphGuidA, 500f));
        forms.put(morphGuidB, form("morph:" + morphGuidB, 600f));
        // An ordinary-cell form also referenced by a morph record (alias).
        String aliasGuid = "syn-form-2-2";
        List<MorphRecord> morphs = new ArrayList<>(List.of(
                new MorphRecord(PARAM_S_GUID, 0.0f, morphGuid0),
                new MorphRecord(PARAM_S_GUID, 0.5f, morphGuidA),
                new MorphRecord(PARAM_S_GUID, 1.0f, morphGuidB),
                new MorphRecord(PARAM_S_GUID, null, aliasGuid)));
        if ("morph-form".equals(fault)) {
            morphs.add(new MorphRecord(PARAM_S_GUID, 0.25f,
                    "syn-morph-form-missing"));
        }
        if ("morph-null-guid".equals(fault)) {
            morphs.add(new MorphRecord(PARAM_S_GUID, 0.75f, null));
        }
        MorphSet morphSet = new MorphSet(morphs);

        float[] srcPos = new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        float[] srcUv = new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        int[] srcIdx = new int[]{0, 1, 2, 1, 2, 3};
        SourceGeometry source = new SourceGeometry(
                DomainSnapshot.of("source.positions", srcPos, null, null, null),
                DomainSnapshot.of("source.uvs", null, srcUv, null, null),
                DomainSnapshot.of("source.indices", null, null, srcIdx, null));

        return new Fixture(scope(), "syn-artmesh-1", source,
                new Grid(bindings, cells), morphSet, new Forms(forms), defaultGuid);
    }
}
