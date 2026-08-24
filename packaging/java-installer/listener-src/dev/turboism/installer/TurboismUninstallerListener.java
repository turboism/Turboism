package dev.turboism.installer;

import com.izforge.izpack.api.event.AbstractUninstallerListener;
import com.izforge.izpack.api.event.ProgressListener;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Uninstall-side listener for the Turboism Java installer (frozen spec
 * "Uninstall" contract).
 *
 * Policy:
 *  - config.json is removed only when the user (or the
 *    {@code turboism.uninstall.deleteConfig} system property, used by
 *    non-interactive verification) selects deletion; the interactive
 *    confirmation defaults to delete and is localized en/zh/ja; closing the
 *    confirmation without choosing preserves config.json;
 *  - installed agent, installer-owned plugin JARs, installer-owned
 *    launch/configuration files and the generated uninstaller are removed by
 *    the IzPack uninstaller itself (they are all installer-owned pack files);
 *  - runtime-owned logs/state/cache directories are removed;
 *  - unknown files and third-party plugin JARs are preserved; the plugins
 *    directory and the home directory are removed only when empty.
 *
 * Custom cleanup runs only when the install home is proven: the uninstaller
 * jar path must normalize to the exact {@code <home>/Uninstaller/uninstaller.jar}
 * layout (from {@code self.mod.jar} or an equally strict code-source
 * identity). A missing or malformed identity performs no custom deletion and
 * never falls back to {@code user.dir} or any other arbitrary path.
 */
public final class TurboismUninstallerListener extends AbstractUninstallerListener {

    static final String DELETE_CONFIG_PROPERTY = "turboism.uninstall.deleteConfig";
    static final String INSTALLATION_STATE_FILE = "cubism-installations.json";
    private static final String[] RUNTIME_DIRS = {"logs", "state", "cache"};

    /** Proven install home, or {@code null} when the identity is missing/malformed. */
    private final Path home;
    private final boolean deleteConfig;

    public TurboismUninstallerListener() {
        this.home = findHome();
        this.deleteConfig = resolveDeleteConfig();
    }

    @Override
    public void afterDelete(List<File> files, ProgressListener listener) {
        if (home == null) {
            // Identity did not prove the <home>/Uninstaller/uninstaller.jar
            // layout: no custom deletion of config, runtime dirs or home.
            return;
        }
        if (isWindows()) {
            Path shortcutDirectory = currentUserShortcutDirectory();
            if (shortcutDirectory == null
                    || !cleanupManagedState(home, shortcutDirectory)) {
                // A takeover conflict, malformed state, or unavailable Windows
                // known-folder identity is a hard stop: keep recovery evidence
                // and leave the home for a later retry.
                return;
            }
        }
        if (deleteConfig) {
            deleteQuietly(home.resolve(ConfigMerge.CONFIG_FILE));
        }
        for (String name : RUNTIME_DIRS) {
            deleteRecursivelyQuietly(home.resolve(name));
        }
        if (isWindows()) {
            deleteRecursivelyQuietly(home.resolve("graal"));
        }
        removeIfEmpty(home.resolve(ConfigMerge.PLUGIN_DIR));
        removeIfEmpty(home);
    }

