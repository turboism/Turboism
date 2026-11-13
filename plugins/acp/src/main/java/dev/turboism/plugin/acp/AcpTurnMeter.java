package dev.turboism.plugin.acp;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Paseo-inspired per-turn token speed meter.
 *
 * <p>Tracks sliding-window live generation speed, active decode speed, time-to-first-token (TTFT),
 * and output token totals while excluding user permission-wait intervals. Reconciles character
 * estimates with exact provider-reported output tokens when available and calibrates the
 * character-to-token ratio across turns.</p>
 */
final class AcpTurnMeter {

    static final double DEFAULT_CHARS_PER_TOKEN = 3.6;
    private static final double MIN_CHARS_PER_TOKEN = 2.0;
    private static final double MAX_CHARS_PER_TOKEN = 8.0;
    private static final int MIN_CALIBRATION_CHARS = 40;
    private static final double CALIBRATION_EMA_WEIGHT = 0.35;
    private static final double MAX_PLAUSIBLE_TOK_PER_SEC = 2_000.0;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long LIVE_WINDOW_NANOS = 2L * NANOS_PER_SECOND;
    private static final long MIN_LIVE_SPAN_NANOS = 200L * NANOS_PER_MILLI;
    private static final long SAMPLE_BUCKET_NANOS = 100L * NANOS_PER_MILLI;
    private static final long STALL_GAP_NANOS = 3L * NANOS_PER_SECOND;
    private static final long MIN_AVERAGE_DECODE_NANOS = 50L * NANOS_PER_MILLI;
    private static final String APPROX_PREFIX = "≈";
    private static final String PLACEHOLDER = "—";

    private final LongSupplier clock;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double charsPerToken = DEFAULT_CHARS_PER_TOKEN;
    private boolean running;
    private boolean waitingOnUser;
    private long turnStartNanos;
    private long firstOutputNanos = -1L;
    private long lastOutputNanos = -1L;
    private long userWaitStartNanos = -1L;
    private long userWaitTotalNanos;
    private long userWaitBeforeFirstTokenNanos;
    private long streamedChars;
    private long reportedOutputTokens;
    private boolean sawExactUsage;
    private Snapshot settled = Snapshot.IDLE;

    private record Sample(long atNanos, double cumulativeTokens) {}

    record Snapshot(
            boolean visible,
            boolean running,
            boolean waiting,
            boolean approximate,
            double liveTokPerSec,
            double averageTokPerSec,
            long outputTokens,
            long ttftMillis,
            long activeMillis) {
        static final Snapshot IDLE = new Snapshot(false, false, false, true, 0.0, 0.0, 0L, -1L, 0L);

        String pillText() {
            if (!visible) return "";
            if (running) {
                final double rate = liveTokPerSec > 0.0 ? liveTokPerSec : averageTokPerSec;
                if (rate <= 0.0) return "";
                return formatApproximate(formatRate(rate), approximate) + " tok/s";
            }
            if (averageTokPerSec <= 0.0) return "";
            return formatApproximate(formatRate(averageTokPerSec), approximate) + " tok/s";
        }
    }

    AcpTurnMeter() {
        this(System::nanoTime);
    }

