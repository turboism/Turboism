package dev.turboism.distribution;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class NestedJarInspector {
    private static final Limits DEFAULT_LIMITS = new Limits(
        32L * 1024 * 1024, 64L * 1024 * 1024, 256, 200.0);
    private final Limits limits;

    NestedJarInspector() {
        this(DEFAULT_LIMITS);
    }

    NestedJarInspector(Limits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    void inspect(String role, byte[] bytes, String path) throws DistributionValidationException {
        List<String> centralNames = NestedZipDirectory.parse(bytes, path);
        Observed observed = stream(role, bytes, path);
        if (!centralNames.equals(observed.names())) {
            fail(DistributionErrors.JAR_INVALID, "Artifact is not a valid JAR", path);
        }
        if (!observed.required()) {
            fail("ARTIFACT_REQUIRED_CLASS_MISSING", "Artifact lacks required framework classes", path);
        }
    }

    private Observed stream(String role, byte[] bytes, String path)
        throws DistributionValidationException {
        List<String> names = new ArrayList<>();
        Set<String> foldedNames = new HashSet<>();
        boolean required = false;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                validateName(name, foldedNames, path);
                names.add(name);
                count(names.size(), path);
                long actual = consume(zip, path);
                total = total(total, actual, path);
                ratio(entry, actual, path);
                required |= requiredClass(role, name);
                contamination(role, name, path);
            }
        } catch (DistributionValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            fail(DistributionErrors.JAR_INVALID, "Artifact is not a valid JAR", path);
        }
        return new Observed(List.copyOf(names), required);
    }

    private long consume(ZipInputStream zip, String path)
        throws IOException, DistributionValidationException {
        byte[] buffer = new byte[8192];
        long size = 0;
        for (int read; (read = zip.read(buffer)) >= 0;) {
            size += read;
            if (size > limits.entryMax()) {
                fail("NESTED_ENTRY_TOO_LARGE", "Nested entry exceeds limit", path);
            }
        }
        return size;
    }

    private long total(long previous, long actual, String path)
        throws DistributionValidationException {
        if (actual > limits.totalMax() - previous) {
            fail("NESTED_TOTAL_TOO_LARGE", "Nested expanded total exceeds limit", path);
        }
        return previous + actual;
    }

    private void count(int count, String path) throws DistributionValidationException {
        if (count > limits.countMax()) {
            fail("NESTED_ENTRY_LIMIT", "Too many nested entries", path);
        }
    }

    private void ratio(ZipEntry entry, long actual, String path)
        throws DistributionValidationException {
        long compressed = entry.getCompressedSize();
        boolean excessive = actual > 0 && compressed == 0;
        excessive |= compressed > 0 && (double) actual / compressed > limits.ratioMax();
        if (excessive) {
            fail("NESTED_COMPRESSION_RATIO", "Nested compression ratio exceeds limit", path);
        }
    }

    private static void validateName(String name, Set<String> names, String path)
        throws DistributionValidationException {
        ArchivePolicy.safeRelative(name, "NESTED_PATH_UNSAFE", path);
        String folded = name.toLowerCase(Locale.ROOT);
        if (!names.add(folded)) fail("NESTED_PATH_COLLISION", "Nested path collision", path);
        if (folded.startsWith("meta-inf/versions/")) {
            fail("MULTI_RELEASE_JAR_UNSUPPORTED", "Multi-release JAR content is forbidden", path);
        }
    }

    private static boolean requiredClass(String role, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".class")) return false;
        return role.equals("runtime") ? lower.startsWith("dev/turboism/")
            && !lower.startsWith("dev/turboism/sdk/") : lower.startsWith("dev/turboism/sdk/");
    }

    private static void contamination(String role, String name, String path)
        throws DistributionValidationException {
        if (forbidden(role, name)) {
            fail("FRAMEWORK_CONTENT_CONTAMINATION",
                "Forbidden framework artifact content: " + name, path);
        }
    }

    private static boolean forbidden(String role, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (binary(lower)) return true;
        String file = lower.substring(lower.lastIndexOf('/') + 1);
        boolean test = file.matches(".*(?:test|tests|testcase)(?:\\$.*)?\\.class");
        if (lower.equals("meta-inf/turboism/plugin.json") || lower.contains("/test/") || test
            || lower.contains("live2d") || lower.contains("cubism")) return true;
        if (lower.startsWith("dev/turboism/plugin/")) return true;
        if (role.equals("runtime")) return lower.startsWith("dev/turboism/sdk/");
        return lower.startsWith("dev/turboism/core/")
            || lower.startsWith("dev/turboism/adapter/")
            || lower.startsWith("dev/turboism/hook/")
            || lower.startsWith("dev/turboism/mapping/");
    }

    private static boolean binary(String name) {
        return name.endsWith(".jar") || name.endsWith(".dll")
            || name.endsWith(".so") || name.endsWith(".dylib");
    }

    private static void fail(String code, String message, String path)
        throws DistributionValidationException {
        throw ArchivePolicy.problem(code, message, path);
    }

    record Limits(long entryMax, long totalMax, int countMax, double ratioMax) {
        Limits {
            if (entryMax < 0 || totalMax < 0 || countMax < 0 || ratioMax <= 0) {
                throw new IllegalArgumentException("Invalid nested JAR limits");
            }
        }
    }

    private record Observed(List<String> names, boolean required) {}
}
