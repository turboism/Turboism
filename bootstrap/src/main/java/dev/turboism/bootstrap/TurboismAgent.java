package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.preview.PreviewRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Java-agent entrypoint for the Turboism 0.1 Developer Preview. */
public final class TurboismAgent {

    private static final String VERIFICATION_RESOURCE_DIRECTORY =
        "/META-INF/turboism/verification/";
    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<PreviewRuntime> RUNTIME = new AtomicReference<>();
    private static final AtomicReference<VerifiedParameterHookInstaller> PARAMETER_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedDockTabPopupHookInstaller> DOCK_TAB_POPUP_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedPhysicsEditorHookInstaller> PHYSICS_EDITOR_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedMeshMirrorHookInstaller> MESH_MIRROR_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<StartupSuppressionInstaller.Installation> STARTUP_SUPPRESSION =
        new AtomicReference<>();

    private TurboismAgent() {
    }

    public static void premain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            options,
            instrumentation
        );
    }

    public static void agentmain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.AGENTMAIN,
            options,
            instrumentation
        );
    }

    private static void requestStart(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation
    ) {
        if (!START_REQUESTED.compareAndSet(false, true)) {
            System.err.println("Turboism agent start ignored: runtime has already been requested");
            return;
        }

        final AgentOptions options;
        try {
            options = AgentOptions.parse(rawOptions, defaultHome());
        } catch (RuntimeException exception) {
            System.err.println("Turboism agent options rejected: " + exception.getMessage());
            return;
        }

        final StartupSuppressionInstaller.Installation startupSuppression =
            StartupSuppressionInstaller.install(
                attachmentMode,
                instrumentation,
                options.home(),
                System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")),
                code -> System.err.println("Turboism startup suppression: " + code)
            );
        if (!STARTUP_SUPPRESSION.compareAndSet(null, startupSuppression)) {
            startupSuppression.close();
        }
        System.err.println(
            "Turboism startup suppression status=" + startupSuppression.status()
                + ", safeMode=" + startupSuppression.policy().safeMode()
                + ", requestedUpdate="
                + startupSuppression.policy().requestedSkipStartupUpdateCheck()
                + ", effectiveUpdate=" + startupSuppression.policy().skipStartupUpdateCheck()
                + ", requestedSplash=" + startupSuppression.policy().requestedSkipStartupSplash()
                + ", effectiveSplash=" + startupSuppression.policy().skipStartupSplash()
                + ", requestedInformation="
                + startupSuppression.policy().requestedSkipStartupInformation()
                + ", effectiveInformation=" + startupSuppression.policy().skipStartupInformation()
        );
        final Thread bootstrap = new Thread(
            () -> start(options, instrumentation),
            "turboism-bootstrap"
        );
        bootstrap.setDaemon(true);
        bootstrap.setContextClassLoader(TurboismAgent.class.getClassLoader());
        bootstrap.start();
    }

    private static void start(final AgentOptions options, final Instrumentation instrumentation) {
        try {
            System.err.println(
                "Turboism agent active; waiting for " + options.hostClassName()
                    + " for up to " + options.detectionTimeout().toSeconds() + " seconds"
            );
            final Optional<HostClassLocator.LocatedHost> located = new HostClassLocator().await(
                instrumentation,
                options.hostClassName(),
                options.detectionTimeout()
            );
            if (located.isEmpty()) {
                System.err.println("Turboism agent stopped: Cubism host class was not observed");
                return;
            }

            final HostClassLocator.LocatedHost host = located.orElseThrow();
            final String profile = EditorModelVerificationManifest.resourceProfileForArtifact(
                HostArtifactDigest.from(host.artifact())
            );
            final Path verificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-project-workspace.json"
            );
            final Path editorModelVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-editor-model.json"
            );
            final Path mainToolbarVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-ui-main-toolbar.json"
            );
            final Path embeddedPanelVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-ui-embedded-panel.json"
            );
            final Path topMenuVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-ui-top-menu.json"
            );
            final PreviewRuntime runtime = PreviewRuntime.start(
                options.home(),
                verificationRecord,
                editorModelVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                host.artifact(),
                host.classLoader()
            );
            if (!RUNTIME.compareAndSet(null, runtime)) {
                runtime.close();
                return;
            }
            installParameterHook(runtime, instrumentation, host);
            installDockTabPopupHook(
                embeddedPanelVerificationRecord,
                instrumentation,
                host
            );
            installPhysicsEditorHook(runtime, instrumentation, host);
            installMeshMirrorHook(
                runtime,
                instrumentation,
                host,
                dev.turboism.adapter.cubism.mesh.MeshMirrorHookAdmission.admitted(
                    runtime.loadReport().loaded()
                ) && STARTUP_SUPPRESSION.get() != null
                    && STARTUP_SUPPRESSION.get().policy().hookEnabled("cubism.mesh.mirror-axis")
            );
            Runtime.getRuntime().addShutdownHook(new Thread(TurboismAgent::shutdown, "turboism-shutdown"));
            System.err.println(
                "Turboism Developer Preview started: host=" + runtime.hostState()
                    + ", plugins=" + runtime.loadReport().loaded().size()
                    + ", failures=" + runtime.loadReport().failures().size()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("Turboism bootstrap interrupted");
        } catch (Throwable failure) {
            System.err.println(
                "Turboism bootstrap failed safely: " + failure.getClass().getName()
                    + ": " + failure.getMessage()
            );
        }
    }

    private static Path extractVerificationRecord(
        final Path home,
        final String fileName
    ) throws IOException {
        final String resource = VERIFICATION_RESOURCE_DIRECTORY + fileName;
        final Path target = home.resolve("state")
            .resolve("verification")
            .resolve(fileName)
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(target.getParent());
        try (InputStream source = TurboismAgent.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IOException("Embedded Cubism verification record is missing");
            }
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void installParameterHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedParameterHookInstaller installer = null;
        try {
            installer = VerifiedParameterHookInstaller.fromVerifiedResolver(
                instrumentation,
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.hostAccess().parameterLifecycle(),
                runtime.hostAccess().modelAccess()
            );
            installer.install();
            if (!PARAMETER_HOOK.compareAndSet(null, installer)) {
                installer.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism parameter hook disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static void installDockTabPopupHook(
        final Path embeddedPanelVerificationRecord,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedDockTabPopupHookInstaller installer = null;
        try {
            final var resolver = new dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory()
                .create(
                    embeddedPanelVerificationRecord,
                    host.artifact(),
                    host.classLoader()
                );
            installer = new VerifiedDockTabPopupHookInstaller(
                instrumentation,
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.operation"),
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.palette-field"),
                resolver.verifiedSelector("cubism.ui-panel.dock-tab-popup.menu-append"),
                host.classLoader()
            );
            installer.install();
            if (!DOCK_TAB_POPUP_HOOK.compareAndSet(null, installer)) installer.close();
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism dock-tab popup hook disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static void installPhysicsEditorHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedPhysicsEditorHookInstaller installer = null;
        try {
            final PhysicsEditorHostProfile profile = PhysicsEditorHostProfile.forArtifact(
                HostArtifactDigest.from(host.artifact())
            ).orElseThrow(() -> new IllegalStateException("Unsupported Physics Settings host artifact"));
            installer = new VerifiedPhysicsEditorHookInstaller(
                instrumentation,
                host.classLoader(),
                runtime.hostAccess().physicsEditorCoordinator(),
                profile
            );
            installer.install();
            if (!PHYSICS_EDITOR_HOOK.compareAndSet(null, installer)) installer.close();
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism physics editor hook disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static void installMeshMirrorHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host,
        final boolean enabled
    ) {
        if (!enabled) {
            System.err.println("Turboism mesh mirror hook disabled by policy or missing authorized consumer");
            return;
        }
        VerifiedMeshMirrorHookInstaller installer = null;
        try {
            final var profile = dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forArtifact(
                HostArtifactDigest.from(host.artifact())
            ).orElseThrow(() -> new IllegalStateException("Unsupported mesh mirror host artifact"));
            installer = new VerifiedMeshMirrorHookInstaller(
                instrumentation,
                host.classLoader(),
                runtime.hostAccess().meshMirrorAxisService(),
                runtime.hostAccess().meshEditUiService(),
                profile
            );
            installer.install();
            if (!MESH_MIRROR_HOOK.compareAndSet(null, installer)) installer.close();
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism mesh mirror hook disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static Path defaultHome() {
        final String configured = System.getProperty("turboism.home");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        try {
            final Path location = Path.of(
                TurboismAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            if (Files.isRegularFile(location)) {
                return location.getParent();
            }
            return location.resolve("turboism-preview");
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of("turboism-preview").toAbsolutePath().normalize();
        }
    }

    static boolean shutdownForTesting() {
        return shutdownRuntime();
    }

    private static void shutdown() {
        shutdownRuntime();
    }

    private static boolean shutdownRuntime() {
        final StartupSuppressionInstaller.Installation startupSuppression =
            STARTUP_SUPPRESSION.getAndSet(null);
        if (startupSuppression != null) {
            try {
                startupSuppression.close();
            } catch (Throwable failure) {
                System.err.println("Turboism startup suppression cleanup failed safely");
            }
        }
        final VerifiedMeshMirrorHookInstaller meshMirrorHook = MESH_MIRROR_HOOK.getAndSet(null);
        if (meshMirrorHook != null) {
            try {
                meshMirrorHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism mesh mirror hook cleanup failed safely");
            }
        }
        final VerifiedDockTabPopupHookInstaller dockTabPopupHook = DOCK_TAB_POPUP_HOOK.getAndSet(null);
        if (dockTabPopupHook != null) {
            try {
                dockTabPopupHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism dock-tab popup hook cleanup failed safely");
            }
        }
        final VerifiedParameterHookInstaller parameterHook = PARAMETER_HOOK.getAndSet(null);
        if (parameterHook != null) {
            try {
                parameterHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism parameter hook cleanup failed safely");
            }
        }
        final VerifiedPhysicsEditorHookInstaller physicsHook = PHYSICS_EDITOR_HOOK.getAndSet(null);
        if (physicsHook != null) {
            try {
                physicsHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism physics editor hook cleanup failed safely");
            }
        }
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime == null) {
            return false;
        }
        try {
            runtime.close();
        } catch (Throwable failure) {
            System.err.println(
                "Turboism shutdown hook failed safely: RUNTIME_CLOSE_FAILED"
            );
        }
        return true;
    }
}
