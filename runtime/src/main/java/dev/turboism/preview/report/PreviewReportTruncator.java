package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Deterministically bounds preview reports with fixed, bounded-cost pruning phases. */
final class PreviewReportTruncator {

    private static final int SIZE_MARGIN_BYTES = 256;
    private static final int SAMPLE_LIMIT = 8;
    private static final int MAX_PHASE_ATTEMPTS = 2;

    byte[] truncate(final ObjectNode document) throws IOException {
        byte[] bytes = serialize(document);
        if (bytes.length <= PreviewReportValidator.MAX_REPORT_BYTES) {
            return bytes;
        }

        long droppedEntries = 0;
        for (Phase phase : phases(document)) {
            for (int attempt = 0; attempt < MAX_PHASE_ATTEMPTS
                && bytes.length > PreviewReportValidator.MAX_REPORT_BYTES; attempt++) {
                final int excess = bytes.length - PreviewReportValidator.MAX_REPORT_BYTES;
                final long dropped = prunePhase(phase, excess + SIZE_MARGIN_BYTES);
                if (dropped == 0) {
                    break;
                }
                droppedEntries += dropped;
                markTruncated(document, droppedEntries);
                bytes = serialize(document);
            }
            if (bytes.length <= PreviewReportValidator.MAX_REPORT_BYTES) {
                return bytes;
            }
        }

        throw new PreviewReportValidationException(
            "REPORT_SIZE",
            "Preview report minimum summary exceeds the runtime bound."
        );
    }

    private static long prunePhase(final Phase phase, final int bytesToRemove)
        throws IOException {
        final int available = removableCount(phase);
        if (available == 0) {
            return 0;
        }
        final int averageBytes = sampleAverageBytes(phase);
        final int requested = Math.min(
            available,
            Math.max(1, (bytesToRemove + averageBytes - 1) / averageBytes)
        );
        return removeEntries(phase, requested);
    }

    private static int removableCount(final Phase phase) {
        int available = 0;
        for (Target target : phase.targets()) {
            available += Math.max(0, target.array().size() - target.minimumSize());
        }
        return available;
    }

    private static int sampleAverageBytes(final Phase phase) throws IOException {
        long bytes = 0;
        int samples = 0;
        for (Target target : phase.targets()) {
            final ArrayNode array = target.array();
            final int removable = Math.max(0, array.size() - target.minimumSize());
            for (int offset = 0; offset < removable && samples < SAMPLE_LIMIT; offset++) {
                bytes += PreviewReportDocuments.JSON.writeValueAsBytes(
                    array.get(array.size() - 1 - offset)
                ).length + 1L;
                samples++;
            }
            if (samples == SAMPLE_LIMIT) {
                break;
            }
        }
        return samples == 0 ? 1 : Math.max(1, (int) (bytes / samples));
    }

    private static long removeEntries(final Phase phase, final int requested) {
        int remaining = requested;
        long removed = 0;
        for (Target target : phase.targets()) {
            if (remaining == 0) {
                break;
            }
            final ArrayNode array = target.array();
            final int removable = Math.max(0, array.size() - target.minimumSize());
            final int count = Math.min(remaining, removable);
            final int firstRemoved = array.size() - count;
            for (int index = array.size() - 1; index >= firstRemoved; index--) {
                array.remove(index);
            }
            removed += count;
            remaining -= count;
        }
        return removed;
    }

    private static byte[] serialize(final ObjectNode document) throws IOException {
        return PreviewReportDocuments.JSON.writeValueAsBytes(document);
    }

    private static List<Phase> phases(final ObjectNode document) {
        final ObjectNode payload = (ObjectNode) document.path("payload");
        return switch (document.path("reportType").asText()) {
            case "PREVIEW_RUNTIME" -> List.of(
                phase(target(payload, "taskFailures", 0)),
                phase(target(payload, "storageFailures", 0)),
                phase(target(payload, "configFailures", 0))
            );
            case "PLUGIN_LOAD" -> pluginLoadPhases(payload);
            case "CAPABILITY" -> capabilityPhases(payload);
            case "I18N" -> i18nPhases(payload);
            default -> List.of();
        };
    }

    private static List<Phase> pluginLoadPhases(final ObjectNode payload) {
        final ArrayNode plugins = array(payload, "plugins");
        return List.of(
            nestedPhase(plugins, "failures", 0),
            phase(new Target(plugins, 1))
        );
    }

    private static List<Phase> capabilityPhases(final ObjectNode payload) {
        final ArrayNode capabilities = array(payload, "capabilities");
        return List.of(
            nestedPhase(capabilities, "failures", 0),
            nestedPhase(capabilities, "evidence", 1),
            phase(new Target(capabilities, 1))
        );
    }

    private static List<Phase> i18nPhases(final ObjectNode payload) {
        final ArrayNode plugins = array(payload, "plugins");
        return List.of(
            nestedPhase(plugins, "missingKeys", 0),
            nestedPhase(plugins, "malformedPatterns", 0),
            nestedPhase(plugins, "catalogs", 1),
            phase(new Target(plugins, 1))
        );
    }

    private static Phase nestedPhase(
        final ArrayNode parents,
        final String field,
        final int minimum
    ) {
        final List<Target> targets = new ArrayList<>(parents.size());
        for (int index = parents.size() - 1; index >= 0; index--) {
            targets.add(target((ObjectNode) parents.get(index), field, minimum));
        }
        return new Phase(List.copyOf(targets));
    }

    private static Phase phase(final Target target) {
        return new Phase(List.of(target));
    }

    private static Target target(final ObjectNode object, final String field, final int minimum) {
        return new Target(array(object, field), minimum);
    }

    private static ArrayNode array(final ObjectNode object, final String field) {
        return (ArrayNode) object.path(field);
    }

    private static void markTruncated(final ObjectNode document, final long droppedEntries) {
        final ObjectNode truncation = (ObjectNode) document.path("truncation");
        truncation.put("truncated", true);
        truncation.put("droppedEntries", droppedEntries);
        truncation.put("reason", "WRITER_LIMIT");
    }

    private record Phase(List<Target> targets) {
    }

    private record Target(ArrayNode array, int minimumSize) {
    }
}
