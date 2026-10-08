package dev.turboism.validation.modelimport3d;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Machine report: per-check expected/actual/status/evidenceKind/reason plus a
 * stable id and the identity source. Statuses are exactly PASS, FAIL, BLOCKED,
 * NOT_RUN; collected evidence kinds are OFFLINE_FIXTURE, STATIC_CLASSFILE,
 * EXACT_HOST. Unobserved entries carry evidenceKind=null with
 * requiredEvidenceKind + evidenceProvided=false. The offline summary is kept
 * strictly separate from native/seam state and a zero-execution section can
 * never summarize to PASS.
 */
final class Report {

    enum Status { PASS, FAIL, BLOCKED, NOT_RUN }
    enum Evidence { OFFLINE_FIXTURE, STATIC_CLASSFILE, EXACT_HOST }

    /** Build/input fingerprint collected by the tool itself, never caller text. */
    record BuildInfo(String head, String dirtyFingerprint, String javaVersion,
                     List<String> argv, String classesSha256, String sourceSha256,
                     int sourceFiles, String compilerVersion, String gitStatusSha256,
                     String executionId) {
        BuildInfo { argv = List.copyOf(argv); }
    }

    record Check(String id, String section, String expected, String actual,
                 Status status, String evidenceKind,
                 String requiredEvidenceKind, boolean evidenceProvided,
                 String reason, String identitySource,
                 String operationStatus) {}

    /** Offline-evidence check (fixture or material; both OFFLINE_FIXTURE). */
    static Check check(String id, String section, Object expected, Object actual,
                       Status status, String reason) {
        return new Check(id, section, String.valueOf(expected),
                String.valueOf(actual), status, "OFFLINE_FIXTURE",
                "OFFLINE_FIXTURE", status == Status.PASS || status == Status.FAIL,
                reason, "synthetic-fixture", null);
    }

    /** Unobserved native/seam entry: NOT_RUN or BLOCKED, no evidence claimed. */
    static Check unobserved(String id, String section, String expected,
                            Status status, String requiredKind, String reason) {
        return new Check(id, section, expected, status.name(), status, null,
                requiredKind, false, reason, "synthetic-fixture", null);
    }

    /**
     * Rejection-suite entry. Separates whether the operation rejected
     * (operationStatus) from whether the reason matched (the PASS criterion).
     * A rejection with the wrong reason is NOT a pass.
     */
    static Check rejection(String id, String expectedReason, String actualReason,
                           boolean rejected, boolean reasonMatch) {
        boolean ok = rejected && reasonMatch && expectedReason.equals(actualReason);
        return new Check(id, "rejections",
                "operation rejected with reason=" + expectedReason,
                rejected ? "rejected with reason=" + actualReason
                        : "operation was not rejected",
                ok ? Status.PASS : Status.FAIL, "OFFLINE_FIXTURE",
                "OFFLINE_FIXTURE", true,
                ok ? "expected rejection observed"
                        : rejected ? "rejected but with wrong reason"
                                : "rejection path missing",
                "synthetic-fixture",
                rejected ? "REJECTED" : "NOT_REJECTED");
    }

