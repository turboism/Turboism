package dev.turboism.validation.separatesavepath;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 离线执行探针断言；不调用 init，不启动宿主或后台线程。 */
public final class SeparateSavePathProbeTest {
    private static final Path PROJECT = Path.of("separate-save-path/project-saves");
    private static final Path EXPORT = Path.of("separate-save-path/export-saves");
    private static String seededHistory;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        seededHistory = Files.readString(Path.of(args[1]));
        check(run(root.resolve("readonly"), new History("readonly"), "seeded"), true, "只读服务与预置持久化");
        for (String mode : List.of("writable", "noop", "project-writable", "export-writable", "provider-writable",
                "unavailable", "wrong-project", "wrong-export", "wrong-flag", "throws-read")) {
            check(run(root.resolve(mode), new History(mode), "seeded"), false, mode);
        }
        check(run(root.resolve("missing"), new History("readonly"), "missing"), false, "缺少持久化文件");
        check(run(root.resolve("wrong-store"), new History("readonly"), "wrong-store"), false, "持久化与读取不一致");
        check(run(root.resolve("global"), new History("readonly"), "global"), false, "旧全局配置未迁移");
        System.out.println("PASS: separate-save-path probe offline contract (14 cases)");
    }

    private static List<String> run(Path home, History service, String fixture) throws Exception {
        Path state = Files.createDirectories(home.resolve("state/dev.turboism.validation.separatesavepath"));
        Path config = Files.createDirectories(home.resolve("config/turboism.core"));
        if (!fixture.equals("missing")) {
            Files.writeString(config.resolve("save-directory-history.properties"), fixture.equals("wrong-store")
                    ? seededHistory.replace(PROJECT.toString(), "wrong") : seededHistory);
        }
        if (fixture.equals("global")) Files.writeString(home.resolve("config.json"), "{\"fileChooserHistory\":{}}");
        PluginServiceDirectory directory = (PluginServiceDirectory) Proxy.newProxyInstance(
                PluginServiceDirectory.class.getClassLoader(), new Class<?>[]{PluginServiceDirectory.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("find")) return Optional.of(service);
                    throw new AssertionError(method.getName());
                });
        PluginContext context = (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(), new Class<?>[]{PluginContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("services")) return directory;
                    throw new AssertionError(method.getName());
                });
        PluginLogger logger = (PluginLogger) Proxy.newProxyInstance(
                PluginLogger.class.getClassLoader(), new Class<?>[]{PluginLogger.class}, (proxy, method, args) -> null);
        SeparateSavePathHostValidationPlugin probe = new SeparateSavePathHostValidationPlugin();
        for (var entry : java.util.Map.of("context", context, "logger", logger, "stateDir", state).entrySet()) {
            var field = probe.getClass().getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(probe, entry.getValue());
        }
        var assertion = probe.getClass().getDeclaredMethod("runAssertions", List.class);
        assertion.setAccessible(true);
        List<String> failures = new ArrayList<>();
        assertion.invoke(probe, failures);
        return failures;
    }

    private static void check(List<String> failures, boolean expectedPass, String label) {
        if (failures.isEmpty() != expectedPass) throw new AssertionError(label + ": " + failures);
    }

    private static final class History implements FileChooserHistoryService {
        private final String mode;
        private Path project = PROJECT;
        private Path export = EXPORT;
        History(String mode) { this.mode = mode; }
        public boolean isAvailable() { return !mode.equals("unavailable"); }
        public Optional<Path> projectRecentDirectory() {
            if (mode.equals("throws-read")) throw new IllegalStateException("read failure");
            return Optional.of(mode.equals("wrong-project") ? Path.of("wrong") : project);
        }
        public Optional<Path> exportRecentDirectory() {
            return Optional.of(mode.equals("wrong-export") ? Path.of("wrong") : export);
        }
        public boolean exportSeparationEnabled() { return mode.equals("wrong-flag"); }
        public void setProjectRecentDirectory(Path value) {
            if (mode.equals("noop")) return;
            if (mode.equals("writable") || mode.equals("project-writable")) { project = value; return; }
            throw new UnsupportedOperationException("readonly");
        }
        public void setExportRecentDirectory(Path value) {
            if (mode.equals("noop")) return;
            if (mode.equals("writable") || mode.equals("export-writable")) { export = value; return; }
            throw new UnsupportedOperationException("readonly");
        }
        public Registration registerProvider(Provider provider) {
            if (mode.equals("provider-writable")) return () -> {};
            throw new UnsupportedOperationException("readonly");
        }
    }
}
