package dev.turboism.tests.validation;

import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedWorkspaceControlResolverFactory;
import dev.turboism.preview.PreviewRuntime;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.ui.workspace.WorkspaceCoordinator;
import dev.turboism.ui.workspace.WorkspaceHostProvider;
import dev.turboism.ui.workspace.WorkspaceHostProviderFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Disposable validation javaagent for the exact-host Workspace control run.
 *
 * <p>This agent is packaged separately from the production agent and only ever runs in the
 * task-scoped validation home. It waits boundedly for the production runtime to reach
 * {@code host=ACTIVE}, locates the exact Cubism host class through {@link Instrumentation},
 * builds the already-implemented exact {@link WorkspaceHostProvider} from the reviewed
 * workspace-control record, and injects it into the live production
 * {@link WorkspaceCoordinator}. The only reflective access is one private static holder field
 * ({@code TurboismAgent.RUNTIME}) of Turboism's own bootstrap; everything else uses public
 * Turboism API. It never reflects private Cubism implementation bodies and never bypasses
 * licensing. A connect/disconnect marker protocol lets the operator disconnect the provider so
 * stale plugin calls can be observed failing closed. Default admission requires the production
 * runtime to be ACTIVE; an explicit {@code allowDegradedRuntime=true} admits a runtime in FAILED
 * (never SAFE_MODE/CLOSED) for narrow workspace-slice diagnosis, with the observed host state
 * recorded loudly in the evidence so it cannot be mistaken for production readiness.</p>
 */
public final class WorkspaceValidationAgent {

    private static final String HOST_CLASS_NAME = "com.live2d.cubism.CEAppCtrl";
    private static final long POLL_INTERVAL_MILLIS = 500L;
    private static final long MARKER_INTERVAL_MILLIS = 1000L;
    private static final String VERIFICATION_RESOURCE_DIRECTORY = "/META-INF/turboism/verification/";
    private static final String CONNECT_MARKER = "validation-agent.connect";
    private static final String DISCONNECT_MARKER = "validation-agent.disconnect";
    private static final String EVIDENCE_FILE = "validation-agent.txt";

    private WorkspaceValidationAgent() {
    }

    /** Agent options; mirrors the production {@code AgentOptions} grammar. */
    record Options(
        Path home,
        String hostClassName,
        Duration timeout,
        Path recordOverride,
        boolean allowDegradedRuntime
    ) {

        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);

