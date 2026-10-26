package dev.turboism.plugin.acp;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.mcp.McpStdioLaunch;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Test-only Linux sidecar. Uses the shipped ACP client and native harness adapters unchanged. */
public final class NativeObserve implements AcpListener {
    private final StringBuilder answer = new StringBuilder();
    private final List<Map<String, String>> tools = new ArrayList<>();
    private final List<Map<String, Object>> images = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected runtime, home, task, python, client");
        Path runtime = Path.of(args[0]);
        Path home = Path.of(args[1]);
        Path cwd = home.resolve("state/native-observe-private");
        Files.createDirectories(cwd);
        var config = Json.parseObject(Files.readAllBytes(runtime));
        var listener = new NativeObserve();
        var report = new LinkedHashMap<String, Object>();
        report.put("schemaVersion", 1);
        report.put("runId", args[2]);
        report.put("status", "FAIL");
        report.put("phase", "STARTUP");
        boolean claude = "claude".equals(config.get("harness"));
        boolean codex = "codex".equals(config.get("harness"));
        boolean editing = "keyform-edit".equals(config.get("workflow"));
        report.put("harness", codex ? "Codex native" : claude ? "Claude native" : "Pi native");
        report.put("version", config.get(codex ? "codexVersion" : claude ? "claudeVersion" : "piVersion"));
        report.put("modelMutation", editing ? "ONE_BOUNDED_ORDINARY_MESH_FORM" : "NONE");
        report.put("swingWindowVerified", false);
        var catalog = new Catalog();
        try {
            Files.deleteIfExists(home.resolve("state/native-observe-vision-ready.json"));
            String route = System.getenv("ANTHROPIC_BASE_URL");
            if (claude && route != null && (route.toLowerCase(java.util.Locale.ROOT).contains("opencode")
                    || route.toLowerCase(java.util.Locale.ROOT).contains("z.ai")
                    || route.toLowerCase(java.util.Locale.ROOT).contains("zhipu") || route.toLowerCase(java.util.Locale.ROOT).contains("bigmodel.cn")))
                throw new IllegalStateException("Previously unavailable model route excluded");
            var launch = launch(config, cwd);
            if (codex) { verifyCodexConfiguration(launch); report.put("configurationIsolationVerified", true); }
            var nativeTransport = new NativeAgentTransport(launch,
                    spec -> new ObservedTransport(AcpProcessTransport.start(spec), catalog));
            try (var client = new AcpClient(nativeTransport, listener)) {
                client.initialize(Duration.ofSeconds(30));
                var bridge = new McpStdioLaunch(args[3], List.of("-I", args[4], "--native-mcp-stdio", home.toString(), args[2]));
                var session = client.newSession(cwd,
                        new McpHttpConnection(URI.create("http://127.0.0.1:1/mcp"), "2025-11-25", bridge),
                        Duration.ofSeconds(40));
                if (codex) {
                    String requested = (String) config.get("model");
                    if (!"gpt-6.1-sol".equals(requested) || !catalog.visionModels().contains(requested))
                        throw new IllegalStateException("Requested Codex image model unavailable");
                    client.setConfigOption(session.sessionId(), "model", requested).result().get(20, TimeUnit.SECONDS);
                    client.setConfigOption(session.sessionId(), "effort", "medium").result().get(20, TimeUnit.SECONDS);
                    report.put("model", requested);
                    report.put("runtimeDeclaredImageInput", true);
                }
                if (claude || codex) {
                    report.put("phase", "VISION_CANARY");
                    // The CLI has no reviewed input-modality registry. Verify a fresh image, not a name heuristic.
                    var canary = VisionCanary.create(new java.security.SecureRandom());
                    Files.write(home.resolve("state/mcp-native-vision-canary.png"), Base64.getDecoder().decode(canary.image().data()));
                    Files.writeString(home.resolve("state/mcp-native-canary-expected.json"), Json.stringify(canary.expected()));
                    String canaryStop = client.prompt(session.sessionId(), VisionCanary.PROMPT,
                            List.of(canary.image())).get(60, TimeUnit.SECONDS);
                    Files.writeString(home.resolve("state/mcp-native-canary-answer.txt"), listener.answer.toString());
                    report.put("canaryAnswerMatched", canary.matches(listener.answer.toString()));
                    report.put("systemToolsVerified", !codex && catalog.claudeToolsSafe);
                    if (!"end_turn".equals(canaryStop) || (!codex && !catalog.claudeToolsSafe) || !listener.tools.isEmpty()
                            || (codex && catalog.codexRerouted) || !canary.matches(listener.answer.toString()))
                        throw new IllegalStateException("Claude image/tool isolation canary failed");
                    report.put("visionCanaryPassed", true);
                    report.put("canaryImageSha256", canary.imageSha256());
                    report.put("runtimeDeclaredImageInput", codex);
                    report.put("systemToolsVerified", !codex);
                    report.put("model", codex ? config.get("model") : catalog.claudeModel);
                    var ready = home.resolve("state/native-observe-vision-ready.json");
                    var temporary = ready.resolveSibling("native-observe-vision-ready.tmp");
                    Files.writeString(temporary, Json.stringify(Map.of("schemaVersion", 1, "harness", codex ? "codex" : "claude",
                            "visionCanaryPassed", true, "model", report.get("model"))));
                    Files.move(temporary, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    listener.answer.setLength(0);
                    listener.tools.clear();
                    listener.images.clear();
                } else {
                var model = session.option("model");
                var excluded = NativeAgentTransport.list(config.get("excludedVisionProviders")).stream()
                        .map(String::valueOf).toList();
                report.put("excludedVisionProviders", excluded);
                report.put("availableVisionModels", catalog.visionModels());
                String selected = catalog.chooseVision(model == null ? "" : model.currentValue(), excluded);
                report.put("initialModel", model == null ? "UNKNOWN" : model.currentValue());
                report.put("availableVisionModelCount", catalog.visionCount());
                if (selected.isEmpty()) throw new IllegalStateException("No runtime-declared available vision model");
                if (model == null || !selected.equals(model.currentValue()))
                    client.setConfigOption(session.sessionId(), "model", selected).result().get(20, TimeUnit.SECONDS);
                report.put("model", selected);
                report.put("runtimeDeclaredImageInput", true);
                }
                report.put("phase", editing ? "KEYFORM_EDIT" : "HOST_OBSERVATION");
                String instruction;
                if (editing) {
                    if (!codex) throw new IllegalStateException("Edit requires exact Codex harness");
                    String scope = Files.readString(home.resolve("state/mcp-native-edit-scope.json"));
                    instruction = "This is an authorized bounded write integration test on an owned synthetic colour fixture copy. "
                            + "Use only supplied MCP tools. Discover resources and read turboism://active/document. "
                            + "Your exact allowed scope is " + scope + ". "
                            + "Read turboism.authoring.meshes.read for this object with limit=512 (complete all pages). "
                            + "Identify the scoped independent non-default ordinary form, its complete native axis tuple, and ordered form_point rows. "
                            + "Call render.state/capture for a 512x512 baseline, using discovered expectedState. "
                            + "Plan exactly one vertex change: increase one x by a positive finite amount no greater than min(4, form x extent / 32). "
                            + "A 1-unit shift is suitable if within this bound. Preserve every y and all other vertices/forms/source/UV/topology. "
                            + "Construct the full positions array from actual rows, never invent identities or geometry. "
                            + "Call authoring.keyforms.preview with documentId/modelId/operationId from scope, targets=[{kind:art_mesh,objectId,snapshotId,edits:[{formGuid,coordinates,positions}]}]. "
                            + "Only after successful preview, call authoring.keyforms.write with the exact same request ONCE. "
                            + "If the write outcome is uncertain, query keyforms.result with the same operationId; never replay a write. "
                            + "Read meshes.read again without the old snapshotId, verify the actual changed vertex and unchanged others. "
                            + "Call render.state/capture again at 512x512. At most two captures total. "
                            + "Do not move parameters, add objects, save files or call history. The independent verifier handles target-pose images and Undo/Redo/final restoration. "
                            + "Report the actual before/after coordinate, operation outcome and capture IDs; distinguish current-pose pixels from stored-form readback.";
                } else {
                    instruction = "This is a read-only Turboism integration test. "
                            + "Use only the supplied MCP tools. First list the MCP resources and read turboism://active/document. "
                            + "Call turboism.render.state, then turboism.render.capture with its discovered expectedState "
                            + "and width=512,height=512 (no crop). Do not change any parameter or model. "
                            + "Inspect the actual returned image. In your final answer, briefly describe the visible subject, "
                            + "its main colors, and its location in the image. Distinguish what you see from metadata. "
                            + "If you cannot see the image, explicitly say so; do not infer appearance from object names. "
                            + "Include captureId, documentId, modelId and revision from the result. Do not call capture more than twice.";
                }
                var prompt = client.prompt(session.sessionId(), instruction);
                String stop;
                try { stop = prompt.get(claude ? 120 : 180, TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException timeout) {
                    client.cancel(session.sessionId());
                    try { prompt.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { /* Not a host cancellation claim. */ }
                    throw timeout;
                }
                report.put("stopReason", stop);
                if (claude && (!catalog.claudeToolsSafe || !catalog.claudeModel.equals(report.get("model"))))
                    throw new IllegalStateException("Claude runtime changed after the image canary");
                if ((codex && catalog.codexRerouted) || !"end_turn".equals(stop) || listener.images.isEmpty() || listener.answer.isEmpty())
                    throw new IllegalStateException("No completed image-bearing answer");
                report.put("status", "CAPTURE_DELIVERED_REVIEW_REQUIRED");
            }
        } catch (Exception failure) {
            // Never publish provider diagnostics, CLI config, endpoints, or credentials.
            report.put("failureType", failure.getClass().getSimpleName());
        } finally {
            report.put("tools", listener.tools);
            report.put("images", listener.images);
            report.put("answer", listener.answer.toString());
            report.put("modelVisionAccepted", false);
            report.put("providerHttp429Observed", catalog.http429);
            report.put("providerHttp401Observed", catalog.http401);
            if (claude) {
                report.put("systemToolsVerified", catalog.claudeToolsSafe);
                report.put("resolvedModel", catalog.claudeModel);
                report.put("declaredTools", catalog.claudeTools);
            }
            Files.writeString(home.resolve("state/mcp-native-observe-private.json"), Json.stringify(report));
        }
        if (!"CAPTURE_DELIVERED_REVIEW_REQUIRED".equals(report.get("status"))) System.exit(1);
    }

    static AgentLaunchSpec launch(Map<String, ?> config, Path cwd) {
        boolean claude = "claude".equals(config.get("harness"));
        if ("codex".equals(config.get("harness"))) {
            var command = new ArrayList<>(List.of((String) config.get("nodeExecutable"), (String) config.get("codexEntrypoint")));
            codexOverrides().forEach((key, value) -> { command.add("-c"); String encoded = "features".equals(key)
                    ? NativeAgentTransport.object(value).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(",", "{", "}")) : "mcp_servers".equals(key) ? "{}" : value instanceof String ? "\"" + value + "\"" : value.toString();
                command.add(key + "=" + encoded); });
            return new AgentLaunchSpec(command, cwd, Map.of(), AgentProtocol.CODEX);
        }
        return claude
                    ? new AgentLaunchSpec(List.of((String) config.get("claudeExecutable"),
                            "--restricted", "--setting-sources=", "--strict-mcp-config",
                            "--tools", "ListMcpResourcesTool,ReadMcpResourceTool",
                            "--allowedTools", "mcp__turboism__*", "ListMcpResourcesTool", "ReadMcpResourceTool",
                            "--disable-slash-commands", "--no-chrome", "--no-session-persistence",
                            "--settings", "{\"disableAllHooks\":true,\"enabledPlugins\":{}}",
                            "--max-budget-usd", "2"), cwd, Map.of(), AgentProtocol.CLAUDE)
                    : new AgentLaunchSpec(
                            List.of((String) config.get("nodeExecutable"), (String) config.get("piEntrypoint"),
                                    "--no-tools", "--no-extensions", "--no-skills", "--no-prompt-templates"),
                            cwd, Map.of(), AgentProtocol.PI);
    }

    static Map<String, Object> codexOverrides() {
        var flags = new LinkedHashMap<String, Object>();
        for (String name : List.of("shell_tool", "apply_patch_freeform", "view_image", "code_mode",
                "js_repl", "multi_agent", "multi_agent_v2", "apps", "connectors", "plugins", "remote_plugin", "hooks",
                "codex_hooks", "plugin_hooks", "computer_use", "browser_use", "memory_tool", "memories", "skill_search",
                "skill_mcp_dependency_install", "request_permissions_tool", "tool_suggest", "image_generation",
                "shell_snapshot", "shell_snapshot_v2")) flags.put(name, false);
        flags.put("skip_host_skill_discovery", true);
        flags.put("code_mode_host", true);
        return Map.of("features", flags, "model", "gpt-6.1-sol", "sandbox_mode", "read-only", "approval_policy", "on-request",
                "web_search", "disabled", "mcp_servers", Map.of(), "project_doc_max_bytes", 0,
                "memories.use_memories", false, "memories.generate_memories", false);
    }

    /** No prompt: verify effective config under the same argv/cwd, without publishing user config. */
    static void verifyCodexConfiguration(AgentLaunchSpec spec) throws Exception {
        var command = new ArrayList<>(spec.command()); command.add("app-server");
        var process = new ProcessBuilder(command).directory(spec.workingDirectory().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                var input = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                var output = new java.io.PrintWriter(process.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8);
                output.println(Json.stringify(Map.of("id", 1, "method", "initialize", "params",
                        Map.of("clientInfo", Map.of("name", "turboism_observer_preflight", "version", "1")))));
                boolean initialized = false;
                for (String line; (line = input.readLine()) != null;) {
                    var message = Json.parseObject(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (message.containsKey("error")) throw new IllegalStateException("Codex preflight failed");
                    if (Long.valueOf(1).equals(message.get("id")) && !initialized) {
                        initialized = true;
                        output.println("{\"method\":\"initialized\"}");
                        output.println("{\"id\":2,\"method\":\"config/read\",\"params\":{\"includeLayers\":false}}");
                    }
                    if (Long.valueOf(2).equals(message.get("id"))) {
                        verifyCodexConfig(NativeAgentTransport.object(NativeAgentTransport.object(message.get("result")).get("config")));
                        return true;
                    }
                }
                throw new IllegalStateException("Codex preflight closed");
            }).get(35, TimeUnit.SECONDS);
        } finally {
            process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            executor.shutdownNow();
        }
    }

