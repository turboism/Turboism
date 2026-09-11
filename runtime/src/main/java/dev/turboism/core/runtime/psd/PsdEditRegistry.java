package dev.turboism.core.runtime.psd;

import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * One plugin instance's identity-based PSD capability registry.
 *
 * <p>Only trusted runtime composition may register handles and staged inputs. SDK implementations,
 * equals/hashCode overrides, or matching text IDs grant no authority. Binding arguments must come
 * from the runtime, not a plugin request. This component checks provenance, not permissions or
 * current host state: the service must recheck both at asynchronous/native admission, and must
 * validate the staged bytes at use time. Revocation here blocks new admission only; the coordinator
 * must separately await in-flight work before reporting STOPPED. No operation deletes a file.</p>
 */
final class PsdEditRegistry implements AutoCloseable {
    static final int MAX_HANDLES = 8;
    static final int MAX_REVISIONS_PER_HANDLE = 2;

    private final IdentityHashMap<PsdEditFile, Entry> entries = new IdentityHashMap<>();
    private boolean closed;

    /** Registers a trusted runtime-created handle after external policy/host validation. */
    synchronized void register(final Binding binding, final PsdEditFile file, final PsdTemporaryFile allocation) {
        requireOpen();
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(allocation, "allocation");
        if (entries.containsKey(file)) throw new IllegalStateException("PSD handle already registered");
        if (entries.size() >= MAX_HANDLES) throw new IllegalStateException("PSD handle quota exceeded");
        entries.put(file, new Entry(binding, allocation));
    }

    /**
     * Issues an opaque token for an already validated, trusted stage inside this handle's allocation.
     * The coordinator explicitly retires a consumed or superseded token before replacing that slot;
     * this registry never guesses which active/pending revision may be discarded.
     */
    synchronized PsdFileRevision issueRevision(
        final Binding binding, final PsdEditFile file, final PsdStableSnapshot.Snapshot snapshot
    ) throws IOException {
        final Entry entry = requireEntry(binding, file);
        Objects.requireNonNull(snapshot, "snapshot");
        final var live = entry.allocation.validatedPath();
        if (snapshot.path() == null || !live.getParent().equals(snapshot.path().getParent())
            || live.equals(snapshot.path()) || !snapshot.path().equals(snapshot.path().toAbsolutePath().normalize())) {
            throw new SecurityException("PSD revision stage is outside this allocation");
        }
        if (snapshot.size() <= 0 || snapshot.size() > PsdStableSnapshot.MAX_BYTES
            || snapshot.sha256() == null || !snapshot.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid PSD stage metadata");
        }
        if (entry.revisions.size() >= MAX_REVISIONS_PER_HANDLE) {
            throw new IllegalStateException("PSD revision quota exceeded");
        }
        final Revision token = new Revision();
        entry.revisions.put(token, snapshot);
        return token;
    }

    /** Resolves only this registry's registered handle without invoking any caller method. */
    synchronized PsdTemporaryFile requireFile(final Binding binding, final PsdEditFile file) {
        return requireEntry(binding, file).allocation;
    }

    /**
     * Resolves an exact token from this handle and binding. The result is not an enduring grant;
     * re-admission is required after any async wait, permission change, stop or model transition.
     */
    synchronized PsdStableSnapshot.Snapshot requireRevision(
        final Binding binding, final PsdEditFile file, final PsdFileRevision revision
    ) {
        final Entry entry = requireEntry(binding, file);
        final PsdStableSnapshot.Snapshot snapshot = entry.revisions.get(Objects.requireNonNull(revision, "revision"));
        if (snapshot == null) throw new SecurityException("PSD revision is foreign, retired or forged");
        return snapshot;
    }

    /** Retires provenance only; keeps the stage file for OS cleanup. */
    synchronized void retireRevision(final Binding binding, final PsdEditFile file, final PsdFileRevision revision) {
        final Entry entry = requireEntry(binding, file);
        if (entry.revisions.remove(Objects.requireNonNull(revision, "revision")) == null) {
            throw new SecurityException("PSD revision is foreign, retired or forged");
        }
    }

    /** Idempotently revokes a handle in this registry without invoking it or deleting its files. */
    synchronized void revoke(final PsdEditFile file) {
        entries.remove(Objects.requireNonNull(file, "file"));
    }

    /** Revokes all handles for the exact document/model binding and generation. */
    synchronized void revokeBinding(final Binding binding) {
        Objects.requireNonNull(binding, "binding");
        entries.values().removeIf(entry -> entry.binding.equals(binding));
    }

    /** Stops future registry admission; does not claim in-flight native work has settled. */
    @Override
    public synchronized void close() {
        closed = true;
        entries.clear();
    }

    private Entry requireEntry(final Binding binding, final PsdEditFile file) {
        requireOpen();
        Objects.requireNonNull(binding, "binding");
        final Entry entry = entries.get(Objects.requireNonNull(file, "file"));
        if (entry == null || !entry.binding.equals(binding)) {
            throw new SecurityException("PSD handle is foreign, revoked or bound to another model session");
        }
        return entry;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("PSD registry is closed");
    }

    /**
     * Runtime's opaque document/model session identity plus its generation. Display names,
     * RawImageId and host generation counters alone are insufficient, and this component never
     * parses the identity: it only compares the exact runtime-issued value, so a document switch
     * that keeps the same model id still yields a different binding.
     */
    record Binding(String sessionIdentity, long generation) {
        Binding {
            Objects.requireNonNull(sessionIdentity, "sessionIdentity");
            if (sessionIdentity.isBlank() || generation < 0) {
                throw new IllegalArgumentException("invalid PSD model session binding");
            }
        }
    }

    private static final class Entry {
        private final Binding binding;
        private final PsdTemporaryFile allocation;
        private final IdentityHashMap<PsdFileRevision, PsdStableSnapshot.Snapshot> revisions = new IdentityHashMap<>();

        private Entry(final Binding binding, final PsdTemporaryFile allocation) {
            this.binding = binding;
            this.allocation = allocation;
        }
    }

    private static final class Revision implements PsdFileRevision { }
}
