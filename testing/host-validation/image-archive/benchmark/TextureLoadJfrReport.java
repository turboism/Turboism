package dev.turboism.validation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Streaming diagnostic attribution, not a CPU timer or an end-to-end A/B benchmark. */
public final class TextureLoadJfrReport {
    private TextureLoadJfrReport() {}

    private static void add(Map<String, Long> counts, String key, long value) {
        counts.merge(key, value, Long::sum);
    }

    private static List<List<Object>> top(Map<String, Long> counts, int limit) {
        var entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        var result = new ArrayList<List<Object>>();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            var entry = entries.get(i);
            result.add(List.of(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static List<String> frames(RecordedEvent event) {
        var result = new ArrayList<String>();
        if (event.getStackTrace() != null) {
            for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                if (frame.getMethod() != null) result.add(frame.getMethod().getType().getName()
                        + "." + frame.getMethod().getName());
            }
        }
        return result;
    }

    private static boolean contains(List<String> frames, String value) {
        return frames.stream().anyMatch(frame -> frame.contains(value));
    }

    private static String category(List<String> frames) {
        if (contains(frames, "AWTTextureData.createFromCustom")) return "JOGL custom texture conversion";
        if (contains(frames, "CanonicalRgbaImage.prepare")) return "direct texture preparation";
        if (contains(frames, "PNGImageReader")) return "PNG decoding (including inflate/cache IO)";
        if (contains(frames, "CWritableImage$b") || contains(frames, "UtImage.getBufferedImage"))
            return "native image materialization";
        if (contains(frames, "CExtendedInterpolationExtension") || contains(frames, ".extendedInterpolation."))
            return "extended interpolation setup/evaluation";
        if (contains(frames, "FloatingDecimal") || contains(frames, "Float.parseFloat")) return "float parsing";
        if (contains(frames, "com.live2d.serialize")) return "model serialization/deserialization";
        if (contains(frames, "org.jdom") || contains(frames, "xerces")) return "XML parsing/validation";
        if (contains(frames, "java.util.zip.")) return "archive/other inflate";
        if (contains(frames, "com.reprisesoftware.rlm") || contains(frames, "com.live2d.c."))
            return "licensing (not an optimization target)";
        if (contains(frames, "WToolkit.eventLoop") || contains(frames, "WEPoll.wait") || contains(frames, "Net.poll"))
            return "native event/network wait (not CPU attribution)";
        if (contains(frames, "dispatch_gl") || contains(frames, "dispatch_wgl") || contains(frames, "dispatch_cl"))
            return "GL/CL/native graphics call (not GPU timing)";
        if (contains(frames, "dev.turboism.validation") || contains(frames, "jdk.jfr.internal"))
            return "observer/JFR overhead";
        if (contains(frames, "dev.turboism")) return "other Turboism";
        return "other host/JDK";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("recording startEpochMs endEpochMs");
        Instant start = Instant.ofEpochMilli(Long.parseLong(args[1]));
        Instant end = Instant.ofEpochMilli(Long.parseLong(args[2]));
        if (!end.isAfter(start)) throw new IllegalArgumentException("empty window");
        Map<String, Long> kinds = new HashMap<>(), leaves = new HashMap<>(), inclusive = new HashMap<>();
        Map<String, Long> javaLeaves = new HashMap<>(), nativeLeaves = new HashMap<>(), threads = new HashMap<>();
        Map<String, Long> categories = new HashMap<>(), stacks = new HashMap<>(), buckets = new HashMap<>();
        Map<String, Long> allocationClasses = new HashMap<>(), allocationSites = new HashMap<>();
        Map<String, Long> allocationCategories = new HashMap<>(), waits = new HashMap<>(), waitCounts = new HashMap<>();
        Map<String, Long> durationKinds = new HashMap<>(), durationCounts = new HashMap<>();
        Map<String, Map<String, Long>> threadInclusive = new HashMap<>();
        long truncated = 0, allocationEvents = 0;
        try (var recording = new RecordingFile(Path.of(args[0]))) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String kind = event.getEventType().getName();
                boolean sample = kind.equals("jdk.ExecutionSample") || kind.equals("jdk.NativeMethodSample");
                boolean allocation = kind.equals("jdk.ObjectAllocationSample");
                boolean wait = List.of("jdk.FileRead", "jdk.FileWrite", "jdk.ThreadPark", "jdk.JavaMonitorEnter",
                        "jdk.JavaMonitorWait", "jdk.GCPhasePause").contains(kind);
                if (!sample && !allocation && !wait) continue;
                if (event.getStartTime().isBefore(start) || event.getStartTime().isAfter(end)) continue;
                var trace = frames(event);
                String leaf = trace.isEmpty() ? "<no stack>" : trace.get(0);
                if (sample) {
                    add(kinds, kind, 1);
                    if (event.getStackTrace() != null && event.getStackTrace().isTruncated()) truncated++;
                    RecordedThread thread = event.getThread("sampledThread");
                    String name = thread == null ? "?" : thread.getJavaName();
                    if (name == null && thread != null) name = thread.getOSName();
                    if (name == null) name = "?";
                    add(threads, name, 1);
                    add(leaves, leaf, 1);
                    add(kind.equals("jdk.ExecutionSample") ? javaLeaves : nativeLeaves, leaf, 1);
                    String group = category(trace);
                    add(categories, kind + " | " + group, 1);
                    long bucket = (event.getStartTime().toEpochMilli() - start.toEpochMilli()) / 5000;
                    add(buckets, String.format("%02d-%02ds | %s | %s", bucket * 5, (bucket + 1) * 5, kind, group), 1);
                    add(stacks, name + " | " + String.join(" <- ", trace.subList(0, Math.min(trace.size(), 18))), 1);
                    Map<String, Long> perThread = threadInclusive.computeIfAbsent(name, key -> new HashMap<>());
                    for (String method : new HashSet<>(trace)) {
                        add(inclusive, method, 1);
                        add(perThread, method, 1);
                    }
                } else if (allocation) {
                    allocationEvents++;
                    long weight = event.getLong("weight");
                    add(allocationClasses, event.getClass("objectClass").getName(), weight);
                    add(allocationSites, leaf + " | " + event.getClass("objectClass").getName(), weight);
                    add(allocationCategories, category(trace), weight);
                } else {
                    Instant clippedEnd = event.getEndTime().isAfter(end) ? end : event.getEndTime();
                    long ns = java.time.Duration.between(event.getStartTime(), clippedEnd).toNanos();
                    RecordedThread thread = event.getThread();
                    String key = kind + " | " + (thread == null ? "?" : thread.getJavaName()) + " | " + leaf;
                    add(waits, key, ns);
                    add(waitCounts, key, 1);
                    add(durationKinds, kind, ns);
                    add(durationCounts, kind, 1);
                }
            }
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("windowStartEpochMs", start.toEpochMilli());
        output.put("windowEndEpochMs", end.toEpochMilli());
        output.put("sampleKinds", top(kinds, 10));
        output.put("truncatedSamples", truncated);
        output.put("topThreads", top(threads, 30));
        output.put("javaExecutionLeaves", top(javaLeaves, 50));
        output.put("nativeMethodLeaves", top(nativeLeaves, 40));
        output.put("inclusiveFrames", top(inclusive, 70));
        output.put("categoriesBySampleKind", top(categories, 50));
        output.put("topStacks", top(stacks, 60));
        output.put("timeBuckets", top(buckets, 200));
        var threadReports = new LinkedHashMap<String, Object>();
        for (var entry : top(threads, 12)) {
            String name = (String) entry.get(0);
            threadReports.put(name, top(threadInclusive.getOrDefault(name, Map.of()), 35));
        }
        output.put("threadInclusive", threadReports);
        output.put("allocationSampleCount", allocationEvents);
        output.put("estimatedAllocationBytesByClass", top(allocationClasses, 30));
        output.put("estimatedAllocationBytesByLeaf", top(allocationSites, 40));
        output.put("estimatedAllocationBytesByCategory", top(allocationCategories, 30));
        output.put("recordedDurationNsByEventType", top(durationKinds, 20));
        output.put("recordedDurationCountByEventType", top(durationCounts, 20));
        output.put("recordedWaitOrIoNs", top(waits, 40));
        output.put("recordedWaitOrIoCounts", top(waitCounts, 40));
        System.out.println(json(output));
    }

    private static String json(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<String>();
            map.forEach((key, item) -> entries.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(TextureLoadJfrReport::json).toList()) + "]";
        return "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
