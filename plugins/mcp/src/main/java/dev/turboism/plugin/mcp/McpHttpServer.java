package dev.turboism.plugin.mcp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpStdioLaunch;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only Streamable HTTP transport for the embedded MCP server. */
final class McpHttpServer implements AutoCloseable {

    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final Set<String> ACCEPTED_PROTOCOL_VERSIONS = McpProtocol.SUPPORTED_VERSIONS;
    private static final List<String> PREFERRED_PROTOCOL_VERSIONS =
            List.of(McpProtocol.VERSION, "2025-06-18", "2025-03-26");
    static final int DEFAULT_PORT = 43123;
    /**
     * Request threads. All requests share this pool, so one thread stays reserved for
     * lifecycle and read requests while {@link #HOST_WORK_SLOTS} threads may wait on a
     * stalled host write.
     */
    private static final int HTTP_THREADS = 4;

    private static final int HOST_WORK_SLOTS = HTTP_THREADS - 1;

    /** A client that connects before the warm-up finishes waits at most this long per request. */
    private static final long GRAPH_READY_TIMEOUT_SECONDS = 30L;

    private final HttpServer server;
    private final ExecutorService executor;
    private final PluginLogger logger;
    /**
     * Tool surface, installed by {@link #installGraph} on a warm-up thread after the server is
     * already bound and published. Building it (domains + combined catalog + protocol) is the
     * single largest cost of this plugin's enable, and nothing before the first MCP request needs
     * it, so it stays off the plugin lifecycle path.
     */
    private volatile McpProtocol protocol;

    private volatile McpToolCatalog tools;
    private final java.util.concurrent.CountDownLatch graphReady = new java.util.concurrent.CountDownLatch(1);
    private volatile String graphFailure = "";
    private final McpAccessToken accessToken;
    private final Path connectionFile;
    private final Path bridgeFile;
    private final URI endpoint;
    private final Optional<McpStdioLaunch> stdioLaunch;
    private final WindowRateLimiter rateLimiter;
    private final boolean requireAuthForRead;
    private volatile McpSessionRegistry sessions;
    private volatile McpFileGrantDomain fileGrants;
    private volatile McpFileInspectDomain fileInspect;
    private volatile McpImageRegistrationDomain imageRegistration;
    private volatile McpAtlasDomain atlas;
    private volatile McpMocExportDomain mocExport;
    private volatile McpConnectionHistory history;
    private final McpHostWorkLane hostWork;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpHttpServer(
            final HttpServer server,
            final ExecutorService executor,
            final PluginLogger logger,
            final McpProtocol protocol,
            final McpToolCatalog tools,
            final McpAccessToken accessToken,
            final Path connectionFile,
            final Path bridgeFile,
            final URI endpoint,
            final Optional<McpStdioLaunch> stdioLaunch,
            final WindowRateLimiter rateLimiter,
            final boolean requireAuthForRead,
            final McpSessionRegistry sessions,
            final McpFileGrantDomain fileGrants,
            final McpFileInspectDomain fileInspect,
            final McpImageRegistrationDomain imageRegistration,
            final McpAtlasDomain atlas,
            final McpMocExportDomain mocExport,
            final McpConnectionHistory history) {
        this.server = server;
        this.executor = executor;
        this.logger = logger;
        this.protocol = protocol;
        this.tools = Objects.requireNonNull(tools, "tools");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.connectionFile = connectionFile;
        this.bridgeFile = bridgeFile;
        this.endpoint = endpoint;
        this.stdioLaunch = Objects.requireNonNull(stdioLaunch, "stdioLaunch");
        this.rateLimiter = rateLimiter;
        this.requireAuthForRead = requireAuthForRead;
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.fileGrants = Objects.requireNonNull(fileGrants, "fileGrants");
        this.fileInspect = Objects.requireNonNull(fileInspect, "fileInspect");
        this.imageRegistration = Objects.requireNonNull(imageRegistration, "imageRegistration");
        this.atlas = Objects.requireNonNull(atlas, "atlas");
        this.mocExport = Objects.requireNonNull(mocExport, "mocExport");
        this.history = Objects.requireNonNull(history, "history");
        this.hostWork = new McpHostWorkLane(HOST_WORK_SLOTS);
        graphReady.countDown();
    }

    /**
     * Constructs a transport whose tool surface is installed later by {@link #installGraph}.
     * Everything a client needs before its first tool call — bound socket, endpoint, access
     * token, stdio descriptor — is already in place.
     */
    private McpHttpServer(
            final HttpServer server,
            final ExecutorService executor,
            final PluginLogger logger,
            final McpAccessToken accessToken,
            final Path connectionFile,
            final Path bridgeFile,
            final URI endpoint,
            final Optional<McpStdioLaunch> stdioLaunch,
            final WindowRateLimiter rateLimiter,
            final boolean requireAuthForRead) {
        this.server = server;
        this.executor = executor;
        this.logger = logger;
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.connectionFile = connectionFile;
        this.bridgeFile = bridgeFile;
        this.endpoint = endpoint;
        this.stdioLaunch = Objects.requireNonNull(stdioLaunch, "stdioLaunch");
        this.rateLimiter = rateLimiter;
        this.requireAuthForRead = requireAuthForRead;
        this.hostWork = new McpHostWorkLane(HOST_WORK_SLOTS);
    }

