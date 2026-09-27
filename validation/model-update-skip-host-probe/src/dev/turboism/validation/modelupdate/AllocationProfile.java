package dev.turboism.validation.modelupdate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Post-window JFR allocation attribution. Sample weights are NOT exact allocation counts or bytes. */
final class AllocationProfile {
    private static final int MAX_KEYS = 4096;
    private static final class Count {
        long samples, weight;
        void add(long value) { samples++; weight = Math.addExact(weight, value); }
    }
    private final Map<String, Count> categories = new LinkedHashMap<>();
    private final Map<String, Count> sites = new LinkedHashMap<>();
    private long samples, weight, firstWeight, missingStacks, overflowSites;

    static void configure(Recording recording) {
        if (FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .noneMatch(type -> type.getName().equals("jdk.ObjectAllocationSample"))) {
            throw new IllegalStateException("allocation sampling is unavailable on this JVM");
        }
        recording.enable("jdk.ObjectAllocationSample").withStackTrace().with("throttle", "500/s");
        recording.enable("jdk.ThreadAllocationStatistics").withPeriod(java.time.Duration.ofSeconds(1));
        recording.enable("jdk.GarbageCollection");
        recording.enable("jdk.GCPhasePause").withThreshold(java.time.Duration.ofMillis(1));
    }

    static String summarize(Path path) throws IOException {
        AllocationProfile result = new AllocationProfile();
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) continue;
                var thread = event.getThread();
                String name = thread == null ? "unknown" : thread.getJavaName();
                var type = event.getClass("objectClass");
                List<String> stack = new ArrayList<>();
                if (event.getStackTrace() != null) {
                    for (var frame : event.getStackTrace().getFrames()) {
                        var method = frame.getMethod();
                        stack.add(method.getType().getName() + "." + method.getName());
                        if (stack.size() == 48) break;
                    }
                }
                result.add(name, type == null ? "unknown" : type.getName(), event.getLong("weight"), stack);
            }
        }
        return result.report();
    }

    void add(String thread, String type, long value, List<String> stack) {
        if (value < 0) throw new IllegalArgumentException("negative allocation sample weight");
        if (thread == null || !thread.startsWith("AWT-EventQueue")) return;
        if (samples == 0) firstWeight = value;
        samples++; weight = Math.addExact(weight, value);
        if (stack.isEmpty()) missingStacks++;
        String category = category(stack);
        categories.computeIfAbsent(category, ignored -> new Count()).add(value);
        String key = type + " | " + String.join(" <- ", stack);
        if (!sites.containsKey(key) && sites.size() >= MAX_KEYS) { overflowSites++; key = "other-sites-over-bound"; }
        sites.computeIfAbsent(key, ignored -> new Count()).add(value);
    }

    private static String category(List<String> stack) {
        for (String frame : stack) {
            if (frame.startsWith("dev.turboism.adapter.cubism.optimization.uniform.")) return "turboism.uniform";
            if (frame.startsWith("dev.turboism.validation.")) return "turboism.validation";
            if (frame.startsWith("com.live2d.graphics3d.type.GMatrix")
                    || frame.startsWith("com.live2d.graphics3d.component.GTransform.")) return "native.matrix";
            if (frame.startsWith("com.live2d.graphics3d.rendering.")
                    || frame.startsWith("com.live2d.graphics3d.entity.")) return "native.scene";
            if (frame.startsWith("com.live2d.graphics3d.shader.")) return "native.shader";
            if (frame.startsWith("com.live2d.")) return "native.other";
            if (frame.startsWith("dev.turboism.")) return "turboism.other";
        }
        return "other";
    }

    String report() {
        StringBuilder out = new StringBuilder("allocation.source=jdk.ObjectAllocationSample\n")
            .append("allocation.exactBytes=false\nallocation.performanceAccepted=false\n")
            .append("allocation.edtSamples=").append(samples).append('\n')
            .append("allocation.edtSampleWeight=").append(weight).append('\n')
            .append("allocation.firstEdtSampleWeight=").append(firstWeight).append('\n')
            .append("allocation.missingStacks=").append(missingStacks).append('\n')
            .append("allocation.overflowSites=").append(overflowSites).append('\n');
        categories.forEach((name, count) -> out.append("allocation.category.").append(name)
            .append(".weight=").append(count.weight).append('\n'));
        var sorted = sites.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Count>>comparingLong(entry -> entry.getValue().weight).reversed())
            .limit(30).toList();
        for (int index = 0; index < sorted.size(); index++) {
            var entry = sorted.get(index);
            out.append("allocation.site.").append(index).append(".samples=").append(entry.getValue().samples).append('\n')
                .append("allocation.site.").append(index).append(".weight=").append(entry.getValue().weight).append('\n')
                .append("allocation.site.").append(index).append(".stack=").append(entry.getKey()).append('\n');
        }
        return out.toString();
    }
}
