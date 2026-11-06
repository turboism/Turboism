package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.internal.core.CorePluginManagement;
import dev.turboism.internal.core.ShellHandle;
import dev.turboism.internal.core.ShellServices;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.testkit.RuntimeTestKit;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shell kind's content: its own resource roots and EDT-readiness budget, its reserved identity,
 * and the two things the shell must never do — claim a plugin executor set or publish a plugin
 * lifecycle event.
 *
 * <p>The admission and close shapes are driven through {@link AdmittedGeneration} with a stub
 * {@link ShellHandle}, so the adapter is proven against the module it now uses rather than against
 * the runtime composition it sits in.</p>
 */
class ShellGenerationContentTest {

    private static final PluginLifecyclePolicy POLICY = new PluginLifecyclePolicy(
            1,
            4,
            Duration.ofSeconds(5),
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofMillis(100),
            Duration.ofMillis(20));

    @TempDir
    Path temporary;

    @Test
    void anchorSource_stripsJarPrefixAndEntrySuffix() throws Exception {
        assertEquals(
                "file:/Z:/home/developer/turboism-agent.jar",
                ShellGenerationContent.anchorSource(
                                new URL(
                                        "jar:file:/Z:/home/developer/turboism-agent.jar!/META-INF/turboism/i18n/messages.properties"))
                        .toExternalForm());
    }

    @Test
    void anchorSource_locatesExplodedResourceDirectory() throws Exception {
        assertEquals(
                "file:/opt/turboism/resources/",
                ShellGenerationContent.anchorSource(
                                new URL("file:/opt/turboism/resources/META-INF/turboism/i18n/messages.properties"))
                        .toExternalForm());
    }

    @Test
    void anchorSource_passesThroughNonJarUrls() throws Exception {
        final URL fileUrl = new URL("file:/opt/turboism/classes/");
        assertEquals(fileUrl, ShellGenerationContent.anchorSource(fileUrl));
    }

