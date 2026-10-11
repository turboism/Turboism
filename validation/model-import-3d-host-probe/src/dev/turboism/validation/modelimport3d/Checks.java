package dev.turboism.validation.modelimport3d;

import dev.turboism.validation.modelimport3d.Locate.Axis;
import dev.turboism.validation.modelimport3d.Locate.Result;
import dev.turboism.validation.modelimport3d.Model;
import dev.turboism.validation.modelimport3d.Model.*;
import dev.turboism.validation.modelimport3d.Report.Check;
import dev.turboism.validation.modelimport3d.Report.Status;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Offline check suite over the synthetic fixture. No host classes run.
 * Rejection entries separate "operation rejected" from "reason matched".
 */
final class Checks {

    private Checks() {}

    private static Axis a(String ref, float v) { return new Axis(ref, v); }

    /** Frozen known-answer vector for Digest.of on a fixed input. */
    static final String DIGEST_VECTOR_INPUT_DOMAIN = "vector";
    static final float[] DIGEST_VECTOR_POS = {1f, -1f, 0.5f, 2.5f};
    static final float[] DIGEST_VECTOR_UV = {0f, 0.25f, 0.5f, 0.75f};
    static final int[] DIGEST_VECTOR_IDX = {0, 1, 2};
    static final Integer DIGEST_VECTOR_DRAW = 7;
    // sha256 over the canonical encoding; recomputed by verify_report.py too.
    static final String DIGEST_VECTOR_EXPECTED =
            "06fcece681e565ea32af9183fdbf8324a72dab7d8e68b56ec40b4a53a94fc757";