    static String toJson(List<Check> offline, List<Check> rejectionChecks,
                         List<Check> nativeChecks, List<Check> blockedChecks,
                         Model.Scope scope, Map<String, String> domainDigests,
                         List<String> materialSamples, String runFault,
                         BuildInfo build, String materialSha256) {
        String payload = payloadSha256(offline, rejectionChecks, nativeChecks,
                blockedChecks, scope, domainDigests, materialSamples, materialSha256);
        StringBuilder s = new StringBuilder();
        s.append("{\n");
        nl(s, "schemaVersion", "t006-offline-r3");
        nl(s, "runId", runIdOf(build, payload));
        nl(s, "payloadSha256", payload);
        nl(s, "hostExecuted", false);
        s.append("  \"hostIdentity\": {\"observed\": false, \"cubismVersion\": null, "
                + "\"jarSha256\": null, \"reason\": \"offline entrypoint; host not executed\"},\n");
        s.append("  \"build\": {\"head\": ").append(quote(build.head()))
                .append(", \"worktreeDirtyFingerprint\": ")
                .append(quote(build.dirtyFingerprint()))
                .append(", \"javaVersion\": ")
                .append(quote(build.javaVersion()))
                .append(", \"classesSha256\": ")
                .append(quote(build.classesSha256()))
                .append(", \"sourceSha256\": ").append(quote(build.sourceSha256()))
                .append(", \"sourceFiles\": ").append(build.sourceFiles())
                .append(", \"compilerVersion\": ").append(quote(build.compilerVersion()))
                .append(", \"gitStatusSha256\": ").append(quote(build.gitStatusSha256()))
                .append(", \"executionId\": ").append(quote(build.executionId()))
                .append(", \"argv\": [");
        for (int i = 0; i < build.argv().size(); i++) {
            s.append(i == 0 ? "" : ", ").append(quote(build.argv().get(i)));
        }
        s.append("]},\n");
        s.append("  \"scope\": {");
        field(s, 0, "sessionId", scope.sessionId(), true);
        field(s, 0, "documentId", scope.documentId(), false);
        field(s, 0, "modelId", scope.modelId(), false);
        field(s, 0, "generation", scope.generation(), false);
        field(s, 0, "ownerId", scope.ownerId(), false);
        s.append("},\n");
        if (runFault != null) nl(s, "injectedFault", runFault);
        nl(s, "materialSha256", materialSha256);
        s.append("  \"offlineSelfcheck\": {\n");
        array(s, 2, "checks", offline);
        array(s, 2, "rejectionChecks", rejectionChecks);
        s.append("    \"summary\": ")
                .append(offlineSummary(concat(offline, rejectionChecks)))
                .append("\n");
        s.append("  },\n");
        s.append("  \"native\": {\n");
        array(s, 2, "checks", nativeChecks);
        array(s, 2, "blocked", blockedChecks);
        s.append("    \"summary\": ")
                .append(nativeSummary(concat(nativeChecks, blockedChecks)))
                .append("\n");
        s.append("  },\n");
        s.append("  \"domainDigests\": {");
        int i = 0;
        for (Map.Entry<String, String> e
                : new TreeMap<>(domainDigests).entrySet()) {
            s.append(i++ == 0 ? "" : ",").append('\n');
            s.append("    ").append(quote(e.getKey())).append(": ")
                    .append(quote(e.getValue()));
        }
        s.append("\n  },\n");
        s.append("  \"materialSamples\": [\n");
        for (i = 0; i < materialSamples.size(); i++) {
            s.append("    ").append(quote(materialSamples.get(i)))
                    .append(i == materialSamples.size() - 1 ? "\n" : ",\n");
        }
        s.append("  ],\n");
        s.append("  \"exitCodeRule\": \"0 = all offline checks as expected; 2 = at least one FAIL; 3 = usage/io error\"\n");
        s.append("}\n");
        return s.toString();
    }

