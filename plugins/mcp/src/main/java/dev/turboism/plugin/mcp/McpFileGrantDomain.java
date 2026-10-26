package dev.turboism.plugin.mcp;

import dev.turboism.sdk.ui.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Session capabilities, never paths or serialized native handles. Chooser completion is asynchronous. */
final class McpFileGrantDomain implements AutoCloseable {
    static final String REQUEST = "turboism.files.request_grant";
    static final String STATUS = "turboism.files.grant_status";
    static final String REVOKE = "turboism.files.revoke_grant";
    static final int MAX_REQUESTS = 128;
    static final Duration TTL = Duration.ofMinutes(5);
    private final UserFileAccessService files;
    private final McpExecutionBridge execution;
    private final Clock clock;
    private final Map<Key, Entry> requests = new LinkedHashMap<>();
    // A revoked/expired chooser still counts until native completion: no dialog-spam via cancellation.
    private Entry pendingChooser;
    private ScheduledExecutorService maintenance;
    private boolean closed;

    McpFileGrantDomain(UserFileAccessService files, McpExecutionBridge execution) {
        this(files, execution, Clock.systemUTC());
    }

    McpFileGrantDomain(UserFileAccessService files, McpExecutionBridge execution, Clock clock) {
        this.files = Objects.requireNonNull(files);
        this.execution = Objects.requireNonNull(execution);
        this.clock = Objects.requireNonNull(clock);
    }

    synchronized void startMaintenance() {
        if (closed || maintenance != null) return;
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "turboism-mcp-file-grants");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    McpToolCatalog tools() {
        var tools = new ArrayList<McpRegisteredTool>();
        for (String name : List.of(REQUEST, STATUS, REVOKE))
            tools.add(McpRegisteredTool.typed(
                    McpFileGrantSchemas.definition(name),
                    name.equals(STATUS) ? McpOperationEffect.READ : McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                    McpExecutionAffinity.DIRECT,
                    false,
                    execution,
                    args -> call(name, args)));
        return McpToolCatalog.of(tools);
    }

    private Map<String, Object> call(String name, Map<String, Object> args) {
        try {
            String session = McpRequestRegistry.currentSessionId();
            if (session.isBlank()) return error("SESSION_REQUIRED");
            String operation = text(args.get("operationId"), 128);
            if (name.equals(REQUEST)) return request(new Key(session, operation), args);
            synchronized (this) {
                expire();
                if (closed) return error("UNAVAILABLE");
                Entry entry = requests.get(new Key(session, operation));
                if (entry == null) return error("NOT_FOUND");
                if (name.equals(REVOKE)) retire(entry, "REVOKED");
                return output(entry);
            }
        } catch (IllegalArgumentException failure) {
            return error("INVALID_ARGUMENT");
        } catch (RuntimeException failure) {
            return error("UNAVAILABLE");
        }
    }

