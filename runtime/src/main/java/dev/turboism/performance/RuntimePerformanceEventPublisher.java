package dev.turboism.performance;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSampleEvent;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.Objects;

/** Session-owned latest-only bridge from performance sampling to the event broker. */
public final class RuntimePerformanceEventPublisher implements AutoCloseable {

    public static final Duration DEFAULT_SAMPLE_INTERVAL = Duration.ofMillis(250);
    public static final Duration DEFAULT_PUBLICATION_INTERVAL = Duration.ofSeconds(1);

    private final RuntimeEventBroker eventBroker;
    private final RuntimeScheduler scheduler;
    private final Duration publicationInterval;
    private final Object lifecycle = new Object();
    private final Registration sampling;
    private RuntimeTimerHandle publicationTimer;
    private PerformanceSnapshot latest;
    private long coalescedSamples;
    private boolean closed;

    public RuntimePerformanceEventPublisher(
        final PerformanceProbeService source,
        final RuntimeEventBroker eventBroker,
        final RuntimeScheduler scheduler
    ) {
        this(
            source,
            eventBroker,
            scheduler,
            DEFAULT_SAMPLE_INTERVAL,
            DEFAULT_PUBLICATION_INTERVAL
        );
    }

    RuntimePerformanceEventPublisher(
        final PerformanceProbeService source,
        final RuntimeEventBroker eventBroker,
        final RuntimeScheduler scheduler,
        final Duration sampleInterval,
        final Duration publicationInterval
    ) {
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.publicationInterval = requirePositive(
            publicationInterval,
            "publicationInterval"
        );
        this.sampling = Objects.requireNonNull(source, "source").sample(
            requirePositive(sampleInterval, "sampleInterval"),
            this::offer
        );
    }

    void offer(final PerformanceSnapshot snapshot) {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            if (latest != null) {
                coalescedSamples++;
            }
            latest = Objects.requireNonNull(snapshot, "snapshot");
            if (publicationTimer == null) {
                final RuntimeTimerSubmission submission = scheduler.schedule(
                    publicationInterval,
                    this::publishLatest
                );
                if (submission.accepted()) {
                    publicationTimer = submission.handle();
                } else {
                    latest = null;
                    coalescedSamples = 0L;
                }
            }
        }
    }

    private void publishLatest() {
        final PerformanceSampleEvent event;
        synchronized (lifecycle) {
            publicationTimer = null;
            if (closed || latest == null) {
                return;
            }
            event = new PerformanceSampleEvent(latest, coalescedSamples);
            latest = null;
            coalescedSamples = 0L;
        }
        try {
            eventBroker.publishRuntime(event);
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable ignored) {
            // Performance observation delivery cannot stop the shared sampler.
        }
    }

    @Override
    public void close() {
        final RuntimeTimerHandle timer;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            timer = publicationTimer;
            publicationTimer = null;
            latest = null;
            coalescedSamples = 0L;
        }
        if (timer != null) {
            timer.cancel();
        }
        sampling.close();
    }

    private static Duration requirePositive(final Duration value, final String name) {
        final Duration duration = Objects.requireNonNull(value, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
