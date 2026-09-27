package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the production wiring for the animation object graph: plugin-visible
 * animation documents, scenes, tracks, and attributes must stay behind the
 * declared model permissions and must fail closed once the plugin
 * {@link DisposableScope} closes — through the real
 * {@code DefaultCubismServicesFactory}, {@code PluginScopedCubismModelAccess},
 * {@code CubismFacadeImpl}, and the exact-version interceptor.
 */
class AnimationScopeCompositionTest {

    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void retainedAnimationGraphFailsClosedAfterPluginScopeCloses() throws Exception {
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final List<String> calls = new ArrayList<>();
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(),
                    () -> animationGraphModel(calls)
                );
            final CorePluginContext.Dependencies dependencies =
                new CorePluginContext.Dependencies(
                    descriptor("turboism.cubism.model.read", "turboism.cubism.model.write"),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> { },
                    CLOCK
                );
            final CubismFacade facade = factory.create(dependencies).cubismFacade();
            final CubismModel model = facade.model().active();
            final AnimationDocument document = model.animationDocuments().get(0);
            final AnimationScene scene = document.scenes().get(0);
            final AnimationTrack childTrack = scene.tracks().get(1).children().get(0);
            final AnimationAttribute attribute = childTrack.attributes().get(0);

            attribute.setKeyframe(9, 0.25);
            scene.rename("Renamed");
            final int callsWhileActive = calls.size();

            scope.close();

            assertThrows(IllegalStateException.class, () -> facade.model().active());
            assertThrows(IllegalStateException.class, model::animationDocuments);
            assertThrows(IllegalStateException.class, document::animationName);
            assertThrows(IllegalStateException.class, document::scenes);
            assertThrows(IllegalStateException.class, scene::name);
            assertThrows(IllegalStateException.class, scene::tracks);
            assertThrows(IllegalStateException.class, () -> scene.rename("x"));
            assertThrows(IllegalStateException.class, childTrack::attributes);
            assertThrows(IllegalStateException.class, attribute::id);
            assertThrows(IllegalStateException.class, attribute::keyframes);
            assertThrows(IllegalStateException.class,
                () -> attribute.setKeyframe(10, 0.5));
            assertThrows(IllegalStateException.class,
                () -> attribute.copyKeyframesFrom(attribute, false));
            assertEquals(callsWhileActive, calls.size());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void animationMutationsRequireDeclaredModelWriteThroughProductionFactory()
        throws Exception {
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final List<String> calls = new ArrayList<>();
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(),
                    () -> animationGraphModel(calls)
                );
            final CorePluginContext.Dependencies dependencies =
                new CorePluginContext.Dependencies(
                    descriptor("turboism.cubism.model.read"),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> { },
                    CLOCK
                );
            final CubismFacade facade = factory.create(dependencies).cubismFacade();
            final AnimationAttribute attribute = facade.model().active()
                .animationDocuments().get(0).scenes().get(0)
                .tracks().get(1).children().get(0).attributes().get(0);
            calls.clear();

            assertThrows(CubismPermissionException.class,
                () -> attribute.setKeyframe(9, 0.25));
            assertThrows(CubismPermissionException.class,
                () -> attribute.copyKeyframesFrom(attribute, false));
            assertThrows(CubismPermissionException.class,
                () -> attribute.offsetKeyframes(3));
            assertEquals(List.of(), calls);
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void animationCopyAcrossActiveViewsStaysLegalWithinPluginScope()
        throws Exception {
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final List<String> calls = new ArrayList<>();
            final DefaultCubismServicesFactory factory =
                DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(),
                    () -> animationGraphModel(calls)
                );
            final CorePluginContext.Dependencies dependencies =
                new CorePluginContext.Dependencies(
                    descriptor("turboism.cubism.model.read", "turboism.cubism.model.write"),
                    logger(),
                    paths(),
                    uiScheduler(),
                    scheduler,
                    diagnostics(),
                    scope,
                    noopHostSnapshotSource(),
                    ignored -> { },
                    CLOCK
                );
            final CubismFacade facade = factory.create(dependencies).cubismFacade();