    static void verifyCodexConfig(Map<String, Object> config) {
        var expected = codexOverrides();
        for (String key : List.of("model", "sandbox_mode", "approval_policy", "web_search", "mcp_servers", "project_doc_max_bytes")) {
            Object actual = config.get(key);
            if ("project_doc_max_bytes".equals(key) && actual instanceof Number number && number.longValue() == 0) continue;
            if (!expected.get(key).equals(actual)) throw new IllegalStateException("Codex isolation configuration mismatch");
        }
        var flags = NativeAgentTransport.object(config.get("features"));
        NativeAgentTransport.object(expected.get("features")).forEach((key, value) -> {
            if (!value.equals(flags.get(key))) throw new IllegalStateException("Codex tool feature mismatch");
        });
        var memories = NativeAgentTransport.object(config.get("memories"));
        if (!Boolean.FALSE.equals(memories.get("use_memories")) || !Boolean.FALSE.equals(memories.get("generate_memories")))
            throw new IllegalStateException("Codex memory isolation mismatch");
    }

    record VisionCanary(AcpAttachment image, Map<String, String> expected, String imageSha256) {
        static final String PROMPT = "Read the attached image, without calling any tools. "
                + "It has a six-digit code in a white strip above a 2-by-2 color grid. "
                + "Reply ONLY a JSON object with string fields code, topLeft, topRight, bottomLeft, bottomRight. "
                + "Use color names red, green, blue, yellow, cyan, magenta, orange, gray. "
                + "If you cannot see the actual image, say so instead of guessing.";
        static VisionCanary create(java.security.SecureRandom random) throws Exception {
            var names = new ArrayList<>(List.of("red", "green", "blue", "yellow", "cyan", "magenta", "orange", "gray"));
            var colors = Map.of("red", 0xff0000, "green", 0x00b000, "blue", 0x0000ff, "yellow", 0xffff00,
                    "cyan", 0x00ffff, "magenta", 0xff00ff, "orange", 0xff8000, "gray", 0x808080);
            java.util.Collections.shuffle(names, random);
            String code = Integer.toString(100000 + random.nextInt(900000));
            var pixels = new java.awt.image.BufferedImage(512, 384, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = pixels.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 512, 384);
                g.setColor(java.awt.Color.BLACK); g.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 60));
                g.drawString(code, 145, 70);
                for (int i = 0; i < 4; i++) {
                    g.setColor(new java.awt.Color(colors.get(names.get(i))));
                    g.fillRect((i % 2) * 256, 96 + (i / 2) * 144, 256, 144);
                }
            } finally { g.dispose(); }
            var image = AcpAttachment.fromClipboard(pixels);
            var data = Base64.getDecoder().decode(image.data());
            return new VisionCanary(image, Map.of("code", code, "topLeft", names.get(0), "topRight", names.get(1),
                    "bottomLeft", names.get(2), "bottomRight", names.get(3)),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        }
        boolean matches(String answer) {
            try { return Json.parseObject(answer.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(expected); }
            catch (RuntimeException failure) { return false; }
        }
    }

    /** Observes real Pi metadata without changing bytes or exposing credentials/configuration. */
    static final class Catalog {
        private final Map<String, Boolean> models = new LinkedHashMap<>();
        volatile boolean http429;
        volatile boolean http401;
        volatile boolean codexRerouted;
        volatile List<String> claudeTools = List.of();
        volatile boolean claudeToolsSafe;
        volatile String claudeModel = "";

        synchronized void line(byte[] bytes) {
            var message = NativeAgentTransport.object(Json.parseObject(bytes));
            if ("model/rerouted".equals(message.get("method"))) codexRerouted = true;
            var result = NativeAgentTransport.object(message.get("result"));
            if (result.get("data") instanceof List<?>) {
                for (Object raw : NativeAgentTransport.list(result.get("data"))) {
                    var entry = NativeAgentTransport.object(raw);
                    if (entry.get("model") instanceof String id && entry.containsKey("inputModalities"))
                        models.put(id, NativeAgentTransport.list(entry.get("inputModalities")).contains("image"));
                }
            }
            if ("system".equals(message.get("type")) && "init".equals(message.get("subtype"))) {
                claudeModel = NativeAgentTransport.text(message.get("model"));
                var declared = message.get("tools");
                claudeTools = NativeAgentTransport.list(declared).stream().map(String::valueOf).toList();
                claudeToolsSafe = declared instanceof List<?> tools && !claudeModel.isBlank()
                        && tools.stream().allMatch(tool -> tool instanceof String name
                                && (name.startsWith("mcp__turboism__")
                                    || List.of("ListMcpResourcesTool", "ReadMcpResourceTool").contains(name)));
            }
            if ("response".equals(message.get("type")) && "get_available_models".equals(message.get("command"))) {
                var data = NativeAgentTransport.object(message.get("data"));
                for (var raw : NativeAgentTransport.list(data.get("models"))) {
                    var model = NativeAgentTransport.object(raw);
                    String id = model.get("provider") + "/" + model.get("id");
                    models.put(id, NativeAgentTransport.list(model.get("input")).contains("image"));
                }
            }
            if ("result".equals(message.get("type")) && (Boolean.TRUE.equals(message.get("is_error"))
                    || NativeAgentTransport.text(message.get("subtype")).startsWith("error"))) {
                String failure = Json.stringify(message);
                http429 |= failure.matches("(?s).*\\b429\\b.*");
                http401 |= failure.matches("(?s).*\\b401\\b.*") || failure.contains("Invalid API key") || failure.contains("Not logged in");
            }
            if ("message_end".equals(message.get("type"))) {
                var value = NativeAgentTransport.object(message.get("message"));
                if ("error".equals(value.get("stopReason")) && String.valueOf(value.get("errorMessage")).matches("(?s).*\\b429\\b.*"))
                    http429 = true;
            }
        }

        synchronized long visionCount() { return models.values().stream().filter(Boolean.TRUE::equals).count(); }

        synchronized String chooseVision(String current) {
            return chooseVision(current, List.of());
        }

        synchronized List<String> visionModels() {
            return models.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        }

        synchronized String chooseVision(String current, List<String> excludedProviders) {
            var eligible = models.entrySet().stream().filter(Map.Entry::getValue)
                    .filter(e -> excludedProviders.stream().noneMatch(p -> e.getKey().startsWith(p + "/"))).toList();
            if (eligible.stream().anyMatch(e -> e.getKey().equals(current))) return current;
            String provider = current.contains("/") ? current.substring(0, current.indexOf('/') + 1) : "";
            return eligible.stream()
                    .sorted(java.util.Comparator.comparing(e -> !e.getKey().startsWith(provider)))
                    .map(Map.Entry::getKey).findFirst().orElse("");
        }
    }

    static final class ObservedTransport implements AcpTransport {
        private final AcpTransport delegate;
        private final java.io.InputStream observed;

        ObservedTransport(AcpTransport delegate, Catalog catalog) {
            this.delegate = delegate;
            observed = new java.io.FilterInputStream(delegate.stdout()) {
                private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                private void observe(int value) throws java.io.IOException {
                    if (value == '\n') {
                        if (line.size() > 0) catalog.line(line.toByteArray());
                        line.reset();
                    } else {
                        if (line.size() >= 8 * 1024 * 1024) throw new java.io.IOException("Observed Pi line exceeds budget");
                        line.write(value);
                    }
                }
                @Override public int read() throws java.io.IOException {
                    int value = in.read(); if (value >= 0) observe(value); return value;
                }
                @Override public int read(byte[] bytes, int offset, int size) throws java.io.IOException {
                    int count = in.read(bytes, offset, size);
                    for (int i = 0; i < count; i++) observe(bytes[offset + i] & 255);
                    return count;
                }
            };
        }
        public java.io.InputStream stdout() { return observed; }
        public java.io.InputStream stderr() { return delegate.stderr(); }
        public java.io.OutputStream stdin() { return delegate.stdin(); }
        public boolean isAlive() { return delegate.isAlive(); }
        public void terminate(Duration grace) { delegate.terminate(grace); }
        public void close() { delegate.close(); }
    }

    @Override public synchronized void agentText(AcpClient source, String session, String text) {
        if (answer.length() + text.length() > 16000) throw new IllegalStateException("Answer budget exceeded");
        answer.append(text);
    }

    @Override public synchronized void toolCall(AcpClient source, String session, String id, String title, String kind, String status) {
        if (tools.size() >= 64) throw new IllegalStateException("Tool budget exceeded");
        tools.add(Map.of("id", id, "title", title, "status", status));
    }

    @Override public synchronized void agentImage(AcpClient source, String session, AcpAttachment image) {
        try {
            var bytes = Base64.getDecoder().decode(image.data());
            var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (decoded == null || decoded.getWidth() != 512 || decoded.getHeight() != 512 || images.size() >= 2)
                throw new IllegalStateException("Invalid observed image");
            images.add(Map.of("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    "width", decoded.getWidth(), "height", decoded.getHeight(), "mimeType", image.mimeType(),
                    "name", image.name()));
        } catch (Exception failure) { throw new IllegalStateException("Image validation failed"); }
    }
}