        static Options parse(final String rawOptions, final Path defaultHome) {
            final Map<String, String> values = new LinkedHashMap<>();
            if (rawOptions != null && !rawOptions.isBlank()) {
                for (String token : rawOptions.split(";")) {
                    if (token.isBlank()) {
                        continue;
                    }
                    final int separator = token.indexOf('=');
                    if (separator <= 0 || separator == token.length() - 1) {
                        throw new IllegalArgumentException("Agent option must be key=value: " + token);
                    }
                    final String key = token.substring(0, separator).trim();
                    final String value = token.substring(separator + 1).trim();
                    if (value.isBlank()) {
                        throw new IllegalArgumentException("Agent option value must not be blank: " + key);
                    }
                    if (!key.equals("home") && !key.equals("hostClass") && !key.equals("timeoutSeconds")
                        && !key.equals("workspaceControlRecord")
                        && !key.equals("allowDegradedRuntime")) {
                        throw new IllegalArgumentException("Unknown validation agent option: " + key);
                    }
                    if (values.putIfAbsent(key, value) != null) {
                        throw new IllegalArgumentException("Duplicate validation agent option: " + key);
                    }
                }
            }
            final Path home = Path.of(values.getOrDefault("home", defaultHome.toString()))
                .toAbsolutePath().normalize();
            final String hostClass = values.getOrDefault("hostClass", HOST_CLASS_NAME);
            if (hostClass.isBlank()) {
                throw new IllegalArgumentException("hostClass must not be blank");
            }
            final long timeoutSeconds;
            try {
                timeoutSeconds = Long.parseLong(values.getOrDefault(
                    "timeoutSeconds", Long.toString(DEFAULT_TIMEOUT.toSeconds())
                ));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("timeoutSeconds must be an integer", exception);
            }
            if (timeoutSeconds < 1 || timeoutSeconds > 600) {
                throw new IllegalArgumentException("timeoutSeconds must be between 1 and 600");
            }
            final Path record = values.containsKey("workspaceControlRecord")
                ? Path.of(values.get("workspaceControlRecord")).toAbsolutePath().normalize()
                : null;
            final boolean allowDegradedRuntime;
            if (values.containsKey("allowDegradedRuntime")) {
                final String raw = values.get("allowDegradedRuntime");
                if (raw.equals("true")) {
                    allowDegradedRuntime = true;
                } else if (raw.equals("false")) {
                    allowDegradedRuntime = false;
                } else {
                    throw new IllegalArgumentException(
                        "allowDegradedRuntime must be true or false: " + raw);
                }
            } else {
                allowDegradedRuntime = false;
            }
            return new Options(
                home, hostClass, Duration.ofSeconds(timeoutSeconds), record, allowDegradedRuntime
            );
        }
    }

    /** Pure host-state admission decision for the validation run; never relabels the state. */
    /** Pure host-state admission decision; {@code degraded} is true only when FAILED was admitted. */
    record HostAdmission(boolean allowed, boolean degraded, String reason) {
    }

    /**
     * Default behavior requires ACTIVE. Only an explicit {@code allowDegradedRuntime=true} may
     * admit an already-created runtime in FAILED. SAFE_MODE and CLOSED are never admissible.
     * Degraded classification follows the admitted state (FAILED), never the option flag, so an
     * ACTIVE run with the option enabled is recorded as normal.
     */
    static HostAdmission admitHostState(
        final dev.turboism.adapter.host.HostSession.State state,
        final boolean allowDegradedRuntime
    ) {
        return switch (state) {
            case ACTIVE -> new HostAdmission(true, false, "host=ACTIVE");
            case FAILED -> allowDegradedRuntime
                ? new HostAdmission(true, true, "degraded mode: host=FAILED")
                : new HostAdmission(false, false,
                    "host=FAILED requires allowDegradedRuntime=true");
            case SAFE_MODE, CLOSED -> new HostAdmission(
                false, false, "host state " + state + " is never admissible");
        };
    }

    public static void premain(final String rawOptions, final Instrumentation instrumentation) {
        final Path defaultHome;
        final String configured = System.getProperty("turboism.home");
        if (configured != null && !configured.isBlank()) {
            defaultHome = Path.of(configured).toAbsolutePath().normalize();
        } else {
            defaultHome = Path.of("turboism-validation").toAbsolutePath().normalize();
        }
        final Options options;
        try {
            options = Options.parse(rawOptions, defaultHome);
        } catch (RuntimeException exception) {
            System.err.println("Turboism workspace validation agent options rejected: "
                + exception.getMessage());
            return;
        }
        final Thread worker = new Thread(
            () -> run(options, instrumentation),
            "turboism-workspace-validation-agent"
        );
        worker.setDaemon(true);
        worker.start();
    }

    /** The exact runtime/state pair that passed admission; the state is never re-read later. */
    private record AdmittedRuntime(PreviewRuntime runtime, dev.turboism.adapter.host.HostSession.State state) {
    }

    private static void run(final Options options, final Instrumentation instrumentation) {
        final Evidence evidence = new Evidence(options.home().resolve("state"), EVIDENCE_FILE);
        try {
            evidence.write(Evidence.State.WAITING, "awaiting Turboism runtime in an admissible host state");
            final AdmittedRuntime admitted = awaitRuntime(options, evidence);
            if (admitted == null) {
                return;
            }
            final PreviewRuntime runtime = admitted.runtime();
            final dev.turboism.adapter.host.HostSession.State admittedState = admitted.state();
            // Actual degraded mode is defined by the admitted state, never by the option flag:
            // an ACTIVE run with allowDegradedRuntime=true is recorded as a normal ACTIVE run.
            final boolean degraded = admittedState == dev.turboism.adapter.host.HostSession.State.FAILED;
            final LocatedHost host = locateHost(instrumentation, options.hostClassName());
            if (host == null) {
                evidence.fail("Cubism host class " + options.hostClassName() + " was not observed");
                return;
            }
            final HostArtifactDigest digest = HostArtifactDigest.from(host.artifact());
            final String profile = EditorModelVerificationManifest.resourceProfileForArtifact(digest);
            final Path record = resolveRecord(options, profile);
            evidence.recordIdentity(profile, host, digest, record, admittedState, degraded);
            final VerifiedMemberResolver resolver = new VerifiedWorkspaceControlResolverFactory()
                .create(record, host.artifact(), host.classLoader());
            final CountingWorkspaceHostProvider provider =
                new CountingWorkspaceHostProvider(WorkspaceHostProviderFactory.create(resolver));
            final WorkspaceCoordinator coordinator = runtime.hostAccess().workspaceCoordinator();

            evidence.event("provider=" + provider.description()
                + " coordinator=" + coordinator.getClass().getName());
            // Deterministic baseline: the provider is created READY but DISCONNECTED. No host
            // mutation is possible until the operator places the explicit connect marker, so
            // pre-connect probe commands deterministically observe typed UNAVAILABLE.
            provider.markConnected(false);
            evidence.write(Evidence.State.DISCONNECTED, degraded
                ? "DEGRADED mode: production hostState=" + admittedState
                    + "; provider ready; awaiting explicit connect marker"
                : "provider ready; awaiting explicit connect marker");
            awaitMarkers(options.home(), coordinator, provider, evidence);
        } catch (Throwable failure) {
            evidence.fail(failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /**
     * Waits boundedly for an already-created production runtime in an admissible host state.
     * ACTIVE always admits; FAILED admits only with the explicit degraded option; SAFE_MODE
     * keeps polling (it may transition) and CLOSED fails fast; the timeout fails closed and
     * records the last observed state without relabeling it.
     */
    private static AdmittedRuntime awaitRuntime(final Options options, final Evidence evidence)
        throws InterruptedException {
        final long deadline = System.nanoTime() + options.timeout().toNanos();
        dev.turboism.adapter.host.HostSession.State lastObserved = null;
        do {
            final PreviewRuntime runtime = runtimeOrNull();
            if (runtime != null) {
                final dev.turboism.adapter.host.HostSession.State state = runtime.hostState();
                lastObserved = state;
                final HostAdmission admission = admitHostState(state, options.allowDegradedRuntime());
                if (admission.allowed()) {
                    evidence.event("production runtime admitted: " + admission.reason());
                    return new AdmittedRuntime(runtime, state);
                }
                if (state == dev.turboism.adapter.host.HostSession.State.CLOSED) {
                    evidence.fail("production runtime is CLOSED; not admissible");
                    return null;
                }
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } while (System.nanoTime() < deadline);
        evidence.fail("Turboism runtime did not reach an admissible host state within "
            + options.timeout().toSeconds() + " seconds; lastObserved=" + lastObserved
            + " allowDegradedRuntime=" + options.allowDegradedRuntime());
        return null;
    }

    /**
     * Sole reflective access: Turboism's own private static runtime holder. Reading it fails
     * closed (reported, never silently skipped).
     */
    private static PreviewRuntime runtimeOrNull() {
        try {
            final Class<?> agentClass = Class.forName("dev.turboism.bootstrap.TurboismAgent");
            final java.lang.reflect.Field field = agentClass.getDeclaredField("RUNTIME");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            final AtomicReference<PreviewRuntime> holder =
                (AtomicReference<PreviewRuntime>) field.get(null);
            return holder.get();
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new IllegalStateException(
                "Turboism runtime holder is not readable: " + exception.getMessage(),
                exception
            );
        }
    }

    private static LocatedHost locateHost(final Instrumentation instrumentation, final String name) {
        for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!loaded.getName().equals(name)) {
                continue;
            }
            final ClassLoader classLoader = loaded.getClassLoader();
            if (classLoader == null) {
                throw new IllegalStateException("Cubism host class was loaded by the bootstrap classloader");
            }
            final Path artifact;
            try {
                artifact = Path.of(
                    loaded.getProtectionDomain().getCodeSource().getLocation().toURI()
                ).toAbsolutePath().normalize();
            } catch (Exception exception) {
                throw new IllegalStateException("Cubism host artifact path is invalid", exception);
            }
            return new LocatedHost(classLoader, artifact);
        }
        return null;
    }

    private static Path resolveRecord(final Options options, final String profile) throws IOException {
        if (options.recordOverride() != null) {
            return options.recordOverride();
        }
        final String resource = VERIFICATION_RESOURCE_DIRECTORY + "cubism-" + profile + "-workspace-control.json";
        final Path target = options.home().resolve("state").resolve("verification")
            .resolve("cubism-" + profile + "-workspace-control.json");
        Files.createDirectories(target.getParent());
        try (InputStream source = WorkspaceValidationAgent.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IOException("Embedded workspace-control verification record is missing: " + resource);
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void awaitMarkers(
        final Path home,
        final WorkspaceCoordinator coordinator,
        final CountingWorkspaceHostProvider provider,
        final Evidence evidence
    ) throws InterruptedException, IOException {
        final Path state = home.resolve("state");
        long refreshCounter = 0;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final boolean disconnected = Files.exists(state.resolve(DISCONNECT_MARKER));
                final boolean connected = Files.exists(state.resolve(CONNECT_MARKER));
                if (disconnected && connected) {
                    // Ambiguity fails closed: consume both markers, actively disconnect if
                    // needed, and remain DISCONNECTED. Never retain CONNECTED on ambiguity.
                    Files.deleteIfExists(state.resolve(DISCONNECT_MARKER));
                    Files.deleteIfExists(state.resolve(CONNECT_MARKER));
                    if (provider.isConnected()) {
                        coordinator.disconnect(provider);
                        provider.markConnected(false);
                    }
                    evidence.event("both connect/disconnect markers consumed; remaining disconnected");
                    evidence.write(Evidence.State.DISCONNECTED, "ambiguous both-markers consumed; disconnected");
                } else if (disconnected) {
                    Files.deleteIfExists(state.resolve(DISCONNECT_MARKER));
                    if (provider.isConnected()) {
                        coordinator.disconnect(provider);
                        provider.markConnected(false);
                        evidence.event("disconnect marker consumed");
                        evidence.write(Evidence.State.DISCONNECTED, "provider disconnected on marker");
                    } else {
                        evidence.write(Evidence.State.DISCONNECTED, "marker ignored: already disconnected");
                    }
                } else if (connected) {
                    Files.deleteIfExists(state.resolve(CONNECT_MARKER));
                    if (!provider.isConnected()) {
                        coordinator.connect(provider);
                        provider.markConnected(true);
                        evidence.event("connect marker consumed");
                        evidence.write(Evidence.State.CONNECTED, "provider connected on marker");
                    } else {
                        evidence.write(Evidence.State.CONNECTED, "marker ignored: already connected");
                    }
                } else if (refreshCounter++ % 10 == 0) {
                    evidence.write(
                        provider.isConnected() ? Evidence.State.CONNECTED : Evidence.State.DISCONNECTED,
                        "counts=" + provider.counts()
                    );
                }
                Thread.sleep(MARKER_INTERVAL_MILLIS);
            }
        } finally {
            if (provider.isConnected()) {
                coordinator.disconnect(provider);
                provider.markConnected(false);
            }
        }
    }

    private record LocatedHost(ClassLoader classLoader, Path artifact) {
    }

    /** Wraps the exact provider to count calls and record the executing thread and EDT status. */
    static final class CountingWorkspaceHostProvider implements WorkspaceHostProvider {

        private final WorkspaceHostProvider delegate;
        private final AtomicLong readCalls = new AtomicLong();
        private final AtomicLong switchCalls = new AtomicLong();
        private final AtomicLong updateCalls = new AtomicLong();
        private final AtomicLong resetCalls = new AtomicLong();
        private final AtomicLong onEdtCalls = new AtomicLong();
        private final AtomicLong offEdtCalls = new AtomicLong();
        private final AtomicReference<String> lastCallThread = new AtomicReference<>("");
        private volatile boolean lastCallOnEdt;
        private volatile boolean connected;

        CountingWorkspaceHostProvider(final WorkspaceHostProvider delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public WorkspaceStatus readStatus() {
            recordCall();
            readCalls.incrementAndGet();
            return delegate.readStatus();
        }

        @Override
        public WorkspaceOperationResult.Outcome switchTo(final WorkspaceId workspaceId) {
            recordCall();
            switchCalls.incrementAndGet();
            return delegate.switchTo(workspaceId);
        }

        @Override
        public WorkspaceOperationResult.Outcome updateDefault() {
            recordCall();
            updateCalls.incrementAndGet();
            return delegate.updateDefault();
        }

        @Override
        public WorkspaceOperationResult.Outcome resetToDefault() {
            recordCall();
            resetCalls.incrementAndGet();
            return delegate.resetToDefault();
        }

        private void recordCall() {
            final Thread thread = Thread.currentThread();
            lastCallThread.set(thread.getName());
            lastCallOnEdt = SwingUtilities.isEventDispatchThread();
            if (lastCallOnEdt) {
                onEdtCalls.incrementAndGet();
            } else {
                offEdtCalls.incrementAndGet();
            }
        }

        void markConnected(final boolean value) {
            connected = value;
        }

        boolean isConnected() {
            return connected;
        }

        String counts() {
            return "read=" + readCalls.get()
                + " switch=" + switchCalls.get()
                + " update=" + updateCalls.get()
                + " reset=" + resetCalls.get()
                + " onEdt=" + onEdtCalls.get()
                + " offEdt=" + offEdtCalls.get()
                + " lastThread=" + lastCallThread.get()
                + " lastOnEdt=" + lastCallOnEdt;
        }

        String description() {
            return delegate.getClass().getName();
        }
    }

    /** Small key=value evidence writer; identity is preserved and state is rewritten atomically. */
    static final class Evidence {

        enum State {
            WAITING, CONNECTED, DISCONNECTED, FAILED
        }

        private static final Pattern SAFE_VALUE = Pattern.compile("[\\x20-\\x7E]{0,2000}");

        private final Path target;
        private String identityBlock = "";
        private final StringBuilder eventLog = new StringBuilder();

        Evidence(final Path stateDir, final String fileName) {
            this.target = stateDir.resolve(fileName);
        }

        void event(final String line) {
            eventLog.append("time=").append(Instant.now()).append(" ").append(line).append('\n');
        }

        void write(final State state, final String detail) throws IOException {
            final StringBuilder text = new StringBuilder();
            if (!identityBlock.isEmpty()) {
                text.append(identityBlock);
            }
            text.append("status=").append(state).append('\n');
            text.append("time=").append(Instant.now()).append('\n');
            text.append("detail=").append(sanitize(detail)).append('\n');
            if (!eventLog.isEmpty()) {
                text.append("events:\n").append(eventLog);
            }
            Files.createDirectories(target.getParent());
            final Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }

        void fail(final String detail) {
            try {
                write(State.FAILED, detail);
            } catch (IOException ignored) {
                // The validation home is not writable; nothing more can be recorded.
            }
        }

        void recordIdentity(
            final String profile,
            final LocatedHost host,
            final HostArtifactDigest digest,
            final Path record,
            final dev.turboism.adapter.host.HostSession.State hostState,
            final boolean degradedMode
        ) throws IOException {
            final StringBuilder identity = new StringBuilder();
            identity.append("profile=cubism-").append(profile).append('\n');
            identity.append("artifact=").append(host.artifact()).append('\n');
            identity.append("artifactSize=").append(digest.size()).append('\n');
            identity.append("artifactSha256=").append(digest.sha256()).append('\n');
            identity.append("record=").append(record).append('\n');
            identity.append("recordSha256=").append(sha256(record)).append('\n');
            identity.append("hostClassLoader=").append(host.classLoader().toString()).append('\n');
            // Loud, durable host-state record: a FAILED run must never be mistaken for
            // production readiness. These lines persist in every evidence rewrite.
            identity.append("hostState=").append(hostState).append('\n');
            identity.append("degradedMode=").append(degradedMode).append('\n');
            identityBlock = identity.toString();
        }

        private static String sanitize(final String value) {
            final String sanitized = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
            return SAFE_VALUE.matcher(sanitized).matches() ? sanitized : "unprintable-detail-redacted";
        }

        private static String sha256(final Path path) throws IOException {
            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    final byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                    }
                }
                final StringBuilder hex = new StringBuilder();
                for (final byte value : digest.digest()) {
                    hex.append(String.format("%02x", value & 0xFF));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }
}
