package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.bootstrap.PreviewRuntimeLauncher.ResolvedHost;
import dev.turboism.preview.PreviewRuntime;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-agent entrypoint for the Turboism 0.1 Developer Preview.
 *
 * <p>Install-time hooks are no longer wired here: the agent scans the
 * {@code META-INF/turboism/hooks} manifest, lets each {@link HookContributor}
 * decide its own admission, and forwards install/bind/uninstall through the
 * shared protocol. Verified/fail-closed semantics live in the contributors
 * and their installers, not in this class.</p>
 */
public final class TurboismAgent {

    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<PreviewRuntime> RUNTIME = new AtomicReference<>();
    private static final AtomicReference<HookRegistry> HOOKS =
        new AtomicReference<>(new HookRegistry());

    @FunctionalInterface
    interface ShutdownHookRegistrar {
        void register(Thread hook);
    }

    private static final ShutdownHookRegistrar JVM_SHUTDOWN_HOOK_REGISTRAR =
        hook -> Runtime.getRuntime().addShutdownHook(hook);

    private TurboismAgent() {
    }

    /**
     * Entry point used when attached at JVM startup. This is the supported
     * mode: it runs before Cubism's own classes load, so transformers can
     * still see them.
     */
    public static void premain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            options,
            instrumentation,
            JVM_SHUTDOWN_HOOK_REGISTRAR
        );
    }

    /**
     * Entry point used when attached to an already-running JVM. Classes the
     * host has already loaded are past the transformers, so this mode starts
     * the runtime with a reduced set of hooks.
     */
    public static void agentmain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.AGENTMAIN,
            options,
            instrumentation,
            JVM_SHUTDOWN_HOOK_REGISTRAR
        );
    }

    static void requestStartForTesting(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation,
        final ShutdownHookRegistrar shutdownHookRegistrar
    ) {
        requestStart(attachmentMode, rawOptions, instrumentation, shutdownHookRegistrar);
    }

    private static void requestStart(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation,
        final ShutdownHookRegistrar shutdownHookRegistrar
    ) {
        if (!START_REQUESTED.compareAndSet(false, true)) {
            RuntimeDiagnostics.debug(
                "bootstrap",
                "Agent start ignored because the runtime was already requested"
            );
            return;
        }
        final AgentOptions options;
        try {
            options = AgentOptions.parse(rawOptions, AgentOptions.defaultHome());
        } catch (RuntimeException exception) {
            START_REQUESTED.set(false);
            System.out.println("Turboism agent options rejected: " + exception.getMessage());
            return;
        }
        try {
            shutdownHookRegistrar.register(new Thread(TurboismAgent::shutdown, "turboism-shutdown"));
        } catch (RuntimeException failure) {
            START_REQUESTED.set(false);
            System.err.println("Turboism agent start rejected: shutdown hook is unavailable");
            return;
        }
        JvmShims.install(attachmentMode, instrumentation, options);
        final HookEnvironment premainEnvironment = HookEnvironment.builder()
            .instrumentation(instrumentation)
            .options(options)
            .classPath(System.getProperty("java.class.path", ""))
            .workingDirectory(Path.of(System.getProperty("user.dir", ".")))
            .startupPolicy(dev.turboism.config.RuntimeStartupConfig.load(options.home()))
            .build();
        final boolean premain = MeshMirrorHookContributor.premainOnly(attachmentMode);
        final List<HookContributor> premainInstalled = new ArrayList<>();
        final List<HookContributor> deferred = new ArrayList<>();
        for (HookContributor contributor : loadHookManifest()) {
            if (contributor.phase() == HookContributor.Phase.PREMAIN) {
                if (premain && installPhaseHook(contributor, premainEnvironment)) {
                    premainInstalled.add(contributor);
                }
            } else {
                deferred.add(contributor);
            }
        }
        BootstrapThreadFactory.create(
            () -> start(
                options,
                instrumentation,
                List.copyOf(premainInstalled),
                List.copyOf(deferred)
            )
        ).start();
    }

    private static void start(
        final AgentOptions options,
        final Instrumentation instrumentation,
        final List<HookContributor> premainInstalled,
        final List<HookContributor> contributors
    ) {
        final List<HookContributor> bound = new ArrayList<>(premainInstalled);
        try {
            RuntimeDiagnostics.debug(
                "bootstrap",
                "Agent active; waiting for the Cubism host"
            );
            final Optional<ResolvedHost> located =
                PreviewRuntimeLauncher.resolveHost(instrumentation, options);
            if (located.isEmpty()) {
                System.out.println("Turboism agent stopped: Cubism host class was not observed");
                return;
            }
            final ResolvedHost resolved = located.orElseThrow();
            bound.addAll(installPhase(
                contributors,
                HookContributor.Phase.HOST_RESOLVED,
                environment(instrumentation, options, resolved, null)
            ));
            final VerifiedMeshMirrorHookInstaller meshMirrorHook =
                MeshMirrorHookContributor.CURRENT.get();
            final PreviewRuntime runtime = PreviewRuntimeLauncher.startPreviewRuntime(
                meshMirrorHook,
                () -> PreviewRuntimeLauncher.start(options, resolved)
            );
            if (!RUNTIME.compareAndSet(null, runtime)) {
                PreviewRuntimeLauncher.closeDuplicateRuntimeAndMeshMirrorHook(
                    runtime::close,
                    meshMirrorHook
                );
                return;
            }
            final HookEnvironment runtimeEnvironment =
                environment(instrumentation, options, resolved, runtime);
            for (HookContributor contributor : bound) {
                try {
                    contributor.bind(runtimeEnvironment);
                } catch (Throwable failure) {
                    runtimeWarn("Turboism hook binding disabled safely: " + contributor.id());
                }
            }
            installPhase(contributors, HookContributor.Phase.RUNTIME_STARTED, runtimeEnvironment);
            runtimeInfo(
                "Turboism Developer Preview started: host=" + runtime.hostState()
                    + ", plugins=" + runtime.loadReport().loaded().size()
                    + ", failures=" + runtime.loadReport().failures().size()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            runtimeWarn("Turboism bootstrap interrupted");
        } catch (Throwable failure) {
            // A failed runtime start must not leave the runtime-dependent hooks
            // installed: close everything this attempt enrolled after the host was
            // admitted. Premain host fixes stay until process exit, matching the
            // hand-wired agent's startup-failure teardown.
            HOOKS.get().closePhase(
                HookContributor.Phase.HOST_RESOLVED,
                TurboismAgent::runtimeWarn,
                TurboismAgent::runtimeInfo
            );
            final PreviewRuntime runtime = RUNTIME.get();
            if (runtime == null) {
                System.err.println(
                    "Turboism bootstrap failed safely: " + failure.getClass().getName()
                        + ": " + failure.getMessage()
                );
            } else {
                runtime.error("bootstrap", "Turboism bootstrap failed safely", failure);
            }
        }
    }

    private static List<HookContributor> installPhase(
        final List<HookContributor> contributors,
        final HookContributor.Phase phase,
        final HookEnvironment environment
    ) {
        final List<HookContributor> installed = new ArrayList<>();
        for (HookContributor contributor : contributors) {
            if (contributor.phase() == phase && installPhaseHook(contributor, environment)) {
                installed.add(contributor);
            }
        }
        return installed;
    }

    private static boolean installPhaseHook(
        final HookContributor contributor,
        final HookEnvironment environment
    ) {
        try {
            if (!contributor.admitted(environment)) {
                return false;
            }
            HOOKS.get().enroll(contributor, contributor.install(environment));
            return true;
        } catch (Throwable failure) {
            runtimeWarn("Turboism hook disabled safely: " + contributor.id());
            return false;
        }
    }

    private static HookEnvironment environment(
        final Instrumentation instrumentation,
        final AgentOptions options,
        final ResolvedHost resolved,
        final PreviewRuntime runtime
    ) {
        return HookEnvironment.builder()
            .instrumentation(instrumentation)
            .options(options)
            .host(resolved.host())
            .runtime(runtime)
            .profile(resolved.profile())
            .fullRuntimeAdmission(resolved.fullRuntimeAdmission())
            .safeMode(JvmShims.safeModeActive())
            .verificationDirectory(options.home().resolve("state").resolve("verification"))
            .build();
    }

    private static List<HookContributor> loadHookManifest() {
        try {
            return HookManifest.load(TurboismAgent.class.getClassLoader());
        } catch (HookManifest.HookManifestException failure) {
            RuntimeDiagnostics.warn(
                "bootstrap",
                "Turboism hooks disabled safely: " + failure.getMessage()
            );
            return List.of();
        }
    }

    private static void runtimeInfo(final String message) {
        final PreviewRuntime runtime = RUNTIME.get();
        if (runtime == null) {
            RuntimeDiagnostics.info("bootstrap", message);
        } else {
            runtime.info("bootstrap", message);
        }
    }

    private static void runtimeWarn(final String message) {
        final PreviewRuntime runtime = RUNTIME.get();
        if (runtime == null) {
            RuntimeDiagnostics.warn("bootstrap", message);
        } else {
            runtime.warn("bootstrap", message);
        }
    }

    private static void shutdown() {
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
        HOOKS.get().closeOnProcessExit(TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
        if (runtime == null) {
            return;
        }
        try {
            runtime.closeForProcessExit();
        } catch (Throwable failure) {
            System.err.println(
                "Turboism process-exit report cleanup failed safely: RUNTIME_CLOSE_FAILED"
            );
        }
    }

    static boolean shutdownForTesting() {
        JvmShims.closeAll(TurboismAgent::runtimeWarn);
        HOOKS.get().closeAll(TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime == null) {
            return false;
        }
        try {
            runtime.close();
        } catch (Throwable failure) {
            RuntimeDiagnostics.error(
                "bootstrap",
                "Shutdown hook failed safely: RUNTIME_CLOSE_FAILED",
                failure
            );
        }
        return true;
    }
}
