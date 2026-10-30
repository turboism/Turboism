package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Untrusted local task records. All authority must be supplied again by the caller on recovery. */
final class FrameworkUpdateTaskStore {
    static final int MAX_BYTES = 128 * 1024;
    static final String FORMAT = "turboism-framework-task-v1";
    final String id;
    private final String binding;
    private final UpdateIncrementalDiscovery descriptor;
    private Map<String, Long> objects = Map.of();

    FrameworkUpdateTaskStore(
            final UpdateIncrementalDiscovery descriptor,
            final UpdateVerificationContext context,
            final Path installation) {
        this.descriptor = descriptor;
        final ObjectNode input = UpdateFileSupport.JSON.createObjectNode();
        input.put("root", hash(installation.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        final InstalledBuild old = context.installedBuild();
        final ObjectNode installed = input.putObject("installed");
        installed.put("version", old.version().map(Object::toString).orElse("unknown"));
        if (old.buildNumber().isPresent())
            installed.put("buildNumber", old.buildNumber().getAsLong());
        else installed.putNull("buildNumber");
        installed.put("display", old.display()).put("channel", old.channel()).put("variant", old.variant());
        if (!context.receiptBinding().isEmpty()) input.put("receiptBinding", context.receiptBinding());
        final ObjectNode evidence = input.putObject("evidence");
        new TreeMap<>(context.installedFrameworkHashes()).forEach(evidence::put);
        input.put("updater", context.currentUpdaterVersion()).put("platform", context.supportedPlatform());
        binding = hash(bytes(input));
        id = hash(bytes(base(descriptor, binding)));
    }

    void begin(final FrameworkUpdateCache cache, final boolean recover) throws IOException {
        final Path path = path(cache, id, false);
        if (cache.present(path)) {
            final ObjectNode record = parse(cache, path);
            if (!binding.equals(record.path("binding").asText())
                    || !id.equals(record.path("id").asText())) {
                throw new IOException("Task identity binding changed");
            }
            objects = references(record);
        } else if (cache.files().stream()
                        .filter(p -> p.getFileName().toString().matches("task-[a-f0-9]{64}\\.json"))
                        .count()
                >= 64) {
            throw new IOException("Task count limit reached");
        } else if (recover) {
            throw new IOException("Task record missing; recovery cannot create authority");
        }
        write(cache, "DOWNLOADING"); // Persistently invalidate a previous STAGED before any revalidation.
    }

    void references(final FrameworkUpdateCache cache, final Map<String, Long> closure) throws IOException {
        objects = Map.copyOf(closure);
        write(cache, "DOWNLOADING");
    }

    Path prepare(final FrameworkUpdateCache cache) throws IOException {
        final Path temporary = path(cache, id, true);
        cache.writeForced(temporary, bytes(record("STAGED")));
        return temporary;
    }

    void write(final FrameworkUpdateCache cache, final String state) throws IOException {
        cache.atomicRecord(path(cache, id, true), path(cache, id, false), bytes(record(state)));
    }

    private ObjectNode record(final String state) {
        final ObjectNode record = base(descriptor, binding);
        record.put("id", id).put("state", state);
        final ObjectNode refs = record.putObject("objects");
        new TreeMap<>(objects).forEach(refs::put);
        return record;
    }

    static Path path(final FrameworkUpdateCache cache, final String id, final boolean temporary) {
        if (!sha(id)) throw new IllegalArgumentException("Invalid task ID");
        return cache.ownedPath("task-" + id + (temporary ? ".writing" : ".json"));
    }

    static void discard(final FrameworkUpdateCache cache, final String id) throws IOException {
        final Path target = path(cache, id, false);
        if (!cache.present(target)) throw new IOException("Task not found");
        parse(cache, target); // Cannot use caller-controlled record paths or malformed records for deletion.
        cache.remove(target);
        cache.remove(path(cache, id, true));
        cache.forceDirectory();
    }

    static Set<String> pins(final FrameworkUpdateCache cache) throws IOException {
        final Set<String> pins = new HashSet<>();
        int count = 0;
        for (final Path path : cache.files()) {
            if (!path.getFileName().toString().matches("task-[a-f0-9]{64}\\.json")) continue;
            if (++count > 64) throw new IOException("Too many cached tasks");
            final ObjectNode record = parse(cache, path);
            final UpdateIncrementalDiscovery d = UpdateIncrementalDiscovery.parse(record.get("descriptor"));
            pins.add("document-" + d.manifest().sha256());
            pins.add("document-" + d.envelope().sha256());
            references(record).keySet().forEach(h -> pins.add("object-" + h));
        }
        return pins;
    }

    private static ObjectNode parse(final FrameworkUpdateCache cache, final Path path) throws IOException {
        try {
            final JsonNode parsed =
                    UpdateFileSupport.JSON.readTree(UpdateManifest.strictUtf8(cache.read(path, MAX_BYTES)));
            if (!(parsed instanceof ObjectNode record)
                    || !keys(record, "format", "schemaVersion", "descriptor", "binding", "id", "state", "objects")
                    || !FORMAT.equals(record.path("format").asText())
                    || !record.path("schemaVersion").isIntegralNumber()
                    || !record.path("schemaVersion").canConvertToInt()
                    || record.path("schemaVersion").intValue() != 1
                    || !record.path("binding").isTextual()
                    || !sha(record.path("binding").asText())
                    || !record.path("id").isTextual()
                    || !sha(record.path("id").asText())
                    || !Set.of("DOWNLOADING", "STAGED")
                            .contains(record.path("state").asText())) {
                throw new IOException("Invalid task record");
            }
            final UpdateIncrementalDiscovery descriptor = UpdateIncrementalDiscovery.parse(record.get("descriptor"));
            final String id = hash(bytes(base(descriptor, record.path("binding").asText())));
            if (!id.equals(record.path("id").asText())
                    || !path.getFileName().toString().equals("task-" + id + ".json")) {
                throw new IOException("Task filename/identity mismatch");
            }
            references(record);
            return record;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid task record", failure);
        }
    }

    private static Map<String, Long> references(final ObjectNode record) throws IOException {
        if (!(record.get("objects") instanceof ObjectNode refs) || refs.size() > UpdateManifest.MAX_ENTRIES) {
            throw new IOException("Invalid task object references");
        }
        final Map<String, Long> result = new TreeMap<>();
        final var fields = refs.fields();
        while (fields.hasNext()) {
            final var field = fields.next();
            final JsonNode n = field.getValue();
            if (!sha(field.getKey())
                    || !n.isIntegralNumber()
                    || !n.canConvertToLong()
                    || n.longValue() < 0
                    || n.longValue() > UpdateManifest.MAX_FILE_BYTES) {
                throw new IOException("Unsafe task resource reference");
            }
            result.put(field.getKey(), n.longValue());
        }
        return result;
    }

    private static ObjectNode base(final UpdateIncrementalDiscovery descriptor, final String binding) {
        final ObjectNode base = UpdateFileSupport.JSON.createObjectNode();
        base.put("format", FORMAT).put("schemaVersion", 1).put("binding", binding);
        base.set("descriptor", descriptor.toJson(UpdateFileSupport.JSON));
        return base;
    }

    private static boolean keys(final ObjectNode value, final String... expected) {
        return value.size() == expected.length
                && java.util.Arrays.stream(expected).allMatch(value::hasNonNull);
    }

    private static boolean sha(final String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static byte[] bytes(final ObjectNode node) {
        try {
            final byte[] bytes = UpdateFileSupport.JSON.writeValueAsBytes(node);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Task binding/record too large");
            return bytes;
        } catch (IOException impossible) {
            throw new IllegalArgumentException("Task serialization failed", impossible);
        }
    }

    static String hash(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
