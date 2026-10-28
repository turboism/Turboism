package dev.turboism.preview;

import dev.turboism.mapping.verification.VerifiedAccessException;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal formatting, sanitization, and bounded exception snapshot utilities for PreviewLog.
 */
final class PreviewLogFormatter {

    static final int MAX_MESSAGE_CHARS = 16_384;
    static final int MAX_COMPONENT_CHARS = 64;
    static final int MAX_FRAME_CHARS = 512;
    static final int MAX_CAUSE_DEPTH = 8;
    static final int MAX_LOGGED_FRAMES = 24;

    private PreviewLogFormatter() {}

    /**
     * Sanitizes a single-line string, truncating prior to replacement so giant inputs never
     * trigger huge intermediate string allocations.
     */
    static String safe(final String value, final int maxChars) {
        if (value == null) return "";
        final String truncated = value.length() > maxChars ? value.substring(0, maxChars) + "... [truncated]" : value;
        return truncated.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Sanitizes a multiline string, truncating prior to replacement so giant inputs never
     * trigger huge intermediate string allocations.
     */
    static String safeMultiline(final String value, final int maxChars) {
        if (value == null) return "";
        final String truncated = value.length() > maxChars ? value.substring(0, maxChars) + "\n... [truncated]" : value;
        return truncated.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Single-pass snapshot of a failure cause chain into bounded formatted lines and a clean
     * throwable without retaining arbitrary external heap objects or giant stack traces.
     */
    static FailureSnapshot capture(final Throwable failure) {
        if (failure == null) {
            return new FailureSnapshot(List.of(), null, 0L);
        }

        final List<String> formattedLines = new ArrayList<>();
        final List<CleanCauseData> causes = new ArrayList<>(MAX_CAUSE_DEPTH);
        Throwable current = failure;
        int depth = 0;
        long estimatedBytes = 0L;

        while (current != null && depth < MAX_CAUSE_DEPTH) {
            final String rawClassName = current.getClass().getName();
            final String className = safe(rawClassName, 128);
            final String rawMessage = current.getMessage();
            final String safeMessage = safe(rawMessage, MAX_MESSAGE_CHARS);

            String diagnostic = null;
            if (current instanceof VerifiedAccessException verified) {
                diagnostic = " [alias=" + safe(verified.alias(), MAX_COMPONENT_CHARS) + ", failureKind="
                        + verified.failureKind() + "]";
            }

            final StringBuilder line = new StringBuilder(depth > 0 ? "caused by " : "")
                    .append(className)
                    .append(": ")
                    .append(safeMessage);
            if (diagnostic != null) {
                line.append(diagnostic);
            }
            final String headerLine = line.toString();
            formattedLines.add(headerLine);
            estimatedBytes += headerLine.length() * 2L + 48L;

            final StackTraceElement[] rawFrames = current.getStackTrace();
            final int frameCount = rawFrames == null ? 0 : rawFrames.length;
            final int loggedCount = Math.min(frameCount, MAX_LOGGED_FRAMES);
            final StackTraceElement[] cleanFrames = new StackTraceElement[loggedCount];

            for (int index = 0; index < loggedCount; index++) {
                final StackTraceElement raw = rawFrames[index];
                final String frameLine = "\tat " + safe(raw.toString(), MAX_FRAME_CHARS);
                formattedLines.add(frameLine);
                estimatedBytes += frameLine.length() * 2L + 48L;

                final String cleanClass = safe(raw.getClassName(), 128);
                final String cleanMethod = safe(raw.getMethodName(), 64);
                final String cleanFile = safe(raw.getFileName(), 64);
                cleanFrames[index] = new StackTraceElement(cleanClass, cleanMethod, cleanFile, raw.getLineNumber());
                estimatedBytes += (cleanClass.length() + cleanMethod.length() + cleanFile.length()) * 2L + 64L;
            }

            if (frameCount > MAX_LOGGED_FRAMES) {
                final String elidedLine = "\t... " + (frameCount - MAX_LOGGED_FRAMES) + " more";
                formattedLines.add(elidedLine);
                estimatedBytes += elidedLine.length() * 2L + 48L;
            }

            final String displayMsg = diagnostic == null ? safeMessage : (safeMessage + diagnostic);
            causes.add(new CleanCauseData(className, displayMsg, cleanFrames));
            estimatedBytes += className.length() * 2L + displayMsg.length() * 2L + 64L;

            current = current.getCause();
            depth++;
        }

        // Build CleanLogThrowable chain backwards from innermost to outermost cause
        CleanLogThrowable cleanThrowable = null;
        for (int i = causes.size() - 1; i >= 0; i--) {
            final CleanCauseData data = causes.get(i);
            cleanThrowable = new CleanLogThrowable(data.className, data.message, data.frames, cleanThrowable);
        }

        return new FailureSnapshot(List.copyOf(formattedLines), cleanThrowable, estimatedBytes);
    }

    record FailureSnapshot(List<String> formattedLines, Throwable cleanThrowable, long estimatedMemoryBytes) {}

    private record CleanCauseData(String className, String message, StackTraceElement[] frames) {}

    static final class CleanLogThrowable extends Throwable {
        private final String displayClassName;

        CleanLogThrowable(
                final String className, final String message, final StackTraceElement[] frames, final Throwable cause) {
            super(message, cause);
            this.displayClassName = className;
            setStackTrace(frames);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            // Avoid capturing the execution thread's stack trace
            return this;
        }

        @Override
        public String toString() {
            final String msg = getLocalizedMessage();
            return (msg != null && !msg.isEmpty()) ? (displayClassName + ": " + msg) : displayClassName;
        }
    }
}