    /** Installs the deferred tool surface and releases any handler waiting for it. */
    private void installGraph(
            final McpProtocol protocol,
            final McpToolCatalog tools,
            final McpSessionRegistry sessions,
            final McpFileGrantDomain fileGrants,
            final McpFileInspectDomain fileInspect,
            final McpImageRegistrationDomain imageRegistration,
            final McpAtlasDomain atlas,
            final McpMocExportDomain mocExport,
            final McpConnectionHistory history) {
        if (closed.get()) {
            // The transport closed while the surface was warming: release what was built instead
            // of installing it onto a stopped server.
            fileGrants.close();
            fileInspect.close();
            imageRegistration.close();
            atlas.close();
            mocExport.close();
            graphReady.countDown();
            return;
        }
        this.protocol = protocol;
        this.tools = tools;
        this.sessions = sessions;
        this.fileGrants = fileGrants;
        this.fileInspect = fileInspect;
        this.imageRegistration = imageRegistration;
        this.atlas = atlas;
        this.mocExport = mocExport;
        this.history = history;
        fileGrants.startMaintenance();
        graphReady.countDown();
    }

    private void failGraph(final Throwable failure) {
        graphFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        graphReady.countDown();
    }

    /**
     * Waits for the warm-up thread to publish the tool surface. A request that arrives before it
     * is ready is served once it is; a failed build answers 503 instead of hanging.
     */
    private void awaitGraph() throws IOException {
        try {
            if (!graphReady.await(GRAPH_READY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IOException("MCP tool surface is still initializing");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the MCP tool surface", interrupted);
        }
        if (protocol == null || tools == null) {
            throw new IOException("MCP tool surface is unavailable: " + graphFailure);
        }
    }

    static McpHttpServer start(final PluginContext context) throws IOException {
        Objects.requireNonNull(context, "context");
        final StartupStage stage = new StartupStage();
        try {
            stage.enter("context.logger()");
            final PluginLogger logger = context.logger();
            stage.enter("context.modelObjects()");
            final ModelObjectService modelObjects = context.modelObjects();
            stage.enter("context.modelHierarchyQuery()");
            final ModelHierarchyQueryService hierarchyQuery = context.modelHierarchyQuery();
            stage.enter("context.selectionQuery()");
            final SelectionQueryService selectionQuery = context.selectionQuery();
            stage.enter("context.cubismRead()");
            final CubismReadCapabilityService read = context.cubismRead();
            stage.enter("context.services().find(CubismClipMaskService.class)");
            final CubismClipMaskService clipMasks =
                    context.services().find(CubismClipMaskService.class).orElseGet(CubismClipMaskService::unavailable);
            stage.enter("context.cubism()");
            final CubismFacade cubism = context.cubism();
            stage.enter("context.cubism().history()");
            final CubismHistory history = optionalHistory(cubism);
            stage.enter("context.services().find(WorkspaceService.class)");
            final WorkspaceService workspace =
                    context.services().find(WorkspaceService.class).orElseGet(WorkspaceService::unavailable);
            stage.enter("context.services().find(WorkspaceLayoutService.class)");
            final WorkspaceLayoutService workspaceLayout = context.services()
                    .find(WorkspaceLayoutService.class)
                    .orElseGet(WorkspaceLayoutService::unavailable);
            stage.enter("context.diagnostics()");
            final DiagnosticReport diagnostics = context.diagnostics();
            stage.enter("context.services().find(EditorCommandService.class)");
            final EditorCommandService editorCommands =
                    context.services().find(EditorCommandService.class).orElseGet(EditorCommandService::unavailable);
            stage.enter("context.uiScheduler()");
            final UiScheduler uiScheduler = context.uiScheduler();
            stage.enter("context.userFiles()");
            final UserFileAccessService userFiles = context.userFiles();
            stage.enter("context.paths().stateDir()");
            final Path stateDir = context.paths().stateDir();
            stage.enter("port property");
            final int port = integerProperty("turboism.mcp.port", DEFAULT_PORT, 0, 65535);
            stage.enter("requests-per-minute property");
            final int requestsPerMinute = integerProperty("turboism.mcp.requestsPerMinute", 120, 10, 6000);
            stage.enter("require-auth-for-read property");
            final boolean requireAuthForRead = booleanProperty("turboism.mcp.requireAuthForRead", false);
            stage.enter("context dependency extraction");
            return start(
                    new Dependencies(
                            logger,
                            modelObjects,
                            hierarchyQuery,
                            selectionQuery,
                            read,
                            clipMasks,
                            cubism,
                            history,
                            workspace,
                            workspaceLayout,
                            diagnostics,
                            editorCommands,
                            uiScheduler,
                            stateDir,
                            port,
                            requestsPerMinute,
                            requireAuthForRead,
                            userFiles),
                    stage);
        } catch (McpStartupFailure failure) {
            throw failure;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw stage.failure(failure);
        }
    }

    static McpHttpServer start(final Dependencies dependencies) throws IOException {
        return start(dependencies, new StartupStage());
    }

    private static McpHttpServer start(final Dependencies dependencies, final StartupStage stage) throws IOException {
        final Dependencies checked = Objects.requireNonNull(dependencies, "dependencies");
        final PluginLogger logger = checked.logger();
        HttpServer server = null;
        ExecutorService executor = null;
        McpHttpServer transport = null;
        try {
            stage.enter("numeric loopback resolution");
            final InetAddress loopback = InetAddress.getByName("127.0.0.1");
            stage.enter("socket-address construction");
            final InetSocketAddress address = new InetSocketAddress(loopback, checked.port());
            stage.enter("HTTP server create/bind");
            server = HttpServer.create(address, 0);

            stage.enter("executor/transport construction");
            executor = Executors.newFixedThreadPool(HTTP_THREADS, new DaemonThreadFactory("turboism-mcp-http-"));
            server.setExecutor(executor);
            final int actualPort = server.getAddress().getPort();
            final URI endpoint = URI.create("http://127.0.0.1:" + actualPort + "/mcp");
            final Path connectionFile = checked.stateDir().resolve("mcp-connection.json");
            final McpExecutionBridge execution = new McpExecutionBridge(checked.uiScheduler());
            stage.enter("access-token publication");
            final McpAccessToken accessToken = McpAccessToken.loadOrCreate(checked.stateDir(), logger::warn);
            stage.enter("stdio bridge publication");
            final Path bridgeFile = McpStdioBridge.publish(checked.stateDir(), logger::warn);
            stage.enter("stdio launch descriptor");
            final Optional<McpStdioLaunch> stdioLaunch = McpStdioBridge.launch(checked.stateDir(), logger);
            stage.enter("transport construction");
            transport = new McpHttpServer(
                    server,
                    executor,
                    logger,
                    accessToken,
                    connectionFile,
                    bridgeFile,
                    endpoint,
                    stdioLaunch,
                    new WindowRateLimiter(checked.requestsPerMinute()),
                    checked.requireAuthForRead());
            stage.enter("context registration");
            server.createContext("/mcp", transport::handle);

            stage.enter("server start");
            server.start();

            stage.enter("connection-file publication");
            transport.writeConnectionFile();
            // "started" stays in this line: the endpoint is live and serving before the tool
            // surface finishes warming, and startup diagnostics key on it.
            logger.info("Turboism MCP server started on the local loopback interface (tool surface warming);"
                    + " startup profile:" + stage.profile());
            startToolSurfaceWarmup(transport, checked);
            return transport;
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            final String profile = stage.profile();
            closeAfterStartupFailure(transport, server, executor, failure);
            final McpStartupFailure reported = stage.failure(failure, profile);
            throw reported;
        }
    }

    /**
     * Warms the deferred tool surface on a daemon thread.
     *
     * <p>Domains, the combined catalog and the protocol are the largest part of this plugin's
     * startup (hundreds of milliseconds), and nothing before the first MCP request needs them.
     * A client that connects earlier is served once the surface lands; a build failure surfaces
     * as 503 with the recorded cause instead of blocking the editor's plugin load.</p>
     */
    private static void startToolSurfaceWarmup(final McpHttpServer transport, final Dependencies checked) {
        final Thread warmup = new Thread(
                () -> {
                    final long startedNanos = System.nanoTime();
                    try {
                        final GraphParts parts = buildGraph(checked);
                        transport.installGraph(
                                parts.protocol(),
                                parts.tools(),
                                parts.sessions(),
                                parts.fileGrants(),
                                parts.fileInspect(),
                                parts.imageRegistration(),
                                parts.atlas(),
                                parts.mocExport(),
                                parts.history());
                        transport.logger.info("Turboism MCP tool surface ready after "
                                + ((System.nanoTime() - startedNanos) / 1_000_000L) + "ms");
                    } catch (ThreadDeath | VirtualMachineError fatal) {
                        throw fatal;
                    } catch (RuntimeException | Error failure) {
                        transport.failGraph(failure);
                        transport.logger.warn("Turboism MCP tool surface failed to initialize: " + failure);
                    }
                },
                "turboism-mcp-tool-surface");
        warmup.setDaemon(true);
        warmup.start();
    }

    /** Deferred tool surface: everything a request handler needs once the editor is ready. */
    private record GraphParts(
            McpProtocol protocol,
            McpToolCatalog tools,
            McpSessionRegistry sessions,
            McpFileGrantDomain fileGrants,
            McpFileInspectDomain fileInspect,
            McpImageRegistrationDomain imageRegistration,
            McpAtlasDomain atlas,
            McpMocExportDomain mocExport,
            McpConnectionHistory history) {}

    private static GraphParts buildGraph(final Dependencies checked) {
        final PluginLogger logger = checked.logger();
        final McpExecutionBridge execution = new McpExecutionBridge(checked.uiScheduler());
        final McpTools legacyTools = new McpTools(
                checked.modelObjects(),
                checked.cubism(),
                checked.hierarchyQuery(),
                checked.selectionQuery(),
                checked.read(),
                checked.clipMasks(),
                logger,
                execution);
        final McpProductionDomainCatalog production = new McpProductionDomainCatalog(legacyTools);
        final McpParameterDomain parameters = new McpParameterDomain(checked.cubism(), execution);
        final McpResourceCatalog parameterResources = parameters.resourceCatalog();
        final McpGlueDomain glues = new McpGlueDomain(checked.cubism(), execution);
        final McpTextureDomain textures = new McpTextureDomain(checked.cubism());
        final McpFileGrantDomain fileGrants = new McpFileGrantDomain(checked.userFiles(), execution);
        final McpFileInspectDomain fileInspect = new McpFileInspectDomain(checked.userFiles(), fileGrants, execution);
        final McpRenderDomain render = new McpRenderDomain(checked.cubism(), execution);
        final McpPoseGridDomain poses = new McpPoseGridDomain(checked.cubism(), execution, render);
        final McpAuthoringSnapshotStore authoringSnapshots = new McpAuthoringSnapshotStore();
        final McpStoredAuthoringDomain storedAuthoring =
                new McpStoredAuthoringDomain(checked.cubism(), execution, authoringSnapshots);
        final McpDocumentLifecycleDomain documents = new McpDocumentLifecycleDomain(
                checked.cubism(),
                checked.editorCommands(),
                execution,
                () -> storedAuthoring.context().binding());
        final McpDocumentFileDomain documentFiles = new McpDocumentFileDomain(
                checked.editorCommands(),
                fileGrants,
                execution,
                () -> storedAuthoring.context().binding(),
                documents);
        final McpEvaluatedMeshDomain evaluated =
                new McpEvaluatedMeshDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpSourcePropertiesDomain properties = new McpSourcePropertiesDomain(
                checked.cubism(), execution, authoringSnapshots, storedAuthoring::context);
        final McpAssetSourceDomain assetSources = new McpAssetSourceDomain(execution, storedAuthoring::context);
        final McpImageBindingDomain imageBindings =
                new McpImageBindingDomain(checked.cubism(), execution, authoringSnapshots, storedAuthoring::context);
        final McpImageRegistrationDomain imageRegistration =
                new McpImageRegistrationDomain(checked.userFiles(), fileGrants, imageBindings, execution);
        final McpAtlasDomain atlas = new McpAtlasDomain(checked.cubism(), execution, storedAuthoring::context);
        final McpMocExportDomain mocExport = new McpMocExportDomain(
                checked.cubism(), checked.userFiles(), fileGrants, execution, storedAuthoring::context);
        final McpParameterStructureDomain parameterStructure =
                new McpParameterStructureDomain(checked.cubism(), execution, storedAuthoring::context);
        final McpBindingInitializationDomain bindingInitialization = new McpBindingInitializationDomain(
                checked.cubism(), execution, authoringSnapshots, storedAuthoring::context);
        final McpMeshCreationDomain meshCreation =
                new McpMeshCreationDomain(checked.cubism(), execution, storedAuthoring::context);
        final McpMaskDomain masks = new McpMaskDomain(checked.cubism(), execution, storedAuthoring::context);
        final McpGeometryValidationDomain geometryValidation =
                new McpGeometryValidationDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpContinuityDomain continuity =
                new McpContinuityDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpRigTransferDomain rigTransfer =
                new McpRigTransferDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpRotationRigDomain rotationRig =
                new McpRotationRigDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpWarpValidationDomain warpValidation =
                new McpWarpValidationDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpMeshRigDomain meshRig = new McpMeshRigDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpWarpRigDomain warpRig = new McpWarpRigDomain(authoringSnapshots, execution, storedAuthoring::context);
        final McpKeyformWriteDomain keyforms =
                new McpKeyformWriteDomain(checked.cubism(), execution, authoringSnapshots, storedAuthoring::context);
        final McpHistoryCommandDomain historyCommands =
                new McpHistoryCommandDomain(checked.history(), checked.editorCommands(), execution);
        final McpRuntimeDiagnostics runtimeDiagnostics = new McpRuntimeDiagnostics();
        final McpDiagnosticsDomain diagnostics = new McpDiagnosticsDomain(
                checked.cubism(),
                checked.workspace(),
                checked.workspaceLayout(),
                checked.diagnostics(),
                runtimeDiagnostics,
                parameterResources,
                execution);
        final McpToolCatalog tools = McpTransactionDomain.attach(
                McpToolCatalog.combine(
                        production.tools(),
                        parameters.toolCatalog(),
                        parameterStructure.tools(),
                        meshCreation.tools(),
                        bindingInitialization.tools(),
                        documents.tools(),
                        historyCommands.tools(),
                        glues.tools(),
                        textures.tools(execution),
                        render.tools(),
                        poses.tools(),
                        storedAuthoring.tools(),
                        keyforms.tools(),
                        geometryValidation.tools(),
                        continuity.tools(),
                        rigTransfer.tools(),
                        warpValidation.tools(),
                        rotationRig.tools(),
                        warpRig.tools(),
                        meshRig.tools(),
                        masks.tools(),
                        evaluated.tools(),
                        properties.tools(),
                        assetSources.tools(),
                        imageBindings.tools(),
                        imageRegistration.tools(),
                        atlas.tools(),
                        mocExport.tools(),
                        fileGrants.tools(),
                        documentFiles.tools(),
                        fileInspect.tools()),
                optionalAuthoringTransactions(checked.cubism()),
                execution,
                runtimeDiagnostics::observe);
        final McpResourceCatalog resources = runtimeDiagnostics.observe(McpResourceCatalog.combine(
                production.resourceCatalog(),
                diagnostics.resourceCatalog(),
                parameterResources,
                historyCommands.resources()));
        final McpConnectionHistory history = new McpConnectionHistory();
        final McpSessionRegistry sessions =
                new McpSessionRegistry(java.time.Duration.ofMinutes(30), java.time.Clock.systemUTC(), session -> {
                    fileGrants.dropSession(session);
                    fileInspect.dropSession(session);
                    imageRegistration.dropSession(session);
                    atlas.dropSession(session);
                    mocExport.dropSession(session);
                    history.record(McpConnectionHistory.Event.SESSION_EXPIRED, "", "idle timeout");
                });
        return new GraphParts(
                McpProtocol.forCatalogs(tools, resources, McpPromptCatalog.defaults()),
                tools,
                sessions,
                fileGrants,
                fileInspect,
                imageRegistration,
                atlas,
                mocExport,
                history);
    }

    /**
     * Best-effort cleanup of every allocated resource after a failed startup.
     * Every independent cleanup Throwable is added as suppressed to the exact
     * original startup Throwable; the wrapper cause is never replaced. A
     * cleanup that rethrows the original object itself is not self-suppressed
     * (addSuppressed rejects self-suppression).
     */
    static void closeAfterStartupFailure(
            final McpHttpServer transport,
            final HttpServer server,
            final ExecutorService executor,
            final Throwable original) {
        if (transport != null) {
            try {
                transport.close();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
        if (server != null) {
            try {
                server.stop(0);
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (ThreadDeath | VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable cleanup) {
                if (cleanup != original) {
                    original.addSuppressed(cleanup);
                }
            }
        }
    }

    URI endpoint() {
        return endpoint;
    }

    Path connectionFile() {
        return connectionFile;
    }

    Optional<McpStdioLaunch> stdioLaunch() {
        return stdioLaunch;
    }

    String stdioClientConfig() {
        return McpStdioBridge.clientConfigJson(connectionFile.getParent());
    }

    List<McpConnectionHistory.Entry> connectionHistory() {
        final McpConnectionHistory current = history;
        // Before the warm-up publishes the surface there is no history to report; the window
        // renders an empty list rather than failing on a not-yet-built domain graph.
        return current == null ? List.of() : current.snapshot();
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try (exchange) {
            final Headers responseHeaders = exchange.getResponseHeaders();
            awaitGraph();
            responseHeaders.set("Cache-Control", "no-store");
            responseHeaders.set("X-Content-Type-Options", "nosniff");

            // DNS-rebinding surface: the socket only binds 127.0.0.1, but a
            // browser sandboxed to a hostile origin can still reach it. The Host
            // header must therefore name the loopback listener itself.
            if (!hostAllowed(exchange.getRequestHeaders().getFirst("Host"))) {
                sendEmpty(exchange, 403);
                return;
            }
            if (!originAllowed(exchange.getRequestHeaders().getFirst("Origin"))) {
                sendEmpty(exchange, 403);
                return;
            }
            if (!rateLimiter.acquire()) {
                responseHeaders.set("Retry-After", "60");
                sendEmpty(exchange, 429);
                return;
            }
            final String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                responseHeaders.set("Allow", "POST, DELETE");
                sendEmpty(exchange, 405);
                return;
            }
            if ("DELETE".equalsIgnoreCase(method)) {
                if (!authorized(exchange)) {
                    unauthorized(exchange);
                    return;
                }
                final String sessionId = exchange.getRequestHeaders().getFirst("MCP-Session-Id");
                if (sessionId == null || !sessions.remove(sessionId)) {
                    sendEmpty(exchange, 404);
                } else {
                    fileGrants.dropSession(sessionId);
                    fileInspect.dropSession(sessionId);
                    imageRegistration.dropSession(sessionId);
                    atlas.dropSession(sessionId);
                    mocExport.dropSession(sessionId);
                    history.record(McpConnectionHistory.Event.SESSION_CLOSED, "", "client requested session close");
                    sendEmpty(exchange, 200);
                }
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                responseHeaders.set("Allow", "POST, DELETE");
                sendEmpty(exchange, 405);
                return;
            }
            if (!acceptsMcp(exchange.getRequestHeaders().getFirst("Accept"))) {
                sendEmpty(exchange, 406);
                return;
            }
            if (!isJson(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                sendEmpty(exchange, 415);
                return;
            }
            final String protocolVersion = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            final String requestedProtocolVersion = protocolVersion == null ? null : protocolVersion.strip();
            if (requestedProtocolVersion != null
                    && !requestedProtocolVersion.isEmpty()
                    && !ACCEPTED_PROTOCOL_VERSIONS.contains(requestedProtocolVersion)) {
                sendJson(
                        exchange,
                        400,
                        McpProtocol.unsupportedProtocolVersion(requestedProtocolVersion, PREFERRED_PROTOCOL_VERSIONS));
                return;
            }

            final byte[] body;
            try {
                body = readBounded(exchange);
            } catch (BodyTooLarge failure) {
                sendEmpty(exchange, 413);
                return;
            } catch (IllegalArgumentException failure) {
                sendEmpty(exchange, 400);
                return;
            }
            final Object request;
            try {
                request = Json.parseObject(body);
            } catch (IllegalArgumentException failure) {
                sendJson(exchange, 200, McpProtocol.parseError(failure.getMessage()));
                return;
            }
            final boolean initializeRequest = isInitializeRequest(request);
            final boolean initializedNotification = isInitializedNotification(request);
            final String sessionId = exchange.getRequestHeaders().getFirst("MCP-Session-Id");
            if (initializeRequest) {
                final McpProtocol.Outcome outcome = protocol.handle(request);
                if (outcome.body() == null) {
                    sendEmpty(exchange, outcome.status());
                } else {
                    McpProtocol.negotiatedVersion(outcome).ifPresent(version -> {
                        final McpSessionRegistry.Session session = sessions.create(version);
                        responseHeaders.set("MCP-Session-Id", session.id());
                        history.record(
                                McpConnectionHistory.Event.SESSION_CREATED, clientName(request), "protocol " + version);
                    });
                    sendJson(exchange, outcome.status(), outcome.body());
                }
                return;
            }
            if (requestedProtocolVersion == null || requestedProtocolVersion.isEmpty()) {
                sendEmpty(exchange, 400);
                return;
            }
            final McpSessionRegistry.Session session =
                    sessionId == null ? null : sessions.find(sessionId).orElse(null);
            if (session == null) {
                sendEmpty(exchange, 404);
                return;
            }
            if (!session.protocolVersion().equals(requestedProtocolVersion)) {
                sendEmpty(exchange, 400);
                return;
            }
            if (initializedNotification) {
                sessions.initialize(session.id());
                history.record(McpConnectionHistory.Event.SESSION_INITIALIZED, "", "client completed initialization");
                sendEmpty(exchange, 202);
                return;
            }
            if (!session.initialized() && !isPingRequest(request)) {
                sendEmpty(exchange, 400);
                return;
            }
            if (requiresBearer(request) && !authorized(exchange)) {
                unauthorized(exchange);
                return;
            }
            history.record(McpConnectionHistory.Event.REQUEST, "", requestMethod(request));
            final boolean hostWork = waitsOnHost(request);
            if (hostWork && !this.hostWork.tryEnter()) {
                // The host-work lane is full: fail explicitly rather than queueing behind a
                // stalled host write. Lifecycle and read requests keep their own thread.
                responseHeaders.set("Retry-After", "1");
                sendEmpty(exchange, 503);
                return;
            }
            final McpProtocol.Outcome outcome;
            try {
                outcome = protocol.handle(request, session.id());
            } finally {
                if (hostWork) {
                    this.hostWork.exit();
                }
            }
            if (outcome.body() == null) {
                sendEmpty(exchange, outcome.status());
            } else {
                sendJson(exchange, outcome.status(), outcome.body());
            }
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            logger.error("Unhandled Turboism MCP transport failure", failure);
            if (exchange.getResponseCode() < 0) {
                try {
                    sendEmpty(exchange, 500);
                } catch (IOException ignored) {
                    // The client may already have disconnected.
                }
            }
        }
    }

    /**
     * Reports whether serving this request may block on host execution.
     *
     * <p>Every {@code tools/call} handler may submit work to the host thread through
     * {@link McpExecutionBridge}; a started host write cannot be cancelled, so the serving
     * thread waits for the definitive result. {@code resources/read} handlers submit their
     * read through the same boundary. Lifecycle, discovery, session, and authorization
     * paths never wait on the host, so they stay outside the lane and keep a free thread.</p>
     */
    private static boolean waitsOnHost(final Object request) {
        if (!(request instanceof Map<?, ?> values)) return false;
        final Object method = values.get("method");
        return "tools/call".equals(method) || "resources/read".equals(method);
    }

    /** @return host-work admissions currently held; test and diagnostics support only */
    int hostWorkInFlight() {
        return hostWork.inFlight();
    }

    /** @return maximum concurrent host-work waits; test and diagnostics support only */
    int hostWorkBound() {
        return hostWork.bound();
    }

    private byte[] readBounded(final HttpExchange exchange) throws IOException {
        final String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                final long length = Long.parseLong(contentLength);
                if (length < 0 || length > MAX_BODY_BYTES) throw new BodyTooLarge();
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid Content-Length", failure);
            }
        }
        try (InputStream input = exchange.getRequestBody();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_BODY_BYTES) throw new BodyTooLarge();
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private void writeConnectionFile() throws IOException {
        final LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("transport", "streamable-http");
        content.put("endpoint", endpoint.toString());
        content.put("protocolVersion", McpProtocol.VERSION);
        content.put(
                "authentication",
                Map.of(
                        "scheme", "bearer",
                        "scope", "mutating tools/call methods and DELETE",
                        "tokenFile", McpAccessToken.FILE_NAME));
        content.put("stdio", McpStdioBridge.clientConfig(connectionFile.getParent()));
        content.put("pid", ProcessHandle.current().pid());
        content.put("startedAt", Instant.now().toString());
        McpStateFiles.publish(connectionFile, ".mcp-connection-", Json.bytes(content), logger::warn);
    }

    /**
     * /**
     * Mutating operations require {@code Authorization: Bearer <mcp.token>}:
     * session close plus every {@code tools/call} whose registered tool is not
     * effect {@code READ}. Read-only tools, lifecycle methods, and discovery stay
     * credential-free behind the existing loopback + Origin + session checks.
     * Setting {@code turboism.mcp.requireAuthForRead=true} additionally gates
     * every read-effect {@code tools/call} behind the same bearer token.
     */
    private boolean requiresBearer(final Object request) {
        if (!(request instanceof Map<?, ?> values)) return false;
        if (!"tools/call".equals(values.get("method"))) return false;
        if (requireAuthForRead) return true;
        final Object params = values.get("params");
        if (!(params instanceof Map<?, ?> paramValues)) return false;
        final Object name = paramValues.get("name");
        if (!(name instanceof String toolName)) return false;
        try {
            return tools.registration(toolName).effect() != McpOperationEffect.READ;
        } catch (IllegalArgumentException unknownTool) {
            return false;
        }
    }

    private boolean authorized(final HttpExchange exchange) {
        return accessToken.accepts(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private static void unauthorized(final HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer realm=\"turboism-mcp\"");
        sendEmpty(exchange, 401);
    }

    /**
     * Accepts only {@code 127.0.0.1}, {@code localhost}, and {@code [::1]} — each
     * with an optional {@code :port} suffix — as the {@code Host} header of an
     * incoming request. A missing or foreign value fails closed.
     */
    static boolean hostAllowed(final String host) {
        if (host == null) {
            return false;
        }
        final String value = host.strip();
        if (value.isEmpty()) {
            return false;
        }
        if (value.startsWith("[")) {
            final int close = value.indexOf(']');
            if (close < 0 || !"::1".equals(value.substring(1, close))) {
                return false;
            }
            final String suffix = value.substring(close + 1);
            return suffix.isEmpty() || (suffix.startsWith(":") && isPort(suffix.substring(1)));
        }
        final int colon = value.indexOf(':');
        if (colon >= 0 && !isPort(value.substring(colon + 1))) {
            return false;
        }
        final String name = colon < 0 ? value : value.substring(0, colon);
        return "127.0.0.1".equals(name) || "localhost".equalsIgnoreCase(name);
    }

    private static boolean isPort(final String text) {
        if (text.isEmpty() || text.length() > 5) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        try {
            return Integer.parseInt(text) <= 65535;
        } catch (NumberFormatException overflow) {
            return false;
        }
    }

    private static boolean originAllowed(final String origin) {
        if (origin == null || origin.isBlank()) return true;
        try {
            final URI value = URI.create(origin);
            final String scheme = value.getScheme();
            final String host = value.getHost();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && host != null
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))
                    && value.getRawUserInfo() == null
                    && value.getRawQuery() == null
                    && value.getRawFragment() == null
                    && (value.getRawPath() == null || value.getRawPath().isEmpty() || "/".equals(value.getRawPath()));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    private static boolean acceptsMcp(final String accept) {
        if (accept == null) return false;
        final String normalized = accept.toLowerCase(Locale.ROOT);
        return normalized.contains("application/json") && normalized.contains("text/event-stream");
    }

    private static boolean isJson(final String contentType) {
        if (contentType == null) return false;
        final String normalized = contentType.toLowerCase(Locale.ROOT).strip();
        return normalized.equals("application/json") || normalized.startsWith("application/json;");
    }

    private static boolean isInitializeRequest(final Object request) {
        return methodIs(request, "initialize");
    }

    private static boolean isInitializedNotification(final Object request) {
        return methodIs(request, "notifications/initialized");
    }

    private static boolean isPingRequest(final Object request) {
        return methodIs(request, "ping");
    }

    private static boolean methodIs(final Object request, final String method) {
        if (!(request instanceof Map<?, ?> values)) return false;
        return method.equals(values.get("method"));
    }

    private static String requestMethod(final Object request) {
        if (!(request instanceof Map<?, ?> values)) return "unknown request";
        final Object method = values.get("method");
        return method instanceof String text && !text.isBlank() ? text : "unknown request";
    }

    private static String clientName(final Object request) {
        if (!(request instanceof Map<?, ?> values)) return "unknown client";
        final Object params = values.get("params");
        if (!(params instanceof Map<?, ?> paramValues)) return "unknown client";
        final Object clientInfo = paramValues.get("clientInfo");
        if (!(clientInfo instanceof Map<?, ?> clientValues)) return "unknown client";
        final Object name = clientValues.get("name");
        return name instanceof String text && !text.isBlank() ? text : "unknown client";
    }

    private static void sendJson(final HttpExchange exchange, final int status, final Map<String, ?> body)
            throws IOException {
        final byte[] bytes = Json.bytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void sendEmpty(final HttpExchange exchange, final int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static boolean booleanProperty(final String name, final boolean defaultValue) {
        final String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return defaultValue;
        final String normalized = configured.strip().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) return true;
        if ("false".equals(normalized)) return false;
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static int integerProperty(
            final String name, final int defaultValue, final int minimum, final int maximum) {
        final String configured = System.getProperty(name);
        if (configured == null || configured.isBlank()) return defaultValue;
        try {
            final int value = Integer.parseInt(configured.strip());
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer", failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        // The tool surface may still be warming: every domain is released only when it exists,
        // and a warm-up that finishes after this point sees a closed server and exits.
        final McpImageRegistrationDomain currentImage = imageRegistration;
        if (currentImage != null) currentImage.close();
        final McpAtlasDomain currentAtlas = atlas;
        if (currentAtlas != null) currentAtlas.close();
        final McpMocExportDomain currentMoc = mocExport;
        if (currentMoc != null) currentMoc.close();
        final McpFileInspectDomain currentInspect = fileInspect;
        if (currentInspect != null) currentInspect.close();
        final McpFileGrantDomain currentGrants = fileGrants;
        if (currentGrants != null) currentGrants.close();
        executor.shutdownNow();
        try {
            if (Files.isRegularFile(connectionFile, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(connectionFile)) {
                Files.deleteIfExists(connectionFile);
            } else if (Files.exists(connectionFile, LinkOption.NOFOLLOW_LINKS)) {
                logger.warn("Refused to remove an unsafe MCP connection file");
            }
        } catch (IOException failure) {
            logger.warn("Could not remove the MCP connection file");
        }
        logger.info("Turboism MCP server stopped");
    }

    /**
     * Last-entered deterministic startup stage, advanced before every step.
     */
    private static final class StartupStage {
        private String current = "context dependency extraction";
        private final long startedNanos = System.nanoTime();
        private long markNanos = startedNanos;
        private final StringBuilder timings = new StringBuilder();

        private void enter(final String name) {
            final long now = System.nanoTime();
            timings.append(' ').append(current).append('=').append((now - markNanos) / 1_000_000L);
            markNanos = now;
            this.current = name;
        }

        /** Milliseconds spent inside the last entered stage. */
        private long lastStageMillis() {
            return (System.nanoTime() - markNanos) / 1_000_000L;
        }

        private String profile() {
            final long now = System.nanoTime();
            return timings + " " + current + "=" + ((now - markNanos) / 1_000_000L) + " totalMs="
                    + ((now - startedNanos) / 1_000_000L);
        }

        private McpStartupFailure failure(final Throwable original) {
            return failure(original, "");
        }

        private McpStartupFailure failure(final Throwable original, final String profile) {
            return new McpStartupFailure(current, frameChain(original), original, profile);
        }

        private static String frameChain(final Throwable original) {
            // Bounded diagnostic frame chain: at most the first six frames in
            // original order, one line, class.method[:line] only, no file paths.
            final StackTraceElement[] trace = original.getStackTrace();
            if (trace.length == 0) {
                return "<empty stack>";
            }
            final StringBuilder chain = new StringBuilder();
            final int limit = Math.min(trace.length, 6);
            for (int index = 0; index < limit; index++) {
                if (index > 0) {
                    chain.append(", ");
                }
                final StackTraceElement frame = trace[index];
                chain.append(frame.getClassName()).append('.').append(frame.getMethodName());
                if (frame.getLineNumber() >= 0) {
                    chain.append(':').append(frame.getLineNumber());
                }
            }
            return chain.toString();
        }
    }

    /**
     * Diagnostic-only wrapper: reports the startup stage where the original
     * failure surfaced and a bounded, ordered frame chain of at most six
     * original frames, keeping the exact original Throwable as cause.
     */
    static final class McpStartupFailure extends IOException {
        private final String stage;

        private McpStartupFailure(
                final String stage, final String frameChain, final Throwable cause, final String profile) {
            super(
                    "Turboism MCP startup failed at stage '" + stage + "'" + " (original failure at " + frameChain
                            + ")"
                            + (profile == null || profile.isBlank() ? "" : " | start-state profile:" + profile),
                    cause);
            this.stage = stage;
        }

        String stage() {
            return stage;
        }
    }

    /** Exact-version absence of the history capability must not prevent read-only MCP startup. */
    static CubismHistory optionalHistory(final CubismFacade cubism) {
        try {
            return cubism.history();
        } catch (CubismEditorApiUnavailableException unavailable) {
            return CubismHistory.unavailable();
        }
    }

    /** Exact-version absence of this optional backend must not prevent read-only MCP startup. */
    static AuthoringTransactionService optionalAuthoringTransactions(final CubismFacade cubism) {
        try {
            return cubism.authoringTransactions();
        } catch (CubismEditorApiUnavailableException unavailable) {
            return AuthoringTransactionService.unavailable();
        }
    }

    record Dependencies(
            PluginLogger logger,
            ModelObjectService modelObjects,
            ModelHierarchyQueryService hierarchyQuery,
            SelectionQueryService selectionQuery,
            CubismReadCapabilityService read,
            CubismClipMaskService clipMasks,
            CubismFacade cubism,
            CubismHistory history,
            WorkspaceService workspace,
            WorkspaceLayoutService workspaceLayout,
            DiagnosticReport diagnostics,
            EditorCommandService editorCommands,
            UiScheduler uiScheduler,
            Path stateDir,
            int port,
            int requestsPerMinute,
            boolean requireAuthForRead,
            UserFileAccessService userFiles) {
        Dependencies(
                PluginLogger logger,
                ModelObjectService modelObjects,
                ModelHierarchyQueryService hierarchyQuery,
                SelectionQueryService selectionQuery,
                CubismReadCapabilityService read,
                CubismClipMaskService clipMasks,
                CubismFacade cubism,
                CubismHistory history,
                WorkspaceService workspace,
                WorkspaceLayoutService workspaceLayout,
                DiagnosticReport diagnostics,
                EditorCommandService editorCommands,
                UiScheduler uiScheduler,
                Path stateDir,
                int port,
                int requestsPerMinute,
                boolean requireAuthForRead) {
            this(
                    logger,
                    modelObjects,
                    hierarchyQuery,
                    selectionQuery,
                    read,
                    clipMasks,
                    cubism,
                    history,
                    workspace,
                    workspaceLayout,
                    diagnostics,
                    editorCommands,
                    uiScheduler,
                    stateDir,
                    port,
                    requestsPerMinute,
                    requireAuthForRead,
                    UserFileAccessService.unavailable());
        }

        Dependencies(
                final PluginLogger logger,
                final ModelObjectService modelObjects,
                final ModelHierarchyQueryService hierarchyQuery,
                final SelectionQueryService selectionQuery,
                final CubismReadCapabilityService read,
                final CubismClipMaskService clipMasks,
                final UiScheduler uiScheduler,
                final Path stateDir,
                final int port,
                final int requestsPerMinute) {
            this(
                    logger,
                    modelObjects,
                    hierarchyQuery,
                    selectionQuery,
                    read,
                    clipMasks,
                    unavailableCubism(),
                    CubismHistory.unavailable(),
                    WorkspaceService.unavailable(),
                    WorkspaceLayoutService.unavailable(),
                    emptyDiagnostics(),
                    EditorCommandService.unavailable(),
                    uiScheduler,
                    stateDir,
                    port,
                    requestsPerMinute,
                    false);
        }

        static DiagnosticReport emptyDiagnostics() {
            return new DiagnosticReport() {
                @Override
                public Instant createdAt() {
                    return null;
                }

                @Override
                public java.util.List<Problem> problems() {
                    return java.util.List.of();
                }
            };
        }

        static CubismFacade unavailableCubism() {
            return new CubismFacade() {
                @Override
                public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                    return null;
                }

                @Override
                public java.util.Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                    return java.util.Optional.empty();
                }

                @Override
                public boolean isHostPresent() {
                    return false;
                }
            };
        }

        Dependencies {
            logger = Objects.requireNonNull(logger, "logger");
            modelObjects = Objects.requireNonNull(modelObjects, "modelObjects");
            hierarchyQuery = Objects.requireNonNull(hierarchyQuery, "hierarchyQuery");
            selectionQuery = Objects.requireNonNull(selectionQuery, "selectionQuery");
            read = Objects.requireNonNull(read, "read");
            clipMasks = Objects.requireNonNull(clipMasks, "clipMasks");
            cubism = Objects.requireNonNull(cubism, "cubism");
            history = Objects.requireNonNull(history, "history");
            workspace = Objects.requireNonNull(workspace, "workspace");
            workspaceLayout = Objects.requireNonNull(workspaceLayout, "workspaceLayout");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            editorCommands = Objects.requireNonNull(editorCommands, "editorCommands");
            userFiles = Objects.requireNonNull(userFiles, "userFiles");
            uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
            stateDir = Objects.requireNonNull(stateDir, "stateDir")
                    .toAbsolutePath()
                    .normalize();
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be between 0 and 65535");
            }
            if (requestsPerMinute < 1 || requestsPerMinute > 6000) {
                throw new IllegalArgumentException("requestsPerMinute must be between 1 and 6000");
            }
        }
    }

    private static final class BodyTooLarge extends IOException {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        private DaemonThreadFactory(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(final Runnable task) {
            final Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class WindowRateLimiter {
        private final int limit;
        private long windowStart = System.nanoTime();
        private int count;

        private WindowRateLimiter(final int limit) {
            this.limit = limit;
        }

        private synchronized boolean acquire() {
            final long now = System.nanoTime();
            if (now - windowStart >= 60_000_000_000L) {
                windowStart = now;
                count = 0;
            }
            if (count >= limit) return false;
            count++;
            return true;
        }
    }
}
