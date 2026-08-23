package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectFileOperation;
import dev.turboism.sdk.cubism.ProjectFileOperationResult;
import dev.turboism.sdk.cubism.ProjectFileOperationType;
import dev.turboism.sdk.cubism.hook.AnimationFileHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFileLifecycleCoordinatorTest {

    @Test
    void successfulModelCreateUsesBeforeOnAfterOrder() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.register(plugin(
            List.of(new ModelFileHooks() {
                @Override public void beforeCreateModel(final ProjectFileOperation operation) {
                    events.add("before:" + operation.operation());
                }
                @Override public void onModelCreated(final ProjectContentSnapshot model) {
                    events.add("on:" + model.name());
                }
                @Override public void afterCreateModel(final ProjectFileOperationResult result) {
                    events.add("after:" + result.succeeded());
                }
            }),
            List.of()
        ));
        final ProjectFileOperation request = operation(
            ProjectContentKind.MODEL,
            ProjectFileOperationType.CREATE,
            "Untitled"
        );

        final ProjectFileLifecycleCoordinator.Invocation invocation = coordinator.begin(request);
        assertEquals(List.of("before:CREATE"), events);
        coordinator.complete(invocation, content(ProjectContentKind.MODEL, "Model A"), true, null);
        coordinator.awaitIdle();

        assertEquals(List.of("before:CREATE", "on:Model A", "after:true"), events);
        coordinator.close();
    }

    @Test
    void completionListenersRunSynchronouslyAndFailOpenBeforePluginEvents() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.register(plugin(List.of(new ModelFileHooks() {
            @Override public void afterCloseModel(final ProjectFileOperationResult result) {
                events.add("plugin");
            }
        }), List.of()));
        coordinator.registerCompletionListener(result -> {
            events.add("listener");
            throw new IllegalStateException("cleanup failure");
        });

        final var invocation = coordinator.begin(operation(
            ProjectContentKind.MODEL, ProjectFileOperationType.CLOSE, "Model A"
        ));
        coordinator.complete(invocation, content(ProjectContentKind.MODEL, "Model A"), true, null);
        coordinator.awaitIdle();

        assertEquals(List.of("listener", "plugin"), events);
        coordinator.close();
    }

    @Test
    void closeClearsCompletionListeners() {
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.registerCompletionListener(result -> calls.incrementAndGet());
        coordinator.close();

        final var invocation = coordinator.begin(operation(
            ProjectContentKind.MODEL, ProjectFileOperationType.CLOSE, "Model A"
        ));
        coordinator.complete(invocation, content(ProjectContentKind.MODEL, "Model A"), true, null);

        assertEquals(0, calls.get());
    }

    @Test
    void rejectedSaveAndFailedClosePublishAfterWithoutSuccessfulOn() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.register(plugin(
            List.of(new ModelFileHooks() {
                @Override public void beforeSaveModel(final ProjectFileOperation operation) {
                    events.add("before-save");
                }
                @Override public void onModelSaved(final ProjectContentSnapshot model) {
                    events.add("on-save");
                }
                @Override public void afterSaveModel(final ProjectFileOperationResult result) {
                    events.add("after-save:" + result.succeeded() + ":" + result.failureType().isPresent());
                }
                @Override public void beforeCloseModel(final ProjectFileOperation operation) {
                    events.add("before-close");
                }
                @Override public void onModelClosed(final ProjectContentSnapshot model) {
                    events.add("on-close");
                }
                @Override public void afterCloseModel(final ProjectFileOperationResult result) {
                    events.add("after-close:" + result.succeeded() + ":" + result.failureType().orElse("none"));
                }
            }),
            List.of()
        ));
        final ProjectContentSnapshot model = content(ProjectContentKind.MODEL, "Model A");

        final var save = coordinator.begin(operation(
            ProjectContentKind.MODEL,
            ProjectFileOperationType.SAVE,
            model.name()
        ));
        coordinator.complete(save, model, false, null);
        coordinator.awaitIdle();
        final var close = coordinator.begin(operation(
            ProjectContentKind.MODEL,
            ProjectFileOperationType.CLOSE,
            model.name()
        ));
        coordinator.complete(close, model, false, new IllegalStateException("native"));
        coordinator.awaitIdle();

        assertEquals(List.of(
            "before-save",
            "after-save:false:false",
            "before-close",
            "after-close:false:java.lang.IllegalStateException"
        ), events);
        coordinator.close();
    }

    @Test
    void failureOverridesContradictorySuccessFlag() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.register(plugin(List.of(new ModelFileHooks() {
            @Override public void onModelSaved(final ProjectContentSnapshot model) {
                events.add("on");
            }
            @Override public void afterSaveModel(final ProjectFileOperationResult result) {
                events.add("after:" + result.succeeded() + ":" + result.failureType().orElse("none"));
            }
        }), List.of()));
        final ProjectContentSnapshot model = content(ProjectContentKind.MODEL, "Model A");
        final var invocation = coordinator.begin(operation(
            ProjectContentKind.MODEL,
            ProjectFileOperationType.SAVE,
            model.name()
        ));

        coordinator.complete(
            invocation,
            model,
            true,
            new IllegalStateException("native")
        );
        coordinator.awaitIdle();

        assertEquals(List.of(
            "after:false:java.lang.IllegalStateException"
        ), events);
        coordinator.close();
    }

    @Test
    void animationHooksAreDistinctAndPluginFailuresStayIsolated() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();
        final ProjectFileLifecycleCoordinator coordinator = new ProjectFileLifecycleCoordinator();
        coordinator.register(new ProjectFileLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(new ModelFileHooks() {
                @Override public void beforeOpenModel(final ProjectFileOperation operation) {
                    events.add("wrong-model-hook");
                }
            }),
            List.of(new AnimationFileHooks() {
                @Override public void beforeOpenAnimation(final ProjectFileOperation operation) {
                    events.add("before-animation");
                    throw new IllegalStateException("bad before");
                }
                @Override public void onAnimationOpened(final ProjectContentSnapshot animation) {
                    events.add("on-animation");
                    throw new IllegalStateException("bad on");
                }
                @Override public void afterOpenAnimation(final ProjectFileOperationResult result) {
                    events.add("after-animation");
                }
            }),
            logger(failures),
            true
        ));

        final var invocation = coordinator.begin(operation(
            ProjectContentKind.ANIMATION,
            ProjectFileOperationType.OPEN,
            "Motion A"
        ));
        coordinator.complete(
            invocation,
            content(ProjectContentKind.ANIMATION, "Motion A"),
            true,
            null
        );
        coordinator.awaitIdle();

        assertEquals(List.of("before-animation", "on-animation", "after-animation"), events);
        assertEquals(2, failures.size());
        assertTrue(failures.stream().allMatch(value -> value.contains("failed safely")));
        coordinator.close();
    }

    private static ProjectFileLifecycleCoordinator.PluginHooks plugin(
        final List<? extends ModelFileHooks> modelHooks,
        final List<? extends AnimationFileHooks> animationHooks
    ) {
        return new ProjectFileLifecycleCoordinator.PluginHooks(
            descriptor(),
            modelHooks,
            animationHooks,
            logger(new CopyOnWriteArrayList<>()),
            true
        );
    }

    private static ProjectFileOperation operation(
        final ProjectContentKind kind,
        final ProjectFileOperationType type,
        final String name
    ) {
        return new ProjectFileOperation(kind, type, Optional.empty(), name, Optional.empty());
    }

    private static ProjectContentSnapshot content(
        final ProjectContentKind kind,
        final String name
    ) {
        return new ProjectContentSnapshot(
            kind.name().toLowerCase(java.util.Locale.ROOT) + "-a",
            name,
            kind,
            Optional.empty(),
            List.of(),
            List.of()
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "plugin-a"; }
            @Override public String name() { return "plugin-a"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Test"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger(final List<String> failures) {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { failures.add(message); }
            @Override public void error(final String message, final Throwable throwable) {
                failures.add(message + ":" + throwable.getClass().getSimpleName());
            }
        };
    }
}