    static String runIdOf(BuildInfo build, String payload) {
        return "t006-offline-" + sha256((build.executionId() + "\n" + payload
                + "\n" + build.sourceSha256() + "\n" + build.classesSha256()
                + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** UTF-8 fields with signed big-endian byte lengths; -1 means null. */
    static void payloadFields(java.io.ByteArrayOutputStream out, String... fields) {
        for (String field : fields) {
            byte[] value = field == null ? null : field.getBytes(StandardCharsets.UTF_8);
            out.writeBytes(java.nio.ByteBuffer.allocate(4)
                    .putInt(value == null ? -1 : value.length).array());
            if (value != null) out.writeBytes(value);
        }
    }

    /** Deterministic content identity, separate from build/execution identity. */
    static String payloadSha256(List<Check> offline, List<Check> rej,
                                List<Check> natives, List<Check> blocked,
                                Model.Scope scope,
                                Map<String, String> domainDigests,
                                List<String> materialSamples,
                                String materialSha256) {
        var p = new java.io.ByteArrayOutputStream();
        for (Check c : offline) payloadCheck(p, c);
        for (Check c : rej) payloadCheck(p, c);
        for (Check c : natives) payloadCheck(p, c);
        for (Check c : blocked) payloadCheck(p, c);
        for (Map.Entry<String, String> e : new TreeMap<>(domainDigests).entrySet()) {
            payloadFields(p, "DIGEST", e.getKey(), e.getValue());
        }
        for (String line : materialSamples) payloadFields(p, "SAMPLE", line);
        payloadFields(p, "SCOPE", scope.sessionId(), scope.documentId(),
                scope.modelId(), Long.toString(scope.generation()), scope.ownerId());
        payloadFields(p, "MATERIAL", materialSha256);
        return sha256(p.toByteArray());
    }

    private static void payloadCheck(java.io.ByteArrayOutputStream p, Check c) {
        payloadFields(p, "CHECK", c.id(), c.expected(), c.actual(), c.status().name(),
                c.evidenceKind(), c.requiredEvidenceKind(),
                Boolean.toString(c.evidenceProvided()), c.reason(),
                c.identitySource(), c.operationStatus());
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SafeVarargs
    private static List<Check> concat(List<Check>... groups) {
        return java.util.Arrays.stream(groups).flatMap(List::stream).toList();
    }

    private static String offlineSummary(List<Check> checks) {
        int pass = 0, fail = 0;
        for (Check c : checks) {
            if (c.status() == Status.PASS) pass++;
            if (c.status() == Status.FAIL) fail++;
        }
        return "{\"pass\": " + pass + ", \"fail\": " + fail
                + ", \"selfcheckStatus\": \"" + (fail == 0 ? "PASS" : "FAIL")
                + "\"}";
    }

    /** Native/seam counts only — an unexecuted section is never a PASS. */
    private static String nativeSummary(List<Check> checks) {
        int pass = 0, fail = 0, blocked = 0, notRun = 0;
        for (Check c : checks) {
            switch (c.status()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case BLOCKED -> blocked++;
                case NOT_RUN -> notRun++;
            }
        }
        return "{\"pass\": " + pass + ", \"fail\": " + fail
                + ", \"blocked\": " + blocked + ", \"notRun\": " + notRun
                + ", \"executed\": false, \"verdict\": \"NOT_EVALUATED\"}";
    }

    private static void array(StringBuilder s, int ind, String name,
                              List<Check> checks) {
        String pad = "  ".repeat(ind);
        s.append(pad).append('"').append(name).append("\": [\n");
        for (int i = 0; i < checks.size(); i++) {
            Check c = checks.get(i);
            s.append(pad).append("  {");
            field(s, 0, "id", c.id(), true);
            field(s, 0, "expected", c.expected(), false);
            field(s, 0, "actual", c.actual(), false);
            field(s, 0, "status", c.status().name(), false);
            if (c.evidenceKind() == null) {
                field(s, 0, "evidenceKind", NullSentinel.V, false);
            } else {
                field(s, 0, "evidenceKind", c.evidenceKind(), false);
            }
            field(s, 0, "requiredEvidenceKind", c.requiredEvidenceKind(), false);
            field(s, 0, "evidenceProvided", c.evidenceProvided(), false);
            field(s, 0, "reason", c.reason(), false);
            field(s, 0, "identitySource", c.identitySource(), false);
            if (c.operationStatus() != null) {
                field(s, 0, "operationStatus", c.operationStatus(), false);
            }
            s.append("}");
            s.append(i == checks.size() - 1 ? "\n" : ",\n");
        }
        s.append(pad).append("],\n");
    }

    private enum NullSentinel { V }

    /** Top-level field: writes `  "k": v,\n` (always trailing comma+newline). */
    private static void nl(StringBuilder s, String k, Object v) {
        s.append("  ").append(quote(k)).append(": ");
        if (v instanceof String sv) s.append(quote(sv));
        else s.append(v);
        s.append(",\n");
    }

    /** Inline object field: `first` suppresses the leading separator. */
    private static void field(StringBuilder s, int ind, String k, Object v,
                              boolean first) {
        if (!first) s.append(", ");
        if (ind > 0) s.append("  ".repeat(ind));
        s.append(quote(k)).append(": ");
        if (v == NullSentinel.V) s.append("null");
        else if (v instanceof String sv) s.append(quote(sv));
        else s.append(v);
    }

    static String quote(String in) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Refuses to overwrite: the leaf must not already exist in any form
     * (file, directory, or symlink). CREATE_NEW, never truncate-follows-link.
     */
    static void write(Path out, String json) {
        try {
            Files.writeString(out, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
