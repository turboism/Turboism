package dev.turboism.update;

import dev.turboism.core.FrameworkBuildInfo;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** Explicit standalone Windows fixture, excluded from ordinary JUnit discovery (no skip-as-pass). */
public final class WindowsReceiptNativeFixture {
    private final Path base;
    private Path dll;
    private String dllSha256;
    private final FrameworkInstallationFiles.Factory factory;
    private final List<Map<String, String>> results = new ArrayList<>();

    private WindowsReceiptNativeFixture(final Path base, final FrameworkInstallationFiles.Factory factory) {
        this.base = base;
        this.factory = factory;
    }

    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--classpath-smoke")) {
            final Path base = Files.createDirectory(Path.of(args[1]));
            final var smoke = new WindowsReceiptNativeFixture(base, FrameworkInstallationFiles::system);
            final Fixture fixture = smoke.new Fixture();
            try (var paths = Files.walk(fixture.root.resolve("state"))) {
                for (Path path : paths.toList()) {
                    Files.setPosixFilePermissions(
                            path,
                            java.nio.file.attribute.PosixFilePermissions.fromString(
                                    Files.isDirectory(path) ? "rwx------" : "rw-------"));
                }
            }
            require(fixture.loader(fixture.verifier).load().loaded().equals(fixture.info), "bundle classpath");
            System.out.println("Standalone bundle classpath + POSIX signed Loader smoke PASS; Windows NOT_RUN");
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("fixtureRoot dll dllSha256 report.json");
        final Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Fixture directory missing");
        try (var children = Files.list(root)) {
            if (children.findAny().isPresent())
                throw new IOException("Runner must supply a newly created empty private fixture directory");
        }
        final var fixture = new WindowsReceiptNativeFixture(
                root, WindowsInstallationFiles.validationFactory(Path.of(args[1]), args[2]));
        fixture.dll = Path.of(args[1]);
        fixture.dllSha256 = args[2];
        fixture.run();
        final var report = Map.of(
                "schemaVersion",
                1,
                "java",
                System.getProperty("java.runtime.version"),
                "os",
                System.getProperty("os.name"),
                "dllSha256",
                args[2],
                "cases",
                fixture.results,
                "productionEnabled",
                false);
        Files.write(
                Path.of(args[3]),
                UpdateFileSupport.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
        if (fixture.results.stream().anyMatch(r -> !r.get("status").equals("PASS"))) System.exit(2);
    }

