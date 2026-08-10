package dev.turboism.installer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic, stdlib-only regression harness for the bounded config merge
 * (R8 findings). Runs before the live-install matrix; exercises the exact
 * classes shipped in the installer:
 *
 *  - strict RFC 8259 number lexing (leading zeros, missing fraction/exponent
 *    digits, malformed spellings) while preserving valid large
 *    integer/decimal/exponent values via Long/BigDecimal without non-finite
 *    output;
 *  - canonical runtime-config v1 identity (exact format, integral
 *    schemaVersion == 1) failing closed without source mutation;
 *  - the consumed-bytes size cap: exactly MAX bytes load, MAX+1 bytes fail
 *    closed, and concurrent growth is detected deterministically from bytes
 *    actually consumed (the same branch a grown file always trips once it
 *    exceeds the bound), never from a size snapshot and with no production
 *    fault switch;
 *  - atomic replacement with ATOMIC_MOVE + REPLACE_EXISTING.
 *
 * Invocation: java -cp <jar> dev.turboism.installer.ConfigMergeRegression
 */
public final class ConfigMergeRegression {

    public static void main(String[] args) throws Exception {
        strictNumbers();
        canonicalIdentity();
        malformedUtf8();
        sizeBoundary();
        concurrentGrowth();
        atomicReplacement();
        managedStateCleanup();
        System.out.println("ConfigMergeRegression: all checks passed");
    }

    private static void check(String name, boolean cond) {
        if (!cond) {
            System.err.println("FAIL: " + name);
            System.exit(1);
        }
        System.out.println("  ok: " + name);
    }

    private static Map<String, Object> seedConfig() {
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("format", "turboism.runtime.config");
        seed.put("schemaVersion", 1L);
        seed.put("logLevel", "DEBUG");
        return seed;
    }

    /** R8.3: strict number lexing. */
    private static void strictNumbers() {
        String[] bad = {"01", "-01", "1.", "1e", "1e+", "1e-", ".5", "+1",
                "1.2.3", "--1", "1a", "0x1", "1e1.5", "-", ""};
        for (String n : bad) {
            try {
                BoundedJson.parse("{\"x\":" + n + "}");
                check("strict number rejects '" + n + "'", false);
            } catch (BoundedJson.JsonException expected) {
                check("strict number rejects '" + n + "'", true);
            }
        }
        String[] good = {"0", "-0", "123", "-123", "0.5", "-0.25", "1e10",
                "1E+10", "1e-5", "1.5e300", "123456789012345678901234567890",
                "1e400", "10.25e-3"};
        for (String n : good) {
            try {
                Object v = BoundedJson.parse("{\"x\":" + n + "}");
                check("strict number accepts '" + n + "'", true);
            } catch (BoundedJson.JsonException e) {
                check("strict number accepts '" + n + "' (got " + e.getMessage() + ")", false);
            }
        }
        // large exponent must round-trip as valid finite JSON
        String s = BoundedJson.serialize(BoundedJson.parse("{\"x\":1e400}"));
        check("1e400 serializes as finite JSON (" + s + ")",
                s.contains("1E+400") && !s.contains("Infinity") && !s.contains("NaN"));
        // large integers stay exact
        String big = BoundedJson.serialize(
                BoundedJson.parse("{\"x\":123456789012345678901234567890}"));
        check("large integer round-trips exactly (" + big + ")",
                big.contains("123456789012345678901234567890"));
    }

