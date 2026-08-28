package dev.turboism.installer;

import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.resource.Messages;

import java.io.IOException;
import java.lang.reflect.Proxy;
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

    private static final String HEX64 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] args) throws Exception {
        strictNumbers();
        unicodeEscapeUsesAsciiHexOnly();
        managedFxPlatformPolicy();
        canonicalIdentity();
        malformedUtf8();
        sizeBoundary();
        concurrentGrowth();
        atomicReplacement();
        managedStateCleanup();
        managedStateBackupConfinement();
        windowsProgramsKnownFolder();
        bomHandling();
        retiredPluginCleanup();
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

    /** JSON Unicode escapes accept only RFC 8259 ASCII hexadecimal digits. */
    private static void unicodeEscapeUsesAsciiHexOnly() throws Exception {
        for (String[] fixture : List.of(
                new String[] {"Arabic-Indic", "١"},
                new String[] {"fullwidth", "０"})) {
            final String label = fixture[0];
            final String invalid = "{\"value\":\"\\u" + fixture[1] + "000\"}";
            try {
                BoundedJson.parse(invalid);
                check(label + " Unicode escape rejects non-ASCII hex", false);
            } catch (BoundedJson.JsonException expected) {
                check(label + " Unicode escape rejects non-ASCII hex", true);
            }

            final Path home = Files.createTempDirectory("cfg-merge-unicode-");
            final Path config = home.resolve("config.json");
            final byte[] source = ("{\"format\":\"turboism.runtime.config\","
                    + "\"schemaVersion\":1,\"note\":\"\\u" + fixture[1] + "000\"}")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(config, source);
                try {
                    ConfigMerge.loadExisting(home);
                    check(label + " Unicode escape fails closed through config merge", false);
                } catch (ConfigMerge.ConfigException expected) {
                    check(label + " Unicode escape fails closed through config merge", true);
                }
                check(label + " Unicode escape leaves config bytes unchanged",
                        java.util.Arrays.equals(source, Files.readAllBytes(config)));
            } finally {
                deleteTree(home);
            }
        }
    }

    /** The listener must reject unsupported Windows Full before config mutation, not Thin or Lite. */
    private static void managedFxPlatformPolicy() throws Exception {
        final String originalOs = System.getProperty("os.name");
        final String originalArchitecture = System.getProperty("os.arch");
        System.setProperty("os.name", "Windows 11");
        System.setProperty("os.arch", "amd64");
        try {
            final Path fullHome = Files.createTempDirectory("installer-policy-full-");
            final Path fullConfig = fullHome.resolve("config.json");
            final byte[] before = validConfig("full").getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(fullConfig, before);
                try {
                    listener(fullHome, "full").beforePacks(List.of());
                    check("Windows Full rejects unsupported managed fx platform", false);
                } catch (RuntimeException expected) {
                    check("Windows Full rejects unsupported managed fx platform",
                            expected.getCause() instanceof IOException
                                    && expected.getCause().getMessage().contains(
                                            "no reviewed managed fx runtime"));
                }
                check("Windows Full rejection occurs before config mutation",
                        java.util.Arrays.equals(before, Files.readAllBytes(fullConfig)));
            } finally {
                deleteTree(fullHome);
            }

            for (String mode : List.of("thin", "lite")) {
                final Path home = Files.createTempDirectory("installer-policy-" + mode + "-");
                final Path config = home.resolve("config.json");
                try {
                    Files.writeString(config, validConfig(mode), StandardCharsets.UTF_8);
                    listener(home, mode).beforePacks(List.of());
                    check("Windows " + mode + " proceeds without managed fx platform", true);
                    check("Windows " + mode + " reaches config merge",
                            Files.readString(config, StandardCharsets.UTF_8)
                                    .contains("\"worktreeId\":\"turboism-runtime\""));
                } finally {
                    deleteTree(home);
                }
            }
        } finally {
            restoreSystemProperty("os.name", originalOs);
            restoreSystemProperty("os.arch", originalArchitecture);
        }
    }

    private static TurboismInstallerListener listener(Path home, String mode) {
        final Map<String, String> variables = new LinkedHashMap<>();
        variables.put(TurboismInstallerListener.INSTALL_PATH_VAR, home.toString());
        variables.put(TurboismInstallerListener.INSTALL_GROUP_VAR, mode);
        variables.put(TurboismInstallerListener.BUNDLED_PLUGINS_VAR,
                "dev.turboism.plugin.fixture");
        final Messages messages = (Messages) Proxy.newProxyInstance(
                ConfigMergeRegression.class.getClassLoader(),
                new Class<?>[] {Messages.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> String.valueOf(arguments[0]);
                    case "getMessages" -> Map.of();
                    case "newMessages" -> proxy;
                    case "add" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        final InstallData data = (InstallData) Proxy.newProxyInstance(
                ConfigMergeRegression.class.getClassLoader(),
                new Class<?>[] {InstallData.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getVariable" -> variables.get(arguments[0]);
                    case "setVariable" -> {
                        variables.put((String) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "getSelectedPacks" -> List.of();
                    case "getMessages" -> messages;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new TurboismInstallerListener(data);
    }

    private static String validConfig(String marker) {
        return "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,"
                + "\"marker\":\"" + marker + "\"}";
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
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

    /**
     * Installer manual-review r1: a single leading UTF-8 BOM (PowerShell 5.1
     * Set-Content -Encoding UTF8) is tolerated on every parse/read path and is
     * never serialized back; a second BOM, BOM-only documents and malformed
     * UTF-8 still fail closed. Rewriting a BOM-prefixed config produces a
     * BOM-less file.
     */
    private static void bomHandling() throws Exception {
        String doc = "{\"format\":\"turboism.runtime.config\",\"schemaVersion\":1,\"logLevel\":\"DEBUG\"}";
        Object v = BoundedJson.parse("\uFEFF" + doc);
        check("BOM-prefixed document parses", v instanceof Map);
        String s = BoundedJson.serialize(v);
        check("serialization has no BOM",
                !s.startsWith("\uFEFF") && s.contains("\"logLevel\":\"DEBUG\""));
        check("non-BOM behavior unchanged",
                "{\"a\":1}".equals(BoundedJson.serialize(BoundedJson.parse("{\"a\":1}"))));
        try {
            BoundedJson.parse("\uFEFF\uFEFF{}");
            check("double BOM fails closed", false);
        } catch (BoundedJson.JsonException expected) {
            check("double BOM fails closed", true);
        }
        try {
            BoundedJson.parse("\uFEFF");
            check("BOM-only document fails closed", false);
        } catch (BoundedJson.JsonException expected) {
            check("BOM-only document fails closed", true);
        }
        String decoded = ConfigMerge.decodeUtf8Strict(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        check("decodeUtf8Strict accepts BOM bytes", decoded.startsWith("\uFEFF"));

        Path dir = Files.createTempDirectory("cfg-merge-bom-");
        Path cfg = dir.resolve("config.json");
        try {
            byte[] body = doc.getBytes(StandardCharsets.UTF_8);
            byte[] withBom = new byte[3 + body.length];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(body, 0, withBom, 3, body.length);
            Files.write(cfg, withBom);
            Map<String, Object> m = ConfigMerge.loadExisting(dir);
            check("config merge with BOM input loads",
                    m != null && "DEBUG".equals(m.get("logLevel")));
            ConfigMerge.write(dir, ConfigMerge.applyPolicy(m, List.of()));
            byte[] rewritten = Files.readAllBytes(cfg);
            check("rewritten config has no BOM",
                    rewritten.length >= 3
                            && !(rewritten[0] == (byte) 0xEF && rewritten[1] == (byte) 0xBB && rewritten[2] == (byte) 0xBF)
                            && new String(rewritten, StandardCharsets.UTF_8).startsWith("{"));
            check("rewritten config reloads", ConfigMerge.loadExisting(dir) != null);
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

    /** R5.4: dual-mode cleanup, exact takeover restoration, and conflict preservation. */
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
        Path takeover = shortcutDir.resolve("Cubism Official.lnk");
        Path backup = home.resolve("installer/shortcut-backups/" + HEX64 + ".lnk");
        Files.createDirectories(backup.getParent());
        byte[] originalBytes = "original-shortcut-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] managedBytes = "turboism-managed-shortcut-bytes".getBytes(StandardCharsets.UTF_8);
        Files.writeString(managed, "managed", StandardCharsets.UTF_8);
        Files.writeString(unrelated, "unrelated", StandardCharsets.UTF_8);
        Files.writeString(outside, "outside", StandardCharsets.UTF_8);
        Files.writeString(cubismRoot.resolve("sentinel.txt"), "unchanged", StandardCharsets.UTF_8);
        try {
            String valid = "{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
                    + "\"launchMode\":\"independent\",\"installations\":[{\"root\":\"" + cubismRoot + "\",\"version\":\"5.2\",\"selected\":true}],"
                    + "\"managedShortcuts\":[\"" + managed + "\"],\"managedShortcutHashes\":[{\"path\":\"" + managed + "\",\"sha256\":\"" + sha256(managed) + "\"}]}";
            Files.writeString(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE), valid, StandardCharsets.UTF_8);
            check("managed state cleanup removes owned shortcut", ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("managed shortcut removed", !Files.exists(managed));
            check("unrelated shortcut preserved", Files.exists(unrelated));
            check("outside shortcut preserved", Files.exists(outside));
            check("valid managed state removed", !Files.exists(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE)));
            check("cleanup never writes Cubism root", Files.readString(cubismRoot.resolve("sentinel.txt")).equals("unchanged"));

            Files.createDirectories(backup.getParent());
            Files.write(takeover, originalBytes);
            Files.write(backup, originalBytes);
            Files.write(takeover, managedBytes);
            String takeoverState = "{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
                    + "\"launchMode\":\"takeover\",\"installations\":[],\"managedShortcuts\":[],\"shortcutTakeovers\":[{"
                    + "\"shortcutPath\":\"" + takeover + "\",\"backupPath\":\"installer/shortcut-backups/" + HEX64 + ".lnk\","
                    + "\"originalSha256\":\"" + sha256Bytes(originalBytes) + "\",\"managedSha256\":\"" + sha256Bytes(managedBytes) + "\","
                    + "\"root\":\"C:/Cubism 5.2\",\"variant\":\"normal\",\"status\":\"active\"}]}";
            Files.writeString(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE), takeoverState, StandardCharsets.UTF_8);
            check("takeover cleanup restores exact original bytes", ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("takeover restored bytes match", java.util.Arrays.equals(Files.readAllBytes(takeover), originalBytes));
            check("takeover backup removed after restore", !Files.exists(backup));
            check("takeover backup directory removed when empty", !Files.exists(backup.getParent()));
            check("takeover installer directory removed when empty", !Files.exists(backup.getParent().getParent()));
            check("takeover state removed after restore", !Files.exists(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE)));

            Files.createDirectories(backup.getParent());
            Files.write(takeover, originalBytes);
            Files.write(backup, originalBytes);
            Files.write(takeover, "user-edited".getBytes(StandardCharsets.UTF_8));
            Files.writeString(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE), takeoverState, StandardCharsets.UTF_8);
            check("takeover user edit is a conflict", !ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("conflict preserves edited shortcut", Files.readString(takeover).equals("user-edited"));
            check("conflict preserves backup", Files.exists(backup));
            check("conflict preserves state", Files.exists(home.resolve(ConfigMerge.INSTALLATION_STATE_FILE)));
        } finally {
            deleteTree(dir);
        }
    }


    /**
     * R12: backup confinement is exact-name and chain-validated at use time.
     * A relocated 64-hex backup path below home and a linked backup directory
     * both fail closed with every recovery artifact preserved.
     */
    private static void managedStateBackupConfinement() throws Exception {
        Path dir = Files.createTempDirectory("cubism-backup-confinement-");
        Path shortcutDir = dir.resolve("Start Menu/Programs/Turboism");
        Path home = dir.resolve("home");
        Files.createDirectories(shortcutDir);
        Files.createDirectories(home.resolve("installer/shortcut-backups"));
        Path takeover = shortcutDir.resolve("Cubism Official.lnk");
        Path backup = home.resolve("installer/shortcut-backups/" + HEX64 + ".lnk");
        byte[] original = "original-shortcut-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] managed = "turboism-managed-shortcut-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(takeover, managed);
        Files.write(backup, original);
        Path state = home.resolve(ConfigMerge.INSTALLATION_STATE_FILE);
        try {
            String takeoverState = "{\"format\":\"turboism.cubism.installation-state\",\"schemaVersion\":1,"
                    + "\"launchMode\":\"takeover\",\"installations\":[],\"managedShortcuts\":[],\"shortcutTakeovers\":[{"
                    + "\"shortcutPath\":\"" + takeover + "\",\"backupPath\":\"installer/shortcut-backups/" + HEX64 + ".lnk\","
                    + "\"originalSha256\":\"" + sha256Bytes(original) + "\",\"managedSha256\":\"" + sha256Bytes(managed) + "\","
                    + "\"root\":\"C:/Cubism 5.2\",\"variant\":\"normal\",\"status\":\"active\"}]}";
            Files.writeString(state, takeoverState, StandardCharsets.UTF_8);
            check("normal confined backup restores exact bytes",
                    ConfigMerge.cleanupManagedState(home, shortcutDir)
                            && java.util.Arrays.equals(Files.readAllBytes(takeover), original));

            // relocated: a 64-hex name in a wrong directory below home fails closed
            Files.write(takeover, managed);
            Path relocated = home.resolve("installer/other/" + HEX64 + ".lnk");
            Files.createDirectories(relocated.getParent());
            Files.write(relocated, original);
            Files.writeString(state,
                    takeoverState.replace("installer/shortcut-backups/" + HEX64, "installer/other/" + HEX64),
                    StandardCharsets.UTF_8);
            check("relocated backup path fails closed", !ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("relocated failure preserves managed shortcut",
                    java.util.Arrays.equals(Files.readAllBytes(takeover), managed));
            check("relocated failure preserves state", Files.exists(state));
            check("relocated failure preserves backup", Files.exists(relocated));

            // linked backup directory: cleanup must fail closed before any read/delete
            Path escape = dir.resolve("escape");
            Files.createDirectories(escape);
            Path backupsLink = home.resolve("installer/shortcut-backups");
            try {
                Files.delete(backupsLink);
                Files.createSymbolicLink(backupsLink, escape);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                System.out.println("  skip: symbolic-link backup fixture unavailable");
                return;
            }
            Files.writeString(state, takeoverState, StandardCharsets.UTF_8);
            check("linked backup directory fails closed", !ConfigMerge.cleanupManagedState(home, shortcutDir));
            check("linked failure preserves managed shortcut",
                    java.util.Arrays.equals(Files.readAllBytes(takeover), managed));
            check("linked failure preserves state", Files.exists(state));
            check("linked failure preserves link target", Files.isDirectory(escape));
            check("linked failure preserves the link itself", Files.isSymbolicLink(backupsLink));
        } finally {
            deleteTree(dir);
        }
    }

    /** Windows cleanup must consume the queried Programs known-folder value. */
    private static void windowsProgramsKnownFolder() throws Exception {
        Path dir = Files.createTempDirectory("known-programs-");
        try {
            Path redirected = dir.resolve("redirected Start Menu/Programs").toAbsolutePath().normalize();
            Path shortcut = WindowsProgramsPath.parse(
                    "  " + redirected + System.lineSeparator());
            check("redirected Programs known folder resolves exact Turboism directory",
                    shortcut != null && shortcut.equals(redirected.resolve("Turboism")));
            check("relative Programs path fails closed",
                    WindowsProgramsPath.parse("relative/Programs") == null);
            check("multi-line Programs output fails closed",
                    WindowsProgramsPath.parse(
                            redirected + System.lineSeparator() + "foreign") == null);
        } finally {
            deleteTree(dir);
        }
    }

    private static String sha256(Path path) throws Exception {
        return sha256Bytes(Files.readAllBytes(path));
    }

    private static String sha256Bytes(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : value) hex.append(String.format(java.util.Locale.ROOT, "%02X", b));
        return hex.toString();
    }

    /**
     * Retirement slice: managed-upgrade cleanup deletes only JARs proven by
     * their embedded plugin.json id to own a retired id (canonical and renamed
     * filenames alike); every unverifiable or foreign entry is preserved, and
     * mergeDisabled prunes only the four retired ids from disabledPlugins.
     * Preserved or leftover retired descriptors are additionally denied by the
     * runtime PluginJarContract boundary (PLUGIN_RETIRED_ID); config alone
     * does not keep stale retired JARs inactive.
     */
    private static void retiredPluginCleanup() throws Exception {
        Path dir = Files.createTempDirectory("retired-plugins-");
        Path home = dir.resolve("home");
        Path plugins = home.resolve(ConfigMerge.PLUGIN_DIR);
        Path outside = dir.resolve("outside");
        Files.createDirectories(plugins);
        Files.createDirectories(outside);
        try {
            // matching embedded id -> deleted under canonical and renamed names
            Path canonical = plugins.resolve("log-filter.jar");
            writePluginJar(canonical, "dev.turboism.plugin.logfilter");
            Path renamed = plugins.resolve("renamed-archive.jar");
            writePluginJar(renamed, "dev.turboism.plugin.renderopt");
            // known filename with another plugin id -> preserved
            Path foreign = plugins.resolve("clip-mask.jar");
            writePluginJar(foreign, "dev.turboism.plugin.someone-else");
            // retained successor id must never be deleted
            Path successor = plugins.resolve("clipmask-viewer.jar");
            writePluginJar(successor, "dev.turboism.plugin.clipmask-viewer");
            // unreadable entries -> preserved
            Files.writeString(plugins.resolve("perf-opt.jar"), "not a zip archive");
            Files.writeString(plugins.resolve("notes.txt"), "not a jar");
            Files.createDirectories(plugins.resolve("subdir"));
            // valid zip without the canonical descriptor -> preserved
            Path noDescriptor = plugins.resolve("render-opt.jar");
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    Files.newOutputStream(noDescriptor))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
                zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            // outside the managed plugins dir -> never inspected or changed
            Path outsideJar = outside.resolve("log-filter.jar");
            writePluginJar(outsideJar, "dev.turboism.plugin.logfilter");

            ConfigMerge.retireManagedPlugins(home);

            check("retired canonical jar removed", !Files.exists(canonical));
            check("retired renamed jar removed", !Files.exists(renamed));
            check("foreign-id jar preserved", Files.exists(foreign));
            check("retained successor jar preserved", Files.exists(successor));
            check("unreadable jar preserved", Files.exists(plugins.resolve("perf-opt.jar")));
            check("non-jar file preserved", Files.exists(plugins.resolve("notes.txt")));
            check("directory entry preserved", Files.isDirectory(plugins.resolve("subdir")));
            check("descriptor-less jar preserved", Files.exists(noDescriptor));
            check("outside-home jar untouched", Files.exists(outsideJar));

            // symlinked retired jar entry fails closed (link and target kept)
            try {
                Path linkTarget = dir.resolve("linked-target.jar");
                writePluginJar(linkTarget, "dev.turboism.plugin.logfilter");
                Path link = plugins.resolve("linked-retired.jar");
                Files.createSymbolicLink(link, linkTarget);
                ConfigMerge.retireManagedPlugins(home);
                check("symlinked retired jar preserved (link itself)", Files.isSymbolicLink(link));
                check("symlink target untouched", Files.exists(linkTarget));
                Files.delete(link);
            } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
                System.out.println("  skip: symbolic-link plugin fixture unavailable");
            }

            // disabledPlugins pruning: only the four retired ids are pruned
            Map<String, Object> seed = seedConfig();
            seed.put("disabledPlugins", List.of(
                    "dev.turboism.plugin.logfilter",
                    "dev.turboism.plugin.clipmask",
                    "dev.turboism.plugin.perfopt",
                    "dev.turboism.plugin.renderopt",
                    "dev.turboism.plugin.mesh-edit-mirror-axis-enhance",
                    "dev.turboism.plugin.other"));
            List<String> disabled = ConfigMerge.mergeDisabled(
                    seed, java.util.Set.of("dev.turboism.plugin.mesh-edit-mirror-axis-enhance"),
                    java.util.Set.of("dev.turboism.plugin.mesh-edit-mirror-axis-enhance"), false);
            check("retired ids pruned from disabledPlugins",
                    !disabled.contains("dev.turboism.plugin.logfilter")
                            && !disabled.contains("dev.turboism.plugin.clipmask")
                            && !disabled.contains("dev.turboism.plugin.perfopt")
                            && !disabled.contains("dev.turboism.plugin.renderopt")
                            && !disabled.contains("dev.turboism.plugin.mesh-edit-mirror-axis-enhance")
                            && disabled.contains("dev.turboism.plugin.other")
                            && disabled.equals(disabled.stream().sorted().toList()));

            // absent plugins directory and empty home are a no-op
            ConfigMerge.retireManagedPlugins(dir.resolve("empty"));
            check("absent plugins directory is a no-op", true);
        } finally {
            deleteTree(dir);
        }
    }

    private static void writePluginJar(Path jar, String id) throws Exception {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(ConfigMerge.PLUGIN_JSON_ENTRY));
            String meta = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":3,"
                    + "\"id\":\"" + id + "\",\"name\":\"Fixture " + id + "\"}";
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
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