            // Same facade, unchanged backend, live scope: a source obtained
            // through a second active() view shares authorization ownership
            // and session generation, so the copy must stay legal.
            final AnimationAttribute target = facade.model().active()
                .animationDocuments().get(0).scenes().get(0)
                .tracks().get(1).children().get(0).attributes().get(0);
            final AnimationAttribute separateViewSource = facade.model().active()
                .animationDocuments().get(0).scenes().get(0)
                .tracks().get(0).attributes().get(0);
            calls.clear();

            assertEquals(0, target.copyKeyframesFrom(separateViewSource, false));
            assertEquals(List.of("attr.copyKeyframesFrom"), calls);
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    private static CubismModel animationGraphModel(final List<String> calls) {
        final AnimationAttribute leaf = animationAttribute("attr-leaf", calls);
        final AnimationTrack childTrack = animationTrack(
            "track-child", AnimationTrackKind.IMAGE, List.of(), List.of(leaf), calls
        );
        final AnimationTrack modelTrack = animationTrack(
            "track-model", AnimationTrackKind.LIVE2D_MODEL,
            List.of(), List.of(animationAttribute("attr-model", calls)), calls
        );
        final AnimationTrack groupTrack = animationTrack(
            "track-group", AnimationTrackKind.GROUP, List.of(childTrack), List.of(), calls
        );
        final AnimationScene scene = new AnimationScene() {
            @Override public String name() {
                calls.add("scene.name");
                return "SceneA";
            }
            @Override public String guid() { return "scene-guid"; }
            @Override public Optional<String> tag() { return Optional.empty(); }
            @Override public java.util.Map<Integer, String> markers() {
                return java.util.Map.of();
            }
            @Override public int startFrame() { return 0; }
            @Override public int durationFrames() { return 120; }
            @Override public double framesPerSecond() { return 30.0; }
            @Override public int width() { return 100; }
            @Override public int height() { return 100; }
            @Override public boolean loopMotion() { return false; }
            @Override public int workspaceStartFrame() { return 0; }
            @Override public int workspaceEndFrame() { return 60; }
            @Override public List<AnimationTrack> tracks() {
                calls.add("scene.tracks");
                return List.of(modelTrack, groupTrack);
            }
            @Override public int playheadFrame() { return 7; }
            @Override public void seekTo(final int frame) {
                calls.add("scene.seekTo");
            }
            @Override public boolean current() { return true; }
            @Override public void activate() { calls.add("scene.activate"); }
            @Override public AnimationCurveType defaultCurveType() {
                return AnimationCurveType.LINEAR;
            }
            @Override public void setDefaultCurveType(final AnimationCurveType curveType) {
                calls.add("scene.setDefaultCurveType");
            }
            @Override public void rename(final String name) {
                calls.add("scene.rename");
            }
        };
        final AnimationDocument document = new AnimationDocument() {
            @Override public String animationName() {
                calls.add("doc.animationName");
                return "AnimDoc";
            }
            @Override public int sceneCount() { return 1; }
            @Override public Optional<String> currentSceneName() {
                return Optional.of("SceneA");
            }
            @Override public List<String> sceneNames() { return List.of("SceneA"); }
            @Override public List<AnimationScene> scenes() {
                calls.add("doc.scenes");
                return List.of(scene);
            }
        };
        return new CubismModel() {
            @Override public ModelId id() { return new ModelId("model-anim"); }
            @Override public Parameters parameters() {
                throw new UnsupportedOperationException();
            }
            @Override public Parts parts() { throw new UnsupportedOperationException(); }
            @Override public Drawables drawables() {
                throw new UnsupportedOperationException();
            }
            @Override public Deformers deformers() {
                throw new UnsupportedOperationException();
            }
            @Override public Glues glues() { throw new UnsupportedOperationException(); }
            @Override public void update() { throw new UnsupportedOperationException(); }
            @Override public List<AnimationDocument> animationDocuments() {
                calls.add("model.animationDocuments");
                return List.of(document);
            }
        };
    }

