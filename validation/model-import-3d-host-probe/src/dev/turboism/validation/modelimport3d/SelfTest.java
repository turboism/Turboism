package dev.turboism.validation.modelimport3d;

import dev.turboism.validation.modelimport3d.Model.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formal regression for the T006/R1 review observations: each probe asserts
 * the defect is ABSENT now. Exit 0 = no defect; 2 = at least one reproduced.
 */
final class SelfTest {

    private SelfTest() {}

    private static int defects = 0;

    private static void obs(String id, boolean violates, String actual) {
        if (violates) defects++;
        System.out.println(id + " | " + (violates ? "DEFECT" : "fixed")
                + " | " + actual);
    }

    public static void main(String[] args) {
        Fixture f = Fixtures.fixture(null);

        // 1. form topology matches source arity (was: 2-vert forms vs 4-vert source)
        DomainSnapshot def = f.forms().byGuid().get(f.defaultFormGuid());
        obs("form-topology",
                def.positions().length
                        != f.source().positions().positions().length,
                "sourceCoords=" + f.source().positions().positions().length
                        + " formCoords=" + def.positions().length);

        // 2. no public/digest-injecting constructor on DomainSnapshot
        Constructor<?>[] ctors = DomainSnapshot.class.getDeclaredConstructors();
        boolean injectable = false;
        for (Constructor<?> c : ctors) {
            for (Class<?> p : c.getParameterTypes()) {
                if (p == String.class && c.getParameterCount() > 1) {
                    // second String param would be a caller-supplied digest
                    long strings = java.util.Arrays.stream(c.getParameterTypes())
                            .filter(t -> t == String.class).count();
                    if (strings > 1) injectable = true;
                }
            }
            if (!java.lang.reflect.Modifier.isPrivate(c.getModifiers())) {
                injectable = true;
            }
        }
        obs("snapshot-canonical-constructor", injectable || ctors.length != 1,
                "ctorCount=" + ctors.length + " allPrivate+noDigestParam="
                        + !injectable);

        // 3. no raw*/non-copying accessor leaks internal arrays
        boolean rawLeak = false;
        for (Method m : DomainSnapshot.class.getDeclaredMethods()) {
            if (m.getName().startsWith("raw")) rawLeak = true;
        }
        DomainSnapshot s = DomainSnapshot.of("leak", new float[]{0f, 1f},
                null, null, null);
        String before = s.digest();
        float[] got = s.positions();
        got[0] = 73f;
        String recalced = Digest.of(s.domain(), s.positions(), s.uvs(),
                s.indices(), s.drawOrder());
        obs("snapshot-accessor-leak",
                rawLeak || s.positions()[0] != 0f || !s.digest().equals(before)
                        || !recalced.equals(before),
                "rawMethods=" + rawLeak + " content=" + s.positions()[0]
                        + " digestStable+recomputable=" + s.digest().equals(before)
                        + "/" + recalced.equals(before));

        // 4. source NaN positions / mismatched+infinite UV rejected
        try {
            new SourceGeometry(
                    DomainSnapshot.of("p", new float[]{Float.NaN, 0f, 1f, 1f},
                            null, null, null),
                    DomainSnapshot.of("u", null,
                            new float[]{Float.POSITIVE_INFINITY}, null, null),
                    DomainSnapshot.of("i", null, null, new int[]{0, 1, 0},
                            null));
            obs("source-nan-uv-length", true, "accepted");
        } catch (Model.FixtureRejected e) {
            obs("source-nan-uv-length", false, e.reason.name());
        }

        // 5. binding min>max / out-of-range default rejected
        try {
            new Binding("bad", "bad-guid", List.of(10f), 1f, -1f, 10f);
            obs("binding-invalid-range", true, "accepted");
        } catch (Model.FixtureRejected e) {
            obs("binding-invalid-range", false, e.reason.name());
        }

        // 6. duplicate paramId grid rejected at construction
        try {
            Binding x = new Binding("duplicate-id", "a", List.of(0f), 0f, 0f, 0f);
            Binding y = new Binding("duplicate-id", "b", List.of(0f), 0f, 0f, 0f);
            new Grid(List.of(x, y), List.of(new CellEntry(new CellKey(List.of(
                    new KeyPoint("a", 0f), new KeyPoint("b", 0f))), "form")));
            obs("ambiguous-parameter-id", true, "constructed");
        } catch (Model.FixtureRejected e) {
            obs("ambiguous-parameter-id", false, e.reason.name());
        }

        // 7. malformed cell (duplicate dim / missing dim / undeclared key)
        boolean malformedAccepted = false;
        String detail = "";
        try {
            new CellKey(List.of(new KeyPoint("a", 0f), new KeyPoint("a", 1f)));
            malformedAccepted = true;
            detail = "duplicate dim in tuple accepted";
        } catch (Model.FixtureRejected e) {
            detail = e.reason.name();
        }
        try {
            Binding x = new Binding("aid", "a", List.of(0f), 0f, 0f, 0f);
            Binding y = new Binding("bid", "b", List.of(0f), 0f, 0f, 0f);
            new Grid(List.of(x, y), List.of(new CellEntry(new CellKey(List.of(
                    new KeyPoint("a", 0f), new KeyPoint("b", 9f))), "form")));
            malformedAccepted = true;
            detail += "|undeclared cell key accepted";
        } catch (Model.FixtureRejected e) {
            detail += "|" + e.reason.name();
        }
        obs("malformed-grid-cell", malformedAccepted, detail);

        // 8. wrong rejection reason must FAIL, not record PASS
        Report.Check wrong = Report.rejection("neg", "STALE_GENERATION",
                "WRONG_OWNER", true, true);
        obs("wrong-rejection-operation-status",
                wrong.status() == Report.Status.PASS
                        || !"REJECTED".equals(wrong.operationStatus()),
                "status=" + wrong.status() + " operationStatus="
                        + wrong.operationStatus());

        // 9. native summary can never read PASS
        List<String> samples = new ArrayList<>();
        List<Report.Check> checks = Checks.offline(f, samples);
        String json = Report.toJson(checks, Checks.rejections(f),
                Checks.nativeNotRun(), Checks.seamBlocked(), f.scope(),
                Checks.domainDigests(f), samples, null,
                testBuild("test/a"), "m");
        String nativeSection = json.substring(
                json.indexOf("\"native\": {"),
                json.indexOf("\"domainDigests\""));
        obs("native-summary-pass",
                nativeSection.contains("\"selfcheckStatus\": \"PASS\"")
                        || !nativeSection.contains("\"verdict\": \"NOT_EVALUATED\""),
                "native summary: "
                        + nativeSection.substring(
                                nativeSection.indexOf("\"summary\"")));

        // 10. absent drawOrder vs Integer.MIN_VALUE must differ
        obs("digest-null-sentinel",
                Digest.of("x", null, null, null, null)
                        .equals(Digest.of("x", null, null, null,
                                Integer.MIN_VALUE)),
                "absentVsMinValue equal="
                        + Digest.of("x", null, null, null, null)
                                .equals(Digest.of("x", null, null, null,
                                        Integer.MIN_VALUE)));

        // 11. explicit-0 morph target locates with basicIdentity UNKNOWN
        var m0 = Locate.morph(f, f.scope(), Fixtures.PARAM_S_GUID, 0.0f);
        obs("morph-explicit-zero-unknown",
                !m0.ok() || !"UNKNOWN".equals(m0.basicIdentity()),
                m0.ok() ? m0.formGuid() + "/" + m0.basicIdentity()
                        : "rejected:" + m0.rejection());

        // 12. map/list inputs cannot mutate fixture after construction
        Binding one = new Binding("aid", "a", List.of(0f), 0f, 0f, 0f);
        List<Binding> inB = new ArrayList<>(List.of(one));
        List<CellEntry> inC = new ArrayList<>(List.of(new CellEntry(
                new CellKey(List.of(new KeyPoint("a", 0f))), "form")));
        Grid g = new Grid(inB, inC);
        inB.clear();
        inC.clear();
        obs("collection-defensive",
                g.bindings().size() != 1 || g.cellList().size() != 1,
                "bindings=" + g.bindings().size()
                        + " cells=" + g.cellList().size());

        // Direct construction must enforce the same default identity as factory use.
        try {
            new Fixture(f.scope(), f.objectId(), f.source(), f.grid(), f.morphs(),
                    f.forms(), "syn-form-0-0");
            obs("fixture-default-constructor", true, "inconsistent default accepted");
        } catch (FixtureRejected e) {
            obs("fixture-default-constructor", e.reason != Reason.INCONSISTENT_DEFAULT,
                    e.reason.name());
        }

        var p1 = new java.io.ByteArrayOutputStream();
        var p2 = new java.io.ByteArrayOutputStream();
        Report.payloadFields(p1, "a\u001fb", "c\n");
        Report.payloadFields(p2, "a", "b\u001fc\n");
        obs("payload-delimiter-collision", java.util.Arrays.equals(p1.toByteArray(),
                p2.toByteArray()), "length prefixes separate embedded delimiters");
        p1.reset(); p2.reset();
        Report.payloadFields(p1, (String) null);
        Report.payloadFields(p2, "");
        obs("payload-null-collision", java.util.Arrays.equals(p1.toByteArray(),
                p2.toByteArray()), "null and empty encode differently");
        String id = Report.runIdOf(testBuild("test/a"), "payload");
        obs("execution-identity", !id.matches("t006-offline-[0-9a-f]{64}")
                || id.equals(Report.runIdOf(testBuild("test/b"), "payload"))
                || id.equals(Report.runIdOf(testBuild("test/a"), "changed")), id);

        // Different bindings must not compete for a string parameter reference.
        Binding firstRef = new Binding("first", "shared", List.of(0f), 0f, 0f, 0f);
        Binding secondRef = new Binding("shared", "second", List.of(0f), 0f, 0f, 0f);
        for (var order : List.of(List.of(firstRef, secondRef), List.of(secondRef, firstRef))) {
            String label = "cross-field-parameter-ref-" + order.get(0).paramId();
            try {
                Grid ambiguous = new Grid(order, List.of());
                Locate.Result resolved = Locate.normalize(ambiguous,
                        List.of(new Locate.Axis("shared", 0f), new Locate.Axis("second", 0f)));
                obs(label, true, "ambiguous grid accepted; normalize.ok=" + resolved.ok());
            } catch (FixtureRejected e) {
                obs(label, e.reason != Reason.DUPLICATE_DIMENSION, e.reason.name());
            }
        }
        Grid sameRef = new Grid(List.of(
                new Binding("same", "same", List.of(0f), 0f, 0f, 0f)), List.of());
        obs("same-binding-ref-alias", !Locate.normalize(sameRef,
                List.of(new Locate.Axis("same", 0f))).ok(), "one binding remains unambiguous");

        System.out.println("SelfTest defects=" + defects);
        System.exit(defects == 0 ? 0 : 2);
    }

    private static Report.BuildInfo testBuild(String execution) {
        return new Report.BuildInfo("h", "d", "17", List.of(), "c", "s", 1,
                "17", "g", execution);
    }
}
