package dev.turboism.graal;

/** Child entrypoint that exercises GraalHostManager's ProcessBuilder environment policy. */
public final class ManagerEnvironmentIsolationProbe {

    private ManagerEnvironmentIsolationProbe() {
    }

    public static void main(final String[] args) throws Exception {
        final GraalHostConfiguration configuration = new GraalHostConfiguration(
            true,
            args[0],
            args[1],
            ChildEnvironmentProbe.class.getName(),
            5_000L
        );
        try (GraalHostManager manager = new GraalHostManager(configuration, ignored -> { })) {
            final GraalHostManager.TransportResult result = manager.submit(
                "environment-isolation", "", java.util.Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(10, java.util.concurrent.TimeUnit.SECONDS);
            System.out.print(result.status());
            if (result.status() != GraalHostManager.Status.SUCCEEDED
                || !"isolated".equals(result.output())) {
                System.exit(2);
            }
        }
    }

    public static final class ChildEnvironmentProbe {
        private ChildEnvironmentProbe() {
        }

        public static void main(final String[] args) throws Exception {
            final boolean inherited = Boolean.getBoolean("turboism.test.javaToolOptionsInherited")
                || Boolean.getBoolean("turboism.test.legacyJavaOptionsInherited")
                || Boolean.getBoolean("turboism.test.jdkJavaOptionsInherited");
            final java.io.BufferedWriter output = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(System.out, java.nio.charset.StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            final java.io.BufferedReader input = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)
            );
            final String run = input.readLine();
            final String marker = "\"executionId\":\"";
            final int start = run.indexOf(marker) + marker.length();
            final String executionId = run.substring(start, run.indexOf('"', start));
            output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + executionId
                + "\",\"status\":\"SUCCEEDED\",\"output\":\""
                + (inherited ? "inherited" : "isolated") + "\"}");
            output.newLine();
            output.flush();
        }
    }
}
