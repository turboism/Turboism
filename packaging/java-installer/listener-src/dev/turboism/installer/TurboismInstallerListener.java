package dev.turboism.installer;

import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.data.Pack;
import com.izforge.izpack.api.event.AbstractInstallerListener;
import com.izforge.izpack.api.event.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * Install-side listener for the Turboism Java installer.
 *
 * Responsibilities (frozen spec):
 *  - set the per-OS default install path before the target-directory panel;
 *  - handle config.json before any payload file is copied: seed from the
 *    canonical template on fresh targets, update only disabledPlugins on a
 *    valid current document, or atomically migrate the explicit legacy schema
 *    before applying selection; fail closed before payload mutation on invalid,
 *    future, oversized, symlinked, or escaping config targets;
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
        final boolean current = seed != null && ConfigMerge.schemaVersion(seed) == 1L;
        if (seed == null) {
            seed = ConfigMerge.loadTemplate();
        } else if (!current) {
            seed = ConfigMerge.migrateToCurrent(seed);
        }

        List<String> disabled = ConfigMerge.mergeDisabled(seed, bundled, selected, lite);
        disabled = closeRequiredPluginDependencies(disabled, bundled);
        final boolean selectionChanged = !ConfigMerge.disabledSelectionMatches(seed, disabled);
        final Map<String, Object> updated = ConfigMerge.applyPolicy(seed, disabled);
        ConfigMerge.validateCurrent(updated);
        if (!current || selectionChanged) {
            ConfigMerge.write(home, updated);
        }

        // Config validation and any required atomic publication complete before managed files are
        // retired. An invalid/future config therefore cannot mutate the installation tree.
        ConfigMerge.retireManagedPlugins(home);
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
        final List<String> disabled,
        final Set<String> bundled
    ) {
        return List.copyOf(disabled);
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
