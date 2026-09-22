package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHookRegistry;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.adapter.jdk.PipeImplLoopbackInstaller;
import dev.turboism.adapter.cubism.filechooser.FileChooserHistoryHostProfile;
import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import dev.turboism.mapping.verification.AutoBackupVerificationManifest;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
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
import dev.turboism.adapter.cubism.editor.history.NativeEditBeginBridge;
import dev.turboism.adapter.cubism.editor.history.VerifiedNativeEditBeginHookInstaller;
import dev.turboism.adapter.cubism.integration.EditApprovalGate;
import dev.turboism.adapter.cubism.integration.EditBridgeEnvironment;
import dev.turboism.adapter.cubism.integration.EditProtocolBridge;
import dev.turboism.adapter.cubism.integration.EditSocketWriter;
import dev.turboism.adapter.cubism.integration.EditToggleApprovalGate;
import dev.turboism.adapter.cubism.integration.EditToggleConfigStore;
import dev.turboism.adapter.cubism.integration.EditToggleState;
import dev.turboism.adapter.cubism.integration.NativeEditToggleInjector;
import dev.turboism.adapter.cubism.integration.SwingEditApprovalGate;
import dev.turboism.adapter.cubism.integration.VerifiedEditApiDispatchInstaller;
import dev.turboism.adapter.cubism.integration.VerifiedEditToggleHookInstaller;
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
    private static final AtomicReference<VerifiedNativeEditBeginHookInstaller>
        NATIVE_EDIT_BEGIN_HOOK = new AtomicReference<>();
    private static final AtomicReference<VerifiedEditApiDispatchInstaller>
        EDIT_API_DISPATCH_HOOK = new AtomicReference<>();
    private static final AtomicReference<EditProtocolBridge>
        EDIT_API_DISPATCH_BRIDGE = new AtomicReference<>();
    private static final AtomicReference<VerifiedEditToggleHookInstaller>
        EDIT_TOGGLE_HOOK = new AtomicReference<>();
    private static final AtomicReference<NativeEditToggleInjector>
        EDIT_TOGGLE_INJECTOR = new AtomicReference<>();
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
    static final AtomicReference<VerifiedMeshMirrorHookInstaller> MESH_MIRROR_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedControlAppearanceHookInstaller>
        CONTROL_APPEARANCE_HOOK = new AtomicReference<>();
    private static final AtomicReference<StartupSuppressionInstaller.Installation> STARTUP_SUPPRESSION =
        new AtomicReference<>();
    private static final AtomicReference<
        dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller.Installation>
        MESH_TRIANGULATION_HASH = new AtomicReference<>();
    private static final AtomicReference<
        dev.turboism.adapter.cubism.textureatlas.image.VerifiedAtlasTileBboxInstaller.Installation>
        ATLAS_TILE_BBOX = new AtomicReference<>();
    private static final AtomicReference<
        dev.turboism.adapter.cubism.textureatlas.cache
            .VerifiedAtlasCacheReuseInstaller.Installation>
        ATLAS_CACHE_REUSE = new AtomicReference<>();
    private static final AtomicReference<PipeImplLoopbackInstaller.Installation> PIPE_IMPL_SHIM =
        new AtomicReference<>();
    private static final AtomicReference<dev.turboism.sdk.plugin.Registration> OVERLAY_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedPerformanceProbeInstaller> PERFORMANCE_PROBE =
        new AtomicReference<>();
    private static final AtomicReference<PerformanceFpsHook> FPS_HOOK =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedImageArchiveReuseInstaller> IMAGE_ARCHIVE_REUSE =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedFloatArrayParseCacheInstaller> FLOAT_ARRAY_PARSE_CACHE =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedTextureUploadPreparationInstaller> TEXTURE_UPLOAD_PREPARATION =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedWarpPositionProjectionInstaller> WARP_POSITION_PROJECTION =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedModelUpdateSkipInstaller> MODEL_UPDATE_SKIP =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedIncrementalUpdateInstaller> INCREMENTAL_UPDATE =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedUniformLocationInstaller> UNIFORM_LOCATION_CACHE =
        new AtomicReference<>();
    private static final AtomicReference<VerifiedMatrixScratchInstaller> MATRIX_SCRATCH =
        new AtomicReference<>();

    @FunctionalInterface
    interface ShutdownHookRegistrar {
        void register(Thread hook);
    }

    private static final ShutdownHookRegistrar JVM_SHUTDOWN_HOOK_REGISTRAR =
        hook -> Runtime.getRuntime().addShutdownHook(hook);

    private TurboismAgent() {
    }

    /**
     * Java agent entry point used when Turboism is attached at JVM startup.
     *
     * <p>This is the supported attachment mode: it runs before Cubism's own classes load, so the
     * startup-suppression transformer can still see them.</p>
     *
     * @param options the raw agent option string, may be null
     * @param instrumentation the JVM instrumentation handle
     */
    public static void premain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.PREMAIN,
            options,
            instrumentation
        );
    }

    /**
     * Java agent entry point used when Turboism is attached to an already-running JVM.
     *
     * <p>Classes Cubism has already loaded are past the transformer, so this mode starts the
     * runtime with a reduced set of hooks rather than pretending it matched premain.</p>
     *
     * @param options the raw agent option string, may be null
     * @param instrumentation the JVM instrumentation handle
     */
    public static void agentmain(final String options, final Instrumentation instrumentation) {
        requestStart(
            StartupSuppressionInstaller.AttachmentMode.AGENTMAIN,
            options,
            instrumentation
        );
    }

    static boolean meshMirrorPremainOnly(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode
    ) {
        return attachmentMode == StartupSuppressionInstaller.AttachmentMode.PREMAIN;
    }

    private static void requestStart(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final String rawOptions,
        final Instrumentation instrumentation
    ) {
        requestStart(
            attachmentMode,
            rawOptions,
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
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "bootstrap",
                "Agent start ignored because the runtime was already requested"
            );
            return;
        }

        final AgentOptions options;
        try {
            options = AgentOptions.parse(rawOptions, defaultHome());
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

        final StartupSuppressionInstaller.Installation startupSuppression =
            StartupSuppressionInstaller.install(
                attachmentMode,
                instrumentation,
                options.home(),
                System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")),
                code -> dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Startup suppression: " + code
                )
            );
        if (attachmentMode == StartupSuppressionInstaller.AttachmentMode.PREMAIN) {
            final dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller.Installation
                meshTriangulationHash = installMeshTriangulationHashPremain(
                    instrumentation,
                    dev.turboism.config.RuntimeStartupConfig.load(options.home()),
                    options.home()
                );
            if (!MESH_TRIANGULATION_HASH.compareAndSet(null, meshTriangulationHash)) {
                if (meshTriangulationHash != null) meshTriangulationHash.close();
            } else if (meshTriangulationHash != null) {
                dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Mesh triangulation hash status=" + meshTriangulationHash.status()
                );
            }
            final dev.turboism.adapter.cubism.textureatlas.image
                    .VerifiedAtlasTileBboxInstaller.Installation atlasTileBbox =
                installAtlasTileBboxPremain(
                    instrumentation,
                    dev.turboism.config.RuntimeStartupConfig.load(options.home()),
                    options.home()
                );
            if (!ATLAS_TILE_BBOX.compareAndSet(null, atlasTileBbox)) {
                if (atlasTileBbox != null) atlasTileBbox.close();
            } else if (atlasTileBbox != null) {
                dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Atlas tile-bbox status=" + atlasTileBbox.status()
                        + ", transformOutcome=" + atlasTileBbox.transformOutcome()
                );
            }
            final dev.turboism.adapter.cubism.textureatlas.cache
                    .VerifiedAtlasCacheReuseInstaller.Installation atlasCacheReuse =
                installAtlasCacheReusePremain(
                    instrumentation,
                    dev.turboism.config.RuntimeStartupConfig.load(options.home()),
                    options.home()
                );
            if (!ATLAS_CACHE_REUSE.compareAndSet(null, atlasCacheReuse)) {
                if (atlasCacheReuse != null) atlasCacheReuse.close();
            } else if (atlasCacheReuse != null) {
                dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Atlas cache-reuse status=" + atlasCacheReuse.status()
                        + ", transformOutcome=" + atlasCacheReuse.transformOutcome()
                );
            }
            installMeshMirrorHookPremain(
                instrumentation,
                System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")),
                dev.turboism.config.RuntimeStartupConfig.load(options.home())
            );
        }
        if (!STARTUP_SUPPRESSION.compareAndSet(null, startupSuppression)) {
            startupSuppression.close();
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.debug(
            "bootstrap",
            "Startup suppression status=" + startupSuppression.status()
                + ", safeMode=" + startupSuppression.policy().safeMode()
        );
        final PipeImplLoopbackInstaller.Installation pipeImplShim =
            PipeImplLoopbackInstaller.install(
                instrumentation,
                code -> dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "bootstrap",
                    "Pipe shim: " + code
                )
            );
        if (!PIPE_IMPL_SHIM.compareAndSet(null, pipeImplShim)) {
            pipeImplShim.close();
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.debug(
            "bootstrap",
            "Pipe shim status=" + pipeImplShim.status()
                + ", transformOutcome=" + pipeImplShim.transformOutcome()
        );
        final Thread bootstrap = BootstrapThreadFactory.create(
            () -> start(options, instrumentation)
        );
        bootstrap.start();
    }

    private static void start(final AgentOptions options, final Instrumentation instrumentation) {
        try {
            dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                "bootstrap",
                "Agent active; waiting for the Cubism host"
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
            // Editor and Core version lines are independent trust roots. The paired
            // 5.3.03 Editor distribution still carries the reviewed 5.3.02 Core artifact,
            // so Core evidence is selected later by the Core digest rather than this profile.
            final Path coreArtifact = host.artifact().resolveSibling("Live2DCubismCore.jar")
                .toAbsolutePath().normalize();
            if (!Files.isRegularFile(coreArtifact)) {
                throw new IOException("Exact Cubism Core artifact is missing beside the Editor JAR");
            }
            final String coreProfile = dev.turboism.mapping.verification
                .VerifiedCorePublicApiResolverFactory.profileForArtifact(coreArtifact);
            final Path coreRuntimeVerificationRecord = extractVerificationRecord(
                options.home(),
                "cubism-" + coreProfile + "-core-model-read.json"
            );
            final boolean fullRuntimeAdmission = dev.turboism.mapping.verification
                .ReviewedHostArtifacts.admitsFullRuntime(profile);
            final Path mainToolbarVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(options.home(), "cubism-" + profile + "-ui-main-toolbar.json")
                : null;
            final Path embeddedPanelVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(options.home(), "cubism-" + profile + "-ui-embedded-panel.json")
                : null;
            final Path topMenuVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(options.home(), "cubism-" + profile + "-ui-top-menu.json")
                : null;
            final Path boundingBoxOverlayVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(
                    options.home(), "cubism-" + profile + "-ui-bounding-box-overlay.json"
                )
                : null;
            // Hooks remain suppressed until exact artifact identity and full-runtime admission
            // have both completed. Once admitted, the ordinary reviewed profile routes every P3 lane.
            if (fpsRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                publishFpsHook(instrumentation, host);
            }

            final Optional<Path> statusBarVerificationRecord = fullRuntimeAdmission
                ? Optional.of(extractVerificationRecord(
                    options.home(), "cubism-" + profile + "-ui-status-bar.json"
                ))
                : Optional.empty();
            final Optional<Path> clipMaskVerificationRecord =
                ClipMaskVerificationManifest.reviewedCubismVersions().contains(profile)
                    ? Optional.of(extractVerificationRecord(
                        options.home(), "cubism-" + profile + "-clipmask.json"
                    ))
                    : Optional.empty();
            final Path autoBackupVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(
                    options.home(), "cubism-" + profile + "-autobackup.json"
                )
                : null;
            final Path controlAppearanceVerificationRecord = fullRuntimeAdmission
                ? extractVerificationRecord(
                    options.home(), "cubism-" + profile + "-ui-control-appearance.json"
                )
                : null;
            final VerifiedMeshMirrorHookInstaller meshMirrorHook = MESH_MIRROR_HOOK.get();
            // Runtime startup can already open/decode the initial document. Capture
            // native decode provenance before that, but only after exact admission.
            if (fullRuntimeAdmission) installImageArchiveReuse(options, instrumentation, host);
            if (fullRuntimeAdmission) installFloatArrayParseCache(options, instrumentation, host);
            if (fullRuntimeAdmission) installTextureUploadPreparation(options, instrumentation, host);
            if (fullRuntimeAdmission) installWarpPositionProjection(options, instrumentation, host);
            if (fullRuntimeAdmission) installModelUpdateSkip(options, instrumentation, host);
            if (fullRuntimeAdmission) installIncrementalUpdate(options, instrumentation, host);
            if (fullRuntimeAdmission) installUniformLocationCache(options, instrumentation, host);
            if (fullRuntimeAdmission) installMatrixScratch(options, instrumentation, host);
            final PreviewRuntime runtime;
            try {
                runtime = startPreviewRuntime(meshMirrorHook, () -> PreviewRuntime.start(
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
                ));
            } catch (Throwable failure) {
                closeMatrixScratch();
                closeUniformLocationCache();
                closeMeshMirrorHookIfCurrent(meshMirrorHook);
                closeImageArchiveReuse();
                closeFloatArrayParseCache();
                closeTextureUploadPreparation();
                closeWarpPositionProjection();
                closeModelUpdateSkip();
                closeIncrementalUpdate();
                throw failure;
            }
            if (!RUNTIME.compareAndSet(null, runtime)) {
                closeDuplicateRuntimeAndMeshMirrorHook(runtime::close, meshMirrorHook);
                return;
            }
            if (parameterLifecycleRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installParameterHook(runtime, instrumentation, host);
            }
            if (projectLifecycleRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installProjectLifecycleHook(runtime, instrumentation, host);
            }
            if (fileChooserHistoryRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installFileChooserHistoryHook(runtime, instrumentation, host);
            }
            if (textureAtlasRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installTextureAtlasHook(runtime, instrumentation, host);
                installTextureAtlasAutoLayoutHook(runtime, instrumentation, host);
            }
            if (nativeEditBeginHookRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installNativeEditBeginHook(runtime, instrumentation, host);
            }
            if (editApiDispatchRuntimeAdmitted(profile, fullRuntimeAdmission)) {
                installEditApiDispatchHook(runtime, instrumentation, host);
            }
            if (fullRuntimeAdmission) {
                // Republish admission after the runtime logger is available; never install twice.
                if (IMAGE_ARCHIVE_REUSE.get() != null) {
                    runtimeInfo("TURBOISM_IMAGE_ARCHIVE_REUSE installation=COMPLETE phase=runtime-ready");
                }
                if (FLOAT_ARRAY_PARSE_CACHE.get() != null) {
                    runtimeInfo("TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=COMPLETE phase=runtime-ready");
                }
                if (TEXTURE_UPLOAD_PREPARATION.get() != null) {
                    runtimeInfo("TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=COMPLETE phase=runtime-ready");
                }
                if (WARP_POSITION_PROJECTION.get() != null) {
                    runtimeInfo("TURBOISM_WARP_POSITION_PROJECTION installation=COMPLETE phase=runtime-ready");
                }
                if (MODEL_UPDATE_SKIP.get() != null) {
                    runtimeInfo("TURBOISM_MODEL_UPDATE_SKIP installation=COMPLETE phase=runtime-ready");
                }
                if (INCREMENTAL_UPDATE.get() != null) {
                    runtimeInfo("TURBOISM_INCREMENTAL_UPDATE installation=COMPLETE phase=runtime-ready");
                }
                if (MATRIX_SCRATCH.get() != null) {
                    runtimeInfo("TURBOISM_MATRIX_SCRATCH installation=COMPLETE phase=runtime-ready");
                }
                installPerformanceProbe(options, instrumentation, host);
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
                bindMeshMirrorHook(
                    runtime,
                    dev.turboism.adapter.cubism.mesh.MeshMirrorHookAdmission.admitted(
                        runtime.loadReport().loaded()
                    )
                );
                installBoundingBoxOverlayHook(runtime, instrumentation);
                installControlAppearanceHook(
                    runtime,
                    instrumentation,
                    host,
                    controlAppearanceVerificationRecord
                );
            }
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

    private static void installImageArchiveReuse(
        final AgentOptions options,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        if (!Boolean.getBoolean(dev.turboism.adapter.cubism.optimization.image
            .ImageArchiveReuseBridge.ENABLE_PROPERTY)) return;
        VerifiedImageArchiveReuseInstaller installer = null;
        try {
            if (!VerifiedImageArchiveReuseInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true)) {
                runtimeInfo("TURBOISM_IMAGE_ARCHIVE_REUSE installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedImageArchiveReuseInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!IMAGE_ARCHIVE_REUSE.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_IMAGE_ARCHIVE_REUSE installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_IMAGE_ARCHIVE_REUSE installation=FAILED " + failure.getClass().getName());
        }
    }

    private static void installFloatArrayParseCache(AgentOptions options, Instrumentation instrumentation,
                                                   HostClassLocator.LocatedHost host) {
        if (!Boolean.getBoolean(dev.turboism.adapter.cubism.optimization.serialization
            .FloatArrayParseBridge.ENABLE_PROPERTY)) return;
        VerifiedFloatArrayParseCacheInstaller installer = null;
        try {
            if (!VerifiedFloatArrayParseCacheInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true)) {
                runtimeInfo("TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedFloatArrayParseCacheInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!FLOAT_ARRAY_PARSE_CACHE.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_FLOAT_ARRAY_PARSE_CACHE installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void installTextureUploadPreparation(AgentOptions options, Instrumentation instrumentation,
                                                        HostClassLocator.LocatedHost host) {
        if (!Boolean.getBoolean(dev.turboism.adapter.cubism.optimization.image
            .TextureUploadPreparationBridge.ENABLE_PROPERTY)) return;
        VerifiedTextureUploadPreparationInstaller installer = null;
        try {
            if (!VerifiedTextureUploadPreparationInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true, Runtime.version().feature())) {
                runtimeInfo("TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedTextureUploadPreparationInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!TEXTURE_UPLOAD_PREPARATION.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_TEXTURE_UPLOAD_PREPARATION installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void installWarpPositionProjection(AgentOptions options, Instrumentation instrumentation,
                                                       HostClassLocator.LocatedHost host) {
        if (!Boolean.getBoolean(dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge.ENABLE_PROPERTY)) return;
        VerifiedWarpPositionProjectionInstaller installer = null;
        try {
            if (!VerifiedWarpPositionProjectionInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true, Runtime.version().feature())) {
                runtimeInfo("TURBOISM_WARP_POSITION_PROJECTION installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedWarpPositionProjectionInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!WARP_POSITION_PROJECTION.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_WARP_POSITION_PROJECTION installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_WARP_POSITION_PROJECTION installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void closeWarpPositionProjection() {
        VerifiedWarpPositionProjectionInstaller installation = WARP_POSITION_PROJECTION.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism warp position projection cleanup failed safely"); }
        }
    }

    private static void installMatrixScratch(AgentOptions options, Instrumentation instrumentation,
                                              HostClassLocator.LocatedHost host) {
        VerifiedMatrixScratchInstaller installer = null;
        try {
            if (!VerifiedMatrixScratchInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()),
                Boolean.getBoolean(dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer.ENABLE_PROPERTY),
                Runtime.version().feature())) {
                runtimeInfo("TURBOISM_MATRIX_SCRATCH installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedMatrixScratchInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!MATRIX_SCRATCH.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_MATRIX_SCRATCH installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_MATRIX_SCRATCH installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void closeMatrixScratch() {
        VerifiedMatrixScratchInstaller installer = MATRIX_SCRATCH.getAndSet(null);
        if (installer == null) return;
        try {
            installer.close();
            runtimeInfo("TURBOISM_MATRIX_SCRATCH restoration=" + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
        } catch (Throwable failure) {
            runtimeWarn("TURBOISM_MATRIX_SCRATCH restoration=FAILED " + failure);
        }
    }

    private static void installUniformLocationCache(AgentOptions options, Instrumentation instrumentation,
                                                    HostClassLocator.LocatedHost host) {
        if (!dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge.enabledByPreference()) {
            runtimeInfo("TURBOISM_UNIFORM_LOCATION installation=NOT_ADMITTED");
            return;
        }
        VerifiedUniformLocationInstaller installer = null;
        try {
            if (!VerifiedUniformLocationInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true, Runtime.version().feature())) {
                runtimeInfo("TURBOISM_UNIFORM_LOCATION installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedUniformLocationInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!UNIFORM_LOCATION_CACHE.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_UNIFORM_LOCATION installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_UNIFORM_LOCATION installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void closeUniformLocationCache() {
        VerifiedUniformLocationInstaller installer = UNIFORM_LOCATION_CACHE.getAndSet(null);
        if (installer == null) return;
        try {
            installer.close();
            runtimeInfo("TURBOISM_UNIFORM_LOCATION restoration=" + (installer.restored() ? "COMPLETE" : "UNVERIFIED"));
        } catch (Throwable failure) {
            runtimeWarn("TURBOISM_UNIFORM_LOCATION restoration=FAILED " + failure);
        }
    }

    private static void installModelUpdateSkip(AgentOptions options, Instrumentation instrumentation,
                                               HostClassLocator.LocatedHost host) {
        if (!dev.turboism.adapter.cubism.optimization.modelupdate
                .ModelUpdateSkipBridge.flagEnabled()) {
            runtimeInfo("TURBOISM_MODEL_UPDATE_SKIP installation=NOT_ADMITTED");
            return;
        }
        VerifiedModelUpdateSkipInstaller installer = null;
        try {
            if (!VerifiedModelUpdateSkipInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true, Runtime.version().feature())) {
                runtimeInfo("TURBOISM_MODEL_UPDATE_SKIP installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedModelUpdateSkipInstaller(instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!MODEL_UPDATE_SKIP.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_MODEL_UPDATE_SKIP installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_MODEL_UPDATE_SKIP installation=FAILED " + failure.getClass().getName()
                + ": " + failure.getMessage());
        }
    }

    private static void closeModelUpdateSkip() {
        VerifiedModelUpdateSkipInstaller installation = MODEL_UPDATE_SKIP.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism model-update skip cleanup failed safely"); }
        }
    }

    private static void installIncrementalUpdate(AgentOptions options, Instrumentation instrumentation,
                                                 HostClassLocator.LocatedHost host) {
        if (!dev.turboism.adapter.cubism.optimization.modelupdate.incremental
                .IncrementalUpdateBridge.flagEnabled()) {
            runtimeInfo("TURBOISM_INCREMENTAL_UPDATE installation=NOT_ADMITTED");
            return;
        }
        VerifiedIncrementalUpdateInstaller installer = null;
        try {
            if (!VerifiedIncrementalUpdateInstaller.admitted(HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(options.home()), true, Runtime.version().feature())) {
                runtimeInfo("TURBOISM_INCREMENTAL_UPDATE installation=NOT_ADMITTED");
                return;
            }
            installer = new VerifiedIncrementalUpdateInstaller(
                instrumentation, host.artifact(), host.classLoader());
            installer.install();
            if (!INCREMENTAL_UPDATE.compareAndSet(null, installer)) installer.close();
            else runtimeInfo("TURBOISM_INCREMENTAL_UPDATE installation=COMPLETE");
        } catch (Throwable failure) {
            if (installer != null) {
                try { installer.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            runtimeWarn("TURBOISM_INCREMENTAL_UPDATE installation=FAILED "
                + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    private static void closeIncrementalUpdate() {
        VerifiedIncrementalUpdateInstaller installation = INCREMENTAL_UPDATE.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism incremental update cleanup failed safely"); }
        }
    }

    private static void closeTextureUploadPreparation() {
        VerifiedTextureUploadPreparationInstaller installation = TEXTURE_UPLOAD_PREPARATION.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism texture upload preparation cleanup failed safely"); }
        }
    }

    private static void closeFloatArrayParseCache() {
        VerifiedFloatArrayParseCacheInstaller installation = FLOAT_ARRAY_PARSE_CACHE.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism float array parse cache cleanup failed safely"); }
        }
    }

    private static void closeImageArchiveReuse() {
        final VerifiedImageArchiveReuseInstaller installation = IMAGE_ARCHIVE_REUSE.getAndSet(null);
        if (installation != null) {
            try { installation.close(); }
            catch (Throwable failure) { runtimeWarn("Turboism image archive reuse cleanup failed safely"); }
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
            } else {
                runtimeInfo("TURBOISM_PARAMETER_HOOK installation=COMPLETE");
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
            if (!PROJECT_LIFECYCLE_HOOK.compareAndSet(null, installer)) {
                installer.close();
            } else {
                runtimeInfo("TURBOISM_PROJECT_LIFECYCLE_HOOK installation=COMPLETE");
            }
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
            } else {
                runtimeInfo("TURBOISM_FILE_CHOOSER_HISTORY_HOOK installation=COMPLETE");
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
            runtimeInfo("Turboism dock-tab popup hook skipped in safe mode");
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
            runtimeInfo("Turboism floating-frame dispose hook skipped in safe mode");
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
            runtimeInfo("Turboism floating-frame dispose hook installed");
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
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
            runtimeInfo("Turboism floating-tab close hook skipped in safe mode");
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
            runtimeInfo("Turboism floating-tab close hook installed");
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
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

    private static void installPerformanceProbe(
        final AgentOptions options,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        if (!options.performanceProbeInstall()) return;
        VerifiedPerformanceProbeInstaller installer = null;
        try {
            installer = new VerifiedPerformanceProbeInstaller(
                instrumentation,
                host.artifact(),
                host.classLoader(),
                options.home().resolve("lib/performance-probe-carrier.jar"),
                options.performanceProbeScenario()
            );
            installer.install(
                options.performanceProbeCapture(),
                options.performanceProbeScenario(),
                options.performanceProbeAgentSha256(),
                options.performanceProbeFixtureSha256(),
                java.time.Duration.ofSeconds(options.performanceProbeDelaySeconds()),
                java.time.Duration.ofSeconds(options.performanceProbeDurationSeconds()),
                options.performanceProbeOutput(),
                options.performanceProbeRunId(),
                options.performanceProbeRollbackOutput()
            );
            if (!PERFORMANCE_PROBE.compareAndSet(null, installer)) installer.close();
            runtimeInfo(
                "Turboism validation performance probe installed; capture="
                    + options.performanceProbeCapture()
            );
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
                "Turboism validation performance probe disabled safely: "
                    + failure.getClass().getName()
            );
        }
    }

    /**
     * Installs the triangulation hash fix during premain.
     *
     * <p>Two independent gates must both pass: the operator's hook policy, and the persisted user
     * preference. Safety admission is separate again and lives in the transformer, which only
     * rewrites a class whose bytes match a pinned digest. Every failure path returns null after
     * reporting, so a refused fix can never stop official startup.</p>
     */
    private static dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller.Installation
            installMeshTriangulationHashPremain(
        final Instrumentation instrumentation,
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        if (!meshTriangulationHashEnabled(policy, turboismHome)) return null;
        try {
            return dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller.install(
                instrumentation,
                code -> dev.turboism.runtime.log.RuntimeDiagnostics.debug(
                    "mesh-triangulation-hash",
                    code
                )
            );
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "mesh-triangulation-hash",
                "Triangulation hash fix disabled safely",
                failure
            );
            return null;
        }
    }

    static boolean meshTriangulationHashEnabled(
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        return policy != null
            && policy.hookEnabled("cubism.mesh.triangulation-hash")
            && dev.turboism.config.MeshTriangulationPreference.read(turboismHome);
    }

    static void closeMeshTriangulationHashIfCurrent(
        final dev.turboism.adapter.cubism.mesh.VerifiedMeshTriangulationHashInstaller.Installation
            installation
    ) {
        if (installation == null) return;
        if (!MESH_TRIANGULATION_HASH.compareAndSet(installation, null)) return;
        installation.close();
    }

    /**
     * Installs the atlas tile-bbox optimization during premain.
     *
     * <p>Two independent gates must both pass: the operator's hook policy, and the persisted user
     * preference. Safety admission is separate again and lives in the transformer, which only
     * rewrites a class whose bytes match a pinned digest and whose method shape passes every
     * anchor. Every failure path returns null after reporting, so a refused optimization can
     * never stop official startup.</p>
     */
    private static dev.turboism.adapter.cubism.textureatlas.image
            .VerifiedAtlasTileBboxInstaller.Installation installAtlasTileBboxPremain(
        final Instrumentation instrumentation,
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        if (!atlasTileBboxEnabled(policy, turboismHome)) return null;
        try {
            return dev.turboism.adapter.cubism.textureatlas.image
                .VerifiedAtlasTileBboxInstaller.install(
                    instrumentation,
                    // Premain runs before the diagnostics sink exists; the console is the
                    // only place the admission verdict survives on a real host.
                    code -> System.err.println("[turboism] atlas-tile-bbox " + code)
                );
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "atlas-tile-bbox",
                "Atlas tile-bbox optimization disabled safely",
                failure
            );
            return null;
        }
    }

    static boolean atlasTileBboxEnabled(
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        return policy != null
            && policy.hookEnabled("cubism.textureatlas.tile-bbox")
            && dev.turboism.config.AtlasTileBboxPreference.read(turboismHome);
    }

    static void closeAtlasTileBboxIfCurrent(
        final dev.turboism.adapter.cubism.textureatlas.image
            .VerifiedAtlasTileBboxInstaller.Installation installation
    ) {
        if (installation == null) return;
        if (!ATLAS_TILE_BBOX.compareAndSet(installation, null)) return;
        installation.close();
    }

    /**
     * Premain install for the atlas cache-reuse guard. Mirrors the tile-bbox wiring: the
     * policy gate and persisted preference decide, and every failure path returns null
     * after reporting so a refused optimization can never stop official startup.
     */
    private static dev.turboism.adapter.cubism.textureatlas.cache
            .VerifiedAtlasCacheReuseInstaller.Installation installAtlasCacheReusePremain(
        final Instrumentation instrumentation,
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        if (!atlasCacheReuseEnabled(policy, turboismHome)) return null;
        try {
            return dev.turboism.adapter.cubism.textureatlas.cache
                .VerifiedAtlasCacheReuseInstaller.install(
                    instrumentation,
                    code -> System.err.println("[turboism] atlas-cache-reuse " + code)
                );
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "atlas-cache-reuse",
                "Atlas cache-reuse optimization disabled safely",
                failure
            );
            return null;
        }
    }

    static boolean atlasCacheReuseEnabled(
        final dev.turboism.config.RuntimeStartupConfig policy,
        final Path turboismHome
    ) {
        return policy != null
            && policy.hookEnabled("cubism.textureatlas.cache-reuse")
            && dev.turboism.config.AtlasCacheReusePreference.read(turboismHome);
    }

    static void closeAtlasCacheReuseIfCurrent(
        final dev.turboism.adapter.cubism.textureatlas.cache
            .VerifiedAtlasCacheReuseInstaller.Installation installation
    ) {
        if (installation == null) return;
        if (!ATLAS_CACHE_REUSE.compareAndSet(installation, null)) return;
        installation.close();
    }

    private static VerifiedMeshMirrorHookInstaller installMeshMirrorHookPremain(
        final Instrumentation instrumentation,
        final String classPath,
        final Path workingDirectory,
        final dev.turboism.config.RuntimeStartupConfig policy
    ) {
        if (!meshMirrorHookEnabled(policy)) return null;
        final Optional<Path> artifact = StartupSuppressionInstaller.locateHostArtifact(classPath, workingDirectory);
        if (artifact.isEmpty()) {
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                "mesh-mirror",
                "Mesh mirror hook unavailable because the host artifact was not admitted"
            );
            return null;
        }
        try {
            final HostArtifactDigest digest = HostArtifactDigest.from(artifact.orElseThrow());
            if (!meshMirrorRuntimeAdmitted(digest)) {
                throw new IllegalStateException("Mesh mirror host artifact is not runtime-admitted");
            }
            final var profile = dev.turboism.adapter.cubism.mesh.MeshMirrorHostProfile.forArtifact(digest)
                .orElseThrow(() -> new IllegalStateException("Unsupported mesh mirror host artifact"));
            final VerifiedMeshMirrorHookInstaller installer = new VerifiedMeshMirrorHookInstaller(
                instrumentation,
                null,
                artifact.orElseThrow().toAbsolutePath().normalize(),
                profile
            );
            installer.install();
            if (!MESH_MIRROR_HOOK.compareAndSet(null, installer)) {
                installer.close();
                return null;
            }
            return installer;
        } catch (Throwable failure) {
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "mesh-mirror",
                "Mesh mirror hook disabled safely",
                failure
            );
            return null;
        }
    }


    static boolean statusBarRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean parameterLifecycleRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean projectLifecycleRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean fileChooserHistoryRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    /**
     * {@return whether the native edit entry hook may be installed for this host}
     *
     * <p>The hook needs the reviewed Editor-model resolver plus agent instrumentation, which is the
     * same precondition the other ordinary reviewed host hooks use. It installs no capability of its
     * own: without a bound host session the receiver only counts, and the exact selectors it skips
     * over are still gated by the pinned records.</p>
     */
    static boolean nativeEditBeginHookRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    /**
     * {@return whether the edit-protocol dispatch hook may be installed for this host}
     *
     * <p>Same ordinary reviewed admission as the other host hooks; the deeper gate is inside
     * {@code VerifiedEditApiDispatchInstaller.fromVerifiedResolver}, which refuses without a
     * reviewed dispatch-entry selector and keeps the feature inert.</p>
     */
    static boolean editApiDispatchRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean textureAtlasRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean autoBackupRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    static boolean fpsRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return ordinaryReviewedRuntimeAdmitted(profile, fullRuntimeAdmission);
    }

    private static boolean ordinaryReviewedRuntimeAdmitted(
        final String profile,
        final boolean fullRuntimeAdmission
    ) {
        return fullRuntimeAdmission
            && dev.turboism.mapping.verification.ReviewedHostArtifacts.admitsFullRuntime(profile);
    }

    static boolean meshMirrorHookEnabled(
        final dev.turboism.config.RuntimeStartupConfig policy
    ) {
        return policy != null && policy.hookEnabled("cubism.mesh.mirror-axis");
    }

    static boolean meshMirrorRuntimeAdmitted(final HostArtifactDigest artifact) {
        return dev.turboism.mapping.verification.ReviewedHostArtifacts.cubismVersionOf(artifact)
            .filter(dev.turboism.mapping.verification.ReviewedHostArtifacts::admitsFullRuntime)
            .isPresent();
    }

    static void closeMeshMirrorHookIfCurrent(final VerifiedMeshMirrorHookInstaller installer) {
        if (installer == null || !MESH_MIRROR_HOOK.compareAndSet(installer, null)) return;
        installer.close();
    }

    @FunctionalInterface
    interface PreviewRuntimeStarter {
        PreviewRuntime start() throws Throwable;
    }

    static PreviewRuntime startPreviewRuntime(
        final VerifiedMeshMirrorHookInstaller candidate,
        final PreviewRuntimeStarter starter
    ) throws Throwable {
        try {
            return starter.start();
        } catch (Throwable failure) {
            closeMeshMirrorHookIfCurrent(candidate);
            throw failure;
        }
    }

    static void closeDuplicateRuntimeAndMeshMirrorHook(
        final Runnable runtimeClose,
        final VerifiedMeshMirrorHookInstaller candidate
    ) {
        try {
            runtimeClose.run();
        } finally {
            closeMeshMirrorHookIfCurrent(candidate);
        }
    }

    private static void bindMeshMirrorHook(
        final PreviewRuntime runtime,
        final boolean authorized
    ) {
        final VerifiedMeshMirrorHookInstaller installer = MESH_MIRROR_HOOK.get();
        if (installer == null) {
            if (!authorized) {
                runtimeInfo("Turboism mesh mirror hook disabled by policy or missing authorized consumer");
            }
            return;
        }
        if (!authorized) {
            installer.close();
            MESH_MIRROR_HOOK.compareAndSet(installer, null);
            return;
        }
        try {
            installer.defineLazyTargets();
            installer.bind(
                runtime.hostAccess().meshMirrorAxisService(),
                runtime.hostAccess().meshEditUiService()
            );
        } catch (Throwable failure) {
            installer.close();
            MESH_MIRROR_HOOK.compareAndSet(installer, null);
            runtimeWarn(
                "Turboism mesh mirror runtime binding disabled safely: " + failure.getClass().getName()
            );
        }
    }

    private static void publishFpsHook(
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        PerformanceFpsHookInstaller installer = null;
        try {
            installer = new PerformanceFpsHookInstaller(
                instrumentation,
                host.artifact(),
                host.classLoader()
            );
            if (!FPS_HOOK.compareAndSet(null, installer)) installer.close();
            PerformanceFpsHookRegistry.publish(installer);
        } catch (Throwable failure) {
            if (installer != null) {
                try {
                    installer.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            runtimeWarn(
                "Turboism FPS counting hook disabled safely: " + failure.getClass().getName()
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
        if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
    }

    private static void runtimeWarn(final String message) {
        final PreviewRuntime runtime = RUNTIME.get();
        if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
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
        closeMeshTriangulationHashIfCurrent(MESH_TRIANGULATION_HASH.getAndSet(null));
        closeAtlasTileBboxIfCurrent(ATLAS_TILE_BBOX.getAndSet(null));
        closeAtlasCacheReuseIfCurrent(ATLAS_CACHE_REUSE.getAndSet(null));
        closeProjectLifecycleHook(runtime, "process-exit");
        closeFileChooserHistoryHook(runtime, "process-exit");
        closeParameterHook(runtime, "process-exit");
        closeTextureAtlasHooks(runtime, "process-exit");
        closeNativeEditBeginHook(runtime, "process-exit");
        closeEditApiDispatchHook(runtime, "process-exit");
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

    private static void closeProjectLifecycleHook(
        final PreviewRuntime runtime,
        final String phase
    ) {
        final VerifiedProjectLifecycleHookInstaller projectHook =
            PROJECT_LIFECYCLE_HOOK.getAndSet(null);
        if (projectHook == null) {
            return;
        }
        try {
            projectHook.close();
            final String message =
                "TURBOISM_PROJECT_LIFECYCLE_HOOK cleanup=COMPLETE phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
        } catch (Throwable failure) {
            final String message =
                "Turboism project lifecycle hook cleanup failed safely: phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
        }
    }

    private static void closeFileChooserHistoryHook(
        final PreviewRuntime runtime,
        final String phase
    ) {
        final VerifiedFileChooserHistoryHookInstaller fileChooserHistoryHook =
            FILE_CHOOSER_HISTORY_HOOK.getAndSet(null);
        if (fileChooserHistoryHook == null) {
            return;
        }
        try {
            fileChooserHistoryHook.close();
            final String message =
                "TURBOISM_FILE_CHOOSER_HISTORY_HOOK cleanup=COMPLETE phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
        } catch (Throwable failure) {
            final String message =
                "Turboism file-chooser history hook cleanup failed safely: phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
        }
    }

    private static void closeTextureAtlasHooks(
        final PreviewRuntime runtime,
        final String phase
    ) {
        final VerifiedTextureAtlasAutoLayoutHookInstaller autoLayout =
            TEXTURE_ATLAS_AUTO_LAYOUT_HOOK.get();
        if (autoLayout != null) {
            try {
                autoLayout.close();
                TEXTURE_ATLAS_AUTO_LAYOUT_HOOK.compareAndSet(autoLayout, null);
                final String message =
                    "TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK cleanup=COMPLETE phase=" + phase;
                if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
            } catch (Throwable failure) {
                final String message =
                    "Turboism texture-atlas automatic-layout hook cleanup failed safely: phase="
                        + phase;
                if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
            }
        }
        final VerifiedTextureAtlasDataModelHookInstaller dataModel =
            TEXTURE_ATLAS_HOOK.get();
        if (dataModel != null) {
            try {
                dataModel.close();
                TEXTURE_ATLAS_HOOK.compareAndSet(dataModel, null);
                final String message =
                    "TURBOISM_TEXTURE_ATLAS_DATA_MODEL_HOOK cleanup=COMPLETE phase=" + phase;
                if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
            } catch (Throwable failure) {
                final String message =
                    "Turboism texture-atlas data-model hook cleanup failed safely: phase=" + phase;
                if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
            }
        }
    }

    private static void closeParameterHook(
        final PreviewRuntime runtime,
        final String phase
    ) {
        final VerifiedParameterHookInstaller parameterHook = PARAMETER_HOOK.getAndSet(null);
        if (parameterHook == null) {
            return;
        }
        try {
            parameterHook.close();
            final String message = "TURBOISM_PARAMETER_HOOK cleanup=COMPLETE phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.info("bootstrap", message); else runtime.info("bootstrap", message);
        } catch (Throwable failure) {
            final String message = "Turboism parameter hook cleanup failed safely: phase=" + phase;
            if (runtime == null) dev.turboism.runtime.log.RuntimeDiagnostics.warn("bootstrap", message); else runtime.warn("bootstrap", message);
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
        final PipeImplLoopbackInstaller.Installation pipeImplShim =
            PIPE_IMPL_SHIM.getAndSet(null);
        if (pipeImplShim != null) {
            try {
                pipeImplShim.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism pipe shim cleanup failed safely");
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
                runtimeWarn("Turboism mesh mirror hook cleanup failed safely");
            }
        }
        closeMatrixScratch();
        closeUniformLocationCache();
        final VerifiedPerformanceProbeInstaller performanceProbe = PERFORMANCE_PROBE.getAndSet(null);
        if (performanceProbe != null) {
            try {
                performanceProbe.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism performance probe cleanup failed safely");
            }
        }

        closeImageArchiveReuse();
        closeFloatArrayParseCache();
        closeTextureUploadPreparation();
        closeWarpPositionProjection();
        closeModelUpdateSkip();
        closeIncrementalUpdate();
        final PerformanceFpsHook fpsHook = FPS_HOOK.getAndSet(null);
        if (fpsHook != null) {
            try {
                fpsHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism FPS hook cleanup failed safely");
            }
            PerformanceFpsHookRegistry.clear(fpsHook);
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
                runtimeWarn("Turboism floating-frame dispose hook cleanup failed safely");
            }
        }
        final VerifiedFloatingTabCloseHookInstaller floatingTabCloseHook =
            FLOATING_TAB_CLOSE_HOOK.getAndSet(null);
        if (floatingTabCloseHook != null) {
            try {
                floatingTabCloseHook.close();
            } catch (Throwable failure) {
                runtimeWarn("Turboism floating-tab close hook cleanup failed safely");
            }
        }
        closeProjectLifecycleHook(RUNTIME.get(), "runtime-close");

        closeFileChooserHistoryHook(RUNTIME.get(), "runtime-close");
        closeNativeEditBeginHook(RUNTIME.get(), "runtime-close");
        closeEditApiDispatchHook(RUNTIME.get(), "runtime-close");
        closeParameterHook(RUNTIME.get(), "runtime-close");
        closeTextureAtlasHooks(RUNTIME.get(), "runtime-close");
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
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                "bootstrap",
                "Shutdown hook failed safely: RUNTIME_CLOSE_FAILED",
                failure
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
            } else {
                runtimeInfo("TURBOISM_TEXTURE_ATLAS_DATA_MODEL_HOOK installation=COMPLETE");
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtimeWarn(
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
                runtime.hostAccess().textureAtlasAlgorithms(),
                runtime.effectiveLocale()
            );
            installer.install();
            if (!TEXTURE_ATLAS_AUTO_LAYOUT_HOOK.compareAndSet(null, installer)) {
                installer.close();
            } else {
                runtimeInfo("TURBOISM_TEXTURE_ATLAS_AUTO_LAYOUT_HOOK installation=COMPLETE");
            }
        } catch (Throwable failure) {
            if (installer != null) installer.close();
            runtime.error(
                "bootstrap",
                "Turboism texture-atlas automatic-layout hook disabled safely",
                failure
            );
        }
    }

    /**
     * Installs the exact native {@code beginEdit} entry hooks.
     *
     * <p>The hook only records that an edit started; the runtime publishes it when a host session
     * binds the bridge. Installing it before any session exists is therefore safe, and an edit made
     * with no session attached is counted and dropped rather than published or thrown.</p>
     */
    private static void installNativeEditBeginHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedNativeEditBeginHookInstaller installer = null;
        try {
            installer = VerifiedNativeEditBeginHookInstaller.fromVerifiedResolver(
                instrumentation,
                runtime.editorModelResolver(),
                host.classLoader()
            );
            installer.install(NativeEditBeginBridge.ingress());
            if (!NATIVE_EDIT_BEGIN_HOOK.compareAndSet(null, installer)) {
                installer.close();
            } else {
                runtimeInfo(
                    "TURBOISM_NATIVE_EDIT_BEGIN_HOOK installation=COMPLETE retransformed="
                        + String.join(",", installer.retransformedClassNames())
                );
            }
        } catch (Throwable failure) {
            if (installer != null) {
                try {
                    installer.close();
                } catch (Throwable ignored) {
                    // cleanup is best effort
                }
            }
            runtimeWarn("Turboism native edit entry hook disabled safely: "
                + failure.getClass().getName());
        }
    }

    /**
     * Removes the native edit entry hooks and stops observing native edit starts.
     *
     * @param runtime the preview runtime used for reporting, may be null
     * @param phase   the cleanup phase name used in the report
     */
    private static void closeNativeEditBeginHook(
        final PreviewRuntime runtime,
        final String phase
    ) {
        NativeEditBeginBridge.unbind();
        final VerifiedNativeEditBeginHookInstaller installer = NATIVE_EDIT_BEGIN_HOOK.getAndSet(null);
        if (installer == null) return;
        try {
            installer.close();
            runtimeInfo("TURBOISM_NATIVE_EDIT_BEGIN_HOOK cleanup=COMPLETE phase=" + phase);
        } catch (Throwable failure) {
            runtimeWarn("Turboism native edit entry hook cleanup failed safely: phase=" + phase);
        }
    }

    /**
     * Installs the single edit-protocol interception point on the host dispatcher.
     *
     * <p>The bridge answers the 36 official-1.1.0-compatible editing methods through the
     * verified-selector environment (connection records, Phase-046 edit sessions, the approval
     * dialog) while every other message keeps flowing through the native path — including when
     * the reviewed selectors are absent, the bridge is disabled, or the receiver throws.</p>
     */
    private static void installEditApiDispatchHook(
        final PreviewRuntime runtime,
        final Instrumentation instrumentation,
        final HostClassLocator.LocatedHost host
    ) {
        VerifiedEditApiDispatchInstaller installer = null;
        try {
            installer = VerifiedEditApiDispatchInstaller.fromVerifiedResolver(
                instrumentation,
                runtime.editorModelResolver(),
                host.classLoader()
            );
            final dev.turboism.sdk.cubism.edit.EditSessionService editSessions =
                runtime.hostAccess().modelAccess()
                    instanceof dev.turboism.adapter.cubism.edit.RuntimeEditSessionProvider provider
                    ? provider.editSessions(
                        "turboism.edit-api-bridge",
                        () -> editApiActiveDocument(runtime))
                    : dev.turboism.sdk.cubism.edit.EditSessionService.unavailable();
            // 051 P2: the native 「编辑」 checkbox replaces the connection-time approval
            // prompt whenever the verified injector surface is admitted. The toggle state
            // is loaded from the host-domain UUConfig key before the bridge gate is chosen.
            final EditApprovalGate approvalGate = installNativeEditToggle(
                instrumentation, runtime.editorModelResolver(), host)
                .orElseGet(() -> new SwingEditApprovalGate(java.util.Optional::empty));
            final EditProtocolBridge bridge = new EditProtocolBridge(
                EditSocketWriter.reflective(),
                EditBridgeEnvironment.production(
                    runtime.editorModelResolver(),
                    editSessions,
                    () -> editApiActiveDocument(runtime),
                    approvalGate));
            if (!installer.install(bridge.receiver())) {
                return;
            }
            if (!EDIT_API_DISPATCH_HOOK.compareAndSet(null, installer)) {
                installer.close();
            } else {
                EDIT_API_DISPATCH_BRIDGE.set(bridge);
                runtimeInfo(
                    "TURBOISM_EDIT_API_DISPATCH installation=COMPLETE retransformed="
                        + String.join(",", installer.transformedClassNames())
                );
            }
        } catch (Throwable failure) {
            if (installer != null) {
                try {
                    installer.close();
                } catch (Throwable ignored) {
                    // cleanup is best effort
                }
            }
            runtimeWarn("Turboism edit-protocol dispatch hook disabled safely: "
                + failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    /**
     * Wires the native 「编辑」 edit checkbox (spec 051, Phase 2) when the verified surface
     * is admitted.
     *
     * <p>On success this installs the {@code y.b} return hook (re-injection before every
     * dialog open), loads the persisted state from the host-domain UUConfig key
     * {@code CExternalAppSettingDialog.EditEnabled}, registers the write-back listener, and
     * returns the live-state {@link EditToggleApprovalGate} the protocol bridge consults —
     * the checkbox IS the grant, so the 050 connection-time Swing prompt is not installed on
     * this path. Every failure — missing admission, kill switch, hook failure — returns
     * {@code Optional.empty()} and the caller falls back to the 050 prompt gate; nothing is
     * left half-installed and the native dialog stays untouched.</p>
     */
    private static java.util.Optional<EditApprovalGate> installNativeEditToggle(
        final Instrumentation instrumentation,
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver,
        final HostClassLocator.LocatedHost host
    ) {
        if ("false".equalsIgnoreCase(
            System.getProperty(NativeEditToggleInjector.ENABLED_PROPERTY))) {
            return java.util.Optional.empty();
        }
        final EditToggleState state = new EditToggleState();
        final java.util.Optional<NativeEditToggleInjector> injector =
            NativeEditToggleInjector.fromVerifiedResolver(resolver, state);
        if (injector.isEmpty()) {
            return java.util.Optional.empty();
        }
        EditToggleConfigStore.fromVerifiedResolver(resolver).ifPresent(store -> {
            state.setEnabled(store.load());
            state.addListener(store::store);
        });
        VerifiedEditToggleHookInstaller hook = null;
        try {
            hook = VerifiedEditToggleHookInstaller.fromVerifiedResolver(
                instrumentation, resolver, host.classLoader());
            if (!hook.install(() -> injector.get().ensureInjectedOnEdt())) {
                return java.util.Optional.empty();
            }
            if (!EDIT_TOGGLE_HOOK.compareAndSet(null, hook)) {
                hook.close();
                return java.util.Optional.empty();
            }
            EDIT_TOGGLE_INJECTOR.set(injector.get());
            runtimeInfo(
                "TURBOISM_EDIT_TOGGLE_HOOK installation=COMPLETE retransformed="
                    + String.join(",", hook.transformedClassNames()));
            return java.util.Optional.of(new EditToggleApprovalGate(state));
        } catch (Throwable failure) {
            if (hook != null) {
                try {
                    hook.close();
                } catch (Throwable ignored) {
                    // cleanup is best effort
                }
            }
            runtimeWarn("Turboism native edit-toggle hook disabled safely: "
                + failure.getClass().getName());
            return java.util.Optional.empty();
        }
    }

    /**
     * {@return the host's active-document identity for edit-session admission}
     *
     * <p>Mirrors {@code DefaultCubismServicesFactory.activeDocumentId}: empty whenever the
     * workspace cannot report a document, which fails engine calls closed.</p>
     */
    private static java.util.Optional<dev.turboism.sdk.cubism.id.DocumentId>
        editApiActiveDocument(final PreviewRuntime runtime) {
        final dev.turboism.adapter.cubism.ProjectWorkspaceAdapter
            .AdapterResult<java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot>> result =
            runtime.hostAccess().adapters().projectWorkspace().activeDocument();
        if (!result.isAvailable() || result.value().isEmpty()) {
            return java.util.Optional.empty();
        }
        return result.value().orElseThrow().map(
            snapshot -> new dev.turboism.sdk.cubism.id.DocumentId(snapshot.documentId()));
    }

    /**
     * Removes the edit-protocol interception point and unpublishes the bridge.
     *
     * @param runtime the preview runtime used for reporting, may be null
     * @param phase   the cleanup phase name used in the report
     */
    private static void closeEditApiDispatchHook(
        final PreviewRuntime runtime,
        final String phase
    ) {
        closeNativeEditToggle(phase);
        EDIT_API_DISPATCH_BRIDGE.set(null);
        final VerifiedEditApiDispatchInstaller installer = EDIT_API_DISPATCH_HOOK.getAndSet(null);
        if (installer == null) return;
        try {
            installer.close();
            runtimeInfo("TURBOISM_EDIT_API_DISPATCH cleanup=COMPLETE phase=" + phase);
        } catch (Throwable failure) {
            runtimeWarn("Turboism edit-protocol dispatch hook cleanup failed safely: phase=" + phase);
        }
    }

    /**
     * Removes the native edit-toggle wiring: the {@code y.b} hook is uninstalled and the
     * injected checkbox detached on the EDT so the dialog returns to its exact native row.
     */
    private static void closeNativeEditToggle(final String phase) {
        final VerifiedEditToggleHookInstaller hook = EDIT_TOGGLE_HOOK.getAndSet(null);
        if (hook != null) {
            try {
                hook.close();
                runtimeInfo("TURBOISM_EDIT_TOGGLE_HOOK cleanup=COMPLETE phase=" + phase);
            } catch (Throwable failure) {
                runtimeWarn("Turboism native edit-toggle hook cleanup failed safely: phase="
                    + phase);
            }
        }
        final NativeEditToggleInjector injector = EDIT_TOGGLE_INJECTOR.getAndSet(null);
        if (injector != null) {
            try {
                javax.swing.SwingUtilities.invokeLater(injector::removeInjected);
            } catch (Throwable ignored) {
                // best-effort detach; the checkbox carries no host-visible side effects
            }
        }
    }

}
