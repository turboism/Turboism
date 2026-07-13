package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Streams declared plugin artifacts from a strict outer archive into private snapshots. */
final class PluginArtifactInspector {
    private final StrictZipArchive archive;

    PluginArtifactInspector(StrictZipArchive archive) { this.archive = archive; }

    Inspected inspect(JsonNode manifest) throws Exception {
        List<PlannedFile> files = new ArrayList<>();
        Set<String> declaredFiles = new HashSet<>();
        PluginJarInspector jars = new PluginJarInspector();
        PluginJarInspector.Inspected plugin = null;
        JsonNode declarations = manifest.path("files");
        for (int index = 0; index < declarations.size(); index++) {
            JsonNode declaration = declarations.get(index);
            String path = declaration.path("path").textValue();
            StrictZipArchive.Entry entry = archive.entry(path);
            require(entry != null && !entry.directory(), "ARTIFACT_MISSING", field(index, "path"));
            Path snapshot = privateSnapshot();
            try {
                Observation observed = copy(entry, snapshot);
                verifyDeclaration(declaration, observed, index);
                String role = declaration.path("role").textValue();
                files.add(new PlannedFile(role, path, path, observed.sha256(), observed.size()));
                plugin = inspectJar(jars, snapshot, path, role, plugin);
            } finally {
                Files.deleteIfExists(snapshot);
            }
            declaredFiles.add(path);
        }
        exactInventory(declaredFiles);
        require(plugin != null, "ARTIFACT_ROLES_INVALID", "files");
        return new Inspected(List.copyOf(files), plugin);
    }

    private Observation copy(StrictZipArchive.Entry entry, Path snapshot) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (OutputStream file = Files.newOutputStream(snapshot);
             OutputStream output = new DigestingOutputStream(file, digest)) {
            StrictZipArchive.Observation observed = archive.consume(entry, output);
            return new Observation(observed.size(), HexFormat.of().formatHex(digest.digest()));
        }
    }

    private static PluginJarInspector.Inspected inspectJar(PluginJarInspector jars, Path snapshot,
            String path, String role, PluginJarInspector.Inspected existing) throws Exception {
        if ("PLUGIN_JAR".equals(role)) {
            require(existing == null, "ARTIFACT_ROLES_INVALID", "files");
            return jars.inspect(snapshot, path);
        }
        jars.inspectLibrary(snapshot, path);
        return existing;
    }

    private static void verifyDeclaration(JsonNode node, Observation observed, int index) throws Exception {
        require(node.path("sha256").textValue().equals(observed.sha256()),
            "ARTIFACT_HASH_MISMATCH", field(index, "sha256"));
        require(node.path("size").longValue() == observed.size(),
            "ARTIFACT_SIZE_MISMATCH", field(index, "size"));
    }

    private void exactInventory(Set<String> declaredFiles) throws Exception {
        Set<String> allowedDirectories = new HashSet<>();
        allowedDirectories.add("META-INF/");
        allowedDirectories.add("META-INF/turboism/");
        for (String path : declaredFiles) addParents(path, allowedDirectories);
        for (StrictZipArchive.Entry entry : archive.entries()) {
            boolean allowedFile = entry.name().equals(PluginManifestReader.NAME)
                || declaredFiles.contains(entry.name());
            boolean allowedDirectory = entry.directory() && allowedDirectories.contains(entry.name());
            require(allowedFile || allowedDirectory, "ARCHIVE_UNDECLARED_FILE", entry.name());
        }
    }

    private static void addParents(String path, Set<String> parents) {
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            parents.add(path.substring(0, slash + 1));
        }
    }

    private static Path privateSnapshot() throws IOException {
        try {
            return Files.createTempFile("turboism-plugin-artifact-", ".jar",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile("turboism-plugin-artifact-", ".jar");
        }
    }

    private static String field(int index, String name) { return "files[" + index + "]." + name; }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem(code, "Invalid plugin artifact", path);
    }

    record Inspected(List<PlannedFile> files, PluginJarInspector.Inspected plugin) {}
    private record Observation(long size, String sha256) {}

    private static final class DigestingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final MessageDigest digest;

        private DigestingOutputStream(OutputStream delegate, MessageDigest digest) {
            this.delegate = delegate;
            this.digest = digest;
        }

        @Override public void write(int value) throws IOException {
            delegate.write(value);
            digest.update((byte) value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            digest.update(bytes, offset, length);
        }

        @Override public void close() throws IOException { delegate.close(); }
    }
}
