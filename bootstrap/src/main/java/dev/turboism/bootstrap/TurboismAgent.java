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
import java.util.function.Consumer;

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

    static void requestStartForTesting(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation,
        final ShutdownHookRegistrar shutdownHookRegistrar,
        final Consumer<Runnable> bootstrapThreadStarter
    ) {
        requestStart(
            attachmentMode,
            rawOptions,
            instrumentation,
            shutdownHookRegistrar,
            bootstrapThreadStarter
        );
    }

    private static void requestStart(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation,
        final ShutdownHookRegistrar shutdownHookRegistrar
    ) {
        requestStart(
            attachmentMode,
            rawOptions,
            instrumentation,
            shutdownHookRegistrar,
            action -> BootstrapThreadFactory.create(action).start()
        );
    }

    private static void requestStart(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation,
        final ShutdownHookRegistrar shutdownHookRegistrar,
        final Consumer<Runnable> bootstrapThreadStarter
    ) {
        if (!START_REQUESTED.compareAndSet(false, true)) {
            try {
                RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Agent start ignored because the runtime was already requested"
                );
            } catch (Throwable ignored) {
                // Diagnostics must never propagate into the host JVM.
            }
            return;
        }
        try {
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
            final List<HookContributor> contributors = loadHookManifest();
            for (HookContributor contributor : contributors) {
                if (contributor.phase() == HookContributor.Phase.PREMAIN) {
                    if (premain && installPhaseHook(contributor, premainEnvironment)) {
                        premainInstalled.add(contributor);
                    }
                }
            }
            bootstrapThreadStarter.accept(
                () -> start(
                    options,
                    instrumentation,
                    List.copyOf(premainInstalled),
                    contributors
                )
            );
        } catch (Throwable failure) {
            failStartSafely(failure);
        }
    }

    /**
     * Contains a synchronous start failure: reports it, rolls back the shim
     * transformers and premain hook handles the attempt already installed, and
     * releases {@code START_REQUESTED} so a later attach can retry from a clean
     * state. Nothing here may propagate into the host JVM: premain/agentmain
     * throwing would abort the host launch entirely.
     */
    private static void failStartSafely(final Throwable failure) {
        try {
            System.err.println(
                "Turboism agent start failed safely: " + failure.getClass().getName()
                    + ": " + failure.getMessage()
            );
        } catch (Throwable ignored) {
            // Even failure reporting must not reach the host JVM.
        }
        try {
            JvmShims.closeAll(TurboismAgent::runtimeWarn);
            HOOKS.get().closeAll(TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
        } catch (Throwable rollbackFailure) {
            // Rollback is best-effort. Anything left installed is a bounded
            // single-target transformer or hook handle that stays until process
            // exit, matching the teardown rules of the runtime-start path.
        } finally {
            START_REQUESTED.set(false);
        }
    }

    private static void start(
        final AgentOptions options,
        final Instrumentation instrumentation,
        final List<HookContributor> premainInstalled,
        final List<HookContributor> contributors
    ) {
        final List<HookContributor> bound = new ArrayList<>(premainInstalled);
        final VerifiedMeshMirrorHookInstaller meshMirrorHook = MeshMirrorHookContributor.CURRENT.get();
        final VerifiedWarpAltMirrorHookInstaller warpAltMirrorHook = WarpAltMirrorHookContributor.CURRENT.get();
        boolean published = false;
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
            final PreviewRuntime runtime = PreviewRuntimeLauncher.startPreviewRuntime(
                meshMirrorHook,
                warpAltMirrorHook,
                () -> PreviewRuntimeLauncher.start(options, resolved, prepared -> {
                    final HookEnvironment runtimeEnvironment =
                        environment(instrumentation, options, resolved, prepared);
                    prepareEarlyHooks(contributors, bound,
                        contributor -> disableHookCapabilities(contributor, runtimeEnvironment),
                        contributor -> {
                            for (final String hookId : contributor.runtimeHookIds()) {
                                prepared.editorModelResolver().deferCapabilitiesRequiringHook(hookId);
                            }
                        },
                        contributor -> bindRuntimeHook(contributor, runtimeEnvironment));
                    installPhase(contributors, HookContributor.Phase.RUNTIME_STARTED, runtimeEnvironment);
                }, prepared -> {
                    final HookEnvironment runtimeEnvironment =
                        environment(instrumentation, options, resolved, prepared);
                    for (HookContributor contributor : bound) {
                        if (contributor.phase() == HookContributor.Phase.PREMAIN) {
                            bindRuntimeHook(contributor, runtimeEnvironment);
                        }
                    }
                })
            );
            if (!RUNTIME.compareAndSet(null, runtime)) {
                PreviewRuntimeLauncher.closeDuplicateRuntimeAndHooks(
                    runtime::close,
                    meshMirrorHook,
                    warpAltMirrorHook
                );
                return;
            }
            published = true;
            runtimeInfo(
                "Turboism Developer Preview started: host=" + runtime.hostState()
                    + ", plugins=" + runtime.loadReport().loaded().size()
                    + ", failures=" + runtime.loadReport().failures().size()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            runtimeWarn("Turboism bootstrap interrupted");
        } catch (Throwable failure) {
            final PreviewRuntime runtime = RUNTIME.get();
            if (runtime == null) {
                System.err.println(
                    "Turboism bootstrap failed safely: " + failure.getClass().getName()
                        + ": " + failure.getMessage()
                );
            } else {
                runtime.error("bootstrap", "Turboism bootstrap failed safely", failure);
            }
        } finally {
            if (!published) {
                // Also covers host lookup timeout, identity/base-capability rejection,
                // and interruption before startPreviewRuntime owns the later cleanup.
                HOOKS.get().closePhase(HookContributor.Phase.RUNTIME_STARTED,
                    TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
                HOOKS.get().closePhase(HookContributor.Phase.HOST_RESOLVED,
                    TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
                try {
                    PreviewRuntimeLauncher.closePremainRuntimeHooks(meshMirrorHook, warpAltMirrorHook);
                } catch (Throwable cleanupFailure) {
                    runtimeWarn("Turboism premain runtime-hook cleanup failed safely: "
                        + cleanupFailure.getClass().getName());
                }
            }
        }
    }

    // The full manifest is required, including premain hooks that were not installed.
    // Consumers initialize after this pass, while successful premain hooks stay pending
    // until their plugin-dependent bridge binds in the after-plugins callback.
    static void prepareEarlyHooks(
        final List<HookContributor> contributors,
        final List<HookContributor> installed,
        final java.util.function.Consumer<HookContributor> unavailable,
        final java.util.function.Consumer<HookContributor> pending,
        final java.util.function.Consumer<HookContributor> bind
    ) {
        for (final HookContributor contributor : contributors) {
            if (contributor.phase() == HookContributor.Phase.RUNTIME_STARTED) continue;
            if (!installed.contains(contributor)) {
                unavailable.accept(contributor);
            } else if (contributor.phase() == HookContributor.Phase.PREMAIN) {
                pending.accept(contributor);
            } else {
                bind.accept(contributor);
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
        boolean installed = false;
        try {
            if (!contributor.admitted(environment)) {
                return false;
            }
            final AutoCloseable handle = contributor.install(environment);
            if (handle == null) {
                return false;
            }
            HOOKS.get().enroll(contributor, () -> {
                try {
                    handle.close();
                } finally {
                    disableHookCapabilities(contributor, environment);
                }
            });
            installed = true;
            return true;
        } catch (Throwable failure) {
            runtimeWarn("Turboism hook disabled safely: " + contributor.id());
            return false;
        } finally {
            if (!installed) disableHookCapabilities(contributor, environment);
        }
    }

    private static void disableHookCapabilities(
        final HookContributor contributor, final HookEnvironment environment
    ) {
        environment.runtime().ifPresent(runtime -> {
            for (final String hookId : contributor.runtimeHookIds()) {
                runtime.disableEditorCapabilitiesRequiringHook(hookId);
            }
        });
    }

    private static void bindRuntimeHook(
        final HookContributor contributor, final HookEnvironment environment
    ) {
        try {
            contributor.bind(environment);
            environment.runtime().ifPresent(runtime -> {
                for (final String hookId : contributor.runtimeHookIds()) {
                    runtime.editorModelResolver().completeHookBinding(hookId);
                }
            });
        } catch (Throwable failure) {
            disableHookCapabilities(contributor, environment);
            HOOKS.get().closeHook(contributor.id(), TurboismAgent::runtimeWarn, TurboismAgent::runtimeInfo);
            runtimeWarn("Turboism hook binding disabled safely: " + contributor.id());
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
            .hostResolution(resolved.resolution())
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