    AcpTurnMeter(final LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    synchronized void reset() {
        running = false;
        waitingOnUser = false;
        samples.clear();
        turnStartNanos = 0L;
        firstOutputNanos = -1L;
        lastOutputNanos = -1L;
        userWaitStartNanos = -1L;
        userWaitTotalNanos = 0L;
        userWaitBeforeFirstTokenNanos = 0L;
        streamedChars = 0L;
        reportedOutputTokens = 0L;
        sawExactUsage = false;
        settled = Snapshot.IDLE;
    }

    synchronized void startTurn() {
        final long now = clock.getAsLong();
        running = true;
        waitingOnUser = false;
        samples.clear();
        turnStartNanos = now;
        firstOutputNanos = -1L;
        lastOutputNanos = -1L;
        userWaitStartNanos = -1L;
        userWaitTotalNanos = 0L;
        userWaitBeforeFirstTokenNanos = 0L;
        streamedChars = 0L;
        reportedOutputTokens = 0L;
        sawExactUsage = false;
        settled = Snapshot.IDLE;
    }

    synchronized void recordOutputChars(final int charCount) {
        if (!running || charCount <= 0) return;
        final long now = clock.getAsLong();
        if (firstOutputNanos < 0L) {
            firstOutputNanos = now;
            userWaitBeforeFirstTokenNanos = currentUserWaitTotal(now);
        }
        if (lastOutputNanos >= 0L && now - lastOutputNanos > STALL_GAP_NANOS) {
            samples.clear();
        }
        lastOutputNanos = now;
        streamedChars += charCount;
        pushSample(now, currentCumulativeTokens());
    }

    synchronized void recordUsageOutputTokens(final long outputTokens) {
        if (outputTokens <= 0L) return;
        reportedOutputTokens += outputTokens;
        sawExactUsage = true;
        if (streamedChars >= MIN_CALIBRATION_CHARS) {
            final double observedRatio = (double) streamedChars / (double) reportedOutputTokens;
            if (observedRatio >= MIN_CHARS_PER_TOKEN && observedRatio <= MAX_CHARS_PER_TOKEN) {
                charsPerToken = charsPerToken * (1.0 - CALIBRATION_EMA_WEIGHT) + observedRatio * CALIBRATION_EMA_WEIGHT;
            }
        }
        if (running) {
            final long now = clock.getAsLong();
            if (firstOutputNanos < 0L) {
                firstOutputNanos = now;
                userWaitBeforeFirstTokenNanos = currentUserWaitTotal(now);
            }
            lastOutputNanos = now;
            pushSample(now, currentCumulativeTokens());
        } else if (settled.visible()) {
            settled = new Snapshot(
                    true,
                    false,
                    false,
                    false,
                    0.0,
                    computeRate(reportedOutputTokens, settled.activeMillis() * NANOS_PER_MILLI),
                    reportedOutputTokens,
                    settled.ttftMillis(),
                    settled.activeMillis());
        }
    }

    synchronized void beginUserWait() {
        if (!running || waitingOnUser) return;
        waitingOnUser = true;
        userWaitStartNanos = clock.getAsLong();
        samples.clear();
    }

    synchronized void endUserWait() {
        if (!waitingOnUser) return;
        final long now = clock.getAsLong();
        if (userWaitStartNanos >= 0L && now > userWaitStartNanos) {
            userWaitTotalNanos += now - userWaitStartNanos;
        }
        waitingOnUser = false;
        userWaitStartNanos = -1L;
        samples.clear();
    }

    synchronized void finishTurn() {
        if (!running) return;
        endUserWait();
        final long now = clock.getAsLong();
        settled = buildRunningSnapshot(now, false);
        running = false;
        samples.clear();
    }

    synchronized Snapshot snapshot() {
        if (!running) return settled;
        return buildRunningSnapshot(clock.getAsLong(), true);
    }

    private Snapshot buildRunningSnapshot(final long now, final boolean stillRunning) {
        final long tokens = Math.round(currentCumulativeTokens());
        if (tokens <= 0L && firstOutputNanos < 0L) {
            return Snapshot.IDLE;
        }
        final long totalUserWait = currentUserWaitTotal(now);
        final long ttftMillis = firstOutputNanos < 0L
                ? -1L
                : Math.max(0L, (firstOutputNanos - turnStartNanos - userWaitBeforeFirstTokenNanos) / NANOS_PER_MILLI);
        final long decodeEnd = stillRunning ? now : (lastOutputNanos >= 0L ? Math.max(lastOutputNanos, now) : now);
        final long waitDuringDecode = Math.max(0L, totalUserWait - userWaitBeforeFirstTokenNanos);
        final long decodeSpanNanos =
                firstOutputNanos < 0L ? 0L : Math.max(0L, decodeEnd - firstOutputNanos - waitDuringDecode);
        final long totalActiveNanos = Math.max(0L, decodeEnd - turnStartNanos - totalUserWait);
        final long effectiveRateNanos =
                decodeSpanNanos >= MIN_AVERAGE_DECODE_NANOS ? decodeSpanNanos : totalActiveNanos;
        final double live = stillRunning && !waitingOnUser ? computeLiveRate(now) : 0.0;
        final double avg = computeRate(tokens, effectiveRateNanos);
        return new Snapshot(
                true,
                stillRunning,
                stillRunning && waitingOnUser,
                !sawExactUsage,
                live,
                avg,
                tokens,
                ttftMillis,
                effectiveRateNanos / NANOS_PER_MILLI);
    }

    private long currentUserWaitTotal(final long now) {
        if (!waitingOnUser || userWaitStartNanos < 0L) return userWaitTotalNanos;
        return userWaitTotalNanos + Math.max(0L, now - userWaitStartNanos);
    }

    private double currentCumulativeTokens() {
        final double estimated = streamedChars <= 0L ? 0.0 : Math.max(1.0, streamedChars / charsPerToken);
        return sawExactUsage ? Math.max(reportedOutputTokens, estimated) : estimated;
    }

    private void pushSample(final long now, final double cumulativeTokens) {
        final Sample newest = samples.peekLast();
        if (newest != null && now - newest.atNanos() < SAMPLE_BUCKET_NANOS) {
            samples.pollLast();
        }
        samples.addLast(new Sample(now, cumulativeTokens));
        final long cutoff = now - LIVE_WINDOW_NANOS;
        while (samples.size() > 2 && samples.peekFirst().atNanos() < cutoff) {
            samples.pollFirst();
        }
    }

    private double computeLiveRate(final long now) {
        final long cutoff = now - LIVE_WINDOW_NANOS;
        while (samples.size() > 1 && samples.peekFirst().atNanos() < cutoff) {
            samples.pollFirst();
        }
        if (samples.size() < 2) return 0.0;
        final Sample oldest = samples.peekFirst();
        final Sample newest = samples.peekLast();
        if (now - newest.atNanos() > LIVE_WINDOW_NANOS) return 0.0;
        final long spanNanos = newest.atNanos() - oldest.atNanos();
        if (spanNanos < MIN_LIVE_SPAN_NANOS) return 0.0;
        final double deltaTokens = newest.cumulativeTokens() - oldest.cumulativeTokens();
        if (deltaTokens <= 0.0) return 0.0;
        final double rate = (deltaTokens * NANOS_PER_SECOND) / spanNanos;
        return rate <= MAX_PLAUSIBLE_TOK_PER_SEC ? rate : 0.0;
    }

    private static double computeRate(final long tokens, final long activeDecodeNanos) {
        if (tokens <= 0L || activeDecodeNanos < MIN_AVERAGE_DECODE_NANOS) return 0.0;
        final double rate = ((double) tokens * NANOS_PER_SECOND) / activeDecodeNanos;
        return rate <= MAX_PLAUSIBLE_TOK_PER_SEC ? rate : 0.0;
    }

    static String formatRate(final double rate) {
        if (!Double.isFinite(rate) || rate <= 0.0) return PLACEHOLDER;
        if (rate >= 100.0) return Long.toString(Math.round(rate));
        if (rate >= 10.0) return String.format(Locale.ROOT, "%.1f", rate);
        return String.format(Locale.ROOT, "%.2f", rate);
    }

    static String formatTokens(final long tokens) {
        if (tokens < 0L) return PLACEHOLDER;
        if (tokens < 1_000L) return Long.toString(tokens);
        if (tokens < 10_000L) return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0);
        return Math.round(tokens / 1000.0) + "k";
    }

    static String formatDurationMillis(final long millis) {
        if (millis < 0L) return PLACEHOLDER;
        if (millis < 1_000L) return millis + "ms";
        if (millis < 60_000L) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        final long minutes = millis / 60_000L;
        final long seconds = Math.round((millis % 60_000L) / 1000.0);
        return String.format(Locale.ROOT, "%dm%02ds", minutes, seconds);
    }

    static String formatApproximate(final String value, final boolean approximate) {
        if (PLACEHOLDER.equals(value)) return value;
        return approximate ? APPROX_PREFIX + value : value;
    }
}