    private void run() throws Exception {
        check("signed-loader-evidence", () -> {
            final Fixture f = new Fixture();
            final var evidence = f.loader(f.verifier).load();
            require(evidence.loaded().equals(f.info), "loaded identity");
            require(!evidence.hashes().containsKey("plugins/acp.jar"), "plugin excluded");
            require(Files.readString(f.root.resolve("plugins/acp.jar")).equals("user"), "user bytes unchanged");
            require(f.verifier.authenticate(f.manifest, f.envelope).buildNumber() == 49, "historical authentication");
        });
        for (final String mode : List.of("hash", "manifest", "key", "revoked", "root", "source", "build", "channel")) {
            check("reject-" + mode, () -> {
                final Fixture f = new Fixture();
                UpdateManifestVerifier verifier = f.verifier;
                switch (mode) {
                    case "hash" -> Files.writeString(f.agent(), "tampered");
                    case "manifest" -> Files.writeString(f.documents.resolve("manifest.json"), "{}");
                    case "key" -> verifier = new UpdateManifestVerifier(Map.of("other", f.keys.getPublic()));
                    case "revoked" ->
                        verifier = new UpdateManifestVerifier(Map.of("fixture", f.keys.getPublic()), Set.of("fixture"));
                    case "root" -> {
                        final var index = UpdateFileSupport.JSON.readTree(Files.readAllBytes(f.index));
                        ((com.fasterxml.jackson.databind.node.ObjectNode) index).put("rootIdentity", "0".repeat(64));
                        Files.write(f.index, UpdateFileSupport.JSON.writeValueAsBytes(index));
                    }
                    default ->
                        f.info = new FrameworkBuildInfo(
                                "1.0.0",
                                mode.equals("channel")
                                        ? FrameworkBuildInfo.Channel.BETA
                                        : FrameworkBuildInfo.Channel.STABLE,
                                OptionalLong.of(mode.equals("build") ? 50 : 49),
                                mode.equals("source") ? "c".repeat(40) : "b".repeat(40),
                                "ci",
                                false);
                }
                final var loader = f.loader(verifier);
                rejects(loader::load);
            });
        }
        check("hardlink", () -> {
            final Fixture f = new Fixture();
            Files.createLink(f.root.resolve("duplicate"), f.agent());
            rejects(f.loader(f.verifier)::load);
        });
        check("ADS-including-MOTW", () -> {
            final Fixture f = new Fixture();
            // Java NIO rejects ':' in paths; create the alternate data stream through the shell.
            final Process ads = new ProcessBuilder(
                            "cmd.exe", "/d", "/c", "echo [ZoneTransfer]> \"" + f.agent() + ":Zone.Identifier\"")
                    .redirectErrorStream(true)
                    .start();
            final String adsOut =
                    new String(ads.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (ads.waitFor() != 0) throw new AssertionError("ADS setup failed: " + adsOut);
            rejects(f.loader(f.verifier)::load);
        });
        check("bad-DACL", () -> {
            final Fixture f = new Fixture();
            final var view = Files.getFileAttributeView(f.agent(), AclFileAttributeView.class);
            final var entries = new ArrayList<>(view.getAcl());
            entries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(f.root.getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName("Everyone"))
                    .setPermissions(AclEntryPermission.WRITE_DATA)
                    .build());
            view.setAcl(entries);
            rejects(f.loader(f.verifier)::load);
        });
        check("directory-and-file-pins-release", () -> {
            final Fixture f = new Fixture();
            try (var files = factory.open(f.root)) {
                rejects(() -> Files.move(f.root, f.root.resolveSibling("renamed-held")));
                final long root = (long) raw("openRoot", new Class<?>[] {String.class}, f.root.toString());
                final long leaf = (long) raw(
                        "openFile",
                        new Class<?>[] {long.class, String.class, long.class},
                        root,
                        "turboism-agent.jar",
                        4096L);
                try {
                    rejects(() -> Files.move(f.agent(), f.root.resolve("replaced.jar")));
                    rejects(() -> Files.writeString(f.agent(), "cannot overwrite"));
                    rejects(() -> raw("closeToken", new Class<?>[] {long.class, boolean.class}, root, true));
                    rejects(() -> raw("identity", new Class<?>[] {long.class}, leaf));
                } finally {
                    raw("closeToken", new Class<?>[] {long.class, boolean.class}, leaf, false);
                    raw("closeToken", new Class<?>[] {long.class, boolean.class}, root, true);
                }
                rejects(() -> raw("identity", new Class<?>[] {long.class}, root));
                rejects(() -> raw("closeToken", new Class<?>[] {long.class, boolean.class}, root, true));
                rejects(() -> raw("identity", new Class<?>[] {long.class}, Long.MAX_VALUE));
            }
            final Path moved = f.root.resolveSibling("renamed-released");
            Files.move(f.root, moved);
            Files.move(moved.resolve("turboism-agent.jar"), moved.resolve("released.jar"));
        });
        check("path-alias-and-bounds", () -> {
            final Fixture f = new Fixture();
            final long root = (long) raw("openRoot", new Class<?>[] {String.class}, f.root.toString());
            try {
                for (String path : List.of(
                        "../turboism-agent.jar",
                        "turboism-agent.jar.",
                        "turboism-agent.jar:ads",
                        "CON",
                        "graal//lib",
                        "turboism-agent.jar/",
                        "x\u0000y",
                        "x".repeat(241))) {
                    rejects(() ->
                            raw("openFile", new Class<?>[] {long.class, String.class, long.class}, root, path, 4096L));
                }
                rejects(() -> raw(
                        "openFile",
                        new Class<?>[] {long.class, String.class, long.class},
                        root,
                        "turboism-agent.jar",
                        1L));
            } finally {
                raw("closeToken", new Class<?>[] {long.class, boolean.class}, root, true);
            }
        });
        check("cancellation-close-and-fault-cleanup", () -> {
            final Fixture f = new Fixture();
            for (int i = 0; i < 300; i++) {
                try (var files = factory.open(f.root)) {
                    rejects(() -> files.document(f.root.resolve("missing"), 100));
                    Thread.currentThread().interrupt();
                    try {
                        files.document(f.agent(), 1000);
                        throw new AssertionError("cancelled read succeeded");
                    } catch (InterruptedException expected) {
                        require(Thread.currentThread().isInterrupted(), "interrupt preserved");
                    } finally {
                        Thread.interrupted();
                    }
                }
            }
            final var closed = factory.open(f.root);
            closed.close();
            closed.close();
            rejects(closed::rootIdentity);
            Files.move(f.root, f.root.resolveSibling("after-cancel"));
        });
        optional("junction", () -> {
            final Fixture f = new Fixture();
            final Path real = f.root.resolve("actual-graal");
            Files.move(f.root.resolve("graal"), real);
            final Process cmd = new ProcessBuilder(
                            "cmd.exe",
                            "/d",
                            "/c",
                            "mklink",
                            "/J",
                            f.root.resolve("graal").toString(),
                            real.toString())
                    .redirectErrorStream(true)
                    .start();
            final String output =
                    new String(cmd.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (cmd.waitFor() != 0) throw new CapabilityMissing("junction setup failed: " + output);
            rejects(f.loader(f.verifier)::load);
        });
        for (final boolean directory : List.of(false, true)) {
            optional(directory ? "directory-symlink" : "file-symlink", () -> {
                final Fixture f = new Fixture();
                final Path link = directory ? f.root.resolve("graal") : f.agent();
                final Path real = f.root.resolve(directory ? "real-graal" : "real-agent.jar");
                Files.move(link, real);
                try {
                    Files.createSymbolicLink(link, real);
                } catch (IOException | UnsupportedOperationException failure) {
                    throw new CapabilityMissing("symlink setup unavailable: " + failure);
                }
                rejects(f.loader(f.verifier)::load);
            });
        }
        optional("8.3-alias", () -> {
            final Fixture f = new Fixture();
            final Process cmd = new ProcessBuilder(
                            "cmd.exe", "/d", "/c", "for %I in (\"" + f.agent() + "\") do @echo %~sI")
                    .redirectErrorStream(true)
                    .start();
            final String name =
                    new String(cmd.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (cmd.waitFor() != 0 || !name.contains("~"))
                throw new CapabilityMissing("volume supplied no 8.3 alias; policy unchanged");
            try (var files = factory.open(f.root)) {
                rejects(() -> files.document(Path.of(name), 4096));
            }
        });
        optional("foreign-owner", () -> {
            final Fixture f = new Fixture();
            try {
                final var everyone =
                        f.root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
                Files.setOwner(f.agent(), everyone);
            } catch (IOException failure) {
                throw new CapabilityMissing("unprivileged foreign owner setup unavailable: " + failure);
            }
            rejects(f.loader(f.verifier)::load);
        });
        check("mid-read-cancel-and-callback-fault-release", () -> {
            final Fixture f = new Fixture(true);
            final var cancelFactory = WindowsInstallationFiles.validationFactory(
                    dll, dllSha256, () -> Thread.currentThread().interrupt());
            for (int i = 0; i < 300; i++) {
                try (var files = cancelFactory.open(f.root)) {
                    try {
                        files.document(f.agent(), UpdateManifest.MAX_MANIFEST_BYTES);
                        throw new AssertionError("mid-read cancellation succeeded");
                    } catch (InterruptedException expected) {
                        require(Thread.currentThread().isInterrupted(), "interrupt preserved");
                    } finally {
                        Thread.interrupted();
                    }
                }
            }
            final var faultFactory = WindowsInstallationFiles.validationFactory(dll, dllSha256, () -> {
                throw new IllegalStateException("injected callback failure");
            });
            try (var files = faultFactory.open(f.root)) {
                try {
                    files.document(f.agent(), UpdateManifest.MAX_MANIFEST_BYTES);
                    throw new AssertionError("callback fault ignored");
                } catch (IllegalStateException expected) {
                    require(expected.getMessage().equals("injected callback failure"), "callback failure preserved");
                }
            }
            Files.move(f.agent(), f.root.resolve("after-mid-read.jar"));
            Files.move(f.root, f.root.resolveSibling("after-mid-read-directory"));
        });
    }

    private static final class CapabilityMissing extends Exception {
        CapabilityMissing(final String message) {
            super(message);
        }
    }

    private void optional(final String name, final Action action) {
        try {
            action.run();
            results.add(Map.of("name", name, "status", "PASS"));
        } catch (CapabilityMissing missing) {
            results.add(Map.of("name", name, "status", "NOT_RUN", "reason", missing.getMessage()));
        } catch (Throwable failure) {
            results.add(Map.of("name", name, "status", "FAIL", "reason", failure.toString()));
        }
    }

    private void check(final String name, final Action action) {
        try {
            action.run();
            results.add(Map.of("name", name, "status", "PASS"));
        } catch (Throwable failure) {
            results.add(Map.of("name", name, "status", "FAIL", "reason", failure.toString()));
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(final Action action) throws Exception {
        try {
            action.run();
        } catch (IOException | UpdateManifestVerifier.UpdateManifestVerificationException expected) {
            return;
        }
        throw new AssertionError("unsafe operation accepted");
    }

    private static void require(final boolean value, final String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Object raw(final String name, final Class<?>[] types, final Object... args) throws Exception {
        final Method method = WindowsInstallationFiles.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private final class Fixture {
        final Path root = Files.createTempDirectory(base, "installation-");
        final KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        final UpdateManifestVerifier verifier = new UpdateManifestVerifier(Map.of("fixture", keys.getPublic()));
        FrameworkBuildInfo info = new FrameworkBuildInfo(
                "1.0.0", FrameworkBuildInfo.Channel.STABLE, OptionalLong.of(49), "b".repeat(40), "ci", false);
        final Path index =
                root.resolve(FrameworkInstallationReceiptLoader.STATE).resolve("installed-receipt.json");
        final Path documents;
        final byte[] manifest;
        final byte[] envelope;

        Fixture() throws Exception {
            this(false);
        }

        Fixture(final boolean large) throws Exception {
            final List<UpdateManifestEntry> entries = new ArrayList<>();
            final List<String> paths = new ArrayList<>(UpdateManifestEntry.FRAMEWORK_ROOT_FILES);
            paths.add("graal/lib/sdk-1.0.0.jar");
            paths.add("graal/lib/graal-host-1.0.0-all.jar");
            for (String path : paths) {
                final byte[] bytes = large && path.equals("turboism-agent.jar")
                        ? new byte[512 * 1024]
                        : ("old:" + path).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.createDirectories(root.resolve(path).getParent());
                Files.write(root.resolve(path), bytes);
                final String hash = FrameworkUpdateTaskStore.hash(bytes);
                entries.add(new UpdateManifestEntry(path, bytes.length, hash, "sha256:" + hash));
            }
            Files.createDirectories(root.resolve("plugins"));
            Files.writeString(root.resolve("plugins/acp.jar"), "user");
            manifest = new UpdateManifest(
                            1, "fixture", "1.0.0", "stable", 49, "b".repeat(40), "1.0.0", "windows-x64", entries)
                    .toCanonicalBytes();
            final Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keys.getPrivate());
            signer.update(manifest);
            envelope = UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                    .createObjectNode()
                    .put("format", UpdateManifestVerifier.SIGNATURE_FORMAT)
                    .put("schemaVersion", 1)
                    .put("algorithm", "Ed25519")
                    .put("keyId", "fixture")
                    .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                    .put("signature", Base64.getEncoder().encodeToString(signer.sign())));
            documents = index.getParent().resolve("receipts").resolve(FrameworkUpdateTaskStore.hash(manifest));
            Files.createDirectories(documents);
            Files.write(documents.resolve("manifest.json"), manifest);
            Files.write(documents.resolve("manifest.json.sig"), envelope);
            final String identity;
            try (var files = factory.open(root)) {
                identity = files.rootIdentity();
            }
            Files.write(
                    index,
                    UpdateFileSupport.JSON.writeValueAsBytes(UpdateFileSupport.JSON
                            .createObjectNode()
                            .put("format", FrameworkInstallationReceiptLoader.FORMAT)
                            .put("schemaVersion", 1)
                            .put("layout", FrameworkInstallationReceiptLoader.LAYOUT)
                            .put("manifestSha256", FrameworkUpdateTaskStore.hash(manifest))
                            .put("rootIdentity", identity)));
        }

        Path agent() {
            return root.resolve("turboism-agent.jar");
        }

        FrameworkInstallationReceiptLoader loader(final UpdateManifestVerifier trust) {
            return new FrameworkInstallationReceiptLoader(root, trust, () -> info, this::agent, factory);
        }
    }
}
