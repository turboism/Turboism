package dev.turboism.distribution;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PluginPathPolicy {
    private PluginPathPolicy() {}

    static void validate(String path, boolean directory) throws Exception {
        String value = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        require(!value.isEmpty() && Normalizer.isNormalized(value, Normalizer.Form.NFC), path);
        require(value.getBytes(StandardCharsets.UTF_8).length <= PluginArchiveLimits.PATH_BYTES_MAX, path);
        require(value.split("/", -1).length <= PluginArchiveLimits.PATH_DEPTH_MAX, path);
        require(ManifestPrimitives.relativePath(value), path);
    }

    static void validateCollisions(List<String> paths) throws Exception {
        Set<String> files = new HashSet<>(), directories = new HashSet<>();
        for (String path : paths) {
            boolean directory = path.endsWith("/");
            String value = directory ? path.substring(0, path.length() - 1) : path;
            String key = ManifestPrimitives.pathIdentityKey(value);
            require((directory ? directories : files).add(key), path);
            require(directory ? !files.contains(key) : !directories.contains(key), path);
            String[] segments = value.split("/");
            String prefix = "";
            for (int index = 0; index < segments.length - 1; index++) {
                prefix = prefix.isEmpty() ? segments[index] : prefix + "/" + segments[index];
                require(!files.contains(ManifestPrimitives.pathIdentityKey(prefix)), path);
            }
        }
    }

    static boolean contamination(String name, boolean mainDescriptorAllowed) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("meta-inf/versions/") || lower.endsWith(".jar")) return true;
        if (descriptor(lower)) return !(mainDescriptorAllowed && lower.equals("meta-inf/turboism/plugin.json"));
        if (runtimeNamespace(lower) || internalVariant(lower)) return true;
        if (lower.startsWith("dev/turboism/sdk/") || lower.startsWith("dev/turboism/test/")
            || lower.startsWith("dev/turboism/testframework/") || lower.startsWith("testframework/")) return true;
        if (lower.startsWith("com/live2d/") || hostPrefix(lower) || nativeSuffix(lower)) return true;
        return installer(lower);
    }

    private static boolean descriptor(String path) {
        return path.equals("meta-inf/turboism/plugin.json")
            || path.endsWith("/meta-inf/turboism/plugin.json");
    }

    private static boolean runtimeNamespace(String path) {
        return starts(path, "dev/turboism/core/", "dev/turboism/hook/", "dev/turboism/mapping/",
            "dev/turboism/adapter/", "dev/turboism/permissions/", "dev/turboism/diagnostics/",
            "dev/turboism/bootstrap/", "dev/turboism/ui/", "dev/turboism/internal/");
    }

    private static boolean internalVariant(String path) {
        return path.startsWith("dev/turboism/") && path.contains("/internal/");
    }

    private static boolean hostPrefix(String path) {
        return starts(path, "native/", "natives/", "host/", "cubism/", "live2d/");
    }

    private static boolean nativeSuffix(String path) {
        return ends(path, ".dll", ".so", ".dylib", ".exe", ".msi", ".dmg", ".pkg",
            ".app", ".deb", ".rpm");
    }

    private static boolean installer(String path) {
        String base = path;
        if (base.startsWith("scripts/")) base = base.substring("scripts/".length());
        if (!(base.equals("install") || base.equals("installer") || base.equals("setup")
            || base.startsWith("install.") || base.startsWith("installer.") || base.startsWith("setup."))) return false;
        int dot = base.indexOf('.');
        return dot < 0 || Set.of(".sh", ".bat", ".cmd", ".ps1").contains(base.substring(dot));
    }

    private static boolean starts(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean ends(String value, String... suffixes) {
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }

    private static void require(boolean valid, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem("ARCHIVE_PATH_UNSAFE", "Unsafe plugin archive path", path);
    }
}