    private static AnimationTrack animationTrack(
        final String name,
        final AnimationTrackKind kind,
        final List<AnimationTrack> children,
        final List<AnimationAttribute> attributes,
        final List<String> calls
    ) {
        return new AnimationTrack() {
            @Override public String guid() { return name + "-guid"; }
            @Override public String name() {
                calls.add("track.name");
                return name;
            }
            @Override public AnimationTrackKind kind() { return kind; }
            @Override public int startFrame() { return 0; }
            @Override public int durationFrames() { return 60; }
            @Override public List<Integer> keyframeFrames() { return List.of(); }
            @Override public boolean visible() { return true; }
            @Override public boolean editable() { return true; }
            @Override public boolean muted() { return false; }
            @Override public boolean repeat() { return false; }
            @Override public List<AnimationTrack> children() {
                calls.add("track.children");
                return children;
            }
            @Override public List<AnimationAttribute> attributes() {
                calls.add("track.attributes");
                return attributes;
            }
            @Override public Optional<String> linkedModelGuid() { return Optional.empty(); }
            @Override public Optional<String> linkedSceneGuid() { return Optional.empty(); }
        };
    }

    private static AnimationAttribute animationAttribute(
        final String id,
        final List<String> calls
    ) {
        return new AnimationAttribute() {
            @Override public String id() {
                calls.add("attr.id");
                return id;
            }
            @Override public String name() { return id; }
            @Override public String guid() { return id + "-guid"; }
            @Override public String effectId() { return "effect-param"; }
            @Override public Optional<ParameterId> parameterId() {
                return Optional.of(new ParameterId("ParamAngleX"));
            }
            @Override public AnimationAttributeKind kind() {
                return AnimationAttributeKind.FLOAT;
            }
            @Override public boolean active() { return true; }
            @Override public boolean editable() { return true; }
            @Override public List<AnimationKeyframe> keyframes() {
                calls.add("attr.keyframes");
                return List.of();
            }
            @Override public void setKeyframe(final int frame, final double value) {
                calls.add("attr.setKeyframe");
            }
            @Override public void removeKeyframe(final int frame) {
                calls.add("attr.removeKeyframe");
            }
            @Override public int offsetKeyframes(final int frameDelta) {
                calls.add("attr.offsetKeyframes");
                return 0;
            }
            @Override public int copyKeyframesFrom(
                final AnimationAttribute source,
                final boolean replace
            ) {
                calls.add("attr.copyKeyframesFrom");
                return 0;
            }
        };
    }

    private static PluginDescriptor descriptor(final String... permissions) {
        final List<PluginDescriptor.PermissionRef> refs = new ArrayList<>();
        for (String permission : permissions) {
            refs.add(permission(permission));
        }
        return new PluginDescriptor() {
            @Override public String id() { return "test.animation-scope"; }
            @Override public String name() { return "Animation Scope"; }
            @Override public String version() { return "0.1.0"; }
            @Override public String description() { return "Test"; }
            @Override public List<String> entrypoints() {
                return List.of("dev.turboism.test.AnimationScopePlugin");
            }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Project License"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() {
                    return "META-INF/turboism/i18n/messages";
                }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return refs; }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override public String id() { return id; }
            @Override public String scope() { return "application"; }
            @Override public Optional<String> reason() { return Optional.empty(); }
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

    private static PluginPaths paths() {
        return new PluginPaths() {
            @Override public Path dataDir() { return Path.of("."); }
            @Override public Path logsDir() { return Path.of("."); }
            @Override public Path stateDir() { return Path.of("."); }
            @Override public Path cacheDir() { return Path.of("."); }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(Runnable work) {
                work.run();
                return () -> { };
            }
            @Override public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                return () -> { };
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, CLOCK),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
            @Override public Instant createdAt() { return CLOCK.instant(); }
            @Override public List<Problem> problems() { return List.of(); }
        };
    }

    private static HostSnapshotSource noopHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override public Optional<HostProject> activeProject() { return Optional.empty(); }
            @Override public Optional<HostDocument> activeDocument() { return Optional.empty(); }
            @Override public Optional<HostModel> activeModel() { return Optional.empty(); }
            @Override public HostSelection selection() {
                return new HostSelection(
                    List.of(), Optional.empty(), Optional.empty(), Optional.empty()
                );
            }
            @Override public boolean isHostPresent() { return false; }
            @Override public long invalidationToken() { return 0; }
        };
    }
}
