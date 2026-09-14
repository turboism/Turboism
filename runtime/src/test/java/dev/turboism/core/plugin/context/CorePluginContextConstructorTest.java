package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.CubismEditorApiUnavailableException;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskSubmission;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the public {@link RuntimeHostAdapterAccess} constructor overloads of
 * {@link CorePluginContext} from the real entry points: the 3–6 argument convenience overloads
 * must assemble through the same private default path as the legal two-argument entry instead of
 * padding {@code null} into the strict chain, while the complete 7–9 argument overloads keep
 * their required non-null checks and established wrapping semantics.
 */
class CorePluginContextConstructorTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);

    /** The optional service slots a constructor argument can install; nothing else may appear. */
    private static final Set<PluginService> OPTIONAL_SLOTS = Set.of(
        PluginService.LOCALIZATION,
        PluginService.TASKS,
        PluginService.STORAGE,
        PluginService.USER_FILES,
        PluginService.HOST_READS,
        PluginService.RUNTIME_SETTINGS,
        PluginService.FILE_CHOOSER_HISTORY
    );

    @Test
    void twoArgHostAccessEntryRemainsTheLegalBaseline() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final CorePluginContext context =
                new CorePluginContext(fixture.dependencies, fixture.session);

            assertCoreInvariants(context, fixture.dependencies);
            assertOmittedServiceDefaults(context);
            assertOptionalAvailability(context, Set.of());
        }
    }

    @Test
    void threeArgConvenienceForwardsLocalizationAndDefaultsTheRest() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final PluginLocalization localization = localization();
            final CorePluginContext context = new CorePluginContext(
                fixture.dependencies,
                fixture.session,
                localization
            );

            assertCoreInvariants(context, fixture.dependencies);
            assertSame(localization, context.localization());
            assertThrows(UnsupportedOperationException.class, context::tasks);
            assertThrows(UnsupportedOperationException.class, context::storage);
            assertThrows(UnsupportedOperationException.class, context::userFiles);
            assertThrows(UnsupportedOperationException.class, context::hostReads);
            assertThrows(UnsupportedOperationException.class, context::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                context.fileChooserHistory()
            );
            assertOptionalAvailability(context, Set.of(PluginService.LOCALIZATION));
        }
    }

    @Test
    void fourArgConvenienceForwardsExplicitServicesAndDefaultsTheRest() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final CorePluginContext context = new CorePluginContext(
                fixture.dependencies,
                fixture.session,
                localization,
                tasks
            );

            assertCoreInvariants(context, fixture.dependencies);
            assertSame(localization, context.localization());
            assertSame(tasks, context.tasks());
            assertThrows(UnsupportedOperationException.class, context::storage);
            assertThrows(UnsupportedOperationException.class, context::userFiles);
            assertThrows(UnsupportedOperationException.class, context::hostReads);
            assertThrows(UnsupportedOperationException.class, context::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                context.fileChooserHistory()
            );
            assertOptionalAvailability(
                context,
                Set.of(PluginService.LOCALIZATION, PluginService.TASKS)
            );
        }
    }

    @Test
    void fiveArgConvenienceForwardsExplicitServicesAndDefaultsTheRest() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final PluginStorage storage = pluginStorage();
            final CorePluginContext context = new CorePluginContext(
                fixture.dependencies,
                fixture.session,
                localization,
                tasks,
                storage
            );

            assertCoreInvariants(context, fixture.dependencies);
            assertSame(localization, context.localization());
            assertSame(tasks, context.tasks());
            assertSame(storage, context.storage());
            assertThrows(UnsupportedOperationException.class, context::userFiles);
            assertThrows(UnsupportedOperationException.class, context::hostReads);
            assertThrows(UnsupportedOperationException.class, context::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                context.fileChooserHistory()
            );
            assertOptionalAvailability(
                context,
                Set.of(
                    PluginService.LOCALIZATION,
                    PluginService.TASKS,
                    PluginService.STORAGE
                )
            );
        }
    }

    @Test
    void sixArgConvenienceForwardsExplicitServicesAndDefaultsTheRest() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final PluginStorage storage = pluginStorage();
            final UserFileAccessService userFiles = userFiles();
            final CorePluginContext context = new CorePluginContext(
                fixture.dependencies,
                fixture.session,
                localization,
                tasks,
                storage,
                userFiles
            );

            assertCoreInvariants(context, fixture.dependencies);
            assertSame(localization, context.localization());
            assertSame(tasks, context.tasks());
            assertSame(storage, context.storage());
            assertSame(userFiles, context.userFiles());
            assertThrows(UnsupportedOperationException.class, context::hostReads);
            assertThrows(UnsupportedOperationException.class, context::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                context.fileChooserHistory()
            );
            assertOptionalAvailability(
                context,
                Set.of(
                    PluginService.LOCALIZATION,
                    PluginService.TASKS,
                    PluginService.STORAGE,
                    PluginService.USER_FILES
                )
            );
        }
    }

    @Test
    void convenienceOverloadWithAllNullOptionalsMatchesTheTwoArgBaseline() throws Exception {
        try (Fixture control = new Fixture(TEMP); Fixture underTest = new Fixture(TEMP)) {
            final CorePluginContext baseline =
                new CorePluginContext(control.dependencies, control.session);
            final CorePluginContext context = new CorePluginContext(
                underTest.dependencies,
                underTest.session,
                null,
                null,
                null,
                null
            );

            assertCoreInvariants(context, underTest.dependencies);
            assertOmittedServiceDefaults(context);
            assertEquals(baseline.availableServices(), context.availableServices());
            assertEquals(
                baseline.cubismRead().activeProject(),
                context.cubismRead().activeProject()
            );
        }
    }

    @Test
    void strictOverloadsAssembleWithAllExplicitServices() throws Exception {
        try (Fixture seven = new Fixture(TEMP);
             Fixture eight = new Fixture(TEMP);
             Fixture nine = new Fixture(TEMP)) {
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final PluginStorage storage = pluginStorage();
            final UserFileAccessService userFiles = userFiles();
            final AsyncHostReadService hostReads = hostReads();

            final CorePluginContext sevenArg = new CorePluginContext(
                seven.dependencies,
                seven.session,
                localization,
                tasks,
                storage,
                userFiles,
                hostReads
            );
            assertCoreInvariants(sevenArg, seven.dependencies);
            assertSame(localization, sevenArg.localization());
            assertSame(tasks, sevenArg.tasks());
            assertSame(storage, sevenArg.storage());
            assertSame(userFiles, sevenArg.userFiles());
            assertSame(hostReads, sevenArg.hostReads());
            assertThrows(UnsupportedOperationException.class, sevenArg::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                sevenArg.fileChooserHistory()
            );
            assertOptionalAvailability(
                sevenArg,
                Set.of(
                    PluginService.LOCALIZATION,
                    PluginService.TASKS,
                    PluginService.STORAGE,
                    PluginService.USER_FILES,
                    PluginService.HOST_READS
                )
            );

            final dev.turboism.sdk.runtime.RuntimeSettingsService settings = runtimeSettings();
            final CorePluginContext eightArg = new CorePluginContext(
                eight.dependencies,
                eight.session,
                localization,
                tasks,
                storage,
                userFiles,
                hostReads,
                settings
            );
            assertSame(settings, eightArg.runtimeSettings());
            assertOptionalAvailability(
                eightArg,
                Set.of(
                    PluginService.LOCALIZATION,
                    PluginService.TASKS,
                    PluginService.STORAGE,
                    PluginService.USER_FILES,
                    PluginService.HOST_READS,
                    PluginService.RUNTIME_SETTINGS
                )
            );

            final FileChooserHistoryService history = fileChooserHistory();
            final CorePluginContext nineArg = new CorePluginContext(
                nine.dependencies,
                nine.session,
                localization,
                tasks,
                storage,
                userFiles,
                hostReads,
                null,
                history
            );
            assertNotSame(history, nineArg.fileChooserHistory());
            assertThrows(
                CubismEditorApiUnavailableException.class,
                () -> nineArg.fileChooserHistory().projectRecentDirectory()
            );
            assertOptionalAvailability(
                nineArg,
                Set.of(
                    PluginService.LOCALIZATION,
                    PluginService.TASKS,
                    PluginService.STORAGE,
                    PluginService.USER_FILES,
                    PluginService.HOST_READS,
                    PluginService.FILE_CHOOSER_HISTORY
                )
            );
        }
    }

    @Test
    void strictOverloadsAcceptNullOptionalTail() throws Exception {
        try (Fixture eight = new Fixture(TEMP); Fixture nine = new Fixture(TEMP)) {
            final CorePluginContext eightArg = new CorePluginContext(
                eight.dependencies,
                eight.session,
                localization(),
                taskScheduler(),
                pluginStorage(),
                userFiles(),
                hostReads(),
                null
            );
            assertThrows(UnsupportedOperationException.class, eightArg::runtimeSettings);

            final CorePluginContext nineArg = new CorePluginContext(
                nine.dependencies,
                nine.session,
                localization(),
                taskScheduler(),
                pluginStorage(),
                userFiles(),
                hostReads(),
                null,
                null
            );
            assertThrows(UnsupportedOperationException.class, nineArg::runtimeSettings);
            assertSame(
                FileChooserHistoryService.unavailable(),
                nineArg.fileChooserHistory()
            );
        }
    }

    @Test
    void allPublicHostAccessOverloadsRejectNullHostAccess() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final CorePluginContext.Dependencies dependencies = fixture.dependencies;
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final PluginStorage storage = pluginStorage();
            final UserFileAccessService userFiles = userFiles();
            final AsyncHostReadService hostReads = hostReads();
            final dev.turboism.sdk.runtime.RuntimeSettingsService settings = runtimeSettings();
            final FileChooserHistoryService history = fileChooserHistory();

            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null, localization)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null, localization, tasks)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null,
                    localization, tasks, storage)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null,
                    localization, tasks, storage, userFiles)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null,
                    localization, tasks, storage, userFiles, hostReads)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null,
                    localization, tasks, storage, userFiles, hostReads, settings)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, (RuntimeHostAdapterAccess) null,
                    localization, tasks, storage, userFiles, hostReads, settings, history)
            );
        }
    }

    @Test
    void allPublicHostAccessOverloadsRejectNullDependencies() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final HostSession session = fixture.session;
            final CorePluginContext.Dependencies missing =
                (CorePluginContext.Dependencies) null;

            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(missing, session)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(missing, session, localization())
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    missing, session, localization(), taskScheduler(), pluginStorage(),
                    userFiles(), hostReads(), runtimeSettings(), fileChooserHistory())
            );
        }
    }

    @Test
    void strictOverloadsKeepRequiredServiceNullChecks() throws Exception {
        try (Fixture fixture = new Fixture(TEMP)) {
            final CorePluginContext.Dependencies dependencies = fixture.dependencies;
            final HostSession session = fixture.session;
            final PluginLocalization localization = localization();
            final PluginTaskScheduler tasks = taskScheduler();
            final PluginStorage storage = pluginStorage();
            final UserFileAccessService userFiles = userFiles();
            final AsyncHostReadService hostReads = hostReads();

            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, null, tasks, storage, userFiles, hostReads)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, localization, null, storage, userFiles, hostReads)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, localization, tasks, null, userFiles, hostReads)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, localization, tasks, storage, null, hostReads)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, localization, tasks, storage, userFiles, null)
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, localization, tasks, storage, userFiles,
                    null, runtimeSettings(), fileChooserHistory())
            );
            assertThrows(
                NullPointerException.class,
                () -> new CorePluginContext(
                    dependencies, session, null, tasks, storage, userFiles, hostReads,
                    null, null)
            );
        }
    }

    private static void assertCoreInvariants(
        final CorePluginContext context,
        final CorePluginContext.Dependencies dependencies
    ) {
        assertSame(dependencies.descriptor(), context.descriptor());
        assertSame(dependencies.paths(), context.paths());
        assertSame(dependencies.disposableScope(), context.disposableScope());
        assertEquals(dependencies.permissions(), context.permissions());
    }

    private static void assertOmittedServiceDefaults(final CorePluginContext context) {
        assertThrows(UnsupportedOperationException.class, context::localization);
        assertThrows(UnsupportedOperationException.class, context::tasks);
        assertThrows(UnsupportedOperationException.class, context::storage);
        assertThrows(UnsupportedOperationException.class, context::userFiles);
        assertThrows(UnsupportedOperationException.class, context::hostReads);
        assertThrows(UnsupportedOperationException.class, context::runtimeSettings);
        assertSame(
            FileChooserHistoryService.unavailable(),
            context.fileChooserHistory()
        );
    }

    private static void assertOptionalAvailability(
        final CorePluginContext context,
        final Set<PluginService> expectedPresent
    ) {
        final Set<PluginService> available = context.availableServices();
        for (final PluginService slot : OPTIONAL_SLOTS) {
            assertEquals(
                expectedPresent.contains(slot),
                available.contains(slot),
                "unexpected availability for optional slot " + slot
            );
        }
    }

    private static PluginLocalization localization() {
        return new PluginLocalization() {
            @Override public Locale locale() { return Locale.ENGLISH; }
            @Override public String text(final String key) { return key; }
            @Override public String format(final String key, final Object... arguments) {
                return key;
            }
            @Override public boolean contains(final String key) { return true; }
        };
    }

    private static PluginTaskScheduler taskScheduler() {
        return new PluginTaskScheduler() {
            @Override public TaskSubmission submit(final PluginTaskRequest request) {
                return null;
            }
            @Override public TaskSubmission scheduleWithFixedDelay(
                final FixedDelayTaskRequest request
            ) {
                return null;
            }
        };
    }

    private static PluginStorage pluginStorage() {
        return (PluginStorage) Proxy.newProxyInstance(
            PluginStorage.class.getClassLoader(),
            new Class<?>[] {PluginStorage.class},
            (proxy, method, arguments) -> null
        );
    }

    private static UserFileAccessService userFiles() {
        return (UserFileAccessService) Proxy.newProxyInstance(
            UserFileAccessService.class.getClassLoader(),
            new Class<?>[] {UserFileAccessService.class},
            (proxy, method, arguments) -> null
        );
    }

    private static AsyncHostReadService hostReads() {
        return request -> null;
    }

    private static dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings() {
        return new dev.turboism.sdk.runtime.RuntimeSettingsService() {
            @Override public dev.turboism.sdk.runtime.RuntimeSettings read() {
                return null;
            }
            @Override public dev.turboism.sdk.runtime.RuntimeSettings save(
                final dev.turboism.sdk.runtime.RuntimeSettings settings
            ) {
                return null;
            }
            @Override public DockCleanupResult cleanEmptyDocks() {
                return null;
            }
        };
    }

    private static FileChooserHistoryService fileChooserHistory() {
        return new FileChooserHistoryService() {
            @Override public Optional<Path> projectRecentDirectory() {
                return Optional.empty();
            }
            @Override public Optional<Path> exportRecentDirectory() {
                return Optional.empty();
            }
            @Override public void setProjectRecentDirectory(final Path dir) { }
            @Override public void setExportRecentDirectory(final Path dir) { }
            @Override public boolean exportSeparationEnabled() { return false; }
            @Override public FileChooserHistoryService.Registration registerProvider(
                final Provider provider
            ) {
                return () -> { };
            }
        };
    }

    private static CorePluginContext.Dependencies dependencies(
        final Path dataDir,
        final RuntimeScheduler scheduler
    ) {
        return new CorePluginContext.Dependencies(
            descriptor(),
            logger(),
            paths(dataDir),
            uiScheduler(),
            scheduler,
            diagnostics(),
            new DisposableScope(),
            emptyHostSnapshotSource(),
            ignored -> { },
            CLOCK
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "dev.turboism.test.ConstructorTest"; }
            @Override public String name() { return "Constructor Test"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() {
                return List.of("dev.turboism.test.ConstructorTestPlugin");
            }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() {
                return Optional.of("https://turboism.dev");
            }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() {
                        return "META-INF/turboism/i18n/messages";
                    }
                    @Override public List<String> locales() { return List.of(); }
                };
            }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() {
                return List.of(new PermissionRef() {
                    @Override public String id() { return "turboism.cubism.project.read"; }
                    @Override public String scope() { return "application"; }
                    @Override public Optional<String> reason() {
                        return Optional.of("Constructor matrix test");
                    }
                });
            }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() {
                return new Environment() {
                    @Override public boolean requiresCubism() { return false; }
                    @Override public String ui() { return "none"; }
                };
            }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        };
    }

    private static PluginPaths paths(final Path dataDir) {
        return new PluginPaths() {
            @Override public Path dataDir() { return dataDir; }
            @Override public Path logsDir() { return dataDir.resolve("logs"); }
            @Override public Path stateDir() { return dataDir.resolve("state"); }
            @Override public Path cacheDir() { return dataDir.resolve("cache"); }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }
            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                return () -> { };
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource emptyHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                );
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }

    private static final class Fixture implements AutoCloseable {
        private final HostSession session;
        private final RuntimeScheduler scheduler;
        private final CorePluginContext.Dependencies dependencies;

        private Fixture(final Path dataDir) {
            this.session = new HostSession(() -> Optional.empty());
            this.scheduler = scheduler();
            this.dependencies = dependencies(dataDir, scheduler);
        }

        @Override
        public void close() throws Exception {
            dependencies.disposableScope().close();
            session.close();
            scheduler.shutdown();
        }
    }

    @TempDir
    static Path TEMP;
}
