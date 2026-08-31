package dev.turboism.installer;

import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.data.Pack;
import com.izforge.izpack.api.event.AbstractInstallerListener;
import com.izforge.izpack.api.event.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Install-side listener for the Turboism Java installer.
 *
 * Responsibilities (frozen spec):
 *  - set the per-OS default install path before the target-directory panel;
 *  - write config.json before any payload file is copied: seed from the
 *    canonical template on fresh targets, otherwise preserve the existing
 *    valid config and merge only installer-owned fields; fail closed
 *    (abort before mutation) on invalid, oversized, symlinked, or escaping
 *    config targets;
 *  - mark the bundled uninstall.command executable on macOS, only after the
 *    file has been copied (afterPacks).
 *
 * The bundled plugin inventory is provided by the build-generated installer
 * variable {@code turboism.bundledPlugins} (comma-separated ids), derived
 * from each plugin JAR's META-INF/turboism/plugin.json at build time.
 *
 * Selected plugin ids come from the IzPack Pack identity itself: the
 * generated installer.xml gives every plugin pack the XML {@code id}
 * attribute equal to its plugin id, which IzPack exposes as
 * {@link Pack#getLangPackId()}. No display-name to id map exists anywhere,
 * so duplicate display titles cannot alias one plugin to another.
 */
public final class TurboismInstallerListener extends AbstractInstallerListener {

    static final String BUNDLED_PLUGINS_VAR = "turboism.bundledPlugins";
    static final String MANAGED_GRAAL_PACK_ID = "turboism-managed-graal";
    static final String MANAGED_GRAAL_VAR = "turboism.installManagedGraal";
    static final String INSTALL_GROUP_VAR = "INSTALL_GROUP";
    static final String INSTALL_PATH_VAR = "INSTALL_PATH";
    private static final String MCP_PLUGIN_ID = "dev.turboism.plugin.mcp";
    private static final String FX_PLUGIN_ID =
        "dev.turboism.plugin.turboism-with-fx";

    private static final String MANAGED_FX_VERSION = "0.0.5";
    private static final String DISTRIBUTION_NOTICE =
        "TURBOISM-DISTRIBUTION-NOTICE.txt";
    private static final Properties MANAGED_FX_MANIFEST = loadManagedFxManifest();
    private static final Map<String, FileIdentity> MANAGED_FX_LEGAL_IDENTITIES =
        loadManagedFxLegalIdentities(MANAGED_FX_MANIFEST);
    private static final Map<String, ManagedFxIdentity> MANAGED_FX_IDENTITIES =
        loadManagedFxIdentities(MANAGED_FX_MANIFEST);

    private final InstallData installData;

    public TurboismInstallerListener(InstallData installData) {
        this.installData = installData;
    }

    @Override
    public void initialise() {
        // Platform defaults (spec): Windows %LOCALAPPDATA%/Turboism,
        // macOS ~/Library/Application Support/Turboism,
        // Linux ${XDG_DATA_HOME:-~/.local/share}/Turboism.
        if (installData.getVariable(INSTALL_PATH_VAR) == null) {
            installData.setVariable(INSTALL_PATH_VAR, defaultInstallPath());
        }
    }

    @Override
    public void beforePacks(List<Pack> packs) {
        try {
            requireSafeInstallHome();
            requireManagedFxPlatform();
            requireSafeManagedFxModeTransition();
            requireSafeManagedFxExtractionTarget();
            writeConfig();
            announceLocalizedModes();
        } catch (IOException e) {
            throw new RuntimeException("Turboism: cannot update config.json: " + e.getMessage(), e);
        } catch (ConfigMerge.ConfigException e) {
            // Fail closed: the original config.json is untouched and the
            // install aborts before any payload file is copied.
            throw new RuntimeException("Turboism: " + e.getMessage(), e);
        }
    }

    /**
     * Runs after the payload files have been copied: mark the bundled
     * uninstall.command executable on macOS and, only when its optional pack
     * was selected on Windows, invoke the pinned managed-Graal installer bridge.
     */
    @Override
    public void afterPacks(List<Pack> packs, ProgressListener listener) {
        try {
            makeUninstallCommandExecutable();
            makeManagedFxExecutable();
            installManagedGraalIfSelected();
        } catch (IOException e) {
            throw new RuntimeException("Turboism: installer post-processing failed: " + e.getMessage(), e);
        }
    }

    /** Rejects an existing install root that would redirect any installer mode through a link. */
    private void requireSafeInstallHome() throws IOException {
        final String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        final Path home = Paths.get(installPath).toAbsolutePath().normalize();
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(home)
                    || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("install home is not an ordinary directory: " + home);
        }
    }

    /**
     * Thin and Lite are byte-absence modes. They cannot silently convert an existing Full home
     * while its managed fx runtime remains installed, and the installer does not delete an
     * existing runtime without a separate explicit removal action. Reject the mode transition
     * before config mutation or pack extraction. Full upgrades are handled by the exact-identity
     * cleanup and selected-platform finalization below.
     */
    private void requireSafeManagedFxModeTransition() throws IOException {
        if ("full".equalsIgnoreCase(installData.getVariable(INSTALL_GROUP_VAR))) {
            return;
        }
        final String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        final Path home = Paths.get(installPath).toAbsolutePath().normalize();
        final Path versionRoot = home.resolve(
            "runtimes/fx/" + MANAGED_FX_VERSION
        ).normalize();
        requireSafeManagedRuntimeAncestors(home, versionRoot);
        if (Files.exists(versionRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                "Thin and Lite installation require the managed fx runtime to be removed "
                    + "explicitly before changing an existing Full installation: " + versionRoot
            );
        }
    }

    /**
     * Full installation includes Turboism with fx and therefore requires one reviewed managed
     * runtime for the current platform. Reject an unsupported host before config mutation or pack
     * extraction instead of leaving incompatible payloads or an unusable selected plugin behind.
     */
    private void requireManagedFxPlatform() throws IOException {
        if (!"full".equalsIgnoreCase(installData.getVariable(INSTALL_GROUP_VAR))) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String architectureId = architectureId(architecture);
        if (architectureId != null
                && (os.equals("linux") || os.startsWith("linux ")
                    || os.contains("mac") || os.contains("darwin"))) {
            return;
        }
        if ("x86_64".equals(architectureId) && os.contains("win")) {
            return;
        }
        throw new IOException(
            "Full installation has no managed fx runtime payload for "
                + os + "/" + architecture
        );
    }

    /**
     * Rejects unsafe managed-runtime extraction paths before IzPack copies any
     * Full payload bytes. The same path admission runs again during finalization
     * to close replacements between the two lifecycle phases.
     */
    private void requireSafeManagedFxExtractionTarget() throws IOException {
        if (!"full".equalsIgnoreCase(installData.getVariable(INSTALL_GROUP_VAR))) {
            return;
        }
        final String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        final Path home = Paths.get(installPath).toAbsolutePath().normalize();
        if (Files.exists(home, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(home)
                    || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("managed fx install home is not an ordinary directory: " + home);
        }
        final Path versionRoot = home.resolve(
            "runtimes/fx/" + MANAGED_FX_VERSION
        ).normalize();
        requireSafeManagedRuntimeAncestors(home, versionRoot);
        for (String platform : MANAGED_FX_IDENTITIES.keySet()) {
            requireSafeManagedRuntimeAncestors(home, versionRoot.resolve(platform));
        }
    }

    /**
     * Emits the Turboism-owned localized installation-mode names and
     * descriptions (en/zh/ja) through IzPack's own message lookup. The stock
     * IzPack console group panel only prints the group ids ("full"/"lite"),
     * so the live console probe would otherwise never observe the localized
     * mode text; this keeps the console run a truthful live-locale probe.
     */
    private void announceLocalizedModes() {
        com.izforge.izpack.api.resource.Messages messages = installData.getMessages();
        for (String mode : new String[] {"full", "thin", "lite"}) {
            String name = messages.get("InstallationGroupPanel.group." + mode);
            String description = messages.get("InstallationGroupPanel.description." + mode);
            System.out.println("Turboism installation mode '" + mode + "': " + name);
            System.out.println("Turboism installation mode '" + mode + "' description: " + description);
        }
    }

    private void writeConfig() throws IOException, ConfigMerge.ConfigException {
        String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new ConfigMerge.ConfigException("install path is not set");
        }
        Path home = Paths.get(installPath);
        Set<String> bundled = bundledPluginIds();
        Set<String> selected = selectedPluginIds();
        boolean lite = "lite".equalsIgnoreCase(installData.getVariable(INSTALL_GROUP_VAR));

        Map<String, Object> seed = ConfigMerge.loadExisting(home);
        if (seed == null) {
            seed = ConfigMerge.loadTemplate();
        }
        List<String> disabled = ConfigMerge.mergeDisabled(seed, bundled, selected, lite);
        disabled = closeRequiredPluginDependencies(disabled);
        // Managed-upgrade retirement: remove identity-proven retired official
        // JARs, then write the merged config. Deletion and the config write are
        // two separate steps, not one transaction: a later config-write failure
        // does not restore already-deleted JARs. Deletion runs first so a proven
        // deletion failure aborts before any config mutation; leftovers that
        // cannot be verified are preserved and are denied by the runtime's
        // PluginJarContract boundary (PLUGIN_RETIRED_ID), not by config.
        ConfigMerge.retireManagedPlugins(home);
        ConfigMerge.write(home, ConfigMerge.applyPolicy(seed, disabled));
    }

    private void installManagedGraalIfSelected() throws IOException {
        final boolean selected = selectedPackIds().contains(MANAGED_GRAAL_PACK_ID);
        final String declared = installData.getVariable(MANAGED_GRAAL_VAR);
        if (!selected) {
            if (declared != null && !declared.isBlank()
                && !"false".equalsIgnoreCase(declared.trim())) {
                throw new IOException("managed GraalVM selection variable disagreed with selected packs");
            }
            return;
        }
        installData.setVariable(MANAGED_GRAAL_VAR, "true");
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            throw new IOException("managed GraalVM installation is supported only on Windows x64");
        }
        final String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        final Path home = Paths.get(installPath).toAbsolutePath().normalize();
        final Path agent = home.resolve("turboism-agent.jar");
        if (!Files.isRegularFile(agent, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(agent)) {
            throw new IOException("Turboism agent is missing or unsafe: " + agent);
        }
        final Path java = Paths.get(
            System.getProperty("java.home", ""), "bin", "java.exe"
        ).toAbsolutePath().normalize();
        if (!Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(java)) {
            throw new IOException("the Java runtime executing the installer is unavailable: " + java);
        }
        final ProcessBuilder command = new ProcessBuilder(
            java.toString(),
            "-cp", agent.toString(),
            "dev.turboism.graal.ManagedGraalRuntimeCli",
            "install", home.toString()
        ).inheritIO();
        command.environment().remove("JAVA_TOOL_OPTIONS");
        command.environment().remove("_JAVA_OPTIONS");
        command.environment().remove("JDK_JAVA_OPTIONS");
        final Process process = command.start();
        try {
            final int result = process.waitFor();
            if (result != 0) {
                throw new IOException("managed GraalVM installation failed with exit code " + result);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("managed GraalVM installation was interrupted", interrupted);
        }
    }

    private static List<String> closeRequiredPluginDependencies(
        final List<String> disabled
    ) {
        final java.util.TreeSet<String> closed = new java.util.TreeSet<>(disabled);
        if (closed.contains(MCP_PLUGIN_ID)) {
            closed.add(FX_PLUGIN_ID);
        }
        return List.copyOf(closed);
    }

    /**
     * Selected plugin ids, read directly from the IzPack pack identity: every
     * generated plugin pack carries the XML {@code id} attribute equal to its
     * plugin id, exposed at runtime as {@link Pack#getLangPackId()}. The
     * required common and optional managed-Graal packs are not bundled plugin
     * ids and are therefore harmless in this set; the merge only consults
     * bundled ids.
     */
    private Set<String> selectedPluginIds() {
        return selectedPackIds();
    }

    private Set<String> selectedPackIds() {
        Set<String> selected = new HashSet<>();
        for (Pack pack : installData.getSelectedPacks()) {
            String id = pack.getLangPackId();
            if (id != null && !id.trim().isEmpty()) {
                selected.add(id);
            }
        }
        return selected;
    }

    private Set<String> bundledPluginIds() throws ConfigMerge.ConfigException {
        String value = installData.getVariable(BUNDLED_PLUGINS_VAR);
        if (value == null || value.trim().isEmpty()) {
            throw new ConfigMerge.ConfigException("bundled plugin inventory ("
                    + BUNDLED_PLUGINS_VAR + ") is missing from the installer");
        }
        Set<String> ids = new HashSet<>();
        for (String id : value.split(",")) {
            id = id.trim();
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            throw new ConfigMerge.ConfigException("bundled plugin inventory is empty");
        }
        return ids;
    }

    /**
     * macOS (frozen spec "Platform integration"): the bundled
     * uninstall.command must exist as the copied regular non-symlink file and
     * must become executable. A missing file, a symlink, or a failed
     * permission change aborts the installation; {@code File.setExecutable}'s
     * boolean result is never ignored. Non-mac platforms are unchanged.
     */
    private void makeUninstallCommandExecutable() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!(os.contains("mac") || os.contains("darwin"))) {
            return;
        }
        String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        Path command = Paths.get(installPath, "uninstall.command");
        if (!Files.isRegularFile(command, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("uninstall.command is missing or not a regular file: " + command);
        }
        if (!command.toFile().setExecutable(true, false)) {
            throw new IOException("cannot make uninstall.command executable: " + command);
        }
        if (!Files.isExecutable(command)) {
            throw new IOException("uninstall.command is not executable after the permission change: " + command);
        }
    }

    /**
     * Retains only the reviewed payload for the current OS and architecture and makes its
     * executable runnable. IzPack 5.2.6 can filter files by OS but cannot distinguish AArch64
     * from x86_64, so the Full pack temporarily copies both CPU variants for the current OS.
     * Finalization removes the non-current installer-owned directory before the install succeeds.
     */
    private void makeManagedFxExecutable() throws IOException {
        if (!"full".equalsIgnoreCase(installData.getVariable(INSTALL_GROUP_VAR))) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String operatingSystem;
        if (os.equals("linux") || os.startsWith("linux ")) {
            operatingSystem = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            operatingSystem = "macos";
        } else if (os.contains("win")) {
            operatingSystem = "windows";
        } else {
            throw new IOException("managed fx operating system is unsupported: " + os);
        }
        String architectureId = architectureId(architecture);
        if (architectureId == null || ("windows".equals(operatingSystem)
                && !"x86_64".equals(architectureId))) {
            throw new IOException("managed fx architecture is unsupported: " + architecture);
        }
        String installPath = installData.getVariable(INSTALL_PATH_VAR);
        if (installPath == null || installPath.trim().isEmpty()) {
            throw new IOException("install path is not set");
        }
        Path home = Paths.get(installPath).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(home)
                || !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed fx install home is not an ordinary directory: " + home);
        }
        Path versionRoot = home.resolve("runtimes/fx/" + MANAGED_FX_VERSION).normalize();
        requireSafeManagedRuntimeAncestors(home, versionRoot);
        Path selectedDirectory = versionRoot.resolve(operatingSystem + "-" + architectureId);
        requireSafeManagedRuntimeAncestors(home, selectedDirectory);
        for (String platform : List.of(
            "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64",
            "windows-x86_64"
        )) {
            Path platformDirectory = versionRoot.resolve(platform);
            if (!platformDirectory.equals(selectedDirectory)) {
                deleteManagedRuntimeDirectory(versionRoot, platformDirectory);
            }
        }

        final boolean windows = "windows".equals(operatingSystem);
        Path executable = selectedDirectory.resolve(windows ? "fx.exe" : "fx");
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed fx executable is missing: " + executable);
        }
        if (!windows
                && (!executable.toFile().setExecutable(true, true)
                    || !Files.isExecutable(executable))) {
            throw new IOException("managed fx executable is not runnable: " + executable);
        }
    }

    private static String architectureId(String architecture) {
        if (architecture.equals("amd64") || architecture.equals("x86_64")
                || architecture.equals("x64")) {
            return "x86_64";
        }
        if (architecture.equals("aarch64") || architecture.equals("arm64")) {
            return "aarch64";
        }
        return null;
    }

    private static void requireSafeManagedRuntimeAncestors(
        Path home,
        Path versionRoot
    ) throws IOException {
        if (!versionRoot.startsWith(home)) {
            throw new IOException("managed fx runtime escapes the install home: " + versionRoot);
        }
        Path current = home;
        for (Path segment : home.relativize(versionRoot)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("managed fx runtime ancestor is a symlink: " + current);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("managed fx runtime ancestor is not a directory: " + current);
            }
        }
    }

    private static void deleteManagedRuntimeDirectory(
        Path versionRoot,
        Path directory
    ) throws IOException {
        Path confined = directory.toAbsolutePath().normalize();
        if (!confined.startsWith(versionRoot.toAbsolutePath().normalize())) {
            throw new IOException("managed fx runtime deletion escapes its version root: " + directory);
        }
        if (!Files.exists(confined, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(confined)
                || !Files.isDirectory(confined, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("managed fx runtime path is unsafe: " + confined);
        }
        if (!isInstallerOwnedManagedRuntime(confined)) {
            throw new IOException(
                "managed fx runtime path is not an installer-owned payload: " + confined
            );
        }
        final List<Path> paths;
        try (java.util.stream.Stream<Path> stream = Files.walk(confined)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("managed fx runtime contains a symlink: " + path);
            }
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static boolean isInstallerOwnedManagedRuntime(Path directory)
            throws IOException {
        final String executableName = directory.getFileName().toString().startsWith("windows-")
            ? "fx.exe"
            : "fx";
        final Set<String> expected = Set.of(
            executableName,
            "LICENSE",
            "THIRD_PARTY_NOTICES.md",
            "TURBOISM-DISTRIBUTION-NOTICE.txt",
            "manifest.properties"
        );
        final Set<String> actual = new HashSet<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                actual.add(entry.getFileName().toString());
            }
        }
        if (!actual.equals(expected)) {
            return false;
        }
        final ManagedFxIdentity identity = MANAGED_FX_IDENTITIES.get(
            directory.getFileName().toString()
        );
        if (identity == null || !matchesIdentity(
            directory.resolve(executableName),
            new FileIdentity(identity.size(), identity.sha256())
        )) {
            return false;
        }
        for (Map.Entry<String, FileIdentity> entry : MANAGED_FX_LEGAL_IDENTITIES.entrySet()) {
            if (!matchesIdentity(directory.resolve(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesIdentity(
        final Path path,
        final FileIdentity identity
    ) throws IOException {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            && !Files.isSymbolicLink(path)
            && Files.size(path) == identity.size()
            && identity.sha256().equals(sha256(path));
    }

    private static Properties loadManagedFxManifest() {
        final Properties manifest = new Properties();
        try (InputStream input = TurboismInstallerListener.class.getResourceAsStream(
            "/turboism/fx-runtime/manifest.properties"
        )) {
            if (input == null) {
                throw new IllegalStateException("managed fx manifest resource is missing");
            }
            manifest.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("managed fx manifest resource could not be loaded", failure);
        }
        if (!MANAGED_FX_VERSION.equals(manifest.getProperty("fxVersion"))) {
            throw new IllegalStateException("managed fx manifest version is unsupported");
        }
        return manifest;
    }

    private static Map<String, FileIdentity> loadManagedFxLegalIdentities(
        final Properties manifest
    ) {
        return Map.of(
            "LICENSE",
            matchingResourceIdentity(
                manifest,
                "licenseSize",
                "licenseSha256",
                "/turboism/fx-runtime/LICENSE",
                "managed fx license"
            ),
            "THIRD_PARTY_NOTICES.md",
            matchingResourceIdentity(
                manifest,
                "thirdPartyNoticesSize",
                "thirdPartyNoticesSha256",
                "/turboism/fx-runtime/THIRD_PARTY_NOTICES.md",
                "managed fx third-party notices"
            ),
            DISTRIBUTION_NOTICE,
            resourceIdentity("/turboism/fx-runtime/" + DISTRIBUTION_NOTICE),
            "manifest.properties",
            resourceIdentity("/turboism/fx-runtime/manifest.properties")
        );
    }

    private static Map<String, ManagedFxIdentity> loadManagedFxIdentities(
        final Properties manifest
    ) {
        final Map<String, ManagedFxIdentity> identities = new java.util.LinkedHashMap<>();
        final String platforms = manifest.getProperty("platforms", "");
        for (String platform : platforms.split(",")) {
            final String id = platform.strip();
            if (id.isEmpty()) continue;
            final FileIdentity executable = identity(
                manifest,
                id + ".executableSize",
                id + ".executableSha256",
                "managed fx executable"
            );
            if (identities.put(
                id,
                new ManagedFxIdentity(executable.size(), executable.sha256())
            ) != null) {
                throw new IllegalStateException("managed fx platform identity is invalid");
            }
        }
        if (!identities.keySet().equals(Set.of(
            "linux-x86_64", "linux-aarch64", "macos-x86_64", "macos-aarch64",
            "windows-x86_64"
        ))) {
            throw new IllegalStateException("managed fx platform set is unsupported");
        }
        return Map.copyOf(identities);
    }

    private static FileIdentity matchingResourceIdentity(
        final Properties manifest,
        final String sizeKey,
        final String sha256Key,
        final String resource,
        final String label
    ) {
        final FileIdentity declared = identity(manifest, sizeKey, sha256Key, label);
        final FileIdentity packaged = resourceIdentity(resource);
        if (!declared.equals(packaged)) {
            throw new IllegalStateException(label + " resource identity does not match manifest");
        }
        return declared;
    }

    private static FileIdentity identity(
        final Properties manifest,
        final String sizeKey,
        final String sha256Key,
        final String label
    ) {
        final String sha256 = manifest.getProperty(sha256Key);
        final long size;
        try {
            size = Long.parseLong(manifest.getProperty(sizeKey));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(label + " size is invalid", failure);
        }
        if (size <= 0L || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(label + " identity is invalid");
        }
        return new FileIdentity(size, sha256);
    }

    private static FileIdentity resourceIdentity(final String name) {
        try (InputStream input = TurboismInstallerListener.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("managed fx resource is missing: " + name);
            }
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1024];
            long size = 0L;
            while (true) {
                final int read = input.read(buffer);
                if (read < 0) break;
                size = Math.addExact(size, read);
                digest.update(buffer, 0, read);
            }
            return new FileIdentity(size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException failure) {
            throw new IllegalStateException(
                "managed fx resource identity could not be loaded: " + name,
                failure
            );
        }
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] buffer = new byte[64 * 1024];
            while (true) {
                final int read = input.read(buffer);
                if (read < 0) break;
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record FileIdentity(long size, String sha256) { }

    private record ManagedFxIdentity(long size, String sha256) { }

    static String defaultInstallPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null || localAppData.isEmpty()) {
                localAppData = userHome;
            }
            return new File(localAppData, "Turboism").getPath();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new File(new File(userHome, "Library"), "Application Support" + File.separator + "Turboism").getPath();
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome == null || xdgDataHome.isEmpty()) {
            xdgDataHome = new File(userHome, ".local" + File.separator + "share").getPath();
        }
        return new File(xdgDataHome, "Turboism").getPath();
    }

}
