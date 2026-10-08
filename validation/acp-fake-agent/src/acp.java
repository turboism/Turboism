import acpagent.FakeAgent;

/**
 * Entry point for the ACP exact-host validation fake agent.
 *
 * <p>{@code AcpProcessTransport} hard-codes this default-package class name {@code acp} when the
 * {@code turboism.acp.validation.bridge} properties are present. The class only adapts stdio to
 * {@link FakeAgent}; all logic lives in the {@code acpagent} package.</p>
 */
public final class acp {

    private acp() {}

    public static void main(final String[] args) throws Exception {
        FakeAgent.main(args);
    }
}