    static List<Check> offline(Fixture f, List<String> sampleLines) {
        List<Check> out = new ArrayList<>();
        Scope sc = f.scope();

        // --- ordinary tuple location: the full 3x3 grid ---
        float[] vs = {-1f, 0f, 1f};
        Set<String> seenForms = new HashSet<>();
        for (float va : vs) {
            for (float vb : vs) {
                String id = "loc.cell." + (int) va + "_" + (int) vb;
                Result r = Locate.cell(f, sc,
                        List.of(a(Fixtures.PARAM_A_GUID, va),
                                a(Fixtures.PARAM_B_GUID, vb)));
                String expectedGuid = "syn-form-" + (int) (va + 1) + "-"
                        + (int) (vb + 1);
                boolean ok = r.ok() && expectedGuid.equals(r.formGuid());
                if (r.ok()) seenForms.add(r.formGuid());
                String aliasNote = r.ok() && r.alias() ? " alias" : "";
                out.add(Report.check(id, "locate", expectedGuid,
                        r.ok() ? r.formGuid() + aliasNote
                                : "rejected:" + r.rejection(),
                        ok ? Status.PASS : Status.FAIL,
                        ok ? "cell located; writable=" + r.writable()
                                : r.detail()));
            }
        }
        out.add(Report.check("loc.cell.distinct", "locate",
                "9 distinct formGuids", String.valueOf(seenForms.size()),
                seenForms.size() == 9 ? Status.PASS : Status.FAIL,
                "distinct stored forms per cell (aliased cells counted once)"));

        // --- declared-default tuple: default 0 is the MIDDLE key, not first ---
        Result def = Locate.defaultCell(f, sc);
        boolean defOk = def.ok() && "syn-form-1-1".equals(def.formGuid());
        out.add(Report.check("loc.default.tuple", "locate", "syn-form-1-1",
                def.ok() ? def.formGuid() : "rejected:" + def.rejection(),
                defOk ? Status.PASS : Status.FAIL,
                "declared defaultValue tuple (0,0) -> middle-key cell, "
                        + "not index-0 cell"));
        out.add(Report.check("loc.default.guidConsistent", "locate",
                "defaultCell == fixture.defaultFormGuid",
                def.ok() ? def.formGuid() + " vs " + f.defaultFormGuid()
                        : "unresolved",
                defOk && f.defaultFormGuid().equals(def.formGuid())
                        ? Status.PASS : Status.FAIL,
                "frozen default identity must match declared-default locate"));
        Result firstKeyCell = Locate.cell(f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, -1f),
                        a(Fixtures.PARAM_B_GUID, -1f)));
        boolean notFirst = defOk && firstKeyCell.ok()
                && !def.formGuid().equals(firstKeyCell.formGuid());
        out.add(Report.check("loc.default.notFirstKey", "locate",
                "default cell != first-key cell",
                defOk && firstKeyCell.ok()
                        ? def.formGuid() + " vs " + firstKeyCell.formGuid()
                        : "unresolved",
                notFirst ? Status.PASS : Status.FAIL,
                "default tuple must not collapse to linear index 0"));

        // --- normalization by parameter identity, not list position ---
        Result swapped = Locate.cell(f, sc,
                List.of(a(Fixtures.PARAM_B_ID, 1f),
                        a(Fixtures.PARAM_A_ID, -1f)));
        boolean normOk = swapped.ok()
                && "syn-form-0-2".equals(swapped.formGuid());
        out.add(Report.check("loc.tuple.normalized", "locate", "syn-form-0-2",
                swapped.ok() ? swapped.formGuid()
                        : "rejected:" + swapped.rejection(),
                normOk ? Status.PASS : Status.FAIL,
                "reversed order + paramId refs normalize to same cell"));

        // --- morph: three keys on one parameter locate to distinct forms ---
        Result m0 = Locate.morph(f, sc, Fixtures.PARAM_S_GUID, 0.0f);
        Result m1 = Locate.morph(f, sc, Fixtures.PARAM_S_GUID, 0.5f);
        Result m2 = Locate.morph(f, sc, Fixtures.PARAM_S_GUID, 1.0f);
        boolean mDistinct = m0.ok() && m1.ok() && m2.ok()
                && m0.formGuid().equals("syn-morph-form-00")
                && !m0.formGuid().equals(m1.formGuid())
                && !m1.formGuid().equals(m2.formGuid())
                && !m0.formGuid().equals(m2.formGuid());
        out.add(Report.check("morph.keys.distinct", "morph",
                "three morph keys -> three distinct forms",
                (m0.ok() ? m0.formGuid() : "rej:" + m0.rejection()) + " | "
                        + (m1.ok() ? m1.formGuid() : "rej:" + m1.rejection())
                        + " | "
                        + (m2.ok() ? m2.formGuid() : "rej:" + m2.rejection()),
                mDistinct ? Status.PASS : Status.FAIL,
                "keyValue 0.0, 0.5, 1.0 on same paramGuid"));

        // --- explicit-0 morph target locates yet basic identity is UNKNOWN ---
        boolean zeroUnknown = m0.ok() && "UNKNOWN".equals(m0.basicIdentity())
                && "UNKNOWN".equals(m1.basicIdentity())
                && "UNKNOWN".equals(m2.basicIdentity());
        out.add(Report.check("morph.explicitZero.unknown", "morph",
                "explicit 0 target locates; basicIdentity stays UNKNOWN",
                m0.ok() ? m0.formGuid() + " basic=" + m0.basicIdentity()
                        : "rejected:" + m0.rejection(),
                zeroUnknown ? Status.PASS : Status.FAIL,
                "zero keyValue is never classified as base; editable UNKNOWN"));

        // --- alias: ordinary cell (1,1) shares its form with a morph record ---
        Result aliasCell = Locate.cell(f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, 1f),
                        a(Fixtures.PARAM_B_GUID, 1f)));
        boolean aliasOk = aliasCell.ok() && aliasCell.alias()
                && "BLOCKED_ALIAS".equals(aliasCell.writable());
        out.add(Report.check("alias.sharedForm.blocked", "locate",
                "cell (1,1) form aliased -> writable BLOCKED",
                aliasCell.ok()
                        ? "alias=" + aliasCell.alias() + " writable="
                                + aliasCell.writable()
                        : "rejected:" + aliasCell.rejection(),
                aliasOk ? Status.PASS : Status.FAIL,
                "shared form is never a writable independent target"));

        out.addAll(snapshotChecks(f));
        out.addAll(materialChecks(f, sampleLines));

        // --- protocol self-evidence: escaping survives serialization ---
        out.add(Report.check("rpt.jsonEscaping", "protocol",
                "reason string survives JSON escape round-trip",
                "carries \"quote\" \\ backslash \n newline and unicode 日本語",
                Status.PASS,
                "reason contains \"quote\" \\ backslash \n unicode 日本語;"
                        + " verifier asserts parse round-trip"));
        return out;
    }

    private static List<Check> snapshotChecks(Fixture f) {
        List<Check> out = new ArrayList<>();
        float[] srcPos = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
        DomainSnapshot snap = DomainSnapshot.of("probe.positions", srcPos,
                null, null, null);
        String before = snap.digest();
        srcPos[0] = 999f; // external mutation must not affect snapshot
        boolean externalImmune = snap.digest().equals(before)
                && snap.positions()[0] == 0f;
        float[] viaGetter = snap.positions();
        viaGetter[1] = 888f; // mutating the returned copy must not leak
        boolean getterImmune = snap.digest().equals(before)
                && snap.positions()[1] == 0f;
        // digest is computed from content: recomputation must agree
        String recomputed = Digest.of(snap.domain(), snap.positions(),
                snap.uvs(), snap.indices(), snap.drawOrder());
        boolean digestHonest = recomputed.equals(before);
        out.add(Report.check("snap.externalMutationImmune", "snapshot",
                "digest/content unchanged after input array mutation",
                "digest=" + before.substring(0, 16) + "... pos[0]="
                        + snap.positions()[0],
                externalImmune ? Status.PASS : Status.FAIL,
                "defensive copy at construction"));
        out.add(Report.check("snap.getterMutationImmune", "snapshot",
                "digest/content unchanged after mutating returned array",
                "pos[1]=" + snap.positions()[1],
                getterImmune ? Status.PASS : Status.FAIL,
                "getter returns a fresh copy"));
        out.add(Report.check("snap.digestContentDerived", "snapshot",
                "digest recomputed from content equals stored digest",
                recomputed.equals(before) ? "match" : "MISMATCH",
                digestHonest ? Status.PASS : Status.FAIL,
                "no caller-supplied digest; recompute agrees"));

        // Domain separation + all-domain isolation.
        float[] same = {1f, 2f};
        String dA = DomainSnapshot.of("domain.a", same, null, null, null)
                .digest();
        String dB = DomainSnapshot.of("domain.b", same, null, null, null)
                .digest();
        out.add(Report.check("snap.domainSeparation", "snapshot",
                "identical payload in different domains -> different digest",
                !dA.equals(dB),
                !dA.equals(dB) ? Status.PASS : Status.FAIL,
                "domain label is part of the canonical encoding"));

        float[] posIn = {0f, 0f};
        float[] uvIn = {0f, 0f};
        int[] ixIn = {0, 1, 0};
        String pD = DomainSnapshot.of("p", posIn, null, null, null).digest();
        String uD = DomainSnapshot.of("u", null, uvIn, null, null).digest();
        String iD = DomainSnapshot.of("i", null, null, ixIn, null).digest();
        posIn[0] = 5f;
        boolean allIso = DomainSnapshot.of("u", null, uvIn, null, null)
                        .digest().equals(uD)
                && DomainSnapshot.of("i", null, null, ixIn, null)
                        .digest().equals(iD)
                && !DomainSnapshot.of("p", posIn, null, null, null)
                        .digest().equals(pD);
        out.add(Report.check("snap.domainIsolationAll", "snapshot",
                "mutating positions input changes only its domain digest",
                allIso, allIso ? Status.PASS : Status.FAIL,
                "uv/index digests stable, positions digest changed"));

        // Presence encoding: absent drawOrder must differ from MIN_VALUE.
        String absent = Digest.of("x", null, null, null, null);
        String minVal = Digest.of("x", null, null, null, Integer.MIN_VALUE);
        out.add(Report.check("snap.drawOrderPresence", "snapshot",
                "absent drawOrder digest != present Integer.MIN_VALUE",
                !absent.equals(minVal),
                !absent.equals(minVal) ? Status.PASS : Status.FAIL,
                "presence byte is part of the canonical encoding"));

        // Known-answer digest vector (also recomputed independently in Python).
        String vec = Digest.of(DIGEST_VECTOR_INPUT_DOMAIN, DIGEST_VECTOR_POS,
                DIGEST_VECTOR_UV, DIGEST_VECTOR_IDX, DIGEST_VECTOR_DRAW);
        out.add(Report.check("snap.digestKnownVector", "snapshot",
                DIGEST_VECTOR_EXPECTED, vec,
                vec.equals(DIGEST_VECTOR_EXPECTED) ? Status.PASS : Status.FAIL,
                "frozen known-answer vector; verifier recomputes encoding"));
        return out;
    }

    private static List<Check> materialChecks(Fixture f,
                                              List<String> sampleLines) {
        List<Check> out = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        java.awt.image.BufferedImage img = Png.generate();
        List<String> lines = Png.check(img, failures);
        sampleLines.addAll(lines);
        out.add(Report.check("mat.png.sizeOpaque", "material",
                "256x128 fully opaque", failures.isEmpty() ? "ok" : failures,
                failures.isEmpty() ? Status.PASS : Status.FAIL,
                "frozen constants; tolerance=0; see materialSamples; "
                        + "offline tol=0 is NOT an approved native atlas "
                        + "tolerance"));
        boolean markerOk = failures.stream()
                .noneMatch(x -> x.contains("marker"));
        out.add(Report.check("mat.png.markerAsymmetric", "material",
                "right-pointing arrow pixels verified, mirror positions plain",
                sampleLines.stream().filter(l -> l.contains("marker"))
                        .toList().toString(),
                markerOk ? Status.PASS : Status.FAIL,
                "asymmetric marker detects rotation/mirror; not a binding; "
                        + "corner samples are edge pixels, not far-edge "
                        + "samples"));
        return out;
    }

    /** Rejection suite: each case must reject with the expected reason. */
    static List<Check> rejections(Fixture f) {
        List<Check> out = new ArrayList<>();
        Scope sc = f.scope();

        out.add(rej("rej.missingDim", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, 0f)),
                Model.Reason.INCOMPLETE_TUPLE));
        out.add(rej("rej.extraDim", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, 0f),
                        a(Fixtures.PARAM_B_GUID, 0f),
                        a("syn-guid-param-nope", 0f)),
                Model.Reason.UNBOUND_DIMENSION));
        out.add(rej("rej.duplicateDim", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, 0f),
                        a(Fixtures.PARAM_A_GUID, 1f)),
                Model.Reason.DUPLICATE_DIMENSION));
        out.add(rej("rej.keyNotDeclared", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, 0.5f),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.KEY_NOT_DECLARED));
        out.add(rej("rej.nan", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, Float.NaN),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.NON_FINITE));
        out.add(rej("rej.posInf", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, Float.POSITIVE_INFINITY),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.NON_FINITE));
        out.add(rej("rej.signedZero", f, sc,
                List.of(a(Fixtures.PARAM_A_GUID, -0.0f),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.KEY_NOT_DECLARED));
        Fixture cellBad = Fixtures.fixture("cell");
        out.add(rej("rej.cellMissing", cellBad, cellBad.scope(),
                List.of(a(Fixtures.PARAM_A_GUID, 0f),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.CELL_NOT_FOUND));
        Fixture formBad = Fixtures.fixture("form");
        out.add(rej("rej.formMissing", formBad, formBad.scope(),
                List.of(a(Fixtures.PARAM_A_GUID, 0f),
                        a(Fixtures.PARAM_B_GUID, 0f)),
                Model.Reason.FORM_MISSING));
        out.add(rejScope("rej.staleGeneration", f,
                new Scope(sc.sessionId(), sc.documentId(), sc.modelId(),
                        sc.generation() + 1, sc.ownerId()),
                Model.Reason.STALE_GENERATION));
        out.add(rejScope("rej.wrongOwner", f,
                new Scope(sc.sessionId(), sc.documentId(), sc.modelId(),
                        sc.generation(), "other-owner"),
                Model.Reason.WRONG_OWNER));

        // morph rejections
        out.add(rejMorph("rej.morphNullKey", f, sc, Fixtures.PARAM_S_GUID, null,
                Model.Reason.NULL_KEY_VALUE));
        out.add(rejMorph("rej.morphNotFound", f, sc, Fixtures.PARAM_S_GUID,
                0.25f, Model.Reason.MORPH_NOT_FOUND));
        Fixture morphFormBad = Fixtures.fixture("morph-form");
        out.add(rejMorph("rej.morphMissingForm", morphFormBad,
                morphFormBad.scope(), Fixtures.PARAM_S_GUID, 0.25f,
                Model.Reason.FORM_MISSING));
        Fixture morphNullBad = Fixtures.fixture("morph-null-guid");
        out.add(rejMorph("rej.morphNullFormGuid", morphNullBad,
                morphNullBad.scope(), Fixtures.PARAM_S_GUID, 0.75f,
                Model.Reason.NULL_FORM_GUID));

        // topology / fixture-construction rejections
        float[] okPos = {0f, 0f, 1f, 0f};
        float[] okUv = {0f, 0f, 1f, 0f};
        int[] okIx = {0, 1, 0};
        out.add(rejCtor("rej.topologyOddPositions",
                () -> new SourceGeometry(
                        DomainSnapshot.of("s", new float[]{1f, 2f, 3f},
                                null, null, null),
                        DomainSnapshot.of("u", null, okUv, null, null),
                        DomainSnapshot.of("i", null, null, okIx, null)),
                Model.Reason.BAD_TOPOLOGY));
        out.add(rejCtor("rej.topologyIndexRange",
                () -> new SourceGeometry(
                        DomainSnapshot.of("s", okPos, null, null, null),
                        DomainSnapshot.of("u", null, okUv, null, null),
                        DomainSnapshot.of("i", null, null,
                                new int[]{0, 1, 5}, null)),
                Model.Reason.BAD_TOPOLOGY));
        out.add(rejCtor("rej.uvLengthMismatch",
                () -> new SourceGeometry(
                        DomainSnapshot.of("s", okPos, null, null, null),
                        DomainSnapshot.of("u", null, new float[]{0f},
                                null, null),
                        DomainSnapshot.of("i", null, null, okIx, null)),
                Model.Reason.BAD_TOPOLOGY));
        out.add(rejCtor("rej.sourceNaN",
                () -> new SourceGeometry(
                        DomainSnapshot.of("s",
                                new float[]{Float.NaN, 0f, 1f, 1f},
                                null, null, null),
                        DomainSnapshot.of("u", null, okUv, null, null),
                        DomainSnapshot.of("i", null, null, okIx, null)),
                Model.Reason.NON_FINITE));
        out.add(rejCtor("rej.uvInfinite",
                () -> new SourceGeometry(
                        DomainSnapshot.of("s", okPos, null, null, null),
                        DomainSnapshot.of("u", null,
                                new float[]{0f, 0f, 1f,
                                        Float.POSITIVE_INFINITY},
                                null, null),
                        DomainSnapshot.of("i", null, null, okIx, null)),
                Model.Reason.NON_FINITE));
        out.add(rejCtor("rej.morphDuplicateIdentity",
                () -> new MorphSet(List.of(
                        new MorphRecord(Fixtures.PARAM_S_GUID, 0.5f, "g1"),
                        new MorphRecord(Fixtures.PARAM_S_GUID, 0.5f, "g2"))),
                Model.Reason.DUPLICATE_MORPH_IDENTITY));
        out.add(rejCtor("rej.duplicateKeyInBinding",
                () -> new Binding("p", "g", List.of(-1f, 0f, 0f, 1f),
                        -1f, 1f, 0f),
                Model.Reason.DUPLICATE_KEY));
        out.add(rejCtor("rej.bindingMinGtMax",
                () -> new Binding("bad", "bad-guid", List.of(0f),
                        1f, -1f, 0f),
                Model.Reason.INVALID_RANGE));
        out.add(rejCtor("rej.bindingKeyOutOfRange",
                () -> new Binding("bad", "bad-guid", List.of(10f),
                        0f, 1f, 10f),
                Model.Reason.INVALID_RANGE));
        out.add(rejCtor("rej.bindingDefaultOutOfRange",
                () -> new Binding("bad", "bad-guid", List.of(0f),
                        0f, 1f, 10f),
                Model.Reason.INVALID_RANGE));
        // grid-level: duplicate paramId, malformed cells, duplicate cell
        Binding dupA = new Binding("duplicate-id", "a", List.of(0f), 0f, 0f, 0f);
        Binding dupB = new Binding("duplicate-id", "b", List.of(0f), 0f, 0f, 0f);
        CellKey ckAB = new CellKey(List.of(
                new KeyPoint("a", 0f), new KeyPoint("b", 0f)));
        out.add(rejCtor("rej.duplicateParamId",
                () -> new Grid(List.of(dupA, dupB),
                        List.of(new CellEntry(ckAB, "form"))),
                Model.Reason.DUPLICATE_DIMENSION));
        out.add(rejCtor("rej.cellDuplicateDim",
                () -> new CellKey(List.of(
                        new KeyPoint("a", 0f), new KeyPoint("a", 1f))),
                Model.Reason.DUPLICATE_DIMENSION));
        out.add(rejCtor("rej.cellMissingDim",
                () -> new Grid(List.of(dupA),
                        List.of(new CellEntry(ckAB, "form"))),
                Model.Reason.INCOMPLETE_TUPLE));
        out.add(rejCtor("rej.cellUndeclaredKey",
                () -> new Grid(List.of(dupA, dupB2()),
                        List.of(new CellEntry(new CellKey(List.of(
                                new KeyPoint("a", 0f),
                                new KeyPoint("b", 5f))), "form"))),
                Model.Reason.KEY_NOT_DECLARED));
        out.add(rejCtor("rej.cellUnboundParam",
                () -> new Grid(List.of(dupA),
                        List.of(new CellEntry(new CellKey(List.of(
                                new KeyPoint("zz", 0f))), "form"))),
                Model.Reason.UNBOUND_DIMENSION));
        CellKey single = new CellKey(List.of(new KeyPoint("a", 0f)));
        out.add(rejCtor("rej.duplicateCell",
                () -> new Grid(List.of(dupA),
                        List.of(new CellEntry(single, "f1"),
                                new CellEntry(single, "f2"))),
                Model.Reason.DUPLICATE_CELL));
        // missing declared-default exact key
        Fixture defBad = Fixtures.fixture("default-key");
        Result r = Locate.defaultCell(defBad, defBad.scope());
        out.add(rejResult("rej.missingDefaultKey",
                Model.Reason.MISSING_DEFAULT_KEY, r));
        return out;
    }

    private static Binding dupB2() {
        return new Binding("other-id", "b", List.of(0f, 1f), 0f, 1f, 0f);
    }

    private static Check rej(String id, Fixture f, Scope sc,
                             List<Axis> axes, Model.Reason expected) {
        Result r = Locate.cell(f, sc, axes);
        return rejResult(id, expected, r);
    }

    private static Check rejResult(String id, Model.Reason expected, Result r) {
        boolean rejected = !r.ok();
        String actual = rejected ? r.rejection().name()
                : "none-located:" + r.formGuid();
        return Report.rejection(id, expected.name(), actual, rejected,
                rejected && r.rejection() == expected);
    }

    private static Check rejScope(String id, Fixture f, Scope caller,
                                  Model.Reason expected) {
        Result r = Locate.cell(f, caller,
                List.of(a(Fixtures.PARAM_A_GUID, 0f),
                        a(Fixtures.PARAM_B_GUID, 0f)));
        return rejResult(id, expected, r);
    }

    private static Check rejMorph(String id, Fixture f, Scope sc,
                                  String paramGuid, Float kv,
                                  Model.Reason expected) {
        Result r = Locate.morph(f, sc, paramGuid, kv);
        return rejResult(id, expected, r);
    }

    private static Check rejCtor(String id, Runnable ctor,
                                 Model.Reason expected) {
        try {
            ctor.run();
            return Report.rejection(id, expected.name(), "none-constructed",
                    false, false);
        } catch (Model.FixtureRejected e) {
            return Report.rejection(id, expected.name(), e.reason.name(),
                    true, e.reason == expected);
        }
    }

    /** Native entries: never executed offline; all NOT_RUN. */
    static List<Check> nativeNotRun() {
        List<Check> out = new ArrayList<>();
        String[][] items = {
                {"native.eval.s0", "evaluated form at s=0"},
                {"native.eval.s05", "evaluated form at s=0.5"},
                {"native.eval.s1", "evaluated form at s=1"},
                {"native.undoRedo", "native Undo/Redo participation"},
                {"native.dirty", "dirty-state propagation"},
                {"native.saveReopen", "save/reopen persistence of forms"},
                {"native.atlasRepack", "atlas repack survival"},
                {"native.staleHost", "host-side stale reference rejection"},
                {"native.uVsUb", "absolute U vs difference U-B semantics"},
                {"native.morphBasicIdentity",
                        "basic vs non-basic morph identity"},
                {"native.morphIsolation", "second non-basic key isolation"},
                {"native.textureBinding", "model-image binding persistence"},
                {"native.historyAdmission",
                        "undoable admitted into document history"},
        };
        for (String[] it : items) {
            out.add(Report.unobserved(it[0], "native", "host-evaluated",
                    Status.NOT_RUN, "EXACT_HOST",
                    "requires exact-host run under separate G3 authorization; "
                            + it[1] + " not observed offline"));
        }
        return out;
    }

    /** SDK seam gaps: blocked until a read seam exists. */
    static List<Check> seamBlocked() {
        List<Check> out = new ArrayList<>();
        String[][] items = {
                {"seam.grid.bindings",
                        "read keyform bindings + declared keys per bound param"},
                {"seam.grid.accessKeyLocate",
                        "build access key from tuple and resolve cell->formGuid"},
                {"seam.morph.find",
                        "find morph target by paramGuid+keyValue -> keyformGuid"},
                {"seam.form.read",
                        "read stored form positions/drawOrder by formGuid"},
                {"seam.textureInput.read",
                        "read art-mesh texture-input-extension/model-image"},
        };
        for (String[] it : items) {
            out.add(Report.unobserved(it[0], "seam", "sdk read seam available",
                    Status.BLOCKED, "EXACT_HOST",
                    "no SDK read surface today; candidate selectors documented "
                            + "in research.md section 9; " + it[1]));
        }
        return out;
    }

    /** Domain digest map for the report. */
    static Map<String, String> domainDigests(Fixture f) {
        Map<String, String> d = new TreeMap<>();
        d.put("source.positions", f.source().positions().digest());
        d.put("source.uvs", f.source().uvs().digest());
        d.put("source.indices", f.source().indices().digest());
        DomainSnapshot def = f.forms().byGuid().get(f.defaultFormGuid());
        d.put("form.default." + f.defaultFormGuid(),
                def == null ? "MISSING" : def.digest());
        for (Map.Entry<String, DomainSnapshot> e
                : f.forms().byGuid().entrySet()) {
            d.put("form." + e.getKey(), e.getValue().digest());
        }
        return d;
    }
}
