package dev.turboism.core.event;

import dev.turboism.core.archive.ArchivePathPolicy;
import dev.turboism.core.archive.ArchiveStructureException;
import dev.turboism.core.archive.StrictZipArchive;
import dev.turboism.core.event.PublicEventContractPreflight.ContractViolation;
import dev.turboism.core.event.PublicEventContractPreflight.Inspection;
import dev.turboism.core.event.PublicEventContractPreflight.Rejection;
import dev.turboism.core.event.PublicEventContractPreflight.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amendment A-1 parity and hardening matrix for {@link PublicEventContractPreflight}.
 *
 * <p>Every row runs the same artifact bytes through the byte-level preflight and —
 * where the fixture is loadable — through the reflective bind path
 * ({@link ContractArtifactClassLoader} + {@link PublicEventContractClosure}), so the
 * ASM rules are continuously compared to real loader semantics. The byte-level rules
 * are deliberately stricter on one axis: the E bucket resolves every erased member
 * descriptor (private members included), which the reflective pass never touches —
 * those rows expect {@code BIND_ACCEPTS} on purpose: installation rejects earlier,
 * never later. Fixture classes are emitted with ASM where javac cannot produce the
 * shape under test (phantom references, cycles, malformed signatures).</p>
 */
class PublicEventContractPreflightParityTest {

    private static final String CONTRACT_ID = "acme.events";
    private static final String ARTIFACT_PATH =
        "META-INF/turboism/contracts/acme-events-1.0.0.jar";
    private static final String EVENT = "com.acme.events.Greeting";
    private static final String EVENT_INTERNAL = "com/acme/events/Greeting";

    @TempDir
    Path temporary;

    // ------------------------------------------------------------------ parity

    /** The expected verdict of the reflective bind path on the same fixture. */
    private enum Bind {ACCEPTS, REJECTS, SKIP}

    private record Row(
        String name,
        byte[] artifact,
        Set<String> seeds,
        Rejection expectedKind,
        Bind bind
    ) {}

    private static Row accept(
        final String name, final byte[] artifact, final Set<String> seeds) {
        return new Row(name, artifact, seeds, null, Bind.ACCEPTS);
    }

    private static Row reject(
        final String name,
        final byte[] artifact,
        final Set<String> seeds,
        final Rejection kind,
        final Bind bind
    ) {
        return new Row(name, artifact, seeds, kind, bind);
    }

    @Test
    void byteLevelVerdictMatchesTheSpecifiedKindAcrossTheMatrix() throws Exception {
        for (final Row row : parityRows()) {
            final Rejection kind = preflight(row.artifact(), row.seeds());
            assertEquals(row.expectedKind(), kind,
                row.name() + ": preflight verdict mismatch");
        }
    }

    @Test
    void bindPathVerdictsMatchReflectiveSemantics() throws Exception {
        for (final Row row : parityRows()) {
            if (row.bind() == Bind.SKIP) {
                continue;
            }
            final Path jar = Files.write(
                temporary.resolve(row.name().replace(' ', '-') + ".jar"),
                row.artifact());
            final boolean rejected = bindRejects(jar, row.seeds());
            if (row.bind() == Bind.ACCEPTS) {
                assertFalse(rejected, row.name() + ": bind must accept");
            } else {
                assertTrue(rejected, row.name() + ": bind must reject");
            }
        }
    }