    /**
     * The install home: parent of the Uninstaller directory containing the
     * uninstaller jar. The IzPack SelfModifier runs the uninstaller from a
     * sandbox directory, so the jar's own code source is not reliable; the
     * {@code self.mod.jar} system property (set by SelfModifier) carries the
     * original jar path.
     *
     * @return the proven home directory, or {@code null} when no identity
     *         matches the exact normalized {@code <home>/Uninstaller/uninstaller.jar}
     *         layout. There is deliberately no fallback path.
     */
    static Path findHome() {
        String selfModJar = System.getProperty("self.mod.jar");
        if (selfModJar != null && !selfModJar.isEmpty()) {
            Path home = homeFromUninstallerJar(Paths.get(selfModJar));
            if (home != null) {
                return home;
            }
        }
        try {
            java.net.URL location = TurboismUninstallerListener.class
                    .getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                return homeFromUninstallerJar(Paths.get(location.toURI()));
            }
        } catch (Exception ignored) {
            // fall through: no identity, no custom deletion
        }
        return null;
    }

    /**
     * Strict identity check: the normalized jar path must end with exactly
     * {@code Uninstaller/uninstaller.jar}, must be a regular non-symlink file
     * in a real non-symlink Uninstaller directory, and must bind to the
     * generated IzPack {@code install.log} embedded in that same jar: the
     * log's recorded install home must equal the derived home after
     * normalized absolute comparison. Any other layout (renamed copy, wrong
     * directory, relocated matching-shape copy, arbitrary working directory),
     * any missing/malformed/mismatched log, and any symlinked path returns
     * {@code null} and no custom deletion happens.
     */
    private static Path homeFromUninstallerJar(Path jar) {
        try {
            Path normalized = jar.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent == null || parent.getFileName() == null) {
                return null;
            }
            if (!parent.getFileName().toString().equalsIgnoreCase("Uninstaller")) {
                return null;
            }
            if (!normalized.getFileName().toString().equalsIgnoreCase("uninstaller.jar")) {
                return null;
            }
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path home = parent.getParent();
            if (home == null) {
                return null;
            }
            String recorded = recordedInstallHome(normalized);
            if (recorded == null) {
                return null;
            }
            Path recordedPath;
            try {
                recordedPath = Paths.get(recorded);
            } catch (RuntimeException e) {
                return null;
            }
            if (!recordedPath.toAbsolutePath().normalize().equals(home.toAbsolutePath().normalize())) {
                return null;
            }
            return home;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads the generated IzPack install.log embedded in the uninstaller jar:
     * its first line records the install home chosen at install time (the
     * following lines are the absolute installed-file list). A missing entry,
     * an unreadable/corrupt jar, or a missing/empty first line returns
     * {@code null}.
     */
    private static String recordedInstallHome(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            ZipEntry entry = jf.getEntry("install.log");
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (java.io.InputStream in = jf.getInputStream(entry)) {
                byte[] data = in.readAllBytes();
                int end = 0;
                while (end < data.length && data[end] != '\n' && data[end] != '\r') {
                    end++;
                }
                if (end == 0) {
                    return null;
                }
                String first = new String(data, 0, end, StandardCharsets.UTF_8).trim();
                return first.isEmpty() ? null : first;
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Deletes the bounded Windows managed-launch state and only the shortcut
     * paths that the valid state proves to be owned. This overload is package
     * private so the regression harness can use a temporary current-user
     * Start Menu directory without touching the real user profile.
     */
    static boolean cleanupManagedState(Path home, Path shortcutDirectory) {
        return ConfigMerge.cleanupManagedState(home, shortcutDirectory);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Resolves the same current-user Programs known folder that the production
     * PowerShell configurator uses. Reconstructing it from APPDATA is incorrect
     * when Windows Folder Redirection or another known-folder policy applies.
     * No fallback path is trusted: inability to query the known folder makes
     * managed cleanup fail closed and preserves its recovery state.
     */
    private static Path currentUserShortcutDirectory() {
        Process process = null;
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                return null;
            }
            Path powershell = Paths.get(systemRoot, "System32", "WindowsPowerShell",
                    "v1.0", "powershell.exe").toAbsolutePath().normalize();
            if (Files.isSymbolicLink(powershell)
                    || !Files.isRegularFile(powershell, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            ProcessBuilder builder = new ProcessBuilder(
                    powershell.toString(), "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
                            + "[Environment]::GetFolderPath('Programs')");
            builder.redirectErrorStream(true);
            process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] output;
            try (java.io.InputStream input = process.getInputStream()) {
                output = readProcessOutput(input, 32 * 1024);
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return WindowsProgramsPath.parse(
                    new String(output, StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    private static byte[] readProcessOutput(java.io.InputStream input, int maxBytes)
            throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (output.size() + read > maxBytes) {
                throw new IOException("known-folder output exceeds bound");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /**
     * Deletion decision for config.json: explicit system property for
     * non-interactive runs, otherwise an en/zh/ja GUI confirmation that
     * defaults to delete; closing the dialog without choosing preserves the
     * config; console/headless runs without the property default to delete.
     */
    static boolean resolveDeleteConfig() {
        String value = System.getProperty(DELETE_CONFIG_PROPERTY);
        if (value != null && !value.trim().isEmpty()) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            return v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("delete");
        }
        if (!GraphicsEnvironment.isHeadless() && !isConsoleRun()) {
            String message = "Delete config.json (user configuration)?";
            String title = "Uninstall Turboism";
            String lang = Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT);
            if (lang.startsWith("zh")) {
                message = "是否同时删除 config.json（用户配置）？";
                title = "卸载 Turboism";
            } else if (lang.startsWith("ja")) {
                message = "config.json（ユーザー設定）も削除しますか？";
                title = "Turboism のアンインストール";
            }
            int choice = JOptionPane.showConfirmDialog(null, message, title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            // Yes is the default selection (first button); closing the dialog
            // (CLOSED_OPTION) must preserve config.json.
            return choice == JOptionPane.YES_OPTION;
        }
        return true; // default: delete
    }

    private static boolean isConsoleRun() {
        String command = System.getProperty("sun.java.command", "");
        return command.contains("-console");
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort: locked files survive uninstall and are reported by IzPack
        }
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
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

    private static void removeIfEmpty(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (DirectoryNotEmptyException ignored) {
            // unknown or third-party files are preserved
        } catch (IOException ignored) {
            // best effort
        }
    }
}
