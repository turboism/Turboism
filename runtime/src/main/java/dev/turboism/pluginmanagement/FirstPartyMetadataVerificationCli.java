package dev.turboism.pluginmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * First-party package admission: binds each tracked descriptor to the exact
 * Gradle {@code Jar.archiveFile} supplied on the command line, loads the built
 * JAR through the production {@link PluginJarContract}, and proves that the
 * packaged schema v3 classification equals the reviewed tracked metadata.
 *
 * <p>The gate never scans directories or sorts residual JARs: artifact
 * selection is exclusively the explicit descriptor/JAR pairs provided by the
 * Gradle task.</p>
 */
public final class FirstPartyMetadataVerificationCli {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FirstPartyMetadataVerificationCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || (args.length & 1) != 0) {
            System.err.println("Pass pairs of <tracked-descriptor.json> <built-plugin.jar>.");
            System.exit(2);
            return;
        }
        final List<String> problems = new ArrayList<>();
        for (int index = 0; index < args.length; index += 2) {
            final Path descriptorPath = Path.of(args[index]);
            final Path jarPath = Path.of(args[index + 1]);
            try {
                verify(descriptorPath, jarPath);
                System.out.println("PASS " + jarPath.getFileName() + " (tracked " + descriptorPath.getFileName() + ")");
            } catch (FirstPartyRejection rejection) {
                problems.add(rejection.descriptor() + ": " + rejection.code() + " " + rejection.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.err.println("FAIL " + problem));
            System.exit(1);
        }
        System.out.println("First-party plugin metadata verification passed for " + (args.length / 2) + " package(s).");
    }

    static void verify(final Path descriptorPath, final Path jarPath) throws FirstPartyRejection {
        final PluginDescriptor source;
        try (InputStream tracked = Files.newInputStream(descriptorPath)) {
            source = new PluginDescriptorParser().parse(tracked);
        } catch (IOException | DescriptorParseException failure) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_TRACKED_DESCRIPTOR_INVALID",
                "tracked descriptor is not parseable: " + failure.getMessage());
        }
        if (!PluginCategoryRegistry.isRegistered(source.category().orElse(null))) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_CATEGORY_UNREGISTERED",
                "tracked category '" + source.category().orElse("<none>")
                    + "' is not in the official registry");
        }
        if (schemaVersion(descriptorPath) != 3) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_SCHEMA_NOT_V3",
                "tracked descriptor must declare schemaVersion 3");
        }
        final PluginDescriptor embedded;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            final JarEntry entry = jar.getJarEntry("META-INF/turboism/plugin.json");
            if (entry == null || entry.isDirectory()) {
                throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_JAR_MISSING_DESCRIPTOR",
                    "built JAR has no embedded descriptor");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                embedded = new PluginDescriptorParser().parse(input);
            } catch (DescriptorParseException failure) {
                throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_EMBEDDED_DESCRIPTOR_INVALID",
                    "embedded descriptor rejected: " + failure.code() + " " + failure.getMessage());
            }
            try {
                PluginJarContract.validate(embedded, entryNames(jar), jarPath.getFileName().toString());
            } catch (PluginJarContract.PluginJarContractException failure) {
                throw new FirstPartyRejection(descriptorPath, "PLUGIN_JAR_CONTRACT_" + failure.code(),
                    failure.getMessage() + " at " + failure.path());
            }
        } catch (IOException failure) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_JAR_UNREADABLE",
                "built JAR cannot be inspected: " + failure.getMessage());
        }
        if (!source.category().equals(embedded.category()) || !source.tags().equals(embedded.tags())) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_CLASSIFICATION_MISMATCH",
                "packaged category/tags differ from tracked metadata");
        }
    }

    private static List<String> entryNames(final JarFile jar) {
        final List<String> names = new ArrayList<>();
        final Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            final JarEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static int schemaVersion(final Path descriptorPath) throws FirstPartyRejection {
        try {
            final JsonNode root = JSON.readTree(Files.readAllBytes(descriptorPath));
            return root.path("schemaVersion").asInt(-1);
        } catch (IOException failure) {
            throw new FirstPartyRejection(descriptorPath, "FIRST_PARTY_TRACKED_DESCRIPTOR_INVALID",
                "tracked descriptor is not readable: " + failure.getMessage());
        }
    }

    static final class FirstPartyRejection extends Exception {
        private final Path descriptor;
        private final String code;

        FirstPartyRejection(final Path descriptor, final String code, final String message) {
            super(code + ": " + message);
            this.descriptor = descriptor;
            this.code = code;
        }

        Path descriptor() {
            return descriptor;
        }

        String code() {
            return code;
        }
    }
}