    @Test
    void shellEdtWaitBudget_defaultsWhenPropertyAbsent() {
        final String previous = System.getProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY);
        System.clearProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY);
        try {
            assertEquals(ShellGenerationContent.DEFAULT_SHELL_EDT_WAIT, ShellGenerationContent.shellEdtWaitBudget());
        } finally {
            restoreProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY, previous);
        }
    }

    @Test
    void shellEdtWaitBudget_parsesOverrideAndDisablesOnNonPositive() {
        final String previous = System.getProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY);
        try {
            System.setProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY, "90");
            assertEquals(Duration.ofSeconds(90), ShellGenerationContent.shellEdtWaitBudget());
            System.setProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY, "0");
            assertEquals(Duration.ZERO, ShellGenerationContent.shellEdtWaitBudget());
            System.setProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY, "bogus");
            assertEquals(ShellGenerationContent.DEFAULT_SHELL_EDT_WAIT, ShellGenerationContent.shellEdtWaitBudget());
        } finally {
            restoreProperty(ShellGenerationContent.SHELL_EDT_WAIT_PROPERTY, previous);
        }
    }

    /**
     * The shell is admitted and closed through the module: the composed shell starts exactly once,
     * the close pass proves disposal, and the shell's own body runs once — the shell adapter has no
     * lifecycle state machine of its own left to test.
     */
    @Test
    void shellAdmissionAndCloseRunThroughTheModule() throws Exception {
        final AtomicReference<PluginContext> started = new AtomicReference<>();
        final AtomicInteger closes = new AtomicInteger();
        try (Fixture fixture = new Fixture(temporary.resolve("shell-admission"))) {
            final ShellGenerationContent content = fixture.content(new ShellHandle() {
                @Override
                public void start(final PluginContext context) {
                    started.set(context);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            });

            final AdmittedGeneration.Submission<ShellGenerationContent> submission =
                    AdmittedGeneration.submit(content, fixture.admission(), POLICY.shellLoadTimeout());
            final AdmittedGeneration.Outcome<ShellGenerationContent> outcome = submission.await();

            assertEquals(PluginLifecycleLane.Outcome.SUCCEEDED, outcome.kind());
            assertNotNull(started.get(), "the wired admission must start the shell");
            assertEquals(CorePluginManagement.CORE_PLUGIN_ID, content.id(), "the shell keeps its reserved identity");

            final AdmittedGeneration.Handle handle = submission.handle();
            handle.fence(AdmittedGeneration.Cause.CLOSE);
            final AdmittedGeneration.CloseAttempt attempt = handle.close(() -> true);

            assertEquals(PluginLifecycleLane.Outcome.SUCCEEDED, attempt.outcome());
            assertTrue(attempt.report().complete(), "the shell's regeneration has nothing left to retain");
            assertTrue(attempt.report().disposalProven());
            assertEquals(1, closes.get(), "the shell close body runs exactly once");
        }
    }

    /** D1: the shell has no subscriber-derived plugin executor surface, so it claims nothing. */
    @Test
    void shellGenerationClaimsNoPluginExecutors() throws Exception {
        final List<String> executorFields = new ArrayList<>();
        for (Field field : ShellGenerationContent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getName().toLowerCase(Locale.ROOT).contains("executor")) {
                executorFields.add(field.getName());
            }
        }
        assertEquals(List.of(), executorFields, "the shell never claims a plugin executor set");

        try (Fixture fixture = new Fixture(temporary.resolve("shell-executors"))) {
            final ShellGenerationContent content = fixture.content(new RecordingShell());
            assertFalse(content.claimsExecutors());

            // Claiming is what allocates a set in the plugin kind; for the shell it must stay empty
            // even when a fence runs, so nothing is ever allocated or shut down here.
            final AdmittedGeneration.GenerationState state = new AdmittedGeneration.GenerationState();
            content.bind(state);
            content.claimExecutors(state, AdmittedGeneration.Cause.LOAD_FAILED);
            content.releaseExecutors(state, AdmittedGeneration.Cause.CLOSE);
            assertFalse(state.executorReleased, "the shell keeps no executor bookkeeping at all");
        }
    }

    /**
     * I11: the shell is never reported as a plugin, so its content owns no plugin lifecycle event
     * surface — nothing here can publish a load/close verdict for the reserved identity.
     */
    @Test
    void shellGenerationOwnsNoPluginLifecycleEventSurface() {
        final List<String> publishingFields = new ArrayList<>();
        for (Field field : ShellGenerationContent.class.getDeclaredFields()) {
            if (PluginLifecycleEvents.class.isAssignableFrom(field.getType())) {
                publishingFields.add(field.getName());
            }
        }
        assertEquals(
                List.of(),
                publishingFields,
                "the shell publishes no plugin lifecycle event: turboism.core is reserved");
    }

    private static void restoreProperty(final String name, final String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    /** The runtime resources a shell admission needs, assembled the way the runtime assembles them. */
    private static final class Fixture implements AutoCloseable {

        private final HostSession host;
        private final RuntimeScheduler scheduler;
        private final KernelLog log;
        private final KernelPluginRuntimeResources resources;

        private Fixture(final Path home) throws Exception {
            this.host = new HostSession(Optional::empty);
            this.scheduler = RuntimeTestKit.scheduler(Clock.systemUTC(), 1, 16);
            this.log = new KernelLog(home.resolve("logs/turboism.log"));
            this.resources = KernelPluginRuntimeResources.create(
                    home,
                    scheduler,
                    host.adapterAccess(),
                    log,
                    new RuntimeFailureCollector(),
                    (pluginId, phase) -> {},
                    new ArrayList<>(),
                    host.adapterAccess().parameterLifecycle(),
                    host.adapterAccess().partLifecycle(),
                    host.adapterAccess().editorObjectLifecycle(),
                    host.adapterAccess().projectFileLifecycle(),
                    host.adapterAccess().editorLifecycleEvents(),
                    FileChooserHistoryService.unavailable(),
                    Locale.ENGLISH,
                    POLICY);
        }

        private AdmittedGeneration.Admission admission() {
            return new AdmittedGeneration.Admission(POLICY, resources.lifecycleLane(), resources.retention(), log);
        }

        private ShellGenerationContent content(final ShellHandle shell) {
            return new ShellGenerationContent(
                    resources.contextFactory(),
                    resources.shutdown(),
                    services -> shell,
                    new ShellServices(RuntimeSettingsService.unavailable(), resources.pluginManagement()),
                    POLICY,
                    log);
        }

        @Override
        public void close() throws Exception {
            resources.contextFactory().close();
            resources.lifecycleLane().shutdown();
            host.close();
            scheduler.shutdown();
            log.close();
        }
    }

    private static final class RecordingShell implements ShellHandle {

        @Override
        public void start(final PluginContext context) {}

        @Override
        public void close() {}
    }
}