    private Map<String, Object> request(Key key, Map<String, Object> args) {
        String purpose = text(args.get("purpose"), 200);
        var mode = UserFileMode.valueOf(text(args.get("mode"), 5));
        if (!(args.get("allowedExtensions") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 8)
            throw new IllegalArgumentException();
        var extensions = new ArrayList<String>();
        for (Object extension : raw) extensions.add(text(extension, 32));
        // SDK validates extension syntax too; do it before reserving an operation or opening UI.
        var request = new UserFileRequest(
                UUID.randomUUID().toString(),
                "MCP file access (" + mode + "): " + purpose,
                extensions,
                mode,
                UserFileLifetime.UNTIL_DISABLE);
        String fingerprint = McpWriteResults.digest(args);
        Entry entry;
        synchronized (this) {
            expire();
            if (closed) return error("UNAVAILABLE");
            var existing = requests.get(key);
            if (existing != null)
                return existing.fingerprint.equals(fingerprint) ? output(existing) : error("OPERATION_ID_CONFLICT");
            if (!files.isAvailable()) return error("UNAVAILABLE");
            if (pendingChooser != null) return error("CHOOSER_BUSY");
            if (requests.size() >= MAX_REQUESTS) return error("RESULT_STORE_FULL");
            entry = new Entry(key, fingerprint, mode, clock.instant().plus(TTL), extensions);
            requests.put(key, entry);
            pendingChooser = entry;
        }
        synchronized (this) {
            // Order chooser admission against revoke/session close. Never cancel its native stage.
            if (closed || !"PENDING".equals(entry.state) || requests.get(key) != entry) {
                if (pendingChooser == entry) pendingChooser = null;
                return output(entry);
            }
            try {
                Objects.requireNonNull(files.request(request))
                        .whenComplete((result, failure) -> complete(entry, result, failure));
            } catch (RuntimeException failure) {
                complete(entry, null, failure);
            }
            expire();
            return output(entry);
        }
    }

    /** Transfers a grant exactly once at the host-admission boundary. The caller must close it.
     * Expiry/revocation cannot undo an already admitted native command; the lease owns cleanup. */
    synchronized Lease consume(String session, String grantId, UserFileMode mode) {
        return consume(session, grantId, mode, null);
    }

    synchronized Lease consume(String session, String grantId, UserFileMode mode, String requiredExtension) {
        expire();
        if (closed || session.isBlank()) throw new IllegalStateException("GRANT_UNAVAILABLE");
        for (Entry entry : requests.values()) {
            if (!entry.key.session.equals(session) || !Objects.equals(entry.grantId, grantId)) continue;
            if (!"GRANTED".equals(entry.state) || entry.handle == null)
                throw new IllegalStateException("GRANT_UNAVAILABLE");
            if (entry.mode != mode) throw new IllegalArgumentException("GRANT_MODE_MISMATCH");
            if (requiredExtension != null && !entry.extensions.equals(Set.of(requiredExtension)))
                throw new IllegalArgumentException("GRANT_EXTENSION_MISMATCH");
            var handle = entry.handle;
            entry.handle = null;
            entry.state = "CONSUMED";
            return new Lease(handle);
        }
        throw new IllegalStateException("GRANT_UNAVAILABLE");
    }

    static final class Lease implements AutoCloseable {
        private UserFileHandle handle;

        Lease(UserFileHandle handle) {
            this.handle = handle;
        }

        UserFileHandle handle() {
            if (handle == null) throw new IllegalStateException("GRANT_UNAVAILABLE");
            return handle;
        }

        @Override
        public void close() {
            var value = handle;
            handle = null;
            release(value);
        }
    }

    private synchronized void complete(Entry entry, UserFileRequestResult result, Throwable failure) {
        if (pendingChooser == entry) pendingChooser = null;
        UserFileHandle handle = result == null ? null : result.handle().orElse(null);
        expire();
        if (closed || !"PENDING".equals(entry.state) || requests.get(entry.key) != entry) {
            release(handle);
            return;
        }
        if (failure != null || result == null) {
            release(handle);
            entry.state = "FAILED";
        } else if (result.status() != UserFileRequestStatus.GRANTED) {
            entry.state = result.status().name();
        } else {
            try {
                if (handle.mode() != entry.mode
                        || handle.lifetime() != UserFileLifetime.UNTIL_DISABLE
                        || handle.state() != UserFileHandleState.ACTIVE) {
                    release(handle);
                    entry.state = "FAILED";
                } else {
                    entry.handle = handle;
                    entry.grantId = UUID.randomUUID().toString();
                    entry.state = "GRANTED";
                }
            } catch (RuntimeException unavailable) {
                release(handle);
                entry.state = "FAILED";
            }
        }
    }

    synchronized void dropSession(String session) {
        requests.entrySet().removeIf(item -> {
            if (!item.getKey().session.equals(session)) return false;
            retire(item.getValue(), "SESSION_CLOSED");
            return true;
        });
    }

    synchronized void expire() {
        var now = clock.instant();
        for (Entry entry : requests.values()) {
            if (!now.isBefore(entry.expiresAt)) retire(entry, "EXPIRED");
            if (entry.handle != null) {
                try {
                    var state = entry.handle.state();
                    if (state != UserFileHandleState.ACTIVE) retire(entry, state.name());
                } catch (RuntimeException unavailable) {
                    retire(entry, "FAILED");
                }
            }
        }
    }

    private void retire(Entry entry, String state) {
        if (!Set.of("PENDING", "GRANTED").contains(entry.state)) return;
        entry.state = state;
        var handle = entry.handle;
        entry.handle = null;
        release(handle);
    }

    private static void release(UserFileHandle handle) {
        if (handle == null) return;
        try {
            handle.revoke();
        } catch (RuntimeException ignored) {
            // Still attempt independent close; never leak native exception text or a path.
        }
        try {
            handle.close();
        } catch (RuntimeException ignored) {
            // MCP capability has already been removed even if native cleanup fails.
        }
    }

    private Map<String, Object> output(Entry entry) {
        var data = new LinkedHashMap<String, Object>();
        data.put("ok", true);
        data.put("operationId", entry.key.operation);
        data.put("state", entry.state);
        data.put("mode", entry.mode.name());
        data.put("expiresAt", entry.expiresAt.toString());
        data.put("grantId", "GRANTED".equals(entry.state) ? entry.grantId : null);
        data.put("nativeChooserPending", pendingChooser == entry);
        data.put("hostCancellationConfirmed", false);
        return McpWriteResults.envelope(data);
    }

    private static Map<String, Object> error(String code) {
        return McpWriteResults.envelope(Map.of("ok", false, "error", code));
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text)
                || text.isBlank()
                || text.length() > max
                || text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return text;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (maintenance != null) maintenance.shutdownNow();
        for (Entry entry : requests.values()) retire(entry, "SERVER_CLOSED");
        requests.clear();
        // pendingChooser remains referenced until native completion, which must dispose a late grant.
    }

    private record Key(String session, String operation) {}

    private static final class Entry {
        final Key key;
        final String fingerprint;
        final UserFileMode mode;
        final Instant expiresAt;
        final Set<String> extensions;
        String state = "PENDING";
        String grantId;
        UserFileHandle handle;

        Entry(Key key, String fingerprint, UserFileMode mode, Instant expiresAt, List<String> extensions) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.mode = mode;
            this.expiresAt = expiresAt;
            this.extensions = extensions.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }
}
