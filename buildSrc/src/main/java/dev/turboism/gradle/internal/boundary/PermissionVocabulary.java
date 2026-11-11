package dev.turboism.gradle.internal.boundary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The permission id vocabulary the audit and the SDK catalog share, read from one resource.
 *
 * <p>{@code :sdk} ships {@code permission-ids.txt} as the canonical enumeration and
 * {@code dev.turboism.sdk.permission.PermissionIds} is a typed view of it. The audit cannot depend
 * on the SDK jar at configuration time, so it reads the same tracked file from the source tree.
 * That keeps one enumeration: a rule that names an id the SDK does not export, or an SDK id the
 * audit never learned about, is a single-file mistake instead of a silent divergence.
 *
 * <p>Reading fails closed. A missing, empty or duplicated entry raises {@link IllegalStateException}
 * rather than yielding a partial vocabulary that would let the gate pass with fewer ids than the
 * framework ships.
 */
public final class PermissionVocabulary {

    /** Repository-relative location of the canonical vocabulary, shared with {@code :sdk}. */
    public static final String RESOURCE_PATH = "sdk/src/main/resources/dev/turboism/sdk/permission/permission-ids.txt";

    private static final String COMMENT_PREFIX = "#";

    private PermissionVocabulary() {}

    /**
     * Reads the canonical vocabulary from the repository root.
     *
     * @param repositoryRoot the repository root that holds {@link #RESOURCE_PATH}
     * @return the ids in file order
     * @throws IllegalStateException when the file is absent, unreadable, empty or duplicated
     */
    public static List<String> read(final Path repositoryRoot) {
        final Path resource = repositoryRoot.resolve(RESOURCE_PATH);
        if (!Files.isRegularFile(resource)) {
            throw new IllegalStateException("Permission vocabulary is missing: " + resource);
        }
        try (InputStream stream = Files.newInputStream(resource)) {
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read permission vocabulary " + resource, failure);
        }
    }

    /**
     * Parses one id per line, ignoring blank lines and {@code #} comments.
     *
     * @param text the vocabulary content
     * @return the ids in file order
     * @throws IllegalStateException when the vocabulary is empty or repeats an id
     */
    static List<String> parse(final String text) {
        final List<String> ids = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            final String id = line.trim();
            if (id.isEmpty() || id.startsWith(COMMENT_PREFIX)) {
                continue;
            }
            if (!seen.add(id)) {
                throw new IllegalStateException("Permission vocabulary repeats id: " + id);
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("Permission vocabulary declares no id: " + RESOURCE_PATH);
        }
        return List.copyOf(ids);
    }
}
