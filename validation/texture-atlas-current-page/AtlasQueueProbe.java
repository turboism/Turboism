package dev.turboism.validation.texture;

import dev.turboism.agent.shaded.jackson.databind.JsonNode;
import dev.turboism.agent.shaded.jackson.databind.ObjectMapper;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Fixed, single-invocation auxiliary Agent. No process/network/launcher or plugin discovery. */
public final class AtlasQueueProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path evidence;

    public static void premain(String ignored, Instrumentation instrumentation) {
        // Fail before touching Swing if the snapshotted task does not describe this fixed matrix.
        int count = Integer.parseInt(required("count"));
        String implementation = required("implementation");
        validateCase(count, implementation);
        requestedModelImage();
        boolean parallel = requestedParallel();
        if (parallel && ((count != 100 && count != 500) || !implementation.equals("new") || !HostUiProbe.UI_TIMING))
            throw new IllegalArgumentException("Parallel host validation is admitted only for new UI 100/500 cases");
        Path home = Path.of(System.getProperty("turboism.home"));
        String fixtureName = required("fixtureName");
        String dataset = System.getProperty("turboism.validation.atlas.dataset", "geometry");
        if (!List.of("circle", "geometry", "heavy", "pprcut").contains(dataset)
                || (!HostUiProbe.UI_TIMING && dataset.equals("circle")))
            throw new IllegalArgumentException("Only fixed Circle/Geometry UI cases are admitted");
        boolean capturedFixture = dataset.equals("heavy") || dataset.equals("pprcut");
        String suffix = dataset.equals("pprcut") ? "Pprcut.cmo3" : dataset.equals("heavy") ? "heavy.cmo3"
            : "atlas_mapping_" + (dataset.equals("geometry") ? "geometry_" : "") + count + ".cmo3";
        if (capturedFixture != (count == 0)) throw new IllegalArgumentException("Captured fixture count must be discovered live");
        if (!fixtureName.endsWith(suffix)) throw new IllegalArgumentException("Unexpected task fixture name");
        evidence = home.resolve("state/atlas-validation");
        try { Files.createDirectories(evidence); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        if (capturedFixture) installLoadingDiagnostics();
        // Admission diagnostics are offline-only; final timing runs do not install temporary observers.
        HostUiProbe.premain(evidence.toString(), instrumentation);
        HostTimingProbe.premain(evidence.toString(), instrumentation);
        Thread worker = new Thread(() -> {
            try {
                run(count, implementation, fixtureName);
            } catch (Throwable failure) {
                try {
                    java.io.StringWriter stack = new java.io.StringWriter();
                    failure.printStackTrace(new java.io.PrintWriter(stack));
                    Files.writeString(evidence.resolve("driver-error.txt"), stack.toString());
                    Files.writeString(evidence.resolve("failure-threads.txt"), java.util.Arrays.toString(
                        java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)));
                    Files.writeString(evidence.resolve("ui-windows.txt"),
                        String.join("\n", new java.util.TreeSet<>(HostUiProbe.observedWindows)) + "\n");
                    atomic("result.txt", "status=FAIL\nreason=" + failure.getClass().getSimpleName() + "\n");
                } catch (Exception ignoredFailure) { failure.printStackTrace(); }
                // Manager owns timeout/cleanup; never force exit or terminate another process.
            }
        }, "atlas-queue-fixed-driver");
        worker.setDaemon(true);
        worker.start();
    }

    // Heavy-only, before the measured action: capture the actual exit caller and any
    // blocked loading thread without instrumenting the planner or changing UI state.
    private static void installLoadingDiagnostics() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> dumpThreads("shutdown-threads.txt"),
            "atlas-validation-shutdown-evidence"));
        Thread observer = new Thread(() -> {
            try {
                for (int sample = 1; sample <= 10; sample++) {
                    Thread.sleep(30_000);
                    if (Files.exists(evidence.resolve("layout-started.txt"))) return;
                    dumpThreads("loading-threads-" + sample + ".txt");
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "atlas-validation-loading-evidence");
        observer.setDaemon(true);
        observer.start();
    }

    private static void dumpThreads(String name) {
        try {
            StringBuilder output = new StringBuilder();
            Thread.getAllStackTraces().forEach((thread, stack) -> {
                output.append(thread.getName()).append(" ").append(thread.getState()).append('\n');
                for (StackTraceElement frame : stack) output.append("  at ").append(frame).append('\n');
                output.append('\n');
            });
            Files.writeString(evidence.resolve(name), output);
        } catch (Exception failure) { failure.printStackTrace(); }
    }

    static void markLayoutStarted(Path directory, String implementation) throws java.io.IOException {
        Files.writeString(directory.resolve("layout-started.txt"), implementation + "\n",
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
    }

    static void validateCase(int count, String implementation) {
        if (count == 0 && implementation.equals("polygon") && System.getProperty("turboism.validation.atlas.dataset", "").equals("heavy")) return;
        if (count != 100 && count != 500 && count != 1000 && count != 2500)
            throw new IllegalArgumentException("Only fixed Geometry 100/500/1000/2500 cases are admitted");
        if (!List.of("native", "new", "polygon", "nesting", "nesting-rect").contains(implementation))
            throw new IllegalArgumentException("Exactly one implementation per fresh task is required");
        if (count == 0 && !List.of("new", "nesting").contains(implementation))
            throw new IllegalArgumentException("Heavy is admitted only for BSSF and nesting");
    }

    static boolean requestedParallel() {
        String value = System.getProperty("turboism.validation.atlas.parallel", "false");
        if (!List.of("true", "false").contains(value))
            throw new IllegalArgumentException("Expected explicit boolean parallel mode");
        return Boolean.parseBoolean(value);
    }

    private static String required(String suffix) {
        String value = System.getProperty("turboism.validation.atlas." + suffix);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing Atlas " + suffix);
        return value;
    }

    private static void run(int count, String implementation, String fixtureName) throws Exception {
        long readyDeadline = System.nanoTime() + Duration.ofSeconds(count == 0 ? 600 : 240).toNanos();
        boolean ready = false;
        while (System.nanoTime() < readyDeadline) {
            checkErrors();
            if (Files.isRegularFile(evidence.resolve("timer-ready.txt"))) {
                if (tree().stream().anyMatch(row -> row[3].contains(fixtureName))) {
                    ready = true;
                    break;
                }
            }
            Thread.sleep(500);
        }
        require(ready, "Fixture/timer readiness timeout");
        click("编辑纹理集...", false, false);
        List<String[]> pages = tree().stream().filter(row -> row[1].strip().equals(
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.a.a") && row[2].equals("true/true")).toList();
        require(pages.size() == 1, "Expected one native texture-page tab pane");
        Files.writeString(evidence.resolve("texture-pages.txt"), render(pages));
        int page = Integer.parseInt(System.getProperty("turboism.validation.atlas.page", "0"));
        require(page >= 0, "Negative texture page");
        request("tab\t" + pages.get(0)[0] + "\t" + page);
        click("自动排版...", true, false);
        // The algorithm selector is contributed at dialog construction from the live
        // plugin registry: a dialog opened before plugin registration completes stays
        // native-only and never gains the combo, so close it and reopen until the
        // selector for this run's implementation appears. The dialog is rebuilt on
        // every open, so a fresh construction picks up the completed registry.
        String pluginLabel = implementation.equals("new") ? "MaxRects-BSSF"
            : implementation.equals("polygon") ? "Dalsoo" : implementation.equals("nesting") ? "Nesting Validation"
            : implementation.equals("nesting-rect") ? "Bitmask Nesting" : "";
        List<String[]> combos = List.of();
        long selectorDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < selectorDeadline) {
            awaitLayoutDialog();
            combos = algorithmSelectors();
            if (combos.size() == 1
                && (pluginLabel.isEmpty() || combos.get(0)[3].contains(pluginLabel))) break;
            click("Cancel", true, true);
            Thread.sleep(500);
            click("自动排版...", true, false);
        }
        require(combos.size() == 1
            && (pluginLabel.isEmpty() || combos.get(0)[3].contains(pluginLabel)),
            "Expected one algorithm selector");
        String[] combo = combos.get(0);
        String options = combo[3].split("; options=\\[", 2)[1];
        options = options.substring(0, options.length() - 1);
        List<String> nativeOptions = new ArrayList<>(List.of(options.split(", ")));
        nativeOptions.removeIf(option -> option.contains("MaxRects-BSSF") || option.contains("Dalsoo")
            || option.contains("Bitmask Nesting") || option.contains("Nesting Validation"));
        require(nativeOptions.size() == 1, "Ambiguous native algorithm");
        String selection = nativeOptions.get(0);
        if (implementation.equals("new")) selection = "MaxRects-BSSF";
        else if (implementation.equals("polygon"))
            selection = optionsList(options).stream().filter(option -> option.contains("Dalsoo"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Dalsoo algorithm option missing"));
        else if (implementation.equals("nesting")) selection = "Nesting Validation";
        else if (implementation.equals("nesting-rect"))
            selection = optionsList(options).stream().filter(option -> option.contains("Bitmask Nesting"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Nesting algorithm option missing"));
        request("select\t" + combo[0] + "\t" + selection);
        List<String[]> units = tree().stream().filter(row -> row[1].contains("JComboBox")
            && row[2].equals("true/true") && row[3].contains("options=[模型图像, 网格]")).toList();
        require(units.size() == 1, "Expected one native layout-unit selector");
        request("select\t" + units.get(0)[0] + "\t" + (requestedModelImage() ? "模型图像" : "网格"));
        if (implementation.equals("new")) {
            List<String[]> checks = tree().stream().filter(row -> row[1].contains("JCheckBox")
                && row[2].equals("true/true") && row[3].startsWith("启用并行搜索")).toList();
            require(checks.size() == 1, "Expected one enabled parallel checkbox");
            request("check\t" + checks.get(0)[0] + "\t" + requestedParallel());
        }
        if (count == 0 && implementation.equals("polygon") && System.getProperty("turboism.validation.atlas.dataset", "").equals("heavy")) {
            var rotations = tree().stream().filter(row -> row[1].contains("JComboBox") && row[2].equals("true/true")
                && row[3].contains("18 档角度")).toList();
            require(rotations.size() == 1, "Expected the 18-angle rotation selector");
            request("select\t" + rotations.get(0)[0] + "\t自由旋转（18 档角度）");
        }
        Files.writeString(evidence.resolve("layout-dialog.txt"), render(tree()));
        MemorySampler sampler = new MemorySampler();
        sampler.start();
        // The only layout trigger in this driver. No Undo loop, warmup, retry or second invocation.
        markLayoutStarted(evidence, implementation);
        if (Boolean.getBoolean("turboism.validation.atlas.diagnose")) {
            Thread observer = new Thread(() -> {
                try {
                    for (int sample = 1; sample <= 24; sample++) {
                        Thread.sleep(5_000);
                        if (Files.exists(evidence.resolve("timing-001.json"))) return;
                        dumpThreads("layout-threads-" + sample + ".txt");
                    }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }, "atlas-validation-layout-evidence");
            observer.setDaemon(true);
            observer.start();
        }
        click("OK", true, true);
        long deadline = System.nanoTime() + Duration.ofSeconds(layoutDeadlineSeconds(count)).toNanos();
        JsonNode result = null;
        while (System.nanoTime() < deadline) {
            checkErrors();
            if (HostUiProbe.UI_TIMING && HostUiProbe.progressClosed != 0) HostTimingProbe.finishAfterProgress();
            Path file = evidence.resolve("timing-001.json");
            if (Files.isRegularFile(file)) {
                try { result = JSON.readTree(file.toFile()); }
                catch (java.io.IOException incompleteWrite) { /* Probe writes outside the timer. */ }
                if (result != null) break;
            }
            Thread.sleep(200);
        }
        require(result != null, "Layout timing timeout");
        sampler.stop();
        if (count == 0 && implementation.equals("polygon") && System.getProperty("turboism.validation.atlas.dataset", "").equals("heavy")) {
            require(result.path("returned").asBoolean() && result.path("branch").asText().equals("handled"), "Heavy layout did not return through plugin");
            require(result.path("output").path("count").asInt() == 101, "Wrong heavy item count");
            require(result.path("output").path("overflow").isEmpty(), "Heavy overflow");
            require(result.path("output").path("finite").asBoolean() && result.path("output").path("layerMatch").asBoolean(), "Invalid heavy writeback");
            HostTimingProbe.validateHeavy(result);
        } else validateResult(result, count, implementation);
        if (implementation.equals("nesting")) NestingCaptureCheck.validate(evidence, result);
        if (HostUiProbe.UI_TIMING) validateUiTiming(result);
        require(!Files.exists(evidence.resolve("timing-002.json")), "Unexpected second layout invocation");
        atomic("memory-001.json", sampler.toJson());
        Files.writeString(evidence.resolve("ui-windows.txt"),
            String.join("\n", new java.util.TreeSet<>(HostUiProbe.observedWindows)) + "\n");
        atomic("validation.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        if (count == 0 && implementation.equals("polygon") && System.getProperty("turboism.validation.atlas.dataset", "").equals("heavy")) {
            Files.writeString(evidence.resolve("heavy-screenshot-ready"), "ready");
            long captureDeadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (!Files.exists(evidence.resolve("heavy-screenshot-captured")) && System.nanoTime() < captureDeadline)
                Thread.sleep(200);
        }
        // Cancel atlas editing rather than saving the source/project. Native application lifecycle only.
        click("Cancel", true, false); // Observed native atlas label, even in the Chinese locale.
        // Write before native exit: the JVM may terminate inside the Exit action.
        // This is only an algorithm assertion; the queue independently proves normal exit.
        atomic("result.txt", "status=PASS\nimplementation=" + implementation + "\ncount=" + count + "\ninvocations=1\n");
        click("退出", false, false);
    }

    static void validateResult(JsonNode result, int count, String implementation) {
        require(result.path("sequence").asInt(-1) == 1, "Expected first and only invocation");
        require(result.path("returned").isBoolean() && result.path("returned").booleanValue(), "Layout returned false/missing");
        require(result.path("branch").asText().equals(implementation.equals("native") ? "native" : "handled"), "Wrong actual branch");
        JsonNode input = result.path("input"), output = result.path("output");
        if (count == 0) {
            count = input.path("count").asInt(-1);
            require(count > 0, "Heavy fixture issued no items");
        }
        require(input.path("count").asInt(-1) == count && output.path("count").asInt(-1) == count, "Wrong issued current-page image count");
        require(input.path("items").isArray() && input.path("items").size() == count
            && output.path("items").isArray() && output.path("items").size() == count, "Incomplete snapshots");
        require(input.path("rotate").isBoolean() && input.path("rotate").booleanValue(), "Rotation must be enabled");
        require(input.path("modelImage").isBoolean()
            && input.path("modelImage").booleanValue() == requestedModelImage(), "Wrong native layout unit");
        require(input.path("requestedScale").isNumber() && Double.isFinite(input.path("requestedScale").doubleValue())
            && input.path("requestedScale").doubleValue() <= 0, "Expected native automatic-scale sentinel");
        for (String flag : implementation.equals("nesting") ? List.of("finite", "layerMatch") : List.of("finite", "inside", "layerMatch"))
            require(output.path(flag).isBoolean() && output.path(flag).booleanValue(), "Failed geometry: " + flag);
        // Nesting intentionally overlaps AABBs. Original-contour separation and
        // exact host matrices are checked independently by NestingCaptureCheck.
        if (!implementation.equals("nesting"))
            require(output.path("overlaps").isIntegralNumber() && output.path("overlaps").intValue() == 0, "Overlapping output");
        require(output.path("overflow").isArray() && output.path("overflow").isEmpty(), "Unexpected overflow in full-fit matrix");
        double scale = output.path("dataScale").asDouble(Double.NaN);
        double elapsed = result.path("methodMs").asDouble(Double.NaN);
        require(Double.isFinite(scale) && scale > 0 && scale <= 1, "Invalid automatic scale");
        require(Double.isFinite(elapsed) && elapsed > 0, "Invalid timing");
        for (String hash : List.of("inputHash", "outputHash"))
            require(result.path(hash).asText().matches("[0-9a-f]{64}"), "Missing snapshot hash");
        if (implementation.equals("new"))
            require(result.path("plannerParallel").isBoolean()
                && result.path("plannerParallel").booleanValue() == requestedParallel(), "Wrong actual planner parallel mode");
    }

    static void validateUiTiming(JsonNode result) {
        double total = result.path("uiActionToProgressClosedMs").asDouble(Double.NaN);
        double method = result.path("methodMs").asDouble(Double.NaN);
        double probe = result.path("uiInputProbeMs").asDouble(Double.NaN);
        double shown = result.path("uiActionToProgressShownMs").asDouble(Double.NaN);
        double tail = result.path("uiMethodReturnToProgressClosedMs").asDouble(Double.NaN);
        require(Double.isFinite(total) && total > 0 && Double.isFinite(probe) && probe >= 0
            && Double.isFinite(shown) && shown >= 0 && Double.isFinite(tail) && tail >= 0
            && total >= method + probe && total >= shown + method + tail, "Invalid UI timing components");
        require(result.path("uiOutputValidationDeferred").asBoolean(false), "Output validation must follow window close");
        require(result.path("uiProgressClass").asText().equals(HostUiProbe.PROGRESS_CLASS), "Wrong progress window");
    }

    private static void checkErrors() throws Exception {
        Path uiResult = evidence.resolve("ui-result.txt");
        if (Files.isRegularFile(uiResult) && Files.readString(uiResult).startsWith("ERROR"))
            throw new IllegalStateException(Files.readString(uiResult));
        for (String name : List.of("timer-error.txt", "ui-error.txt")) {
            Path path = evidence.resolve(name);
            if (Files.exists(path)) throw new IllegalStateException(Files.readString(path));
        }
    }

    private static void awaitLayoutDialog() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (tree().stream().anyMatch(row -> row[3].equals("自动排版"))) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Automatic layout dialog did not open");
    }

    // The algorithm combo is the only selector whose options carry a plugin label;
    // rotation/lock/scale/kernel combos added by polygon-capable algorithms do not.
    private static List<String[]> algorithmSelectors() throws Exception {
        return tree().stream().filter(row -> row[1].contains("JComboBox")
            && row[2].equals("true/true")
            && (row[3].contains("MaxRects-BSSF") || row[3].contains("Dalsoo"))).toList();
    }

    private static void request(String value) throws Exception {
        require(!Files.exists(evidence.resolve("ui-request.txt")), "Previous UI request not consumed");
        atomic("ui-request.txt", value);
        Thread.sleep(300);
        checkErrors();
    }

    private static List<String[]> tree() throws Exception {
        Path file = evidence.resolve("ui-tree.txt");
        Files.deleteIfExists(file);
        request("dump");
        // The real heavy fixture spends over two minutes loading on the EDT.
        // Keep one dump request pending until it can execute; do not enqueue
        // retries or treat model-load time as planner time.
        long deadline = System.nanoTime() + Duration.ofSeconds(required("count").equals("0") ? 300 : 15).toNanos();
        while (System.nanoTime() < deadline) {
            checkErrors();
            if (Files.isRegularFile(file)) {
                List<String[]> rows = Files.readAllLines(file).stream().map(line -> line.split("\t", -1))
                    .filter(row -> row.length == 4).toList();
                if (!rows.isEmpty()) return rows;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("UI tree timeout");
    }

    private static void click(String label, boolean visible, boolean last) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            List<String[]> rows = tree().stream().filter(row -> row[3].equals(label)
                && row[2].endsWith("/true") && (!visible || row[2].equals("true/true"))).toList();
            if (!rows.isEmpty()) {
                require(last || rows.size() == 1, "Ambiguous control: " + label);
                String command = HostUiProbe.UI_TIMING && label.equals("OK") && last ? "timed-click" : "click";
                request(command + "\t" + rows.get(last ? rows.size() - 1 : 0)[0] + "\t" + label);
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Control unavailable: " + label);
    }

    private static String render(List<String[]> rows) {
        return String.join("\n", rows.stream().map(row -> String.join("\t", row)).toList());
    }

    private static void atomic(String name, String value) throws Exception {
        Path temporary = evidence.resolve(name + ".tmp");
        Files.writeString(temporary, value);
        Files.move(temporary, evidence.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static List<String> optionsList(String options) {
        return new ArrayList<>(List.of(options.split(", ")));
    }

    private static boolean requestedModelImage() {
        String unit = System.getProperty("turboism.validation.atlas.unit", "mesh");
        require(List.of("mesh", "model-image").contains(unit), "Unknown native layout unit");
        return unit.equals("model-image");
    }

    private static long layoutDeadlineSeconds(int count) {
        String override = System.getProperty("turboism.validation.atlas.layoutDeadlineSeconds", "");
        if (!override.isBlank()) {
            long parsed = Long.parseLong(override);
            if (parsed < 60) throw new IllegalArgumentException("Layout deadline below 60s");
            return parsed;
        }
        return HostUiProbe.UI_TIMING ? 1800 : count == 2500 ? 21600 : 7200;
    }

    /** Polls JVM memory during the measured layout window; peaks land in memory-001.json. */
    private static final class MemorySampler implements Runnable {
        private static final long INTERVAL_MS = 50;
        private final java.lang.management.MemoryMXBean memory =
            java.lang.management.ManagementFactory.getMemoryMXBean();
        private final List<java.lang.management.GarbageCollectorMXBean> collectors =
            java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
        private final com.sun.management.OperatingSystemMXBean system =
            java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
        private final java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        private final Thread thread = new Thread(this, "atlas-memory-sampler");
        private volatile boolean running = true;
        private long startEpochMs, endEpochMs, samples;
        private long heapUsedFirst = -1, heapUsedPeak, heapCommittedPeak, nonHeapUsedPeak;
        private long committedVirtualPeak = -1, gcCountStart, gcTimeStart, gcCountPeak, gcTimePeak;

        void start() {
            gcCountStart = gcCount(); gcTimeStart = gcTime();
            startEpochMs = System.currentTimeMillis();
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            stopped.await(10, java.util.concurrent.TimeUnit.SECONDS);
            endEpochMs = System.currentTimeMillis();
        }

        @Override public void run() {
            try {
                while (running) {
                    sample();
                    Thread.sleep(INTERVAL_MS);
                }
                sample();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        }

        private void sample() {
            java.lang.management.MemoryUsage heap = memory.getHeapMemoryUsage();
            java.lang.management.MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
            samples++;
            if (heapUsedFirst < 0) heapUsedFirst = heap.getUsed();
            heapUsedPeak = Math.max(heapUsedPeak, heap.getUsed());
            heapCommittedPeak = Math.max(heapCommittedPeak, heap.getCommitted());
            nonHeapUsedPeak = Math.max(nonHeapUsedPeak, nonHeap.getUsed());
            if (system != null) {
                long committed = system.getCommittedVirtualMemorySize();
                if (committed >= 0) committedVirtualPeak = Math.max(committedVirtualPeak, committed);
            }
            gcCountPeak = Math.max(gcCountPeak, gcCount() - gcCountStart);
            gcTimePeak = Math.max(gcTimePeak, gcTime() - gcTimeStart);
        }

        private long gcCount() {
            long total = 0;
            for (var collector : collectors) if (collector.getCollectionCount() > 0) total += collector.getCollectionCount();
            return total;
        }

        private long gcTime() {
            long total = 0;
            for (var collector : collectors) if (collector.getCollectionTime() > 0) total += collector.getCollectionTime();
            return total;
        }

        String toJson() {
            StringBuilder json = new StringBuilder("{\n");
            json.append("  \"windowStartEpochMs\": ").append(startEpochMs).append(",\n");
            json.append("  \"windowEndEpochMs\": ").append(endEpochMs).append(",\n");
            json.append("  \"samples\": ").append(samples).append(",\n");
            json.append("  \"intervalMs\": ").append(INTERVAL_MS).append(",\n");
            json.append("  \"heapUsedFirstBytes\": ").append(heapUsedFirst).append(",\n");
            json.append("  \"heapUsedPeakBytes\": ").append(heapUsedPeak).append(",\n");
            json.append("  \"heapCommittedPeakBytes\": ").append(heapCommittedPeak).append(",\n");
            json.append("  \"nonHeapUsedPeakBytes\": ").append(nonHeapUsedPeak).append(",\n");
            json.append("  \"committedVirtualPeakBytes\": ").append(committedVirtualPeak).append(",\n");
            json.append("  \"gcCountDelta\": ").append(gcCountPeak).append(",\n");
            json.append("  \"gcTimeDeltaMs\": ").append(gcTimePeak).append(",\n");
            json.append("  \"maxHeapBytes\": ").append(Runtime.getRuntime().maxMemory()).append("\n}");
            return json.toString();
        }
    }
}
