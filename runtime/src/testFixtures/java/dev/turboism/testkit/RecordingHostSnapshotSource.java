package dev.turboism.testkit;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one mutable, read-recording {@link HostSnapshotSource} shared by {@code :runtime} and
 * {@code :testing:integration-tests} tests.
 *
 * <p>It answers every accessor from state a test sets, so a test states the observed host once
 * ({@link #builder()}) and moves it afterwards ({@link #setModel}, {@link #setSelectedObjectIds}).
 * Reads are counted per accessor, which is how {@link #readCount(Accessor)} proves a projection
 * happened once — or, as {@code SnapshotVersioningTest} needs, not at all.
 *
 * <p>Two contracts are deliberate:
 *
 * <ul>
 *   <li>Mutators never advance {@link #invalidationToken()}. A source whose observed inputs move
 *       without moving its token is exactly the case tests need to state, so every token change
 *       is an explicit {@link #advanceInvalidationToken()} or {@link #setInvalidationToken(long)}.
 *   <li>{@link #observe()} and {@link #observeSdkRuntime()} keep their interface defaults. The
 *       shared double is not an alternative for a source with its own paired read; tests that
 *       prove pairing or block a read keep their own fixture.
 * </ul>
 *
 * <p>No host attached is {@link #empty()}: false presence, empty values, token {@code 0}.
 */
public final class RecordingHostSnapshotSource implements HostSnapshotSource {

    /** The accessors {@link #readCount(Accessor)} reports on, one counter each. */
    public enum Accessor {
        PROJECT,
        DOCUMENT,
        MODEL,
        SELECTION,
        HOST_PRESENT,
        INVALIDATION_TOKEN
    }

    private final AtomicInteger[] reads = newReadCounters();
    private final AtomicLong invalidationToken = new AtomicLong();

    private volatile Optional<HostProject> project = Optional.empty();
    private volatile Optional<HostDocument> document = Optional.empty();
    private volatile Optional<HostModel> model = Optional.empty();
    private volatile HostSelection selection = HostSelection.empty();
    private volatile boolean hostPresent;

    private RecordingHostSnapshotSource(final Builder builder) {
        project = builder.project;
        document = builder.document;
        model = builder.model;
        selection = builder.selection;
        hostPresent = builder.hostPresent;
        invalidationToken.set(builder.invalidationToken);
    }

    /** Returns a source that observes nothing: no project, document, model, selection or host. */
    public static RecordingHostSnapshotSource empty() {
        return builder().build();
    }

    /** Returns a builder whose defaults are the {@link #empty()} values. */
    public static Builder builder() {
        return new Builder();
    }

    private static AtomicInteger[] newReadCounters() {
        final AtomicInteger[] counters = new AtomicInteger[Accessor.values().length];
        for (final Accessor accessor : Accessor.values()) {
            counters[accessor.ordinal()] = new AtomicInteger();
        }
        return counters;
    }

    /** Returns how many times the supplied accessor was read since this source was created. */
    public int readCount(final Accessor accessor) {
        return reads[Objects.requireNonNull(accessor, "accessor").ordinal()].get();
    }

    private void record(final Accessor accessor) {
        reads[accessor.ordinal()].incrementAndGet();
    }

    /** Replaces the observed project, or clears it with {@link Optional#empty()}. */
    public void setProject(final Optional<HostProject> next) {
        project = Objects.requireNonNull(next, "project");
    }

    /** Replaces the observed project. */
    public void setProject(final HostProject next) {
        setProject(Optional.of(Objects.requireNonNull(next, "project")));
    }

    /** Replaces the observed document, or clears it with {@link Optional#empty()}. */
    public void setDocument(final Optional<HostDocument> next) {
        document = Objects.requireNonNull(next, "document");
    }

    /** Replaces the observed document. */
    public void setDocument(final HostDocument next) {
        setDocument(Optional.of(Objects.requireNonNull(next, "document")));
    }

    /** Replaces the observed model, or clears it with {@link Optional#empty()}. */
    public void setModel(final Optional<HostModel> next) {
        model = Objects.requireNonNull(next, "model");
    }

    /** Replaces the observed model. */
    public void setModel(final HostModel next) {
        setModel(Optional.of(Objects.requireNonNull(next, "model")));
    }

    /** Replaces the observed selection with {@code next} object ids and no active member. */
    public void setSelectedObjectIds(final List<String> next) {
        selection = new HostSelection(
                Objects.requireNonNull(next, "selectedObjectIds"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Replaces the observed selection. */
    public void setSelection(final HostSelection next) {
        selection = Objects.requireNonNull(next, "selection");
    }

    /** Replaces whether this source reports a host; the values do not change with it. */
    public void setHostPresent(final boolean next) {
        hostPresent = next;
    }

    /** Replaces the invalidation token without recording a read of the old one. */
    public void setInvalidationToken(final long next) {
        invalidationToken.set(next);
    }

    /** Advances the invalidation token by one, the usual "something observable may have moved". */
    public void advanceInvalidationToken() {
        invalidationToken.incrementAndGet();
    }

    @Override
    public Optional<HostProject> activeProject() {
        record(Accessor.PROJECT);
        return project;
    }

    @Override
    public Optional<HostDocument> activeDocument() {
        record(Accessor.DOCUMENT);
        return document;
    }

    @Override
    public Optional<HostModel> activeModel() {
        record(Accessor.MODEL);
        return model;
    }

    @Override
    public HostSelection selection() {
        record(Accessor.SELECTION);
        return selection;
    }

    @Override
    public boolean isHostPresent() {
        record(Accessor.HOST_PRESENT);
        return hostPresent;
    }

    @Override
    public long invalidationToken() {
        record(Accessor.INVALIDATION_TOKEN);
        return invalidationToken.get();
    }

    /** Fluent construction of one starting state; every value defaults to the {@link #empty()} one. */
    public static final class Builder {

        private Optional<HostProject> project = Optional.empty();
        private Optional<HostDocument> document = Optional.empty();
        private Optional<HostModel> model = Optional.empty();
        private HostSelection selection = HostSelection.empty();
        private boolean hostPresent;
        private long invalidationToken;

        private Builder() {}

        /** Sets the observed project. */
        public Builder project(final HostProject value) {
            project = Optional.of(Objects.requireNonNull(value, "project"));
            return this;
        }

        /** Sets the observed document. */
        public Builder document(final HostDocument value) {
            document = Optional.of(Objects.requireNonNull(value, "document"));
            return this;
        }

        /** Sets the observed model. */
        public Builder model(final HostModel value) {
            model = Optional.of(Objects.requireNonNull(value, "model"));
            return this;
        }

        /** Sets the observed selection. */
        public Builder selection(final HostSelection value) {
            selection = Objects.requireNonNull(value, "selection");
            return this;
        }

        /** Sets the observed selection to {@code ids} with no active member. */
        public Builder selectedObjectIds(final List<String> ids) {
            selection = new HostSelection(
                    Objects.requireNonNull(ids, "selectedObjectIds"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
            return this;
        }

        /** Sets whether the source reports a host; default false. */
        public Builder hostPresent(final boolean value) {
            hostPresent = value;
            return this;
        }

        /** Sets the starting invalidation token; default {@code 0}. */
        public Builder invalidationToken(final long value) {
            invalidationToken = value;
            return this;
        }

        /** Returns the source with the state built so far. */
        public RecordingHostSnapshotSource build() {
            return new RecordingHostSnapshotSource(this);
        }
    }
}
