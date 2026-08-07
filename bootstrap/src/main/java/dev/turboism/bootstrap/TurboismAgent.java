package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
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

import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasDataModelHookInstaller;
import dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasAutoLayoutHookInstaller;

/** Java-agent entrypoint for the Turboism 0.1 Developer Preview. */
public final class TurboismAgent {

    private static final String VERIFICATION_RESOURCE_DIRECTORY =
        "/META-INF/turboism/verification/";
    private static final AtomicBoolean START_REQUESTED = new AtomicBoolean(false);
    private static final AtomicReference<PreviewRuntime> RUNTIME = new AtomicReference<>();
    private static final AtomicReference<VerifiedParameterHookInstaller> PARAMETER_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedProjectLifecycleHookInstaller>
        PROJECT_LIFECYCLE_HOOK = new AtomicReference<>();
    private static final AtomicReference<VerifiedFileChooserHistoryHookInstaller>
        FILE_CHOOSER_HISTORY_HOOK = new AtomicReference<>();
    private static final AtomicReference<VerifiedTextureAtlasDataModelHookInstaller> TEXTURE_ATLAS_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedTextureAtlasAutoLayoutHookInstaller> TEXTURE_ATLAS_AUTO_LAYOUT_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedDockTabPopupHookInstaller> DOCK_TAB_POPUP_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedFloatingFrameDisposeHookInstaller> FLOATING_FRAME_DISPOSE_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedFloatingTabCloseHookInstaller> FLOATING_TAB_CLOSE_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedObjectContextMenuHookInstaller> OBJECT_CONTEXT_MENU_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedParameterPointContextMenuHookInstaller> PARAMETER_POINT_MENU_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<dev.turboism.sdk.plugin.Registration> OBJECT_CONTEXT_MENU_BRIDGE =
        new AtomicReference<>();
    private static final AtomicReference<dev.turboism.sdk.plugin.Registration> PARAMETER_POINT_MENU_BRIDGE =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedPhysicsEditorHookInstaller> PHYSICS_EDITOR_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedMeshMirrorHookInstaller> MESH_MIRROR_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedControlAppearanceHookInstaller>
        CONTROL_APPEARANCE_HOOK = new AtomicReference<>();
    private static final AtomicReference<StartupSuppressionInstaller.Installation> STARTUP_SUPPRESSION =
        new AtomicReference<>();
    private static final AtomicReference<dev.turboism.sdk.plugin.Registration> OVERLAY_HOOK =
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
            System.out.println("Turboism agent start ignored: runtime has already been requested");
            return;
        }

        final AgentOptions options;
        try {
            options = AgentOptions.parse(rawOptions, defaultHome());
        } catch (RuntimeException exception) {
            System.out.println("Turboism agent options rejected: " + exception.getMessage());
            return;
        }

        try {
            Runtime.getRuntime().addShutdownHook(
                new Thread(TurboismAgent::shutdown, "turboism-shutdown")
            );
        } catch (IllegalStateException | SecurityException failure) {
            System.err.println("Turboism agent start rejected: shutdown hook is unavailable");
            return;
        }

        final StartupSuppressionInstaller.Installation startupSuppression =
            StartupSuppressionInstaller.install(
                attachmentMode,
                instrumentation,
                options.home(),
                System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")),
                code -> System.out.println("Turboism startup suppression: " + code)
            );
        if (!STARTUP_SUPPRESSION.compareAndSet(null, startupSuppression)) {
            startupSuppression.close();
        }
        System.out.println(
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
            System.out.println(
                "Turboism agent active; waiting for " + options.hostClassName()
                    + " for up to " + options.detectionTimeout().toSeconds() + " seconds"
            );
            final Optional<HostClassLocator.LocatedHost> located = new HostClassLocator().await(
                instrumentation,
                options.hostClassName(),
                options.detectionTimeout()
            );
            if (located.isEmpty()) {
                System.out.println("Turboism agent stopped: Cubism host class was not observed");
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
            final Path coreRuntimeVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-core-model-read.json"
            );
            final Path coreArtifact = host.artifact().resolveSibling("Live2DCubismCore.jar")
                .toAbsolutePath().normalize();
            if (!Files.isRegularFile(coreArtifact)) {
                throw new IOException("Exact Cubism Core artifact is missing beside the Editor JAR");
            }
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
            final Path boundingBoxOverlayVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-ui-bounding-box-overlay.json"
            );
            final Optional<Path> statusBarVerificationRecord = "5.3.02".equals(profile)
                ? Optional.of(extractVerificationRecord(
                    options.home(),
                    "cubism-5.3.02-ui-status-bar.json"
                ))
                : Optional.empty();
            final Optional<Path> clipMaskVerificationRecord = "5.3.02".equals(profile)
                ? Optional.of(extractVerificationRecord(
                    options.home(),
                    "cubism-5.3.02-clipmask.json"
                ))
                : Optional.empty();
            final Path autoBackupVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + ("5.3.02".equals(profile) ? "5.3.02" : "5.2.03") + "-autobackup.json"
            );
            final Path controlAppearanceVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + profile + "-ui-control-appearance.json"
            );
            final PreviewRuntime runtime = PreviewRuntime.start(
                options.home(),
                verificationRecord,
                editorModelVerificationRecord,
                coreRuntimeVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                statusBarVerificationRecord,
                clipMaskVerificationRecord,
                autoBackupVerificationRecord,
                host.artifact(),
                coreArtifact,
                host.classLoader()
            );
            if (!RUNTIME.compareAndSet(null, runtime)) {
                runtime.close();
                return;
            }
            installParameterHook(runtime, instrumentation, host);
            installProjectLifecycleHook(runtime, instrumentation, host);
            installFileChooserHistoryHook(runtime, instrumentation, host);
            installTextureAtlasHook(runtime, instrumentation, host);
            installTextureAtlasAutoLayoutHook(runtime, instrumentation, host);
            installDockTabPopupHook(
                embeddedPanelVerificationRecord,
                instrumentation,
                host
            );
            installFloatingFrameDisposeHook(
                embeddedPanelVerificationRecord,
                instrumentation,
                host,
                runtime
            );
            installFloatingTabCloseHook(
                embeddedPanelVerificationRecord,
                instrumentation,
                host
            );
            installObjectContextMenuHook(runtime, instrumentation, host);
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
            installBoundingBoxOverlayHook(runtime, instrumentation);
            installControlAppearanceHook(
                runtime,
                instrumentation,
                host,
                controlAppearanceVerificationRecord
            );
            Runtime.getRuntime().addShutdownHook(new Thread(TurboismAgent::shutdown, "turboism-shutdown"));
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
            runtimeWarn("Turboism parameter hook disabled safely: " + failure.getClass().getName());

        }
    }

    private static void installProjectLifecycleHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedProjectLifecycleHookInstaller installer = null;
        try {
            final var profile =
                dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile.forArtifact(
                    HostArtifactDigest.from(host.artifact())
                ).orElseThrow(() -> new IllegalStateException(
                    "Unsupported project lifecycle host artifact"
                ));
            installer = new VerifiedProjectLifecycleHookInstaller(
                instrumentation,
                host.classLoader(),
                profile,
                runtime.hostAccess().projectFileLifecycle(),
                runtime.hostAccess().editorLifecycleEvents()
            );
            installer.install();
            if (!PROJECT_LIFECYCLE_HOOK.compareAndSet(null, installer)) installer.close();
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
                "Turboism project lifecycle hook disabled safely: "
                    + failure.getClass().getName()
            );
        }
    }

    private static void installFileChooserHistoryHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedFileChooserHistoryHookInstaller installer = null;
        try {
            final var profile = FileChooserHistoryHostProfile.forArtifact(
                HostArtifactDigest.from(host.artifact())
            ).orElseThrow(() -> new IllegalStateException(
                "Unsupported file-chooser history host artifact"
            ));
            installer = new VerifiedFileChooserHistoryHookInstaller(
                instrumentation,
                host.classLoader(),
                profile,
                runtime.fileChooserHistoryService()
            );
            installer.install();
            if (!FILE_CHOOSER_HISTORY_HOOK.compareAndSet(null, installer)) {
                installer.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
                "Turboism file-chooser history hook disabled safely: "
                    + failure.getClass().getName()
            );
        }
    }
    private static boolean safeModeActive() {
        final StartupSuppressionInstaller.Installation suppression = STARTUP_SUPPRESSION.get();
        return suppression != null && suppression.policy().safeMode();
    }

    private static void installDockTabPopupHook(
        final Path embeddedPanelVerificationRecord,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        if (safeModeActive()) {
            System.err.println("Turboism dock-tab popup hook skipped in safe mode");
            return;
        }
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
            runtimeWarn("Turboism dock-tab popup hook disabled safely: " + failure.getClass().getName());

        }
    }

    private static void installFloatingFrameDisposeHook(
        final Path embeddedPanelVerificationRecord,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host,
        final PreviewRuntime runtime
    ) {
        if (safeModeActive()) {
            System.err.println("Turboism floating-frame dispose hook skipped in safe mode");
            return;
        }
        VerifiedFloatingFrameDisposeHookInstaller installer = null;
        try {
            final var resolver = new dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory()
                .create(embeddedPanelVerificationRecord, host.artifact(), host.classLoader());
            installer = new VerifiedFloatingFrameDisposeHookInstaller(
                instrumentation,
                resolver.verifiedSelector("cubism.ui-panel.palette-frame.raw-disposed"),
                host.classLoader()
            );
            installer.install();
            if (!FLOATING_FRAME_DISPOSE_HOOK.compareAndSet(null, installer)) installer.close();
            System.err.println("Turboism floating-frame dispose hook installed");
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism floating-frame dispose hook disabled safely: "
                    + failure.getClass().getName()
            );
        }
    }

    private static void installFloatingTabCloseHook(
        final Path embeddedPanelVerificationRecord,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        if (safeModeActive()) {
            System.err.println("Turboism floating-tab close hook skipped in safe mode");
            return;
        }
        VerifiedFloatingTabCloseHookInstaller installer = null;
        try {
            final var resolver = new dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory()
                .create(embeddedPanelVerificationRecord, host.artifact(), host.classLoader());
            installer = new VerifiedFloatingTabCloseHookInstaller(
                instrumentation,
                resolver.verifiedSelector("cubism.ui-panel.floating-tab-close.operation"),
                resolver.verifiedSelector("cubism.ui-panel.floating-tab-close.palette-field"),
                host.classLoader()
            );
            installer.install();
            if (!FLOATING_TAB_CLOSE_HOOK.compareAndSet(null, installer)) installer.close();
            System.err.println("Turboism floating-tab close hook installed");
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism floating-tab close hook disabled safely: "
                    + failure.getClass().getName()
            );
        }
    }

    private static void installObjectContextMenuHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedObjectContextMenuHookInstaller installer = null;
        dev.turboism.sdk.plugin.Registration bridge = null;
        dev.turboism.sdk.plugin.Registration parameterPointBridge = null;
        VerifiedParameterPointContextMenuHookInstaller parameterPointInstaller = null;
        try {
            final ObjectContextMenuHostProfile profile = ObjectContextMenuHostProfile.forArtifact(
                HostArtifactDigest.from(host.artifact())
            ).orElseThrow(() -> new IllegalStateException("Unsupported object context-menu host artifact"));
            final var handler = runtime.hostAccess().objectContextMenuHandler();
            if (handler == null) throw new IllegalStateException("Object context-menu runtime handler is unavailable");
            bridge = dev.turboism.ui.context.NativeObjectContextMenuBridge.install(handler);
            final var parameterPointHandler = runtime.hostAccess().parameterPointMenuHandler();
            if (parameterPointHandler == null) {
                throw new IllegalStateException("Parameter-point context-menu runtime handler is unavailable");
            }
            parameterPointBridge =
                dev.turboism.ui.context.NativeParameterPointContextMenuBridge.install(parameterPointHandler);
            installer = new VerifiedObjectContextMenuHookInstaller(
                instrumentation,
                profile.bindings(),
                host.classLoader()
            );
            installer.install();
            final ParameterPointContextMenuHostProfile parameterPointProfile =
                ParameterPointContextMenuHostProfile.forArtifact(HostArtifactDigest.from(host.artifact()))
                    .orElseThrow(() -> new IllegalStateException("Unsupported parameter-point context-menu host artifact"));
            parameterPointInstaller = new VerifiedParameterPointContextMenuHookInstaller(
                instrumentation, parameterPointProfile.owner(), parameterPointProfile.contextDescriptor(), host.classLoader()
            );
            parameterPointInstaller.install();
            if (!OBJECT_CONTEXT_MENU_BRIDGE.compareAndSet(null, bridge)
                || !PARAMETER_POINT_MENU_BRIDGE.compareAndSet(null, parameterPointBridge)
                || !PARAMETER_POINT_MENU_HOOK.compareAndSet(null, parameterPointInstaller)
                || !OBJECT_CONTEXT_MENU_HOOK.compareAndSet(null, installer)) {
                installer.close();
                parameterPointInstaller.close();
                parameterPointBridge.close();
                bridge.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            if (parameterPointInstaller != null) parameterPointInstaller.close();
            if (bridge != null) bridge.close();
            if (parameterPointBridge != null) parameterPointBridge.close();
            runtimeWarn("Turboism object context-menu hook disabled safely: " + failure.getClass().getName());

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
            runtimeWarn("Turboism physics editor hook disabled safely: " + failure.getClass().getName());

        }
    }

    private static void installBoundingBoxOverlayHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation
    ) {
        try {
            final dev.turboism.sdk.plugin.Registration hook =
                new dev.turboism.ui.overlay.BoundingBoxOverlayButtonHookInstaller(instrumentation)
                    .install(runtime.hostAccess().boundingBoxOverlayResolver().orElseThrow());
            if (!OVERLAY_HOOK.compareAndSet(null, hook)) {
                hook.close();
            }
        } catch (Throwable failure) {
            runtimeWarn(
                "Turboism bounding-box overlay hook disabled safely: " + failure.getClass().getName()

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

    private static void installControlAppearanceHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host,
        final Path verificationRecord
    ) {
        VerifiedControlAppearanceHookInstaller installer = null;
        try {
            final var resolver = new dev.turboism.mapping.verification.VerifiedControlAppearanceResolverFactory()
                .create(verificationRecord, host.artifact(), host.classLoader());
            final long generation = runtime.hostAccess().paletteAppearanceCoordinator().hostGeneration();
            installer = VerifiedControlAppearanceHookInstaller.fromVerifiedResolver(
                instrumentation,
                resolver,
                generation,
                runtime.hostAccess().paletteAppearanceCoordinator()
            );
            installer.install();
            if (!CONTROL_APPEARANCE_HOOK.compareAndSet(null, installer)) {
                installer.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn("Turboism control-appearance hook disabled safely: " + failure.getClass().getName());

        }
    }

    private static void runtimeInfo(final String message) {
        final PreviewRuntime runtime = RUNTIME.get();
        if (runtime == null) System.err.println(message); else runtime.info("bootstrap", message);
    }

    private static void runtimeWarn(final String message) {
        final PreviewRuntime runtime = RUNTIME.get();
        if (runtime == null) System.err.println(message); else runtime.warn("bootstrap", message);
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
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
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

    private static boolean shutdownRuntime() {
        final StartupSuppressionInstaller.Installation startupSuppression =
            STARTUP_SUPPRESSION.getAndSet(null);
        if (startupSuppression != null) {
            try {
                startupSuppression.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism startup suppression cleanup failed safely");

            }
        }
        final VerifiedObjectContextMenuHookInstaller objectContextMenuHook =
            OBJECT_CONTEXT_MENU_HOOK.getAndSet(null);
        if (objectContextMenuHook != null) {
            try {
                objectContextMenuHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism object context-menu hook cleanup failed safely");

            }
        }
        final VerifiedParameterPointContextMenuHookInstaller parameterPointMenuHook =
            PARAMETER_POINT_MENU_HOOK.getAndSet(null);
        if (parameterPointMenuHook != null) {
            try {
                parameterPointMenuHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism parameter-point context-menu hook cleanup failed safely");

            }
        }
        final dev.turboism.sdk.plugin.Registration objectContextMenuBridge =
            OBJECT_CONTEXT_MENU_BRIDGE.getAndSet(null);
        if (objectContextMenuBridge != null) {
            try {
                objectContextMenuBridge.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism object context-menu bridge cleanup failed safely");

            }
        }
        final dev.turboism.sdk.plugin.Registration parameterPointMenuBridge =
            PARAMETER_POINT_MENU_BRIDGE.getAndSet(null);
        if (parameterPointMenuBridge != null) {
            try {
                parameterPointMenuBridge.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism parameter-point context-menu bridge cleanup failed safely");

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
                runtimeWarn("Turboism dock-tab popup hook cleanup failed safely");

            }
        }
        final VerifiedFloatingFrameDisposeHookInstaller floatingFrameHook =
            FLOATING_FRAME_DISPOSE_HOOK.getAndSet(null);
        if (floatingFrameHook != null) {
            try {
                floatingFrameHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism floating-frame dispose hook cleanup failed safely");
            }
        }
        final VerifiedFloatingTabCloseHookInstaller floatingTabCloseHook =
            FLOATING_TAB_CLOSE_HOOK.getAndSet(null);
        if (floatingTabCloseHook != null) {
            try {
                floatingTabCloseHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism floating-tab close hook cleanup failed safely");
            }
        }
        final VerifiedProjectLifecycleHookInstaller projectLifecycleHook =
            PROJECT_LIFECYCLE_HOOK.getAndSet(null);
        if (projectLifecycleHook != null) {
            try {
                projectLifecycleHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism project lifecycle hook cleanup failed safely");
            }
        }

        final VerifiedFileChooserHistoryHookInstaller fileChooserHistoryHook =
            FILE_CHOOSER_HISTORY_HOOK.getAndSet(null);
        if (fileChooserHistoryHook != null) {
            try {
                fileChooserHistoryHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism file-chooser history hook cleanup failed safely");
            }
        }
        final VerifiedParameterHookInstaller parameterHook = PARAMETER_HOOK.getAndSet(null);
        if (parameterHook != null) {
            try {
                parameterHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism parameter hook cleanup failed safely");

            }
        }
        final VerifiedTextureAtlasDataModelHookInstaller textureAtlasHook =
            TEXTURE_ATLAS_HOOK.getAndSet(null);
        if (textureAtlasHook != null) {
            try {
                textureAtlasHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism texture-atlas hook cleanup failed safely");
            }
        }
        final VerifiedTextureAtlasAutoLayoutHookInstaller textureAtlasAutoLayoutHook =
            TEXTURE_ATLAS_AUTO_LAYOUT_HOOK.getAndSet(null);
        if (textureAtlasAutoLayoutHook != null) {
            try {
                textureAtlasAutoLayoutHook.close();
            } catch (Throwable failure) {
                System.err.println("Turboism texture-atlas automatic-layout hook cleanup failed safely");
            }
        }
        final VerifiedPhysicsEditorHookInstaller physicsHook = PHYSICS_EDITOR_HOOK.getAndSet(null);
        if (physicsHook != null) {
            try {
                physicsHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism physics editor hook cleanup failed safely");

            }
        }
        final dev.turboism.sdk.plugin.Registration overlayHook = OVERLAY_HOOK.getAndSet(null);
        if (overlayHook != null) {
            try {
                overlayHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism bounding-box overlay hook cleanup failed safely");

            }
        }
        final VerifiedControlAppearanceHookInstaller controlAppearanceHook =
            CONTROL_APPEARANCE_HOOK.getAndSet(null);
        if (controlAppearanceHook != null) {
            try {
                controlAppearanceHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism control-appearance hook cleanup failed safely");

            }
        }
        final PreviewRuntime runtime = RUNTIME.getAndSet(null);
        if (runtime == null) {
            return false;
        }
        try {
            runtime.close();
        } catch (Throwable failure) {
            System.out.println(
                "Turboism shutdown hook failed safely: RUNTIME_CLOSE_FAILED"
            );
        }
        return true;
    }

    private static void installTextureAtlasHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedTextureAtlasDataModelHookInstaller installer = null;
        try {
            installer = VerifiedTextureAtlasDataModelHookInstaller.fromVerifiedResolver(
                instrumentation,
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.textureAtlasDataModelCapture()
            );
            installer.install();
            if (!TEXTURE_ATLAS_HOOK.compareAndSet(null, installer)) {
                installer.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism texture-atlas hook disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static void installTextureAtlasAutoLayoutHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedTextureAtlasAutoLayoutHookInstaller installer = null;
        try {
            final RuntimeTextureAtlasEditorUi editorUi =
                runtime.hostAccess().textureAtlasEditorUi();
            installer = VerifiedTextureAtlasAutoLayoutHookInstaller.fromVerifiedResolver(
                instrumentation,
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.hostAccess().textureAtlasNativeInvocations(),
                () -> {
                    final Object callback = System.getProperties().get(
                        VerifiedTextureAtlasAutoLayoutHookInstaller.PLUGIN_CALLBACK_KEY
                    );
                    return callback instanceof java.util.function.BooleanSupplier supplier
                        && supplier.getAsBoolean();
                },
                editorUi,
                runtime.hostAccess().textureAtlasAlgorithms()
            );
            installer.install();
            if (!TEXTURE_ATLAS_AUTO_LAYOUT_HOOK.compareAndSet(null, installer)) {
                installer.close();
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            System.err.println(
                "Turboism texture-atlas automatic-layout hook disabled safely: "
                    + failure.getClass().getName()
            );
            try {
                final Path home = Path.of(System.getProperty("turboism.home", "."));
                final Path diag = home.resolve("logs").resolve("bootstrap-diagnostic.log");
                java.nio.file.Files.createDirectories(diag.getParent());
                java.nio.file.Files.writeString(
                    diag,
                    java.time.Instant.now() + " texture-atlas auto-layout hook install failed: "
                        + failure + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
                );
            } catch (Throwable ignored) {
                // diagnostics are best-effort
            }
        }
    }
}
