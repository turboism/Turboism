package dev.turboism.plugin.acp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class NativeObserveSelfTest {
    public static void main(String[] args) throws Exception {
        String catalog = "{\"type\":\"response\",\"command\":\"get_available_models\",\"data\":{\"models\":["
                + "{\"provider\":\"p\",\"id\":\"text\",\"input\":[\"text\"]},"
                + "{\"provider\":\"other\",\"id\":\"vision\",\"input\":[\"text\",\"image\"]},"
                + "{\"provider\":\"p\",\"id\":\"视觉\",\"input\":[\"text\",\"image\"]}]}}\n";
        String errors = "{\"type\":\"message_end\",\"message\":{\"stopReason\":\"error\",\"errorMessage\":\"HTTP 429\"}}\n";
        byte[] bytes = (catalog + errors).getBytes(StandardCharsets.UTF_8);
        var delegate = new AcpTransport() {
            public InputStream stdout() { return new ByteArrayInputStream(bytes); }
            public InputStream stderr() { return InputStream.nullInputStream(); }
            public OutputStream stdin() { return OutputStream.nullOutputStream(); }
            public boolean isAlive() { return true; }
            public void terminate(Duration grace) {}
            public void close() {}
        };
        var observed = new NativeObserve.Catalog();
        var wrapped = new NativeObserve.ObservedTransport(delegate, observed);
        var output = new ByteArrayOutputStream();
        byte[] fragment = new byte[7];
        for (int n; (n = wrapped.stdout().read(fragment)) >= 0;) output.write(fragment, 0, n);
        if (!java.util.Arrays.equals(bytes, output.toByteArray())) throw new AssertionError("Changed protocol bytes");
        if (observed.visionCount() != 2 || !observed.chooseVision("p/text").equals("p/视觉")
                || !observed.chooseVision("other/vision").equals("other/vision") || !observed.http429)
            throw new AssertionError("Incorrect runtime capability or error observation");
        if (!new NativeObserve.Catalog().chooseVision("unknown/unknown").isEmpty()) throw new AssertionError("Guessed vision");
        if (!observed.chooseVision("p/视觉", java.util.List.of("p")).equals("other/vision")
                || !observed.chooseVision("p/视觉", java.util.List.of("p", "other")).isEmpty())
            throw new AssertionError("Ignored explicit provider exclusions");
        var launch = NativeObserve.launch(java.util.Map.of("harness", "claude", "claudeExecutable", "/reviewed/claude"),
                java.nio.file.Path.of("/tmp/observer-selftest-never-launched"));
        if (launch.protocol() != AgentProtocol.CLAUDE || launch.command().stream().anyMatch(String::isBlank)
                || !launch.command().contains("--setting-sources=") || !launch.command().contains("--strict-mcp-config")
                || !launch.command().contains("ListMcpResourcesTool,ReadMcpResourceTool"))
            throw new AssertionError("Unsafe or invalid Claude launch");
        var claude = new NativeObserve.Catalog();
        claude.line("{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"actual-model\",\"tools\":[\"ListMcpResourcesTool\",\"ReadMcpResourceTool\",\"mcp__turboism__render\"]}".getBytes(StandardCharsets.UTF_8));
        if (!claude.claudeToolsSafe || !"actual-model".equals(claude.claudeModel)) throw new AssertionError("Rejected isolated tools");
        claude.line("{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"actual-model\",\"tools\":[\"Bash\"]}".getBytes(StandardCharsets.UTF_8));
        if (claude.claudeToolsSafe) throw new AssertionError("Accepted built-in execution");
        claude.line("{\"type\":\"system\",\"subtype\":\"init\",\"model\":\"actual-model\"}".getBytes(StandardCharsets.UTF_8));
        if (claude.claudeToolsSafe) throw new AssertionError("Guessed tool availability");
        var codex = NativeObserve.launch(java.util.Map.of("harness", "codex", "nodeExecutable", "/reviewed/node",
                "codexEntrypoint", "/reviewed/codex.js"), java.nio.file.Path.of("/tmp/observer-selftest-never-launched"));
        if (codex.protocol() != AgentProtocol.CODEX || codex.command().size() > 64
                || codex.command().stream().noneMatch(v -> v.startsWith("features={") && v.contains("shell_tool=false") && v.contains("view_image=false") && v.contains("code_mode_host=true"))
                || !codex.command().contains("mcp_servers={}")) throw new AssertionError("Invalid Codex isolation argv");
        var safeConfig = new java.util.LinkedHashMap<String, Object>(NativeObserve.codexOverrides());
        safeConfig.put("memories", java.util.Map.of("use_memories", false, "generate_memories", false));
        NativeObserve.verifyCodexConfig(safeConfig);
        for (String key : java.util.List.of("sandbox_mode", "web_search", "mcp_servers", "features", "memories")) {
            var invalid = new java.util.LinkedHashMap<>(safeConfig); invalid.remove(key);
            try { NativeObserve.verifyCodexConfig(invalid); throw new AssertionError("Accepted missing " + key); }
            catch (IllegalStateException expected) { }
        }
        var codexCatalog = new NativeObserve.Catalog();
        codexCatalog.line("{\"id\":3,\"result\":{\"data\":[{\"model\":\"gpt-6.1-sol\",\"inputModalities\":[\"text\",\"image\"]},{\"model\":\"unknown\"}]}}".getBytes(StandardCharsets.UTF_8));
        if (!codexCatalog.visionModels().equals(java.util.List.of("gpt-6.1-sol"))) throw new AssertionError("Guessed Codex modality");
        codexCatalog.line("{\"method\":\"model/rerouted\",\"params\":{}}".getBytes(StandardCharsets.UTF_8));
        if (!codexCatalog.codexRerouted) throw new AssertionError("Ignored Codex model reroute");
        var canary = NativeObserve.VisionCanary.create(new java.security.SecureRandom());
        if (!canary.matches(dev.turboism.sdk.json.Json.stringify(canary.expected()))
                || canary.matches("I cannot see images") || canary.matches("{}")) throw new AssertionError("Canary validation failed");
        var guessed = new java.util.LinkedHashMap<>(canary.expected()); guessed.put("code", "000000");
        if (canary.matches(dev.turboism.sdk.json.Json.stringify(guessed))) throw new AssertionError("Accepted wrong image code");
        System.out.println("PASS: fragmented native metadata, unchanged stream, vision selection, unknown rejection, provider 429, Claude tool isolation, Codex argv/config/modality/reroute and random image canary");
    }
}
