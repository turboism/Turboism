package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorTestFixtures.Id;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared native document, physics, and animation graph for editor access contracts. */
public final class EditorDocumentTestFixtures {
    private EditorDocumentTestFixtures() {}

    static final class Fixture {
        final Document document = new Document();
    }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Document document;
        static Project project;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public Project currentProject() {
            if (project == null || project.document != document) {
                project = new Project(document);
            }
            return project;
        }
    }

    public static final class Project {
        final Document document;
        final AnimationFileContent animation;
        final AnimationFileContent decoy;

        Project(final Document document) {
            this.document = document;
            this.animation =
                    new AnimationFileContent(new ModelSource("model-a-reloaded"), document.file(), "Animation A");
            this.decoy = new AnimationFileContent(
                    new ModelSource("model-b"), new java.io.File("probe-model-b.cmo3"), "Decoy Animation");
        }

        public List<Object> children() {
            return List.of(document, animation, decoy);
        }

        AnimationFileContent animation() {
            return animation;
        }
    }

    public static final class Document {
        final ModelSource source = new ModelSource();
        final UndoManager undoManager = new UndoManager();
        final java.io.File file = new java.io.File("probe-model-a.cmo3");

        public ModelSource modelSource() {
            return source;
        }

        public UndoManager undoManager() {
            return undoManager;
        }

        public java.io.File file() {
            return file;
        }
    }

    public static class HistoryEntry {
        final String name;

        HistoryEntry(final String name) {
            this.name = name;
        }

        public String presentationName() {
            return name;
        }

        public boolean significant() {
            return true;
        }
    }

    public static final class UndoManager {
        final List<HistoryEntry> entries = new ArrayList<>();
        int position;

        public List<HistoryEntry> entries() {
            return entries;
        }

        public int position() {
            return position;
        }

        public boolean canUndo() {
            return position > 0;
        }

        public boolean canRedo() {
            return position < entries.size();
        }
    }

    public static final class ModelSource {
        final WarpSource warp = new WarpSource();
        final ParameterHolder parameter = new ParameterHolder();
        final PhysicsSettingsSourceSet physics = new PhysicsSettingsSourceSet();
        final Id guid;

        ModelSource() {
            this("model-a");
        }

        ModelSource(final String guid) {
            this.guid = new Id(guid);
        }

        public String sourceType() {
            return "model-source";
        }

        public Id guid() {
            return guid;
        }

        final Model instance = new Model();

        public Model currentInstance() {
            return instance;
        }

        public List<WarpSource> allDeformers() {
            return List.of(warp);
        }

        public List<ParameterHolder> allParameters() {
            return List.of(parameter);
        }

        public PhysicsSettingsSourceSet physicsSettingsSourceSet() {
            return physics;
        }
    }

    public static final class Model {
        final ParameterSet parameterSet = new ParameterSet();

        public ParameterSet parameterSet() {
            return parameterSet;
        }
    }

    public static final class ParameterSet {
        final ParameterHolder parameter = new ParameterHolder();

        public List<ParameterHolder> parameters() {
            return List.of(parameter);
        }
    }

    public static class ObjectSource {
        final Id id = new Id("source");
        final List<Object> extensions = new ArrayList<>();

        public Id id() {
            return id;
        }

        public List<Object> extensions() {
            return extensions;
        }
    }

    public static final class WarpSource extends ObjectSource {
        final AutoYureConfigExtension autoYure = new AutoYureConfigExtension();
        final Id warpId = new Id("WarpFace");

        WarpSource() {
            extensions.add(autoYure);
        }

        @Override
        public Id id() {
            return warpId;
        }
    }

    public static final class AutoYureConfigExtension {
        final Map<Id, AutoYureConfig> configs = new LinkedHashMap<>();

        AutoYureConfigExtension() {
            configs.put(new Id("guid:ParamAngleX"), new AutoYureConfig());
        }

        public Map<Id, AutoYureConfig> paramToConfigMap() {
            return configs;
        }
    }

    public static final class AutoYureConfig {
        public YureDeformConfig left() {
            return new YureDeformConfig(10.0F, 20.0F, 1.5F, 1.0);
        }

        public YureDeformConfig right() {
            return new YureDeformConfig(30.0F, 40.0F, 2.5F, 2.0);
        }

        public boolean syncLeftRight() {
            return true;
        }

        public YureRootDirection rootDirection() {
            return YureRootDirection.TOP;
        }

        public boolean isFlip() {
            return true;
        }
    }

    public enum YureRootDirection {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT
    }

    public static final class YureDeformConfig {
        final float scalePercentX;
        final float scalePercentY;
        final float expandScale;
        final double decayLevel;

        YureDeformConfig(final float x, final float y, final float expand, final double decay) {
            this.scalePercentX = x;
            this.scalePercentY = y;
            this.expandScale = expand;
            this.decayLevel = decay;
        }

        public float scalePercentX() {
            return scalePercentX;
        }

        public float scalePercentY() {
            return scalePercentY;
        }

        public float expandScale() {
            return expandScale;
        }

        public double decayLevel() {
            return decayLevel;
        }
    }

    public static final class ParameterSource {
        final Id guid = new Id("guid:ParamAngleX");

        public Id guid() {
            return guid;
        }
    }

    public static final class ParameterHolder {
        final ParameterSource parameterSource = new ParameterSource();
        float value = 7.5F;

        public String parameterType() {
            return "parameter";
        }

        public ParameterSource source() {
            return parameterSource;
        }

        public Id id() {
            return new Id("ParamAngleX");
        }

        public float value() {
            return value;
        }
    }

    public static final class PhysicsSettingsSourceSet {
        public GVector2 gravity() {
            return new GVector2(0.0F, -1.0F);
        }

        public GVector2 wind() {
            return new GVector2(0.5F, 0.0F);
        }

        public Integer settingFps() {
            return 60;
        }

        public List<PhysicsSettingsSourceDoc> sources() {
            return List.of(new PhysicsSettingsSourceDoc());
        }
    }

    public static final class GVector2 {
        final float x;
        final float y;

        GVector2(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }
    }

    public static final class PhysicsSettingsSourceDoc {
        public Id id() {
            return new Id("PhysicsA");
        }

        public String name() {
            return "Physics A";
        }

        public float totalAngle() {
            return 90.0F;
        }

        public List<Object> inputs() {
            return List.of(new Object(), new Object());
        }

        public List<Object> outputs() {
            return List.of(new Object());
        }

        public List<Object> vertices() {
            return List.of(
                    new Object(),
                    new Object(),
                    new Object(),
                    new Object(),
                    new Object(),
                    new Object(),
                    new Object(),
                    new Object());
        }
    }

    public static final class AnimationFileContent {
        final Animation animation;
        final List<SceneDocument> sceneDocs = new ArrayList<>();
        SceneDocument currentSceneDoc;

        AnimationFileContent(final ModelSource linked, final java.io.File linkedFile, final String name) {
            this.animation = new Animation(linked, linkedFile, name);
            for (SceneSource scene : animation.scenes()) {
                sceneDocs.add(new SceneDocument(scene, animation));
            }
            currentSceneDoc = sceneDocs.get(0);
        }

        public Animation animation() {
            return animation;
        }

        public List<SceneDocument> sceneDocs() {
            return sceneDocs;
        }

        public SceneDocument currentSceneDoc() {
            return currentSceneDoc;
        }

        public void setCurrentSceneDoc(final SceneDocument doc) {
            currentSceneDoc = doc;
        }
    }

    public static final class SceneDocument {
        final SceneSource sceneSource;
        final Animation animation;
        final SceneEditMode editMode = new SceneEditMode();
        final CompletePack completePack = new CompletePack();
        final List<ViewContext> viewContexts = List.of(new ViewContext());
        boolean modified;

        SceneDocument(final SceneSource sceneSource, final Animation animation) {
            this.sceneSource = sceneSource;
            this.animation = animation;
        }

        public Animation animation() {
            return animation;
        }

        public List<ViewContext> viewContexts() {
            return viewContexts;
        }

        public SceneSource sceneSource() {
            return sceneSource;
        }

        public SceneEditMode currentEditMode() {
            return editMode;
        }

        public CompletePack completePack() {
            return completePack;
        }

        public void updateModified() {
            modified = true;
        }
    }

    public static class EditModeBase {
        GroupUndo current;
        int began;
        int ended;
        boolean lastCancelled;

        public GroupUndo begin(final String name) {
            began++;
            current = new GroupUndo();
            return current;
        }

        public boolean end(final boolean cancelled, final Object fn) {
            ended++;
            lastCancelled = cancelled;
            if (cancelled && current != null) {
                current.undo();
            }
            current = null;
            return true;
        }
    }

    public static final class SceneEditMode extends EditModeBase {}

    public static final class CompletePack {
        int projectUpdates;
        int repaints;

        public void updateProject() {
            projectUpdates++;
        }

        public void repaint(final boolean all) {
            repaints++;
        }
    }

    public interface Listener {
        void changed(Object event);
    }

    public static class Undo {
        final List<Listener> listeners = new ArrayList<>();

        public boolean addListener(final Listener listener) {
            return listeners.add(listener);
        }

        public void undo() {}
    }

    public static final class GroupUndo extends Undo {
        final List<Undo> edits = new ArrayList<>();

        public boolean add(final Undo undo, final boolean unused) {
            return edits.add(undo);
        }

        @Override
        public void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) {
                edits.get(index).undo();
            }
        }
    }

    public static final class SimpleUndo extends Undo {
        final Object target;
        final Object snapshot;

        public SimpleUndo(final String label, final Object target, final Object copier) {
            this.target = target;
            // Mirrors the real SimpleUndo: list targets are not restorable —
            // the host prints a warning telling callers to use ListUndo.
            if (target instanceof Attr attribute) {
                this.snapshot = attribute.snapshot();
            } else if (target instanceof SceneSource scene) {
                this.snapshot = scene.sceneName();
            } else {
                this.snapshot = null;
            }
        }

        @Override
        public void undo() {
            if (target instanceof Attr attribute) {
                attribute.restore(snapshot);
            } else if (target instanceof SceneSource scene) {
                scene.setSceneName((String) snapshot);
            }
        }
    }

    /**
     * Mirrors the real {@code SceneHandler.a(label)}: wraps a
     * {@code SceneSourceBasicData_forUndo} DTO inside a SimpleUndo so scene
     * name/curve-type edits roll back through the scene's own fields.
     */
    public static final class SceneHandler {
        final SceneSource scene;

        public SceneHandler(final SceneSource scene) {
            this.scene = scene;
        }

        public Undo a(final String label) {
            return new SceneBasicDataUndo(scene);
        }
    }

    static final class SceneBasicDataUndo extends Undo {
        final SceneSource scene;
        final String name;
        final CurveType curveType;

        SceneBasicDataUndo(final SceneSource scene) {
            this.scene = scene;
            this.name = scene.sceneName();
            this.curveType = scene.defaultCurveType();
        }

        @Override
        public void undo() {
            scene.setSceneName(name);
            scene.setDefaultCurveType(curveType);
        }
    }

    public static final class Animation {
        final String name;
        final List<SceneSource> scenes;
        SceneSource currentScene;

        Animation(final ModelSource linked, final java.io.File linkedFile, final String name) {
            this.name = name;
            this.scenes = List.of(sceneOne(linked, linkedFile), sceneTwo());
            this.currentScene = scenes.get(1);
        }

        public String name() {
            return name;
        }

        public List<SceneSource> scenes() {
            return scenes;
        }

        public SceneSource currentScene() {
            return currentScene;
        }

        public void setCurrentScene(final SceneSource scene) {
            currentScene = scene;
        }

        static SceneSource sceneOne(final ModelSource linked, final java.io.File linkedFile) {
            final MutableSequence sequence = new MutableSequence(
                    Map.of(0, CurveType.LINEAR, 30, CurveType.BEZIER, 60, CurveType.STEP),
                    Map.of(30, new BezierPt(new CtrlPt(24.5F, 0.4, false), new CtrlPt(36.0F, 0.6, true))));
            final ModelTrack modelTrack = new ModelTrack(
                    "track-model",
                    "Model A",
                    new int[] {0, 60, 120},
                    linked,
                    linkedFile,
                    new EffectManager(
                            new ParamEffect(
                                    "effect-param",
                                    "Live2D Parameters",
                                    new AttrF(
                                            "live2dParam_ParamAngleX",
                                            "Angle X",
                                            "attr-guid-1",
                                            new int[] {0, 30, 60},
                                            Map.of(0, 0.0, 30, 0.5, 60, 1.0),
                                            sequence)),
                            new Effect(
                                    "effect-visual",
                                    "Placement",
                                    new AttrI(
                                            "frameStep",
                                            "Frame Step",
                                            "attr-guid-2",
                                            new int[] {0, 120},
                                            Map.of(0, 0, 120, 2)))));
            final GroupTrack subGroup = new GroupTrack(
                    "track-group",
                    "Nested",
                    new ImageTrack(
                            "track-image",
                            "Sprite",
                            new int[] {0, 120},
                            new EffectManager(new Effect(
                                    "effect-layout",
                                    "Layout",
                                    new AttrPt(
                                            "position",
                                            "Position",
                                            "attr-guid-3",
                                            new int[] {0, 120},
                                            Map.of(0, new GVector2(0.0F, 0.0F), 120, new GVector2(10.0F, 5.0F)))))));
            return new SceneSource(
                    "Scene 1",
                    "scene-guid-1",
                    "intro",
                    new LinkedHashMap<>(Map.of(0, "start", 60, "peak")),
                    new MovieInfo(0, 120, 30.0, 1920, 1080, true, 10, 100),
                    new GroupTrack(
                            "track-root",
                            "Root",
                            modelTrack,
                            subGroup,
                            new SceneTrack("track-scene", "Scene Ref", "scene-guid-2"),
                            new SoundTrack("track-sound", "Voice", new int[] {10, 40})));
        }

        static SceneSource sceneTwo() {
            return new SceneSource(
                    "Scene 2",
                    "scene-guid-2",
                    "",
                    new LinkedHashMap<>(),
                    new MovieInfo(0, 60, 24.0, 800, 600, false, 0, 60),
                    new GroupTrack(
                            "track-root-2",
                            "Root",
                            new TextTrack(
                                    "track-text",
                                    "Caption",
                                    new int[] {0, 60},
                                    new EffectManager(new ParamEffect(
                                            "effect-param-2",
                                            "Live2D Parameters",
                                            new AttrF(
                                                    "live2dParam_ParamAngleY",
                                                    "Angle Y",
                                                    "attr-guid-4",
                                                    new int[] {10, 50},
                                                    Map.of(10, 1.0, 50, 2.0),
                                                    new MutableSequence(
                                                            Map.of(10, CurveType.SMOOTH, 50, CurveType.SMOOTH),
                                                            Map.of())))))));
        }
    }

    public static class SceneSource {
        String sceneName;
        final Id guid;
        final String tag;
        final HashMap<Integer, String> marker;
        final MovieInfo movieInfo;
        final GroupTrack rootTrack;
        final SceneInstance sceneInstance;
        CurveType defaultCurveType = CurveType.LINEAR;

        SceneSource(final String sceneName) {
            this(
                    sceneName,
                    "scene-guid-" + sceneName,
                    null,
                    new LinkedHashMap<>(),
                    new MovieInfo(0, 60, 30.0, 800, 600, false, 0, 60),
                    new GroupTrack("track-root-" + sceneName, "Root"));
        }

        SceneSource(
                final String sceneName,
                final String guid,
                final String tag,
                final HashMap<Integer, String> marker,
                final MovieInfo movieInfo,
                final GroupTrack rootTrack) {
            this.sceneName = sceneName;
            this.guid = new Id(guid);
            this.tag = tag;
            this.marker = marker;
            this.movieInfo = movieInfo;
            this.rootTrack = rootTrack;
            this.sceneInstance = new SceneInstance(this);
        }

        public List<SceneInstance> sceneInstances() {
            return List.of(sceneInstance);
        }

        public CurveType defaultCurveType() {
            return defaultCurveType;
        }

        public void setDefaultCurveType(final CurveType curveType) {
            this.defaultCurveType = curveType;
        }

        public String sceneName() {
            return sceneName;
        }

        public void setSceneName(final String name) {
            this.sceneName = name;
        }

        public Id guid() {
            return guid;
        }

        public String tag() {
            return tag;
        }

        public HashMap<Integer, String> marker() {
            return marker;
        }

        public MovieInfo movieInfo() {
            return movieInfo;
        }

        public GroupTrack rootTrack() {
            return rootTrack;
        }
    }

    public static final class MovieInfo {
        final int startFrame;
        final int duration;
        final double fps;
        final int width;
        final int height;
        final boolean loopMotion;
        final int workspaceStart;
        final int workspaceEnd;

        MovieInfo(
                final int startFrame,
                final int duration,
                final double fps,
                final int width,
                final int height,
                final boolean loopMotion,
                final int workspaceStart,
                final int workspaceEnd) {
            this.startFrame = startFrame;
            this.duration = duration;
            this.fps = fps;
            this.width = width;
            this.height = height;
            this.loopMotion = loopMotion;
            this.workspaceStart = workspaceStart;
            this.workspaceEnd = workspaceEnd;
        }

        public int startFrame() {
            return startFrame;
        }

        public int duration() {
            return duration;
        }

        public double fps() {
            return fps;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public boolean isLoopMotion() {
            return loopMotion;
        }

        public int workspaceStart() {
            return workspaceStart;
        }

        public int workspaceEnd() {
            return workspaceEnd;
        }
    }

    public static class TrackSource {
        final Id guid;
        final String name;
        final int[] keyFrames;
        final EffectManager effectManager;

        TrackSource(final String guid, final String name, final int[] keyFrames, final EffectManager effectManager) {
            this.guid = new Id(guid);
            this.name = name;
            this.keyFrames = keyFrames;
            this.effectManager = effectManager;
            for (Effect effect : effectManager.effectList()) {
                for (Attr attribute : effect.attrList()) {
                    attribute.track = this;
                }
            }
        }

        public String name() {
            return name;
        }

        public Id guid() {
            return guid;
        }

        public int start() {
            return 0;
        }

        public int duration() {
            return 120;
        }

        public boolean editable() {
            return true;
        }

        public boolean visible() {
            return true;
        }

        public boolean mute() {
            return false;
        }

        public boolean repeat() {
            return false;
        }

        public int[] keyFrames() {
            return keyFrames;
        }

        public EffectManager effectManager() {
            return effectManager;
        }
    }

    public static class GroupTrack extends TrackSource {
        final List<TrackSource> childTracks;

        GroupTrack(final String guid, final String name, final TrackSource... children) {
            super(guid, name, new int[0], new EffectManager());
            this.childTracks = List.of(children);
        }

        public List<TrackSource> childTracks() {
            return childTracks;
        }
    }

    public static final class ModelTrack extends TrackSource {
        final ModelSource model;
        final ResourceFile resourceRef;

        ModelTrack(
                final String guid,
                final String name,
                final int[] keyFrames,
                final ModelSource model,
                final java.io.File srcFile,
                final EffectManager effectManager) {
            super(guid, name, keyFrames, effectManager);
            this.model = model;
            this.resourceRef = new ResourceFile(srcFile);
        }

        public ModelSource model() {
            return model;
        }

        public ResourceFile resourceRef() {
            return resourceRef;
        }
    }

    public static final class ResourceFile {
        final java.io.File srcFile;

        ResourceFile(final java.io.File srcFile) {
            this.srcFile = srcFile;
        }

        public java.io.File srcFile() {
            return srcFile;
        }
    }

    public static final class SceneTrack extends TrackSource {
        final Id resourceGuid;

        SceneTrack(final String guid, final String name, final String sceneGuid) {
            super(guid, name, new int[0], new EffectManager());
            this.resourceGuid = new Id(sceneGuid);
        }

        public Id resourceGuid() {
            return resourceGuid;
        }
    }

    public static class ImageTrack extends TrackSource {
        ImageTrack(final String guid, final String name, final int[] keyFrames, final EffectManager effectManager) {
            super(guid, name, keyFrames, effectManager);
        }
    }

    public static final class GuideImageTrack extends TrackSource {
        GuideImageTrack(final String guid, final String name) {
            super(guid, name, new int[0], new EffectManager());
        }
    }

    public static final class TextTrack extends TrackSource {
        TextTrack(final String guid, final String name, final int[] keyFrames) {
            this(guid, name, keyFrames, new EffectManager());
        }

        TextTrack(final String guid, final String name, final int[] keyFrames, final EffectManager effectManager) {
            super(guid, name, keyFrames, effectManager);
        }
    }

    public static final class Moc3Track extends TrackSource {
        Moc3Track(final String guid, final String name) {
            super(guid, name, new int[0], new EffectManager());
        }
    }

    public static final class SoundTrack extends TrackSource {
        SoundTrack(final String guid, final String name, final int[] keyFrames) {
            super(guid, name, keyFrames, new EffectManager());
        }
    }

    public static final class EffectManager {
        final Effect[] effectList;

        EffectManager(final Effect... effects) {
            this.effectList = effects;
        }

        public Effect[] effectList() {
            return effectList;
        }
    }

    public static class Effect {
        final Id id;
        final String name;
        final Attr[] attrList;

        Effect(final String id, final String name, final Attr... attrs) {
            this.id = new Id(id);
            this.name = name;
            this.attrList = attrs;
        }

        public Id id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean active() {
            return true;
        }

        public Attr[] attrList() {
            return attrList;
        }
    }

    public static final class ParamEffect extends Effect {
        ParamEffect(final String id, final String name, final Attr... attrs) {
            super(id, name, attrs);
        }
    }

    public static class Attr {
        final Id id;
        final String name;
        final Id guid;
        final TreeMap<Integer, Object> values;
        TrackSource track;
        boolean failWrites;

        Attr(
                final String id,
                final String name,
                final String guid,
                final int[] keyFrames,
                final Map<Integer, Object> values) {
            this.id = new Id(id);
            this.name = name;
            this.guid = new Id(guid);
            this.values = new TreeMap<>(values);
        }

        public Id id() {
            return id;
        }

        public String name() {
            return name;
        }

        public Id guid() {
            return guid;
        }

        public boolean active() {
            return true;
        }

        public boolean editable() {
            return true;
        }

        public int[] keyFrames() {
            return values.keySet().stream().mapToInt(Integer::intValue).toArray();
        }

        public Object value(final int frame) {
            return values.get(frame);
        }

        public TrackSource track() {
            return track;
        }

        public void setValueAuto(final int frame, final Object value) {
            failIfRequested();
            values.put(frame, value);
        }

        public void removeValueAuto(final int frame) {
            values.remove(frame);
        }

        void failIfRequested() {
            if (failWrites) {
                throw new IllegalStateException("host mutation rejected");
            }
        }

        Object snapshot() {
            return new TreeMap<>(values);
        }

        void restore(final Object snapshot) {
            values.clear();
            @SuppressWarnings("unchecked")
            final TreeMap<Integer, Object> state = (TreeMap<Integer, Object>) snapshot;
            values.putAll(state);
        }
    }

    public static final class AttrF extends Attr {
        final MutableSequence valueData;
        boolean readOnly;

        AttrF(
                final String id,
                final String name,
                final String guid,
                final int[] keyFrames,
                final Map<Integer, Object> values,
                final MutableSequence valueData) {
            super(id, name, guid, keyFrames, values);
            this.valueData = valueData;
        }

        public MutableSequence valueData() {
            return valueData;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setValueAndCurveType(final int frame, final double value, final CurveType curveType) {
            failIfRequested();
            values.put(frame, value);
            valueData.curveTypes.put(frame, curveType);
            valueData.points.computeIfAbsent(
                    frame,
                    key -> new BezierPt(
                            new CtrlPt(frame - 5.0F, value, false), new CtrlPt(frame + 5.0F, value, false)));
        }

        @Override
        public void setValueAuto(final int frame, final Object value) {
            failIfRequested();
            values.put(frame, value);
            valueData.curveTypes.putIfAbsent(frame, CurveType.LINEAR);
            valueData.points.computeIfAbsent(
                    frame,
                    key -> new BezierPt(
                            new CtrlPt(frame - 5.0F, ((Number) value).doubleValue(), false),
                            new CtrlPt(frame + 5.0F, ((Number) value).doubleValue(), false)));
        }

        @Override
        public void removeValueAuto(final int frame) {
            values.remove(frame);
            valueData.curveTypes.remove(frame);
            valueData.points.remove(frame);
        }

        @Override
        Object snapshot() {
            return new Object[] {new TreeMap<>(values), valueData.snapshot()};
        }

        @Override
        void restore(final Object snapshot) {
            final Object[] state = (Object[]) snapshot;
            values.clear();
            @SuppressWarnings("unchecked")
            final TreeMap<Integer, Object> restored = (TreeMap<Integer, Object>) state[0];
            values.putAll(restored);
            valueData.restore(state[1]);
        }
    }

    public static final class AttrI extends Attr {
        AttrI(
                final String id,
                final String name,
                final String guid,
                final int[] keyFrames,
                final Map<Integer, Object> values) {
            super(id, name, guid, keyFrames, values);
        }

        public void setValueAuto(final int frame, final double value) {
            failIfRequested();
            values.put(frame, value);
        }
    }

    public static final class AttrPt extends Attr {
        AttrPt(
                final String id,
                final String name,
                final String guid,
                final int[] keyFrames,
                final Map<Integer, Object> values) {
            super(id, name, guid, keyFrames, values);
        }

        public void setValueAuto(final int frame, final float x, final float y) {
            failIfRequested();
            values.put(frame, new GVector2(x, y));
        }
    }

    public static final class MutableSequence {
        final Map<Integer, CurveType> curveTypes;
        final Map<Integer, BezierPt> points;
        int forceUpdates;
        boolean failWrites;

        MutableSequence(final Map<Integer, CurveType> curveTypes, final Map<Integer, BezierPt> points) {
            this.curveTypes = new LinkedHashMap<>(curveTypes);
            this.points = new LinkedHashMap<>(points);
        }

        public CurveType curveType(final int frame) {
            return curveTypes.get(frame);
        }

        public BezierPt point(final int frame) {
            return points.get(frame);
        }

        public void setCurveType(final int frame, final CurveType curveType) {
            if (failWrites) {
                throw new IllegalStateException("host mutation rejected");
            }
            curveTypes.put(frame, curveType);
        }

        public void forceUpdate() {
            forceUpdates++;
        }

        Object snapshot() {
            final Map<Integer, BezierPt> pointCopies = new LinkedHashMap<>();
            points.forEach((frame, point) -> pointCopies.put(
                    frame,
                    new BezierPt(
                            point.prev() == null ? null : point.prev().copy(),
                            point.next() == null ? null : point.next().copy())));
            return new Object[] {new LinkedHashMap<>(curveTypes), pointCopies};
        }

        void restore(final Object snapshot) {
            final Object[] state = (Object[]) snapshot;
            curveTypes.clear();
            @SuppressWarnings("unchecked")
            final Map<Integer, CurveType> restoredTypes = (Map<Integer, CurveType>) state[0];
            curveTypes.putAll(restoredTypes);
            points.clear();
            @SuppressWarnings("unchecked")
            final Map<Integer, BezierPt> restoredPoints = (Map<Integer, BezierPt>) state[1];
            points.putAll(restoredPoints);
        }
    }

    public static final class BezierPt {
        final CtrlPt prev;
        final CtrlPt next;

        BezierPt(final CtrlPt prev, final CtrlPt next) {
            this.prev = prev;
            this.next = next;
        }

        public CtrlPt prev() {
            return prev;
        }

        public CtrlPt next() {
            return next;
        }
    }

    public static final class CtrlPt {
        float posF;
        double value;
        boolean corner;

        CtrlPt(final float posF, final double value, final boolean corner) {
            this.posF = posF;
            this.value = value;
            this.corner = corner;
        }

        public float posF() {
            return posF;
        }

        public double doubleValue() {
            return value;
        }

        public boolean corner() {
            return corner;
        }

        public void setPos(final float pos) {
            this.posF = pos;
        }

        public void setValue(final double newValue) {
            this.value = newValue;
        }

        public void setCorner(final boolean isCorner) {
            this.corner = isCorner;
        }

        CtrlPt copy() {
            return new CtrlPt(posF, value, corner);
        }
    }

    public enum CurveType {
        LINEAR,
        BEZIER,
        SMOOTH,
        STEP,
        INVERSE_STEP
    }

    public static final class ViewContext {}

    public static final class EvalFlags {
        final boolean includePreview;

        public EvalFlags(final boolean includePreview) {
            this.includePreview = includePreview;
        }
    }

    public static final class SceneTime {
        int frame;

        public int frame() {
            return frame;
        }

        public void frame(final int newFrame) {
            this.frame = newFrame;
        }
    }

    public static final class SceneInstance {
        final SceneTime currentTime = new SceneTime();
        final GroupTrackInstance rootTrack;

        SceneInstance(final SceneSource source) {
            final List<TrackInstance> children = new ArrayList<>();
            for (TrackSource child : source.rootTrack().childTracks()) {
                children.add(
                        child instanceof ModelTrack model ? new ModelTrackInstance(model) : new TrackInstance(child));
            }
            this.rootTrack = new GroupTrackInstance(source.rootTrack(), children);
        }

        public SceneTime currentTime() {
            return currentTime;
        }

        public GroupTrackInstance rootTrack() {
            return rootTrack;
        }

        public List<TrackInstance> allTracks() {
            return rootTrack.children;
        }
    }

    public static class TrackInstance {
        final TrackSource source;

        TrackInstance(final TrackSource source) {
            this.source = source;
        }

        public TrackSource source() {
            return source;
        }

        public void update(final Object viewContext, final SceneTime time, final EvalFlags flags) {}
    }

    public static final class GroupTrackInstance extends TrackInstance {
        final List<TrackInstance> children;

        GroupTrackInstance(final TrackSource source, final List<TrackInstance> children) {
            super(source);
            this.children = children;
        }

        @Override
        public void update(final Object viewContext, final SceneTime time, final EvalFlags flags) {
            for (TrackInstance child : children) {
                child.update(viewContext, time, flags);
            }
        }
    }

    public static final class ModelTrackInstance extends TrackInstance {
        final ParameterSet parameterSet = new ParameterSet();
        int evaluations;

        ModelTrackInstance(final TrackSource source) {
            super(source);
        }

        public ParameterSet parameterSet() {
            return parameterSet;
        }

        @Override
        public void update(final Object viewContext, final SceneTime time, final EvalFlags flags) {
            evaluations++;
            parameterSet.parameters().get(0).value = time.frame() * 0.5F;
        }
    }
}