    private List<Row> parityRows() throws IOException {
        final List<Row> rows = new ArrayList<>();
        final Set<String> seeds = Set.of(EVENT);

        // A legitimately compiled event + payload is the accept baseline.
        final Path compiled = compile(Map.of(
            EVENT, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            "com.acme.events.GreetingPayload", """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ));
        rows.add(accept("compiled contract", jarOf(compiled), seeds));

        // E bucket: a private field's erased descriptor must resolve. Reflective
        // verification never resolves non-API member types, so install is
        // deliberately stricter — the artifact must not reach binding at all.
        rows.add(reject("private erased ghost",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "g", "Lcom/acme/ghost/Gone;", null)))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));

        // A private member's generic signature is never parsed — a phantom inside
        // it must pass on both sides, pinning "non-API signatures stay unread".
        rows.add(accept("private generic ghost is not enumerated",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "g", "Ljava/util/List;",
                        "Ljava/util/List<Lcom/acme/ghost/Gone;>;")))
            ), seeds));

        // Phantom JDK reference: the java.util package existing proves nothing.
        rows.add(reject("phantom JDK reference",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "g",
                        "Ljava/util/NoSuchClassZZZ;", null)))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));

        // Phantom SDK reference: the dev.turboism.sdk package proves nothing either.
        rows.add(reject("phantom SDK reference",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "g",
                        "Ldev/turboism/sdk/NoSuchClassZZZ;", null)))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));

        // A member reached only through a non-API member must be definable, but
        // its own API surface is never traversed — definable, not recursive.
        rows.add(accept("member via private field is definable not api-traversed",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", clazz(
                    "com/acme/events/Payload",
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                    null, "java/lang/Object", null, null, false,
                    member(Opcodes.ACC_PUBLIC, "leak", "()V",
                        "()Lcom/acme/ghost/Unseen;")))
            ), seeds));

        // A member whose supertype is absent is not definable — rejected even
        // though the reflective pass would never load it.
        rows.add(reject("member ancestor missing",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", plainClass(
                    "com/acme/events/Payload", "com/acme/ghost/MissingBase"))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));

        // A member-only inheritance cycle rejects deterministically.
        rows.add(reject("member ancestor cycle",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "x",
                        "Lcom/acme/events/X;", null))),
                entry("com/acme/events/X.class",
                    plainClass("com/acme/events/X", "com/acme/events/Y")),
                entry("com/acme/events/Y.class",
                    plainClass("com/acme/events/Y", "com/acme/events/X"))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));

        // Kind mismatches against trusted ancestors mirror the VM's checks.
        rows.add(reject("member extends a final JDK class",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", plainClass(
                    "com/acme/events/Payload", "java/lang/String"))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));
        rows.add(reject("member extends a JDK interface",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", plainClass(
                    "com/acme/events/Payload", "java/util/List"))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));
        rows.add(reject("member implements a JDK class",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", plainClass(
                    "com/acme/events/Payload", "java/lang/Object",
                    "java/lang/String"))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));

        // A sealed member ancestor must list the member in its permits.
        rows.add(reject("sealed member ancestor does not permit subtype",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "p",
                        "Lcom/acme/events/Payload;", null))),
                entry("com/acme/events/Payload.class", plainClass(
                    "com/acme/events/Payload", "com/acme/events/Base")),
                entry("com/acme/events/Base.class", sealedClass(
                    "com/acme/events/Base", "java/lang/Object",
                    "com/acme/events/Other")),
                entry("com/acme/events/Other.class", plainClass(
                    "com/acme/events/Other", "com/acme/events/Base"))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));

        // Synthetic methods are excluded from the API surface; constructors are not.
        rows.add(accept("synthetic method signature is not enumerated",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    member(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "b",
                        "()V", "()Lcom/acme/ghost/Gone;")))
            ), seeds));
        rows.add(reject("synthetic constructor signature is still api",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    member(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "<init>",
                        "()V", "<T:Lcom/acme/ghost/Gone;>()V")))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));

        // The entry path must equal the internal class name.
        rows.add(reject("entry name differs from internal name",
            jar(entry("com/acme/events/Wrong.class", eventClass())),
            seeds, Rejection.CONTENT, Bind.SKIP));

        // A malformed API signature is invalid metadata, not a VM residual.
        rows.add(reject("malformed api signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g",
                        "Ljava/util/List;", "Ljava/util/List<")))
            ), seeds, Rejection.CONTENT, Bind.REJECTS));

        // Depth beyond the bound rejects as the nesting quota — never an Error.
        rows.add(reject("signature deeper than 512 levels",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g",
                        "Ljava/util/List;", deepSignature(513))))
            ), seeds, Rejection.TOO_LARGE, Bind.SKIP));

        // A signature at exactly the bound still verifies.
        rows.add(accept("signature at the depth boundary",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g",
                        "Ljava/util/List;", deepSignature(512))))
            ), seeds));

        // Context grammar (A-1.R/R2): a field signature is a bare reference
        // type — method-shaped, base-type, and trailing inputs all reject.
        rows.add(reject("method-shaped field signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g", "Ljava/util/List;", "()V")))
            ), seeds, Rejection.CONTENT, Bind.SKIP));
        rows.add(reject("base-type field signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g", "Ljava/util/List;", "I")))
            ), seeds, Rejection.CONTENT, Bind.SKIP));
        rows.add(reject("trailing content after a field signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g", "Ljava/util/List;",
                        "Ljava/util/List;Ljava/lang/Object;")))
            ), seeds, Rejection.CONTENT, Bind.SKIP));

        // A class signature cannot be method-shaped.
        rows.add(reject("method-shaped class signature",
            jar(
                entry(EVENT_INTERNAL + ".class", clazz(
                    EVENT_INTERNAL,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                    "()V", "java/lang/Object",
                    new String[] {"dev/turboism/sdk/event/EventBus$TurboismEvent"},
                    null, false))
            ), seeds, Rejection.CONTENT, Bind.SKIP));

        // A legal generic method signature stays legal — <T:…>(TT;)TT; is a
        // real method grammar, not a first-character heuristic.
        rows.add(accept("generic method signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    member(Opcodes.ACC_PUBLIC, "m", "()V",
                        "<T:Ljava/lang/Object;>(TT;)TT;")))
            ), seeds));

        // A wide but shallow signature stays legal — depth, not width, is bound.
        // Gen declares the matching 300 type parameters so the reflective side
        // accepts the arity too.
        rows.add(accept("wide shallow signature",
            jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PUBLIC, "g",
                        "Lcom/acme/events/Gen;", wideSignature(300)))),
                entry("com/acme/events/Gen.class", clazz(
                    "com/acme/events/Gen",
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                    genericClassSignature(300), "java/lang/Object",
                    null, null, false))
            ), seeds));

        // Record components are enumerated only under real isRecord() semantics.
        rows.add(reject("record component erased ghost",
            jar(
                entry(EVENT_INTERNAL + ".class", recordClass(
                    EVENT_INTERNAL,
                    component("payload", "Lcom/acme/ghost/Gone;", null)))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));
        rows.add(reject("record component generic ghost",
            jar(
                entry(EVENT_INTERNAL + ".class", recordClass(
                    EVENT_INTERNAL,
                    component("payload", "Ljava/util/List;",
                        "Ljava/util/List<Lcom/acme/ghost/Gone;>;")))
            ), seeds, Rejection.CLOSURE, Bind.REJECTS));
        // ACC_RECORD without a java.lang.Record superclass is not a record: its
        // components stay unenumerated. The VM itself rejects the shape at load,
        // so there is no bind-side verdict to compare.
        rows.add(new Row(
            "record flag without a Record superclass is not a record",
            jar(
                entry(EVENT_INTERNAL + ".class", clazz(
                    EVENT_INTERNAL,
                    Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER
                        | Opcodes.ACC_RECORD,
                    null, "java/lang/Object", null, null, true,
                    component("payload", "Lcom/acme/ghost/Gone;", null)))
            ), seeds, null, Bind.SKIP));

        // Sealed permits are API surface. The VM silently drops unresolvable
        // permitted subclasses, so the byte-level check is deliberately stricter —
        // installation rejects what the reflective pass would never see.
        rows.add(reject("sealed event permits a phantom",
            jar(
                entry(EVENT_INTERNAL + ".class", sealedClass(
                    EVENT_INTERNAL, "java/lang/Object",
                    "com/acme/ghost/Permitted"))
            ), seeds, Rejection.CLOSURE, Bind.ACCEPTS));

        return rows;
    }

    /** Reflective cross-check: load the artifact's members, verify the seed. */
    private boolean bindRejects(final Path artifact, final Set<String> seeds) {
        final Set<String> members = new HashSet<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .map(PublicEventContractPreflight::binaryName)
                .forEach(members::add);
        } catch (IOException failure) {
            return true;
        }
        try (ContractArtifactClassLoader loader = new ContractArtifactClassLoader(
            artifact.toUri().toURL(), members)) {
            for (final String seed : seeds) {
                if (!members.contains(seed)) {
                    continue;
                }
                final Class<?> type = Class.forName(seed, false, loader);
                PublicEventContractClosure.verify(type);
            }
            return false;
        } catch (final Throwable failure) {
            // Only the verdict families a bind can legitimately produce count as
            // a rejection — linkage/loading failures, reflective generic
            // metadata failures, and the closure's own rejections. Anything
            // else is a harness bug and must fail the test loudly.
            final boolean verdict = failure instanceof LinkageError
                || failure instanceof ReflectiveOperationException
                || failure instanceof java.lang.reflect.MalformedParameterizedTypeException
                || failure instanceof TypeNotPresentException
                || failure instanceof IllegalArgumentException
                || failure instanceof SecurityException
                || failure instanceof IOException;
            if (!verdict) {
                throw new AssertionError(
                    "bind path failed with an unexpected failure type", failure);
            }
            return true;
        }
    }

    private Rejection preflight(final byte[] artifact, final Set<String> seeds)
            throws ContractViolation {
        try {
            PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), seeds
            );
            return null;
        } catch (final ContractViolation violation) {
            return violation.kind();
        }
    }

    // ------------------------------------------------------- archive fixtures

    @Test
    void truncatedEocdRejectsOnTheByteBackend() throws Exception {
        final byte[] good = jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final byte[] truncated = java.util.Arrays.copyOf(good, good.length - 8);
        assertEquals(Rejection.CONTENT, preflight(truncated, Set.of(EVENT)));
    }

    @Test
    void duplicateEntryRejectsOnTheByteBackend() throws Exception {
        // JDK writers refuse duplicate names; patch a second entry's name bytes
        // (local and central records) onto the first name to build the fixture.
        final byte[] staged = jar(
            entry("com/acme/events/Aaa.class",
                plainClass("com/acme/events/Aaa", "java/lang/Object")),
            entry("com/acme/events/Bbb.class",
                plainClass("com/acme/events/Bbb", "java/lang/Object")));
        final byte[] duplicate = renameEverywhere(
            staged, "com/acme/events/Bbb.class", "com/acme/events/Aaa.class");
        assertEquals(Rejection.CONTENT, preflight(duplicate, Set.of()));
    }

    @Test
    void centralLocalNameMismatchRejects() throws Exception {
        final byte[] good = jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final byte[] mismatched = patchCentralName(
            good, "com/acme/events/Xreeting.class");
        assertEquals(Rejection.CONTENT, preflight(mismatched, Set.of(EVENT)));
    }

    @Test
    void crcCorruptionRejectsOnTheByteBackend() throws Exception {
        final byte[] good = jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final byte[] corrupted = corruptFirstLocalDataByte(good);
        assertEquals(Rejection.CONTENT, preflight(corrupted, Set.of(EVENT)));
    }

    @Test
    void trailingDataRejectsOnTheByteBackend() throws Exception {
        final byte[] good = jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final byte[] trailing = java.util.Arrays.copyOf(good, good.length + 4);
        assertEquals(Rejection.CONTENT, preflight(trailing, Set.of(EVENT)));
    }

    /**
     * The Path and byte[] backends share one verification core: the same malformed
     * fixture must fail with the same structural code through both.
     */
    @Test
    void pathAndByteArrayBackendsRejectIdentically() throws Exception {
        final byte[] good = jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final List<byte[]> fixtures = List.of(
            java.util.Arrays.copyOf(good, good.length - 8),
            java.util.Arrays.copyOf(good, good.length + 4),
            patchCentralName(good, "com/acme/events/Xreeting.class"),
            corruptFirstLocalDataByte(good));
        for (final byte[] fixture : fixtures) {
            final Path file = Files.write(
                temporary.resolve("fixture-" + System.nanoTime() + ".jar"), fixture);
            assertEquals(probe(fixture), probe(file),
                "backends must report the same structural code");
        }
    }

    /** Opens the byte[] backend and consumes every entry; the structural code. */
    private static String probe(final byte[] fixture) throws Exception {
        try (StrictZipArchive archive =
                StrictZipArchive.open(fixture, limits(), paths())) {
            return consumeAll(archive);
        } catch (ArchiveStructureException failure) {
            return failure.code();
        }
    }

    /** Same probe through the Path backend. */
    private static String probe(final Path file) throws Exception {
        try (StrictZipArchive archive =
                StrictZipArchive.open(file, limits(), paths())) {
            return consumeAll(archive);
        } catch (ArchiveStructureException failure) {
            return failure.code();
        }
    }

    private static String consumeAll(final StrictZipArchive archive)
            throws Exception {
        try {
            for (final StrictZipArchive.Entry entry : archive.entries()) {
                if (!entry.directory()) {
                    archive.consume(entry, new ByteArrayOutputStream());
                }
            }
            return null;
        } catch (ArchiveStructureException failure) {
            return failure.code();
        }
    }

    private static StrictZipArchive.Limits limits() {
        return new StrictZipArchive.Limits(
            PublicEventContractPreflight.MAX_ARTIFACT_BYTES,
            PublicEventContractPreflight.MAX_MEMBER_BYTES,
            PublicEventContractPreflight.MAX_EXPANDED_BYTES,
            PublicEventContractPreflight.MAX_ENTRIES,
            PublicEventContractPreflight.MAX_RATIO);
    }

    private static ArchivePathPolicy paths() {
        return new ArchivePathPolicy() {
            @Override
            public void validateEntry(final String name, final boolean directory) {}
            @Override
            public void validateCollisions(final List<String> names) {}
        };
    }

    // ------------------------------------------------------------------ quotas

    @Test
    void memberExpandedBeyondEightMiBRejects() throws Exception {
        final byte[] artifact = jar(
            entry("com/acme/events/Blob.class",
                compressible(9 * 1024 * 1024, 32, 1)));
        assertEquals(Rejection.TOO_LARGE, preflight(artifact, Set.of()));
    }

    @Test
    void memberAtExactlyEightMiBIsConsumed() throws Exception {
        // Exactly at the bound the archive accepts the entry; the zero bytes are
        // not a class, so the rejection is content — proving the bound, not the
        // member check, is what the size gate does.
        final byte[] artifact = jar(
            entry("com/acme/events/Blob.class",
                compressible(8 * 1024 * 1024, 32, 1)));
        assertEquals(Rejection.CONTENT, preflight(artifact, Set.of()));
    }

    @Test
    void totalExpandedBeyondThirtyTwoMiBRejects() throws Exception {
        // Five ~7 MiB members: each below the 8 MiB entry bound, deflate at a
        // ~32:1 stride keeps both the 8 MiB raw-artifact bound and the 100x
        // ratio gate green — only the 32 MiB aggregate trips.
        final List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            entries.add(entry("com/acme/events/T" + i + ".class",
                compressible(7 * 1024 * 1024, 32, i)));
        }
        final byte[] artifact = jar(entries);
        final ContractViolation failure = assertThrows(ContractViolation.class,
            () -> PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), Set.of()));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("ARCHIVE_TOTAL_TOO_LARGE")
                || failure.getMessage().contains("expanded"),
            "the aggregate expanded dimension must be the named failure: "
                + failure.getMessage());
    }

    @Test
    void entryCountBeyondLimitRejects() throws Exception {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < PublicEventContractPreflight.MAX_ENTRIES + 1; i++) {
            entries.put("com/acme/events/C" + i + ".class",
                plainClass("com/acme/events/C" + i, "java/lang/Object"));
        }
        assertEquals(Rejection.TOO_LARGE, preflight(jar(entries), Set.of()));
    }

    @Test
    void compressionRatioBeyondLimitRejects() throws Exception {
        final byte[] artifact = jar(
            entry("com/acme/events/Puff.class", new byte[2 * 1024 * 1024]));
        assertEquals(Rejection.TOO_LARGE, preflight(artifact, Set.of()));
    }

    @Test
    void sessionContractCountIsBounded() throws Exception {
        final Session session = PublicEventContractPreflight.newSession();
        for (int i = 0; i < Session.MAX_CONTRACTS; i++) {
            verify(session, distinctArtifact(i), "c" + i);
        }
        final ContractViolation failure = assertThrows(
            ContractViolation.class,
            () -> verify(session, distinctArtifact(999), "extra"));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("declared contracts"));
    }

    @Test
    void sessionInnerEntriesAreBoundedAcrossArtifacts() throws Exception {
        final Session session = PublicEventContractPreflight.newSession();
        for (int artifact = 0; artifact < 5; artifact++) {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            for (int i = 0; i < 900; i++) {
                final String name = "com/acme/e" + artifact + "/C" + i;
                entries.put(name + ".class",
                    plainClass(name, "java/lang/Object"));
            }
            final byte[] bytes = jar(entries);
            final String contractId = "c" + artifact;
            if (artifact < 4) {
                verify(session, bytes, contractId);
            } else {
                final ContractViolation failure = assertThrows(
                    ContractViolation.class,
                    () -> verify(session, bytes, contractId));
                assertEquals(Rejection.TOO_LARGE, failure.kind());
                assertTrue(failure.getMessage().contains("inner entries"));
            }
        }
    }

    @Test
    void sessionExpandedBytesAreBoundedAcrossArtifacts() throws Exception {
        final Session session = PublicEventContractPreflight.newSession();
        // Three artifacts of ~22 MiB expanded each — inside the 32 MiB per-artifact
        // bound but 67 MiB cumulative — cross the 64 MiB session budget.
        for (int artifact = 0; artifact < 3; artifact++) {
            final Map<String, byte[]> entries = new LinkedHashMap<>();
            for (int i = 0; i < 3; i++) {
                final String name = "com/acme/b" + artifact + "/C" + i;
                entries.put(name + ".class",
                    largeMember(name, 7_500_000, artifact * 3 + i, 32));
            }
            final byte[] bytes = jar(entries);
            final String contractId = "c" + artifact;
            if (artifact < 2) {
                verify(session, bytes, contractId);
            } else {
                final ContractViolation failure = assertThrows(
                    ContractViolation.class,
                    () -> verify(session, bytes, contractId));
                assertEquals(Rejection.TOO_LARGE, failure.kind());
                assertTrue(failure.getMessage().contains("expanded"));
            }
        }
    }

    @Test
    void sessionTypeReferencesAreBounded() throws Exception {
        // References are charged per member surface (no cross-member dedup), so
        // 1024 members each surfacing ~65 resolvable references cross the
        // 65536-reference session bound inside one artifact.
        final List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        entries.add(entry(EVENT_INTERNAL + ".class", eventClass(
            memberFields("com/acme/m/C", 0, 1023))));
        for (int i = 0; i < 1023; i++) {
            final String name = "com/acme/m/C" + i;
            entries.add(entry(name + ".class", plainClass(name,
                "java/lang/Object",
                memberFields("com/acme/m/C", 0, 64))));
        }
        final byte[] artifact = jar(entries);
        final ContractViolation failure = assertThrows(
            ContractViolation.class,
            () -> PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), Set.of(EVENT)));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("references"));
    }

    @Test
    void sessionArtifactBytesAreBoundedAcrossArtifacts() throws Exception {
        // Nine ~7.55 MB artifacts each fit the per-artifact bound; their raw
        // bytes cross the 64 MiB session bound at the ninth declaration. STORED
        // entries keep the compressed size honest — no compression games.
        // Caller-side charging mirrors the install contract: bytes are charged
        // as they are delivered to the verification input, before verify().
        final Session session = PublicEventContractPreflight.newSession();
        for (int artifact = 0; artifact < 9; artifact++) {
            final String name = "com/acme/b" + artifact + "/Blob";
            final byte[] bytes = jarStored(List.of(entry(name + ".class",
                largeMember(name, 7_550_000, artifact, 1))));
            final String contractId = "c" + artifact;
            if (artifact < 8) {
                session.chargeArtifactBytes(bytes.length, contractId);
                verify(session, bytes, contractId);
            } else {
                final ContractViolation failure = assertThrows(
                    ContractViolation.class,
                    () -> session.chargeArtifactBytes(bytes.length, contractId));
                assertEquals(Rejection.TOO_LARGE, failure.kind());
                assertTrue(failure.getMessage().contains("artifact bytes"));
            }
        }
    }

    @Test
    void sessionDescriptorTextIsBounded() throws Exception {
        // Three API-reachable members each carry ~5.9 MUTF-16 units of descriptor
        // text — 65 535-char array descriptors — crossing the 16 777 216-unit
        // session text bound while touching no type references at all.
        final List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        entries.add(entry(EVENT_INTERNAL + ".class", eventClass(
            member(Opcodes.ACC_PUBLIC, "a", "()V", null),
            field(Opcodes.ACC_PUBLIC, "p0", "Lcom/acme/t/P0;", null),
            field(Opcodes.ACC_PUBLIC, "p1", "Lcom/acme/t/P1;", null),
            field(Opcodes.ACC_PUBLIC, "p2", "Lcom/acme/t/P2;", null))));
        for (int i = 0; i < 3; i++) {
            entries.add(entry("com/acme/t/P" + i + ".class",
                textHeavyMember("com/acme/t/P" + i, i)));
        }
        final byte[] artifact = jar(entries);
        final ContractViolation failure = assertThrows(
            ContractViolation.class,
            () -> PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), Set.of(EVENT)));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("text"));
    }

    @Test
    void repeatedVerifyOfTheSameArtifactDoesNotAccumulate() throws Exception {
        final Session session = PublicEventContractPreflight.newSession();
        final byte[] artifact =
            jar(entry(EVENT_INTERNAL + ".class", eventClass()));
        final Inspection first = verify(session, artifact, CONTRACT_ID);
        final Inspection second = verify(session, artifact, CONTRACT_ID);
        assertSame(first, second,
            "a re-verified artifact returns the cached result");
        assertNotNull(verify(
            PublicEventContractPreflight.newSession(), artifact, CONTRACT_ID));
    }

    // ------------------------------------------------- no-execution evidence

    @Test
    void verificationNeverInitializesSeedOrPayloadClasses() throws Exception {
        // Both the seed event type and its reachable payload member carry a
        // <clinit> that flips a system property; neither may run.
        final Path compiled = compile(Map.of(
            EVENT, """
                package com.acme.events;
                public record Greeting(com.acme.events.ArmedPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {
                    static { System.setProperty("turboism.preflight.seed.init", "yes"); }
                }
                """,
            "com.acme.events.ArmedPayload", """
                package com.acme.events;
                public record ArmedPayload(String text) {
                    static { System.setProperty("turboism.preflight.payload.init", "yes"); }
                }
                """
        ));
        final byte[] artifact = jarOf(compiled);
        System.clearProperty("turboism.preflight.seed.init");
        System.clearProperty("turboism.preflight.payload.init");
        assertNull(preflight(artifact, Set.of(EVENT)));
        assertNull(System.getProperty("turboism.preflight.seed.init"));
        assertNull(System.getProperty("turboism.preflight.payload.init"));
    }

    @Test
    void oracleIgnoresACompromisedContextClassLoader() throws Exception {
        // A TCCL that serves REAL, valid class bytes under a phantom SDK name
        // must not make the reference resolvable — the oracle only consults the
        // trusted views, so even a truthful TCCL answer is never read.
        final java.net.URL realBytes =
            dev.turboism.sdk.event.EventBus.class.getResource("EventBus.class");
        assertNotNull(realBytes, "fixture needs the real SDK class resource");
        final ClassLoader lying = new ClassLoader() {
            @Override
            public java.net.URL getResource(final String name) {
                if (name.equals("dev/turboism/sdk/NoSuchClassZZZ.class")) {
                    return realBytes;
                }
                return super.getResource(name);
            }
        };
        final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(lying);
            final byte[] artifact = jar(
                entry(EVENT_INTERNAL + ".class", eventClass(
                    field(Opcodes.ACC_PRIVATE, "g",
                        "Ldev/turboism/sdk/NoSuchClassZZZ;", null))));
            assertEquals(Rejection.CLOSURE, preflight(artifact, Set.of(EVENT)));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void oracleResolvesRealSdkAndPlatformNamesOnBothBranches() {
        final ContractTypeOracle loaderBound = ContractTypeOracle.forLoaders(
            dev.turboism.sdk.event.EventBus.class.getClassLoader(),
            dev.turboism.sdk.event.EventBus.class);
        assertNotNull(loaderBound.lookup("dev.turboism.sdk.event.EventBus"));
        assertNotNull(loaderBound.lookup("java.lang.String"));
        assertNotNull(loaderBound.lookup("com.sun.net.httpserver.HttpServer"));
        assertNull(loaderBound.lookup("dev.turboism.sdk.NoSuchClassZZZ"));
        assertNull(loaderBound.lookup("java.lang.NoSuchClassZZZ"));
        // A classpath-visible runtime type is not resolvable: the platform domain
        // only covers named java.*/jdk.* modules. The class resource IS visible
        // through the application/system view — the divergence is the point.
        assertNotNull(ClassLoader.getSystemResource(
            "dev/turboism/core/event/RuntimeEventBroker.class"),
            "fixture needs an app-only visible class resource");
        assertNull(loaderBound.lookup(
            "dev.turboism.core.event.RuntimeEventBroker"));

        final ContractTypeOracle bootstrap = ContractTypeOracle.forLoaders(
            null, dev.turboism.sdk.event.EventBus.class);
        assertNotNull(bootstrap.lookup("dev.turboism.sdk.event.EventBus"));
        assertNull(bootstrap.lookup("dev.turboism.sdk.NoSuchClassZZZ"));
        assertNotNull(bootstrap.lookup("java.lang.String"));
    }

    /**
     * Real bootstrap-parent evidence (A-1.R/R5): a subprocess where the SDK
     * anchor's class loader is actually {@code null} — the SDK jar is appended
     * to the boot classpath — must still resolve SDK and platform names through
     * the module-resource branch, and still reject phantoms.
     */
    @Test
    void oracleResolvesWithABootstrapLoadedSdkAnchor() throws Exception {
        final Path sdk = codeSource(dev.turboism.sdk.event.EventBus.class);
        final Path runtime = codeSource(ContractTypeOracle.class);
        final Path asm = codeSource(Opcodes.class);
        final Path probeClasses = Files.createDirectories(
            temporary.resolve("probe-classes"));
        final Path probeSource = Files.createDirectories(
            probeClasses.resolve("dev/turboism/core/event"))
            .resolve("BootstrapOracleProbe.java");
        Files.writeString(probeSource, """
            package dev.turboism.core.event;
            public final class BootstrapOracleProbe {
                public static void main(String[] args) {
                    Class<?> anchor = dev.turboism.sdk.event.EventBus.class;
                    if (anchor.getClassLoader() != null) {
                        System.out.println("ANCHOR-NOT-BOOTSTRAP");
                        System.exit(2);
                    }
                    ContractTypeOracle oracle =
                        ContractTypeOracle.forSdkAnchor(anchor);
                    check(oracle.lookup(
                        "dev.turboism.sdk.event.EventBus") != null, 3);
                    check(oracle.lookup(
                        "dev.turboism.sdk.NoSuchClassZZZ") == null, 4);
                    check(oracle.lookup("java.lang.String") != null, 5);
                    check(oracle.lookup("java.lang.NoSuchClassZZZ") == null, 6);
                    System.out.println("BOOTSTRAP-ORACLE-OK");
                }
                private static void check(boolean ok, int code) {
                    if (!ok) {
                        System.exit(code);
                    }
                }
            }
            """, StandardCharsets.UTF_8);
        final String compileClasspath = runtime + java.io.File.pathSeparator + sdk;
        final int compiled = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-classpath", compileClasspath, "-d", probeClasses.toString(),
            probeSource.toString());
        assertEquals(0, compiled, "probe compilation failed");
        final String javaBinary = Path.of(
            System.getProperty("java.home"), "bin", "java").toString();
        final Process process = new ProcessBuilder(
            javaBinary,
            "-Xbootclasspath/a:" + sdk,
            "-cp", runtime + java.io.File.pathSeparator + asm
                + java.io.File.pathSeparator + probeClasses,
            "dev.turboism.core.event.BootstrapOracleProbe"
        ).redirectErrorStream(true).start();
        final String output = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int exit = process.waitFor();
        assertEquals(0, exit,
            "bootstrap oracle probe failed with exit " + exit + ":\n" + output);
        assertTrue(output.contains("BOOTSTRAP-ORACLE-OK"));
    }

    /**
     * Contract-path policy admits the zero-payload directory entries standard
     * JDK jar writers emit (Amendment A-1.R/R3) — the parser still runs them
     * through counted/CRC-verified consume, and a directory carrying payload
     * bytes remains rejected.
     */
    @Test
    void safeEmptyDirectoryEntriesAreAccepted() throws Exception {
        final byte[] artifact = jarWithDirectory(
            "com/acme/events/",
            entry(EVENT_INTERNAL + ".class", eventClass()));
        assertNull(preflight(artifact, Set.of(EVENT)));
    }

    @Test
    void directoryEntryCarryingPayloadIsRejected() throws Exception {
        // A directory name with a non-zero payload is a data channel, not
        // structure — the structural core rejects it before any type check.
        final byte[] artifact = jarWithDirectoryPayload(
            "com/acme/events/", new byte[] {1, 2, 3},
            entry(EVENT_INTERNAL + ".class", eventClass()));
        assertEquals(Rejection.CONTENT, preflight(artifact, Set.of(EVENT)));
    }

    /** Path quota diagnostics name the dimension that tripped. */
    @Test
    void pathByteQuotaReportsItsOwnDimension() throws Exception {
        final String longName =
            "com/acme/" + "a".repeat(1100) + "/Deep.class";
        final byte[] artifact = jar(
            entry(longName, plainClass("com/acme/Deep", "java/lang/Object")));
        final ContractViolation failure = assertThrows(ContractViolation.class,
            () -> PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), Set.of()));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("ARCHIVE_PATH_TOO_LONG"),
            failure.getMessage());
    }

    @Test
    void pathDepthQuotaReportsItsOwnDimension() throws Exception {
        final String deepName =
            "a/".repeat(40) + "Deep.class";
        final byte[] artifact = jar(
            entry(deepName, plainClass("a/Deep", "java/lang/Object")));
        final ContractViolation failure = assertThrows(ContractViolation.class,
            () -> PublicEventContractPreflight.verify(
                PublicEventContractPreflight.newSession(),
                "dev.example.provider", CONTRACT_ID, ARTIFACT_PATH,
                sha256(artifact), artifact, Set.of(), Set.of()));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("ARCHIVE_PATH_TOO_DEEP"),
            failure.getMessage());
    }

    // ---------------------------------------------------- session accounting

    /**
     * Reference occurrences are charged before the tracking sets grow; the
     * unique-type set still dedups. 64 fields on the seed all reference the same
     * payload member, so references() grows per occurrence while uniqueTypes()
     * stays tiny — the pending queue never accumulates a duplicate either.
     */
    @Test
    void referenceChargingIsPerOccurrenceAndUniqueTypesDedup() throws Exception {
        final Member[] fields = new Member[64];
        for (int i = 0; i < fields.length; i++) {
            fields[i] = field(Opcodes.ACC_PUBLIC, "f" + i,
                "Lcom/acme/events/Payload;", null);
        }
        final byte[] artifact = jar(
            entry(EVENT_INTERNAL + ".class", eventClass(fields)),
            entry("com/acme/events/Payload.class", plainClass(
                "com/acme/events/Payload", "java/lang/Object")));
        final Session session = PublicEventContractPreflight.newSession();
        verifySeeded(session, artifact, CONTRACT_ID);
        final Session.Stats stats = session.stats();
        assertTrue(stats.references() >= 64,
            "per-occurrence charging expected >=64 references, got "
                + stats.references());
        assertTrue(stats.uniqueTypes() <= 8,
            "distinct names dedup: event, payload, Object, interface ≈4, got "
                + stats.uniqueTypes());
        // A second artifact accumulates on the same session (the first artifact
        // itself is sha-cached — identical bytes are never re-charged)…
        verifySeeded(session, distinctArtifact(41), "other.contract");
        assertTrue(session.stats().references() >= 64,
            "session accumulates across artifacts, got "
                + session.stats().references());
        assertEquals(2, session.stats().contracts());
        // …while a fresh session starts clean — session isolation.
        final Session fresh = PublicEventContractPreflight.newSession();
        verifySeeded(fresh, artifact, CONTRACT_ID);
        assertTrue(fresh.stats().references() >= 64
                && fresh.stats().references() < 128);
        assertEquals(1, fresh.stats().contracts());
    }

    /**
     * Ancestor edges are charged references too: a member chain
     * Payload→Mid→Base contributes its traversal edges to the session counters.
     */
    @Test
    void ancestorTraversalChargesEdges() throws Exception {
        final byte[] artifact = jar(
            entry(EVENT_INTERNAL + ".class", eventClass(
                field(Opcodes.ACC_PUBLIC, "p", "Lcom/acme/events/Payload;",
                    null))),
            entry("com/acme/events/Payload.class", plainClass(
                "com/acme/events/Payload", "com/acme/events/Mid")),
            entry("com/acme/events/Mid.class", plainClass(
                "com/acme/events/Mid", "com/acme/events/Base")),
            entry("com/acme/events/Base.class", plainClass(
                "com/acme/events/Base", "java/lang/Object")));
        final Session session = PublicEventContractPreflight.newSession();
        verifySeeded(session, artifact, CONTRACT_ID);
        final Session.Stats stats = session.stats();
        // Surface refs (field type + super + interface per visited member) plus
        // the three charged ancestor edges — well above the two members' own
        // surface-only count.
        assertTrue(stats.references() >= 5,
            "ancestor edges must be charged, got " + stats.references());
        assertTrue(stats.uniqueTypes() >= 4,
            "event, payload, mid, base at least, got " + stats.uniqueTypes());
    }

    /** The declared-count check fires before any artifact bytes are read. */
    @Test
    void sessionRejectsOversizedDeclarationCountUpFront() throws Exception {
        final Session session = PublicEventContractPreflight.newSession();
        final ContractViolation failure = assertThrows(ContractViolation.class,
            () -> session.expectContracts(
                Session.MAX_CONTRACTS + 1, "dev.example.provider"));
        assertEquals(Rejection.TOO_LARGE, failure.kind());
        assertTrue(failure.getMessage().contains("declared contracts"));
        // And the session still verifies a legal contract afterwards — the
        // rejection carried no hidden charge.
        assertNotNull(verify(session, distinctArtifact(7), CONTRACT_ID));
    }

    // ----------------------------------------------------------- asm fixtures

    private record Member(int access, String name, String descriptor,
                          String signature) {}

    private static Member field(
        final int access,
        final String name,
        final String descriptor,
        final String signature
    ) {
        return new Member(access, name, descriptor, signature);
    }

    private static Member member(
        final int access,
        final String name,
        final String descriptor,
        final String signature
    ) {
        return new Member(access, name, descriptor, signature);
    }

    private static Member component(
        final String name,
        final String descriptor,
        final String signature
    ) {
        return new Member(0, name, descriptor, signature);
    }

    private static Map.Entry<String, byte[]> entry(
        final String name, final byte[] bytes) {
        return Map.entry(name, bytes);
    }

    private static byte[] eventClass(final Member... members) {
        return clazz(
            EVENT_INTERNAL,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
            null, "java/lang/Object",
            new String[] {"dev/turboism/sdk/event/EventBus$TurboismEvent"},
            null, false, members
        );
    }

    private static byte[] plainClass(
        final String internal,
        final String superInternal,
        final Member... members
    ) {
        return clazz(internal, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            null, superInternal, null, null, false, members);
    }

    private static byte[] plainClass(
        final String internal,
        final String superInternal,
        final String interfaceInternal
    ) {
        return clazz(internal, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            null, superInternal, new String[] {interfaceInternal}, null,
            false);
    }

    private static byte[] sealedClass(
        final String internal,
        final String superInternal,
        final String... permitted
    ) {
        return clazz(internal, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            null, superInternal, null, permitted, false);
    }

    private static byte[] recordClass(
        final String internal,
        final Member... components
    ) {
        return clazz(internal,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER
                | Opcodes.ACC_RECORD,
            null, "java/lang/Record", null, null, true, components);
    }

    private static byte[] clazz(
        final String internal,
        final int access,
        final String signature,
        final String superInternal,
        final String[] interfaces,
        final String[] permitted,
        final boolean recordComponents,
        final Member... members
    ) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access, internal, signature,
            superInternal, interfaces);
        if (permitted != null) {
            for (final String subclass : permitted) {
                writer.visitPermittedSubclass(subclass);
            }
        }
        for (final Member member : members) {
            if (recordComponents) {
                writer.visitRecordComponent(
                    member.name(), member.descriptor(), member.signature()
                ).visitEnd();
            } else if (member.descriptor().startsWith("(")) {
                final var method = writer.visitMethod(
                    member.access(), member.name(), member.descriptor(),
                    member.signature(), null);
                // Loadable fixtures need a Code attribute on concrete methods.
                if ((member.access()
                        & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                    method.visitCode();
                    if (member.name().equals("<init>")) {
                        method.visitVarInsn(Opcodes.ALOAD, 0);
                        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                            superInternal, "<init>", "()V", false);
                        method.visitInsn(Opcodes.RETURN);
                        method.visitMaxs(1, 1);
                    } else {
                        method.visitInsn(Opcodes.RETURN);
                        method.visitMaxs(0, 1);
                    }
                }
                method.visitEnd();
            } else {
                writer.visitField(
                    member.access(), member.name(), member.descriptor(),
                    member.signature(), null
                ).visitEnd();
            }
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Nested generic signature {@code depth} levels deep. */
    private static String deepSignature(final int depth) {
        final StringBuilder signature = new StringBuilder();
        signature.append("Ljava/util/List<".repeat(depth));
        signature.append("Ljava/lang/Object;");
        signature.append(">;".repeat(depth));
        return signature.toString();
    }

    /** One level of nesting with {@code width} sibling type arguments. */
    private static String wideSignature(final int width) {
        final StringBuilder signature =
            new StringBuilder("Lcom/acme/events/Gen<");
        for (int i = 0; i < width; i++) {
            signature.append("Ljava/lang/Object;");
        }
        return signature.append(">;").toString();
    }

    /** A class signature declaring {@code width} Object-bounded type parameters. */
    private static String genericClassSignature(final int width) {
        final StringBuilder signature = new StringBuilder("<");
        for (int i = 0; i < width; i++) {
            signature.append("T").append(i).append(":Ljava/lang/Object;");
        }
        return signature.append(">Ljava/lang/Object;").toString();
    }

    /** {@code count} public fields, each referencing member {@code prefix}{offset+i}. */
    private static Member[] memberFields(
        final String prefix, final int offset, final int count) {
        final Member[] fields = new Member[count];
        for (int i = 0; i < count; i++) {
            fields[i] = field(Opcodes.ACC_PUBLIC, "f" + (offset + i),
                "L" + prefix + (offset + i) + ";", null);
        }
        return fields;
    }

    /**
     * A member whose field descriptors are maximal-length array types — 65 535
     * UTF-16 units of descriptor text each with zero resolvable references.
     * Field names carry ~1 200 chars of entropy to keep the member inside the
     * compression-ratio bound.
     */
    private static byte[] textHeavyMember(final String internal, final int seed) {
        final Random random = new Random(seed);
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internal, null, "java/lang/Object", null);
        final String descriptor = "[".repeat(65_534) + "J";
        for (int i = 0; i < 90; i++) {
            final StringBuilder name = new StringBuilder(1_200);
            for (int j = 0; j < 1_192; j++) {
                name.append((char) ('a' + random.nextInt(26)));
            }
            name.append(String.format("%08d", i));
            writer.visitField(Opcodes.ACC_PUBLIC, name.toString(),
                descriptor, null, null).visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * Moderately compressible bytes: mostly zeros with noise every
     * {@code stride} bytes — a ~stride:1 deflate ratio, inside the 100× bound.
     */
    private static byte[] compressible(
        final int size, final int stride, final int seed) {
        final byte[] bytes = new byte[size];
        final Random random = new Random(seed);
        for (int i = 0; i < size; i += stride) {
            bytes[i] = (byte) random.nextInt();
        }
        return bytes;
    }

    /**
     * A valid class file expanding to at least {@code size} bytes: a minimal class
     * carrying many fields with ~57 KB names whose character entropy is
     * {@code stride}-controlled, so the member's expanded size exercises artifact
     * budgets while staying inside the ratio bound.
     */
    private static byte[] largeMember(
        final String internal, final int size, final int seed, final int stride) {
        final Random random = new Random(seed);
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
            internal, null, "java/lang/Object", null);
        int estimate = 512;
        int sequence = 0;
        while (estimate < size) {
            final StringBuilder name = new StringBuilder(57_000);
            for (int i = 0; i < 56_992; i++) {
                name.append(i % stride == 0
                    ? (char) ('b' + random.nextInt(26)) : 'a');
            }
            name.append(String.format("%08d", sequence++));
            writer.visitField(Opcodes.ACC_PRIVATE, name.toString(),
                "I", null, null).visitEnd();
            estimate += 57_016;
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    // ------------------------------------------------------------- io helpers

    private Inspection verify(
        final Session session,
        final byte[] artifact,
        final String contractId
    ) throws ContractViolation {
        return PublicEventContractPreflight.verify(
            session, "dev.example.provider", contractId, ARTIFACT_PATH,
            sha256(artifact), artifact, Set.of(), Set.of());
    }

    /** Same as {@link #verify} but with the event seed declared. */
    private Inspection verifySeeded(
        final Session session,
        final byte[] artifact,
        final String contractId
    ) throws ContractViolation {
        return PublicEventContractPreflight.verify(
            session, "dev.example.provider", contractId, ARTIFACT_PATH,
            sha256(artifact), artifact, Set.of(), Set.of(EVENT));
    }

    /** The code-source location backing a classpath-visible class. */
    private static Path codeSource(final Class<?> anchor) throws Exception {
        return Path.of(anchor.getProtectionDomain().getCodeSource()
            .getLocation().toURI());
    }

    /** A structurally valid artifact whose bytes differ per {@code variant}. */
    private static byte[] distinctArtifact(final int variant) throws IOException {
        final String internal = "com/acme/events/Variant" + variant;
        return jar(entry(internal + ".class",
            plainClass(internal, "java/lang/Object")));
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @SafeVarargs
    private static byte[] jar(final Map.Entry<String, byte[]>... entries)
            throws IOException {
        return jar(List.of(entries));
    }

    /**
     * Serializes entries in order without de-duplication — {@link JarOutputStream}
     * accepts the writes a strict reader must then reject.
     */
    private static byte[] jar(final List<Map.Entry<String, byte[]>> entries)
            throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            for (final Map.Entry<String, byte[]> entry : entries) {
                try {
                    jar.putNextEntry(new JarEntry(entry.getKey()));
                } catch (java.util.zip.ZipException duplicate) {
                    // JDK writers refuse duplicate names; callers that need a
                    // duplicate fixture patch the bytes instead.
                    throw new IllegalStateException(duplicate);
                }
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Serializes entries STORED (no compression) — the fixture path for raw-size
     * budgets, where deflate would hide the real byte count.
     */
    private static byte[] jarStored(final List<Map.Entry<String, byte[]>> entries)
            throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            for (final Map.Entry<String, byte[]> entry : entries) {
                final JarEntry stored = new JarEntry(entry.getKey());
                stored.setMethod(JarEntry.STORED);
                stored.setSize(entry.getValue().length);
                stored.setCompressedSize(entry.getValue().length);
                final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(entry.getValue());
                stored.setCrc(crc.getValue());
                jar.putNextEntry(stored);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /**
     * Serializes a leading zero-payload directory entry followed by regular
     * entries — exactly the shape standard JDK {@code jar} tooling emits.
     */
    @SafeVarargs
    private static byte[] jarWithDirectory(
        final String directory,
        final Map.Entry<String, byte[]>... entries
    ) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            jar.putNextEntry(new JarEntry(directory));
            jar.closeEntry();
            for (final Map.Entry<String, byte[]> entry : entries) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    /** A directory-named entry carrying real payload bytes — a data channel. */
    @SafeVarargs
    private static byte[] jarWithDirectoryPayload(
        final String directory,
        final byte[] payload,
        final Map.Entry<String, byte[]>... entries
    ) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            jar.putNextEntry(new JarEntry(directory));
            jar.write(payload);
            jar.closeEntry();
            for (final Map.Entry<String, byte[]> entry : entries) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static byte[] jar(final Map<String, byte[]> entries) throws IOException {
        final List<Map.Entry<String, byte[]>> list = new ArrayList<>();
        entries.forEach((name, bytes) -> list.add(Map.entry(name, bytes)));
        return jar(list);
    }

    private static byte[] jarOf(final Path classes) throws IOException {
        final List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        try (var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                entries.add(Map.entry(
                    classes.relativize(file).toString().replace('\\', '/'),
                    Files.readAllBytes(file)));
            }
        }
        return jar(entries);
    }

    private Path compile(final Map<String, String> sources) throws IOException {
        final Path sourceRoot = Files.createDirectories(
            temporary.resolve("src-" + System.nanoTime()));
        final Path classes = Files.createDirectories(
            temporary.resolve("classes-" + System.nanoTime()));
        final List<String> files = new ArrayList<>();
        for (final Map.Entry<String, String> source : sources.entrySet()) {
            final Path file = sourceRoot.resolve(
                source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        final List<String> arguments = new ArrayList<>(List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString()));
        arguments.addAll(files);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final int result = compiler.run(
            null, null, null, arguments.toArray(new String[0]));
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    /**
     * Rewrites the first central-directory record's filename — a central/local
     * disagreement the strict parser must catch.
     */
    private static byte[] patchCentralName(
        final byte[] jar, final String newName) {
        final byte[] bytes = jar.clone();
        for (int i = 0; i + 4 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b
                && bytes[i + 2] == 0x01 && bytes[i + 3] == 0x02) {
                final byte[] name = newName.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(name, 0, bytes, i + 46, name.length);
                return bytes;
            }
        }
        throw new IllegalStateException("no central record found");
    }

    /** Renames every occurrence of an entry name — local and central records. */
    private static byte[] renameEverywhere(
        final byte[] jar, final String oldName, final String newName) {
        assertEquals(oldName.length(), newName.length());
        final byte[] bytes = jar.clone();
        final byte[] oldBytes = oldName.getBytes(StandardCharsets.UTF_8);
        final byte[] newBytes = newName.getBytes(StandardCharsets.UTF_8);
        int replaced = 0;
        outer:
        for (int i = 0; i + oldBytes.length <= bytes.length; i++) {
            for (int j = 0; j < oldBytes.length; j++) {
                if (bytes[i + j] != oldBytes[j]) {
                    continue outer;
                }
            }
            System.arraycopy(newBytes, 0, bytes, i, newBytes.length);
            replaced++;
        }
        if (replaced < 2) {
            throw new IllegalStateException(
                "expected a local and a central name record, patched " + replaced);
        }
        return bytes;
    }

    /** Flips one byte inside the first local record's compressed data region. */
    private static byte[] corruptFirstLocalDataByte(final byte[] jar) {
        final byte[] bytes = jar.clone();
        for (int i = 0; i + 30 <= bytes.length; i++) {
            if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b
                && bytes[i + 2] == 0x03 && bytes[i + 3] == 0x04) {
                final int nameLength =
                    (bytes[i + 26] & 255) | ((bytes[i + 27] & 255) << 8);
                final int extraLength =
                    (bytes[i + 28] & 255) | ((bytes[i + 29] & 255) << 8);
                final int data = i + 30 + nameLength + extraLength;
                bytes[data] ^= 0x5a;
                return bytes;
            }
        }
        throw new IllegalStateException("no local record found");
    }
}