    /** R8.4: canonical runtime-config v1 identity, fail closed, no mutation. */
    private static void canonicalIdentity() throws Exception {
        Path dir = Files.createTempDirectory("cfg-merge-canon-");
        Path cfg = dir.resolve("config.json");
        try {
            Files.write(cfg, "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"logLevel\":\"DEBUG\"}"
                    .getBytes(StandardCharsets.UTF_8));
            Map<String, Object> loaded = ConfigMerge.loadExisting(dir);
            check("valid v1 loads and preserves unrelated fields",
                    loaded != null && "DEBUG".equals(loaded.get("logLevel")));
            String[] bad = {
                    "{\"schemaVersion\":1}",
                    "{\"format\":\"other.runtime.config\",\"schemaVersion\":1}",
                    "{\"format\":\"turboism.runtime.config\"}",
                    "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":\"1\"}",
                    "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":2}",
                    "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1.5}",
                    "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":18446744073709551617}",
            };
            for (String text : bad) {
                Files.write(cfg, text.getBytes(StandardCharsets.UTF_8));
                try {
                    ConfigMerge.loadExisting(dir);
                    check("canonical identity rejects: " + text, false);
                } catch (ConfigMerge.ConfigException expected) {
                    check("canonical identity rejects: " + text, true);
                }
                check("rejection never mutated the source", text.equals(Files.readString(cfg)));
            }
            // integral 1.0 is accepted as schemaVersion 1
            Files.write(cfg, "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1.0}"
                    .getBytes(StandardCharsets.UTF_8));
            check("integral 1.0 schemaVersion accepted", ConfigMerge.loadExisting(dir) != null);
        } finally {
            deleteTree(dir);
        }
    }

    /** R8.1: consumed-bytes bound, exactly MAX loads, MAX+1 fails closed. */
    /**
     * R14: malformed UTF-8 in an unrelated string fails closed through the
     * typed ConfigException and the source bytes stay identical — the strict
     * decoder never replaces the malformed byte and rewrites the field.
     */
    private static void malformedUtf8() throws Exception {
        Path dir = Files.createTempDirectory("cfg-merge-utf8-");
        Path cfg = dir.resolve("config.json");
        try {
            byte[] malformed = new byte[] {
                    '{', '"', 'f', 'o', 'r', 'm', 'a', 't', '"', ':', '"',
                    't', 'u', 'r', 'b', 'o', 'i', 's', 'm', '.', 'r', 'u', 'n',
                    't', 'i', 'm', 'e', '.', 'c', 'o', 'n', 'f', 'i', 'g', '"', ',',
                    '"', 's', 'c', 'h', 'e', 'm', 'a', 'V', 'e', 'r', 's', 'i', 'o', 'n', '"', ':', '1', ',',
                    '"', 'n', 'o', 't', 'e', '"', ':', '"', 'a', (byte) 0xFF, 'b', '"', '}'
            };
            Files.write(cfg, malformed);
            try {
                ConfigMerge.loadExisting(dir);
                check("malformed UTF-8 fails closed", false);
            } catch (ConfigMerge.ConfigException expected) {
                check("malformed UTF-8 fails closed", true);
            }
            check("malformed UTF-8 source bytes unchanged",
                    java.util.Arrays.equals(Files.readAllBytes(cfg), malformed));
            // contrast: the same document with valid UTF-8 loads and preserves
            // the unrelated field
            byte[] valid = "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"note\":\"ab\"}"
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(cfg, valid);
            Map<String, Object> m = ConfigMerge.loadExisting(dir);
            check("valid UTF-8 loads and preserves field",
                    m != null && "ab".equals(m.get("note")));
        } finally {
            deleteTree(dir);
        }
    }

    /** R8.1: consumed-bytes bound, exactly MAX loads, MAX+1 fails closed. */
    private static void sizeBoundary() throws Exception {
        Path dir = Files.createTempDirectory("cfg-merge-size-");
        Path cfg = dir.resolve("config.json");
        try {
            // valid JSON of exactly MAX bytes: three strings at the parser's
            // string bound plus trailing whitespace (legal JSON)
            StringBuilder sb = new StringBuilder();
            sb.append("{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1");
            int padLen = (int) BoundedJson.MAX_STRING_LEN;
            for (int i = 0; i < 3; i++) {
                sb.append(",\"p").append(i).append("\":\"").append("x".repeat(padLen)).append("\"");
            }
            sb.append("}");
            int spaces = (int) ConfigMerge.MAX_CONFIG_BYTES - sb.length();
            check("boundary construction fits with padding", spaces >= 0);
            byte[] maxBytes = (sb + " ".repeat(spaces)).getBytes(StandardCharsets.UTF_8);
            check("boundary file is exactly MAX bytes", maxBytes.length == ConfigMerge.MAX_CONFIG_BYTES);
            Files.write(cfg, maxBytes);
            Map<String, Object> m = ConfigMerge.loadExisting(dir);
            check("exactly MAX bytes still loads", m != null && m.containsKey("p2"));
            // one more byte: the consumed-byte cap must fail closed, source intact
            Files.write(cfg, (new String(maxBytes, StandardCharsets.UTF_8) + " ")
                    .getBytes(StandardCharsets.UTF_8));
            try {
                ConfigMerge.loadExisting(dir);
                check("MAX+1 bytes fails closed", false);
            } catch (ConfigMerge.ConfigException expected) {
                check("MAX+1 bytes fails closed", true);
            }
        } finally {
            deleteTree(dir);
        }
    }

    /**
     * R8.1: deterministic concurrent growth. A writer appends bytes in a
     * tight loop (no sleeps) while the reader repeatedly runs readBounded.
     * The invariant asserted for every attempt: either a valid read of at
     * most MAX bytes or a fail-closed ConfigException — never more than MAX
     * consumed bytes and no other failure mode. Once the writer has pushed
     * the file past the bound, every subsequent attempt MUST fail closed, so
     * the oversized branch is observed deterministically; the growth-during-
     * read interleavings that the race produces exercise the exact same
     * consumed-byte check. No production fault switch is involved.
     */
    private static void concurrentGrowth() throws Exception {
        Path dir = Files.createTempDirectory("cfg-merge-growth-");
        Path file = dir.resolve("config.json");
        try {
            Files.write(file, "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1}"
                    .getBytes(StandardCharsets.UTF_8));
            AtomicBoolean writerDone = new AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    ByteBuffer one = ByteBuffer.wrap(new byte[]{' '});
                    long total = ConfigMerge.MAX_CONFIG_BYTES + (128L * 1024);
                    for (long i = 0; i < total; i++) {
                        one.rewind();
                        ch.write(one);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                writerDone.set(true);
            }, "config-growth-writer");
            writer.start();
            boolean sawOversized = false;
            long maxConsumed = 0;
            long attempts = 0;
            while (!sawOversized && attempts < 2_000_000) {
                attempts++;
                try {
                    byte[] bytes = ConfigMerge.readBounded(file);
                    maxConsumed = Math.max(maxConsumed, bytes.length);
                    if (bytes.length > ConfigMerge.MAX_CONFIG_BYTES) {
                        throw new AssertionError("readBounded returned more than MAX bytes");
                    }
                } catch (ConfigMerge.ConfigException expected) {
                    sawOversized = true;
                }
            }
            writer.join(60_000);
            check("concurrent growth: writer completed", writerDone.get());
            check("concurrent growth: oversized detection fired deterministically", sawOversized);
            check("concurrent growth: never consumed more than MAX bytes", maxConsumed <= ConfigMerge.MAX_CONFIG_BYTES);
            // after the writer finishes the file is far beyond MAX: the next
            // read MUST fail closed
            try {
                ConfigMerge.readBounded(file);
                check("concurrent growth: final grown read fails closed", false);
            } catch (ConfigMerge.ConfigException expected) {
                check("concurrent growth: final grown read fails closed", true);
            }
        } finally {
            deleteTree(dir);
        }
    }

    /** R8.2: atomic replacement with REPLACE_EXISTING over an existing config. */
    private static void atomicReplacement() throws Exception {
        Path dir = Files.createTempDirectory("cfg-merge-replace-");
        try {
            ConfigMerge.write(dir, ConfigMerge.applyPolicy(seedConfig(), List.of()));
            ConfigMerge.write(dir, ConfigMerge.applyPolicy(seedConfig(), List.of("dev.turboism.plugin.x")));
            String text = Files.readString(dir.resolve("config.json"));
            check("second atomic write replaces the existing config",
                    text.contains("dev.turboism.plugin.x"));
            check("replacement keeps canonical identity",
                    text.contains("\"format\":\"turboism.runtime.config\""));
            Map<String, Object> reloaded = ConfigMerge.loadExisting(dir);
            check("replaced config reloads as canonical v1", reloaded != null);
        } finally {
            deleteTree(dir);
        }
    }

    /** R5.4: bounded managed shortcut cleanup and fail-closed path ownership. */
    private static void managedStateCleanup() throws Exception {
        Path dir = Files.createTempDirectory("cubism-managed-state-");
        Path shortcutDir = dir.resolve("Start Menu/Programs/Turboism");
        Path outsideDir = dir.resolve("outside");
        Path home = dir.resolve("home");
        Path cubismRoot = dir.resolve("cubism-root");
        Files.createDirectories(shortcutDir);
        Files.createDirectories(outsideDir);
        Files.createDirectories(home);
        Files.createDirectories(cubismRoot);
        Path managed = shortcutDir.resolve("Turboism Cubism 5.2 [fixture-A1B2C3D4E5F6].lnk");
        Path unrelated = shortcutDir.resolve("Turboism Configurator.lnk");
        Path outside = outsideDir.resolve("Turboism Cubism 5.2 [outside-A1B2C3D4E5F6].lnk");
        Files.writeString(managed, "managed", StandardCharsets.UTF_8);
        Files.writeString(unrelated, "unrelated", StandardCharsets.UTF_8);
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Files.writeString(cubismRoot.resolve("sentinel.txt"), "unchanged", StandardCharsets.UTF_8);
        try {
            String valid = "{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
                    + "\"installations\":[{\"root\":\"" + cubismRoot + "\",\"version\":\"5.2\",\"selected\":true}],"
                    + "\"managedShortcuts\":[\"" + managed + "\"]}";
            Files.writeString(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE), valid, StandardCharsets.UTF_8);
            check("managed state cleanup removes owned shortcut", ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("managed shortcut removed", !Files.exists(managed));
            check("unrelated shortcut preserved", Files.exists(unrelated));
            check("outside shortcut preserved", Files.exists(outside));
            check("valid managed state removed", !Files.exists(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE)));
            check("cleanup never writes Cubism root", Files.readString(cubismRoot.resolve("sentinel.txt")).equals("unchanged"));

            Files.writeString(managed, "managed", StandardCharsets.UTF_8);
            String relocated = shortcutDir.resolve("../outside/Turboism Cubism 5.2 [outside-A1B2C3D4E5F6].lnk").toString();
            String escapedPath = relocated.replace("/", "\\/");
            String escaped = "{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
                    + "\"installations\":[],\"managedShortcuts\":[\"" + escapedPath + "\"]}";
            Files.writeString(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE), escaped, StandardCharsets.UTF_8);
            check("relocated shortcut state fails closed", !ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("malformed ownership preserves managed shortcut", Files.exists(managed));
            check("malformed state is not deleted", Files.exists(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE)));
            check("malformed cleanup preserves unrelated shortcut", Files.exists(unrelated));
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
