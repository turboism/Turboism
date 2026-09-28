package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInputs;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureInputBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.cubism.event.ProjectFileLifecycleEvent;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestResult;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.text.JTextComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Validation-only preparation for the official Cubism 5.3.02 PSD new-model path.
 *
 * <p>The helper is deliberately separate from the ordinary external-edit probe.  The official
 * PSD chooser is visible before a model exists, so this entry point must run before the probe's
 * normal model readiness wait.  It never calls the native PSD importer and never writes a native
 * selector; it only verifies and drives the already displayed Swing chooser.</p>
 */
public final class OfficialPsdFixturePreparation {
    public static final String HOST_JAR_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    public static final String NATIVE_SEVEN_LAYER_SHA256 =
        "8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c";
    public static final String NATIVE_SEVEN_LAYER_TARGET_RGB_SHA256 =
        "12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d";
    public static final String F1_FIXTURE_NAME = "f1-2048-20layers.psd";
    public static final String F1_FIXTURE_SHA256 =
        "2473baeae7fc8942d8d2e0df72cf4567f57f8e3a9a37a924fc4d01034ababfeb";
    public static final String F1_SAVED_COPY_BASENAME = "prepared-f1.cmo3";
    public static final String CONFIG_PREFIX =
        "turboism.validation.externalpsd.prepare.";
    public static final String FIXTURE_PROPERTY = CONFIG_PREFIX + "fixture";
    public static final String FIXTURE_SHA256_PROPERTY = CONFIG_PREFIX + "fixtureSha256";
    public static final String FIXTURE_NAME_PROPERTY = CONFIG_PREFIX + "fixtureName";
    public static final String RUN_ID_PROPERTY = CONFIG_PREFIX + "runId";
    public static final String TASK_ID_PROPERTY = CONFIG_PREFIX + "taskId";
    public static final String SAVED_COPY_PROPERTY = CONFIG_PREFIX + "savedCopy";
    public static final String TARGET_RGB_SHA256_PROPERTY = CONFIG_PREFIX + "targetRgbSha256";
    public static final String HOST_VERSION_PROPERTY = CONFIG_PREFIX + "hostVersion";
    public static final String TIMEOUT_MILLIS_PROPERTY = CONFIG_PREFIX + "timeoutMillis";
    public static final String PROFILE_PROPERTY = CONFIG_PREFIX + "profile";

    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String MAIN_FRAME_CONTROLLER =
        "com.live2d.cubism.view.CEMainFrameCtrl";
    private static final String C_FRAME = "com.live2d.ui.window.CFrame";
    private static final String WINDOW_BASE = "com.live2d.ui.window.V";
    private static final String OPTION = "com.live2d.cubism.process.psd.a$a";
    private static final String PREVIEW_OPTION =
        "com.live2d.cubism.doc.modeling.ui.b";
    private static final String MODEL_DOCUMENT =
        "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String RENDERER = "com.live2d.cubism.process.psd.e";
    private static final String HOST_LIST = "com.live2d.ui.swingImpl.q";
    private static final String HOST_BUTTON = "com.live2d.ui.swingImpl.j";
    private static final String HOST_BUTTON_SUBCLASS = "com.live2d.ui.control.CButton$b";
    private static final String HOST_ACTION = "com.live2d.ui.event.CAction";
    private static final String LOCALIZER = "b.c";
    private static final String FIRST_LABEL_KEY = "CUB3-0418";
    private static final String SECOND_LABEL_KEY = "CUB3-4408";
    private static final String DIALOG_TITLE_KEY = "CUB3-0421";
    private static final String DIALOG_MESSAGE_KEY = "CUB3-0420";
    private static final String PREVIEW_TITLE_KEY = "CUBI-0003";
    private static final String PREVIEW_MESSAGE_KEY = "CUB3-1430";
    private static final long DEFAULT_TIMEOUT_MILLIS = 180_000L;
    private static final long EDT_CALL_TIMEOUT_MILLIS = 2_000L;
    private static final long SAVE_AS_EDT_CALL_TIMEOUT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 150L;
    private static final String UNAVAILABLE = "unavailable";
    private static final String MODE_UNAVAILABLE = "UNAVAILABLE";
    private static final Set<Integer> PREVIEW_RATIOS = Set.of(1, 2, 4, 8);

    private final PluginContext context;
    private final BooleanSupplier stopped;
    private boolean homeCloseAttempted;
    private final Properties properties;

    public OfficialPsdFixturePreparation(final PluginContext context,
        final BooleanSupplier stopped, final Properties properties) {
        this.context = Objects.requireNonNull(context, "context");
        this.stopped = Objects.requireNonNull(stopped, "stopped");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** Entry point used by the future prepare-fixture phase of the main probe. */
    public static PreparationResult prepareFixture(final PluginContext context,
        final BooleanSupplier stopped, final Properties properties) throws Exception {
        return new OfficialPsdFixturePreparation(context, stopped, properties).prepare();
    }

    /** Instance form kept explicit so the caller can retain the returned Window identity. */
    public PreparationResult prepare() throws Exception {
        InputIdentity input = null;
        PreparationProfile profile = null;
        EventRecorder events = null;
        final List<Registration> registrations = new ArrayList<>();
        try {
            final PreparedInput prepared = readInput(properties);
            input = prepared.identity();
            profile = prepared.profile();
            recordInput(input, profile);
            final HostAccess host = preflightHostAccess();
            recordHost(host);
            recordProfile(profile);

            // These subscriptions intentionally happen before the chooser is touched.  OPEN is
            // not assumed for a new document; model/relation identity is the required gate.
            events = new EventRecorder(properties);
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.Before.class, events::before));
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.On.class, events::on));
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.After.class, events::after));

            final Window window = chooseNewModel(host, input, profile, input.timeoutMillis());
            recordWindow(window);
            ModelState state;
            try {
                state = awaitInitialModel(input, profile, input.timeoutMillis(), window);
            } catch (Exception failure) {
                recordModelWaitStacks();
                throw failure;
            }
            recordModelState("model.initial", state);
            if (profile.f1Sharing()) {
                final F1SharingPreparation sharing = prepareF1Sharing(
                    input, state, window, host, input.timeoutMillis());
                recordF1Sharing(sharing);
                state = sharing.afterState();
            }
            recordModelState("model.beforeSave", state);

            final SavedCopyIdentity saved = saveAsAndConfirm(
                input, state, window, host, events);
            recordSavedCopy(saved);
            properties.setProperty("prepare.status", "PASS");
            properties.setProperty("phase", "prepare-fixture");
            properties.setProperty("expected", "official PSD new-model import and SAVE_AS");
            properties.setProperty("actual", "official chooser, relation graph, and SAVE After verified");
            return new PreparationResult(window, saved, state.identity());
        } catch (Throwable failure) {
            properties.setProperty("prepare.status",
                failure instanceof PreparationBlockedException ? "BLOCKED" : "FAIL");
            properties.setProperty("prepare.failure", summarize(failure));
            if (failure instanceof Exception exception) throw exception;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException(failure);
        } finally {
            if (events != null) {
                events.recordSummary();
            }
            for (int index = registrations.size() - 1; index >= 0; index--) {
                try {
                    registrations.get(index).close();
                } catch (Throwable closeFailure) {
                    properties.setProperty("prepare.subscriptionClose." + index,
                        summarize(closeFailure));
                }
            }
        }
    }

    /** Alias useful to a phase coordinator that names its operation {@code run}. */
    public PreparationResult run() throws Exception {
        return prepare();
    }

    private static PreparedInput readInput(final Properties values) {
        final String fixture = configured(values, FIXTURE_PROPERTY);
        final String fixtureSha = configured(values, FIXTURE_SHA256_PROPERTY);
        final String fixtureName = configured(values, FIXTURE_NAME_PROPERTY);
        final String runId = configured(values, RUN_ID_PROPERTY);
        final String taskId = configured(values, TASK_ID_PROPERTY);
        final String savedCopy = configured(values, SAVED_COPY_PROPERTY);
        final String targetRgb = configured(values, TARGET_RGB_SHA256_PROPERTY);
        final String hostVersion = configured(values, HOST_VERSION_PROPERTY);
        final PreparationProfile profile = parseProfile(configured(values, PROFILE_PROPERTY));
        final long timeout = timeoutMillis(configured(values, TIMEOUT_MILLIS_PROPERTY));
        return new PreparedInput(validateInput(fixture, fixtureSha, fixtureName, runId, taskId,
            savedCopy, targetRgb, hostVersion, timeout, profile), profile);
    }

    /**
     * Read only the preparation namespace.  Runner result properties also contain generic keys
     * such as {@code profile}, {@code runId}, and {@code fixture}; falling back to those keys
     * can make a pipeline profile mask the explicitly requested preparation profile.
     */
    private static String configured(final Properties values, final String key) {
        String value = values.getProperty(key, "");
        if (value.isBlank()) value = System.getProperty(key, "");
        return value == null ? "" : value.trim();
    }

    /** Package-private regression seam for the preparation namespace only. */
    static InputIdentity readInputForTest(final Properties values) {
        return readInput(values).identity();
    }

    /** Package-private regression seam for profile precedence and collision handling. */
    static String configuredProfileForTest(final Properties values) {
        return readInput(values).profile().profileName();
    }

    private static long timeoutMillis(final String value) {
        if (value == null || value.isBlank()) return DEFAULT_TIMEOUT_MILLIS;
        try {
            final long parsed = Long.parseLong(value);
            if (parsed < 1_000L || parsed > 600_000L) {
                throw new IllegalArgumentException("timeoutMillis is outside 1s..600s");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("timeoutMillis is not an integer", failure);
        }
    }

    private static InputIdentity validateInput(final String fixture, final String fixtureSha,
        final String fixtureName, final String runId, final String taskId,
        final String savedCopy, final String targetRgb, final String hostVersion,
        final long timeoutMillis) {
        return validateInput(fixture, fixtureSha, fixtureName, runId, taskId, savedCopy,
            targetRgb, hostVersion, timeoutMillis, PreparationProfile.NORMAL);
    }

    private static InputIdentity validateInput(final String fixture, final String fixtureSha,
        final String fixtureName, final String runId, final String taskId,
        final String savedCopy, final String targetRgb, final String hostVersion,
        final long timeoutMillis, final PreparationProfile profile) {
        requireSafeId(runId, "runId");
        requireSafeId(taskId, "taskId");
        if (!runId.equals(taskId)) throw new IllegalArgumentException(
            "runId and taskId must bind the same queue task");
        requireWindowsPath(fixture, "fixture");
        if (!fixtureName.isBlank() && !isPsdName(fixtureName)) {
            throw new IllegalArgumentException("fixtureName must end in .psd");
        }
        if (!isPsdName(lastPathPart(fixture))) {
            throw new IllegalArgumentException("fixture task copy must be a .psd");
        }
        requireSha(fixtureSha, "fixtureSha256");
        if (!profile.fixtureSha256().equals(fixtureSha)) {
            throw new IllegalArgumentException("fixtureSha256 is not the reviewed "
                + profile.profileName() + " PSD");
        }
        if (profile.f1Sharing()) {
            final String expectedTaskFixtureName = runId + "-" + profile.expectedFixtureName();
            if (!expectedTaskFixtureName.equals(fixtureName)
                || !expectedTaskFixtureName.equals(lastPathPart(fixture))) {
                throw new IllegalArgumentException("F1 fixtureName/path must be the runner task copy "
                    + expectedTaskFixtureName);
            }
        }
        if (profile.requiresEmptyTargetRgb() && !targetRgb.isBlank()) {
            throw new IllegalArgumentException("F1 does not accept a seven-layer target RGB hash");
        }
        requireWindowsPath(savedCopy, "savedCopy");
        if (!lastPathPart(savedCopy).equals(profile.savedCopyBasename())) {
            throw new IllegalArgumentException("savedCopy must be " + profile.savedCopyBasename());
        }
        if (!targetRgb.isBlank()) requireSha(targetRgb, "targetRgbSha256");
        if (!hostVersion.isBlank() && !hostVersion.equals("5.3.02")) {
            throw new IllegalArgumentException("only Cubism 5.3.02 is reviewed");
        }
        return new InputIdentity(fixture, fixtureSha, fixtureName, runId, taskId, savedCopy,
            targetRgb, hostVersion.isBlank() ? "5.3.02" : hostVersion, timeoutMillis);
    }

    /** Package-private seam for the offline focused test; it performs no host or file access. */
    static InputIdentity validateInputForTest(final String fixture, final String fixtureSha,
        final String fixtureName, final String runId, final String taskId,
        final String savedCopy, final String targetRgb, final String hostVersion,
        final long timeoutMillis) {
        return validateInput(fixture, fixtureSha, fixtureName, runId, taskId, savedCopy,
            targetRgb, hostVersion, timeoutMillis);
    }

    /** Package-private profile seam for the offline focused test. */
    static InputIdentity validateInputForProfileForTest(final String fixture,
        final String fixtureSha, final String fixtureName, final String runId,
        final String taskId, final String savedCopy, final String targetRgb,
        final String hostVersion, final long timeoutMillis, final String profile) {
        return validateInput(fixture, fixtureSha, fixtureName, runId, taskId, savedCopy,
            targetRgb, hostVersion, timeoutMillis, parseProfile(profile));
    }

    /** Package-private chooser/output profile seam for the offline focused test. */
    static String profileKeyForTest(final String profile) {
        return parseProfile(profile).chooserKey();
    }

    static int profileChooserIndexForTest(final String profile) {
        return parseProfile(profile).chooserIndex();
    }

    static String profileSavedCopyBasenameForTest(final String profile) {
        return parseProfile(profile).savedCopyBasename();
    }

    private static PreparationProfile parseProfile(final String value) {
        if (value == null || value.isBlank() || "normal".equalsIgnoreCase(value.trim())) {
            return PreparationProfile.NORMAL;
        }
        if ("legacy".equalsIgnoreCase(value.trim())) return PreparationProfile.LEGACY;
        if ("f1".equalsIgnoreCase(value.trim())) return PreparationProfile.F1;
        throw new IllegalArgumentException("unknown official PSD preparation profile: " + value);
    }

    private static void requireWindowsPath(final String value, final String name) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0
            || value.contains("..\\") || value.contains("../")) {
            throw new IllegalArgumentException(name + " is not a safe task-bound path");
        }
    }

    private static void requireSafeId(final String value, final String name) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " is not a safe task identity");
        }
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha(final String value, final String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256");
        }
    }

    private static boolean isPsdName(final String value) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).endsWith(".psd")
            && value.length() > 4;
    }

    private static String lastPathPart(final String value) {
        final int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return value.substring(slash + 1);
    }

    private void recordInput(final InputIdentity input, final PreparationProfile profile) {
        properties.setProperty("prepare.fixture.path", input.fixturePath());
        properties.setProperty("prepare.fixture.name", lastPathPart(input.fixturePath()));
        properties.setProperty("prepare.fixture.expectedName", input.fixtureName());
        properties.setProperty("prepare.fixture.sha256", input.fixtureSha256());
        properties.setProperty("prepare.fixture.targetRgbSha256",
            input.targetRgbSha256().isBlank() ? UNAVAILABLE : input.targetRgbSha256());
        properties.setProperty("prepare.taskId", input.taskId());
        properties.setProperty("prepare.runId", input.runId());
        properties.setProperty("prepare.savedCopy.path", input.savedCopyPath());
        properties.setProperty("prepare.savedCopy.sha256", UNAVAILABLE);
        properties.setProperty("prepare.savedCopy.hashStatus",
            "UNAVAILABLE: write-only UserFileHandle does not expose a path/read capability");
        properties.setProperty("prepare.profile", profile.profileName());
        properties.setProperty("prepare.savedCopy.expectedBasename", profile.savedCopyBasename());
    }

    private void recordProfile(final PreparationProfile profile) {
        properties.setProperty("prepare.profile", profile.profileName());
        properties.setProperty("prepare.chooser.expectedSelectedIndex",
            Integer.toString(profile.chooserIndex()));
        properties.setProperty("prepare.chooser.expectedSelectedKey", profile.chooserKey());
        properties.setProperty("prepare.savedCopy.expectedBasename", profile.savedCopyBasename());
        if (!profile.f1PreviewChooser()) {
            properties.setProperty("prepare.previewChooser.status", "NOT_APPLICABLE");
        }
    }

    private HostAccess preflightHostAccess() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("official host preflight must run off EDT");
        }
        // The reviewed official BAT puts Cubism on the JVM application classpath. Plugin
        // workers inherit an isolated plugin TCCL, which cannot see host classes. Load through
        // the application loader, then verify the defining loader, code source and exact SHA.
        final Class<?> app = loadHostApplication();
        final ClassLoader loader = app.getClassLoader();
        if (loader == null) throw new IllegalStateException(
            "official host defining classloader is unavailable");
        final Path artifact = codeSourcePath(app);
        if (!Files.isRegularFile(artifact)) throw new IOException("host code source is not a JAR");
        final String digest = sha256(artifact);
        if (!HOST_JAR_SHA256.equals(digest)) {
            throw new IOException("host JAR SHA-256 mismatch: " + digest);
        }
        final Class<?> mainFrameController = loadExact(loader, MAIN_FRAME_CONTROLLER);
        final Class<?> cFrame = loadExact(loader, C_FRAME);
        final Class<?> windowBase = loadExact(loader, WINDOW_BASE);
        final Class<?> option = loadExact(loader, OPTION);
        final Class<?> previewOption = loadExact(loader, PREVIEW_OPTION);
        final Class<?> modelDocument = loadExact(loader, MODEL_DOCUMENT);
        final Class<?> renderer = loadExact(loader, RENDERER);
        final Class<?> hostList = loadExact(loader, HOST_LIST);
        final Class<?> hostButton = loadExact(loader, HOST_BUTTON);
        final Class<?> hostButtonSubclass = loadExact(loader, HOST_BUTTON_SUBCLASS);
        final Class<?> action = loadExact(loader, HOST_ACTION);
        final Class<?> localizer = loadExact(loader, LOCALIZER);
        final Class<?> home = loadExact(loader, "com.live2d.cubism.appCtrlImpl.ui.e.a");
        final Class<?> homeWindow = loadExact(loader, "com.live2d.ui.window.m");
        final Class<?> documentInterface = loadExact(loader, "com.live2d.cubism.doc.IDocument");
        final Class<?> selector = loadExact(loader, "com.live2d.doc.selection.ISelector");
        final Class<?> selectionBase = loadExact(loader,
            "com.live2d.cubism.doc.selection.ACGuidSelection");
        final Class<?> meshSelection = loadExact(loader,
            "com.live2d.cubism.doc.model.drawable.artMesh.ArtMeshSelection");
        final Class<?> guid = loadExact(loader, "com.live2d.type.Guid");
        for (final Class<?> type : List.of(app, mainFrameController, cFrame, windowBase, option,
            previewOption, modelDocument, renderer, hostList, hostButton, hostButtonSubclass,
            action, localizer, home, homeWindow, documentInterface, selector, selectionBase,
            meshSelection, guid)) {
            verifyClassArtifact(type, loader, artifact);
        }
        if (!JList.class.isAssignableFrom(hostList)
            || !AbstractButton.class.isAssignableFrom(hostButton)
            || !AbstractButton.class.isAssignableFrom(hostButtonSubclass)) {
            throw new IllegalArgumentException("official Swing shape is not exact");
        }
        final Method appInstance = exactMethod(app, "access$get_instance$cp", app, true);
        final Method mainFrame = exactMethod(app, "getMainFrameCtrl", mainFrameController, false);
        final Method cFrameGetter = exactMethod(mainFrameController, "getMainFrame", cFrame, false);
        final Method swingWindow = exactPublicMethod(cFrame, "getJWindow", Window.class, false,
            windowBase);
        final Method swingFrame = exactMethod(cFrame, "getJFrame", JFrame.class, false);
        final Method optionModel = exactMethod(option, "a", modelDocument, false);
        final Method optionLabel = exactMethod(option, "b", String.class, false);
        final Method previewLabel = exactMethod(previewOption, "a", String.class, false);
        final Method previewRatio = exactMethod(previewOption, "b", int.class, false);
        final HomeAccess homeAccess = new HomeAccess(
            exactMethod(home, "e", home, true), exactMethod(home, "a", app, false),
            exactMethod(home, "a", homeWindow, true, home),
            exactMethod(homeWindow, "getJDialog", javax.swing.JDialog.class, false));
        final NativeSelectionAccess selectionAccess = new NativeSelectionAccess(
            modelDocument, meshSelection,
            exactMethod(app, "getCurrentDoc", documentInterface, false),
            exactMethod(modelDocument, "getSelector", selector, false),
            exactMethod(selector, "getSelected", List.class, false),
            exactMethod(selector, "getSelectedCount", int.class, false),
            exactMethod(selectionBase, "getGuid", guid, false),
            exactMethod(guid, "getUuidString", String.class, false));
        final Field localizerInstance = localizer.getDeclaredField("a");
        if (!Modifier.isPublic(localizerInstance.getModifiers())
            || !Modifier.isStatic(localizerInstance.getModifiers())
            || !Modifier.isFinal(localizerInstance.getModifiers())
            || localizerInstance.getType() != localizer) {
            throw new IllegalArgumentException("official localizer field shape is not exact");
        }
        if (!Modifier.isPublic(localizerInstance.getModifiers())
            || !localizerInstance.trySetAccessible()) {
            throw new IllegalArgumentException("official localizer field is inaccessible");
        }
        final Method localize = exactMethod(localizer, "a", String.class, false,
            String.class, String[].class);
        final Object localizerObject = localizerInstance.get(null);
        final String firstLabel = localized(localize, localizerObject, FIRST_LABEL_KEY);
        final String secondLabel = localized(localize, localizerObject, SECOND_LABEL_KEY);
        final String title = localized(localize, localizerObject, DIALOG_TITLE_KEY);
        final String message = localized(localize, localizerObject, DIALOG_MESSAGE_KEY);
        final String previewTitle = localized(localize, localizerObject, PREVIEW_TITLE_KEY);
        final String previewMessage = localized(localize, localizerObject, PREVIEW_MESSAGE_KEY);
        if (firstLabel.isBlank() || secondLabel.isBlank() || title.isBlank() || message.isBlank()
            || firstLabel.equals(secondLabel) || previewTitle.isBlank()
            || previewMessage.isBlank()) {
            throw new IllegalArgumentException("official chooser localization is unavailable");
        }
        return new HostAccess(loader, artifact, digest, app, appInstance, mainFrame, cFrameGetter,
            swingWindow, swingFrame, option, optionModel, optionLabel, previewOption, previewLabel,
            previewRatio, renderer, hostList, hostButton, hostButtonSubclass, action, firstLabel,
            secondLabel, title, message, previewTitle, previewMessage, homeAccess, selectionAccess);
    }

    private static String localized(final Method localize, final Object instance,
        final String key) throws Exception {
        final Object value = localize.invoke(instance, key, new String[0]);
        if (!(value instanceof String text)) throw new IllegalArgumentException(
            "localizer returned non-text for " + key);
        return text;
    }

    private void recordHost(final HostAccess host) {
        properties.setProperty("prepare.host.jar.sha256", host.sha256());
        properties.setProperty("prepare.host.codeSource", host.artifact().toString());
        properties.setProperty("prepare.host.shape",
            "CEAppCtrl.access$get_instance$cp->getMainFrameCtrl->getMainFrame->V.getJWindow");
        properties.setProperty("prepare.chooser.expectedListClass", HOST_LIST);
        properties.setProperty("prepare.chooser.expectedRendererClass", RENDERER);
        properties.setProperty("prepare.chooser.expectedOptionClass", OPTION);
        properties.setProperty("prepare.chooser.labelKey.0", FIRST_LABEL_KEY);
        properties.setProperty("prepare.chooser.labelKey.1", SECOND_LABEL_KEY);
        properties.setProperty("prepare.chooser.titleKey", DIALOG_TITLE_KEY);
        properties.setProperty("prepare.chooser.messageKey", DIALOG_MESSAGE_KEY);
        properties.setProperty("prepare.previewChooser.titleKey", PREVIEW_TITLE_KEY);
        properties.setProperty("prepare.previewChooser.messageKey", PREVIEW_MESSAGE_KEY);
        properties.setProperty("prepare.previewChooser.optionClass", PREVIEW_OPTION);
        properties.setProperty("prepare.previewChooser.ratioGetter", "b():int");
    }

    private Window chooseNewModel(final HostAccess host, final InputIdentity input,
        final PreparationProfile profile, final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final AtomicReference<Window> owner = new AtomicReference<>();
        while (System.nanoTime() < deadline) {
            checkStoppedAndTask(input);
            properties.setProperty("prepare.chooser.edtState", "QUEUED");
            // Startup and the official confirmation action can occupy the EDT for longer than
            // an ordinary read. Wait for this single dispatch within the preparation deadline;
            // a timed-out action is never resubmitted.
            final long remainingMillis = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            final EdtCall<ChoiceObservation> call = invokeEdtBounded(() -> {
                properties.setProperty("prepare.chooser.edtState", "RUNNING");
                final ChoiceObservation choice = inspectAndChooseOnEdt(host, input, profile, owner);
                properties.setProperty("prepare.chooser.edtState", "RETURNED");
                return choice;
            }, remainingMillis);
            if (!call.completed()) {
                recordChooserTimeout();
                throw new IllegalStateException("official chooser EDT inspection timed out");
            }
            if (call.failure() != null) throw asException(call.failure());
            if (call.value() != null && call.value().chosen()) {
                final ChoiceObservation chosen = call.value();
                recordChoice(chosen);
                waitForDialogGone(chosen.dialog(), deadline);
                if (profile.f1PreviewChooser()) {
                    chooseOptionalPreviewReduction(host, input, chosen.owner(), deadline);
                }
                return chosen.owner();
            }
            sleepPoll(deadline);
        }
        throw new IllegalStateException("official PSD chooser did not appear before timeout");
    }

    /**
     * F1 only: the official importer may expose a reduction chooser before a model exists.
     * Its absence is valid; this bounded probe never invents a click or changes native state.
     */
    private void chooseOptionalPreviewReduction(final HostAccess host,
        final InputIdentity input, final Window boundWindow, final long deadline) throws Exception {
        // The native importer runs asynchronously. An absent dialog at two seconds does
        // not prove that it will not appear later; share the original chooser deadline.
        final long probeDeadline = deadline;
        properties.setProperty("prepare.previewChooser.status", "WAITING_OPTIONAL");
        while (System.nanoTime() < probeDeadline) {
            checkStoppedAndTask(input);
            final long remainingNanos = probeDeadline - System.nanoTime();
            final long remainingMillis = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            final EdtCall<PreviewProbe> call = invokeEdtBounded(
                () -> inspectAndChoosePreviewOnEdt(host, input, boundWindow,
                    probeDeadline), remainingMillis);
            if (!call.completed()) {
                properties.setProperty("prepare.previewChooser.status", "TIMEOUT");
                throw new IllegalStateException("F1 preview chooser EDT inspection timed out");
            }
            if (call.failure() != null) throw asException(call.failure());
            final PreviewProbe probe = call.value();
            if (probe != null && probe.selected()) {
                waitForDialogGone(probe.dialog(), deadline);
                properties.setProperty("prepare.previewChooser.return", "RETURNED");
                return;
            }
            if (probe != null && probe.activeModelPresent()) {
                recordPreviewAbsent("active model became available");
                return;
            }
            sleepPoll(probeDeadline);
        }
        throw new IllegalStateException("F1 model or optional preview chooser readiness timed out");
    }

    private PreviewProbe inspectAndChoosePreviewOnEdt(final HostAccess host,
        final InputIdentity input, final Window boundWindow,
        final long actionDeadline) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "preview chooser inspection must run on EDT");
        checkStoppedAndTask(input);
        final Window currentWindow = currentWindowOnEdt(host, false);
        if (currentWindow != boundWindow) throw new IllegalStateException(
            "F1 preview chooser bound window identity changed");

        final List<Dialog> candidates = new ArrayList<>();
        for (final Window candidateWindow : Window.getWindows()) {
            if (!(candidateWindow instanceof Dialog dialog) || !dialog.isShowing()
                || !dialog.isDisplayable()) continue;
            final List<JList<?>> lists = exactLists(dialog, host.hostList());
            if (!containsText(dialog, host.previewTitle())
                || !containsText(dialog, host.previewMessage())) continue;
            if (dialog.getOwner() != boundWindow) throw new IllegalStateException(
                "F1 preview chooser has a wrong owner");
            candidates.add(dialog);
            properties.setProperty("prepare.previewChooser.observedDialog."
                + candidates.size() + ".class", dialog.getClass().getName());
            properties.setProperty("prepare.previewChooser.observedDialog."
                + candidates.size() + ".listCount", Integer.toString(lists.size()));
        }
        properties.setProperty("prepare.previewChooser.candidateCount",
            Integer.toString(candidates.size()));
        final boolean activeModel = activeModelPresentOnEdt();
        if (candidates.isEmpty()) return new PreviewProbe(false, activeModel, null);
        if (candidates.size() != 1) throw new IllegalStateException(
            "multiple F1 preview chooser candidates are visible");
        if (activeModel) throw new IllegalStateException(
            "F1 preview chooser appeared with an existing active model");

        final Dialog dialog = candidates.get(0);
        final List<JList<?>> lists = exactLists(dialog, host.hostList());
        if (lists.size() != 1) throw new IllegalStateException(
            "F1 preview chooser list is ambiguous");
        final JList<?> list = lists.get(0);
        final List<Object> options = new ArrayList<>();
        final List<Class<?>> optionClasses = new ArrayList<>();
        final List<Integer> ratios = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int index = 0; index < list.getModel().getSize(); index++) {
            final Object option = list.getModel().getElementAt(index);
            if (option == null || option.getClass() != host.previewOption()) {
                throw new IllegalStateException("F1 preview chooser option class is unknown");
            }
            final Object ratioValue = host.previewRatio().invoke(option);
            if (!(ratioValue instanceof Integer ratio)) throw new IllegalStateException(
                "F1 preview chooser ratio is unavailable");
            options.add(option);
            optionClasses.add(option.getClass());
            ratios.add(ratio);
            labels.add(previewOptionLabel(host, option));
        }
        if (options.size() != PREVIEW_RATIOS.size()
            || !new LinkedHashSet<>(ratios).equals(PREVIEW_RATIOS)
            || ratios.stream().filter(value -> value == 1).count() != 1) {
            throw new IllegalStateException("F1 preview chooser ratios are not exactly 1/2/4/8");
        }
        final int targetIndex = ratios.indexOf(1);
        final Object targetOption = options.get(targetIndex);
        final AbstractButton confirmation = exactPreviewConfirmation(dialog, host);
        final PreviewChooserObservation observation = new PreviewChooserObservation(
            currentWindow, dialog.getOwner(), dialog, list, 1, list.getClass(), options,
            optionClasses, ratios, targetIndex, targetOption, 1, confirmation.getClass(),
            confirmation.getAction() == null ? null : confirmation.getAction().getClass(),
            confirmation.getAction() == null ? null
                : String.valueOf(confirmation.getAction().getValue(Action.NAME)),
            confirmation.isEnabled(), confirmation.isShowing(), confirmation.isDisplayable());
        recordPreviewObservation(observation, labels);
        final AtomicReference<String> guardFailure = new AtomicReference<>();
        final PreviewChooserGateResult gate = verifyAndExecutePreviewChooser(observation,
            new PreviewChooserGateExpectation(host.hostList(), host.previewOption(),
                host.hostButton(), host.hostButtonSubclass(), host.action(), PREVIEW_RATIOS),
            targetIndex, targetOption,
            () -> previewWriteContextOpen(host, input, boundWindow, dialog, list,
                confirmation, options, ratios, targetOption, targetIndex, actionDeadline,
                guardFailure),
            stopped, () -> isTaskBound(input),
            () -> System.nanoTime() < actionDeadline,
            new PreviewChooserGateActions() {
                @Override public boolean select(final int index) {
                    properties.setProperty("prepare.previewChooser.selection", "DISPATCHED");
                    list.setSelectedIndex(index);
                    final boolean selected = list.getSelectedIndex() == index
                        && list.getSelectedValue() == targetOption;
                    properties.setProperty("prepare.previewChooser.selection.result",
                        selected ? "RETURNED" : "REJECTED");
                    return selected;
                }

                @Override public void clickConfirm() {
                    properties.setProperty("prepare.previewChooser.confirm", "DISPATCHED");
                    confirmation.doClick();
                    properties.setProperty("prepare.previewChooser.confirm", "RETURNED");
                }
            });
        if (!gate.accepted()) {
            properties.setProperty("prepare.previewChooser.return", "REJECTED");
            final String suffix = guardFailure.get();
            throw new IllegalStateException(gate.diagnostic()
                + (suffix == null ? "" : ": " + suffix));
        }
        properties.setProperty("prepare.previewChooser.status", "SELECTED");
        properties.setProperty("prepare.previewChooser.selectedRatio", "1");
        return new PreviewProbe(true, false, dialog);
    }

    private boolean previewWriteContextOpen(final HostAccess host, final InputIdentity input,
        final Window boundWindow, final Dialog dialog, final JList<?> list,
        final AbstractButton confirmation, final List<Object> options,
        final List<Integer> ratios, final Object targetOption, final int targetIndex,
        final long deadline, final AtomicReference<String> guardFailure) {
        try {
            checkStoppedAndTask(input);
            if (System.nanoTime() >= deadline || currentWindowOnEdt(host, false) != boundWindow
                || dialog.getOwner() != boundWindow || !dialog.isShowing()
                || !dialog.isDisplayable()) return false;
            final List<Dialog> candidates = new ArrayList<>();
            for (final Window candidateWindow : Window.getWindows()) {
                if (!(candidateWindow instanceof Dialog candidate) || !candidate.isShowing()
                    || !candidate.isDisplayable() || !containsText(candidate, host.previewTitle())
                    || !containsText(candidate, host.previewMessage())) continue;
                candidates.add(candidate);
            }
            if (candidates.size() != 1 || candidates.get(0) != dialog) return false;
            final List<JList<?>> lists = exactLists(dialog, host.hostList());
            if (lists.size() != 1 || lists.get(0) != list
                || list.getModel().getSize() != 4
                || list.getModel().getElementAt(targetIndex) != targetOption) return false;
            for (int index = 0; index < options.size(); index++) {
                final Object option = list.getModel().getElementAt(index);
                if (option != options.get(index) || option.getClass() != host.previewOption()
                    || !ratios.get(index).equals(host.previewRatio().invoke(option))) return false;
            }
            if (activeModelPresentOnEdt()) return false;
            final AbstractButton currentConfirmation = exactPreviewConfirmation(dialog, host);
            if (currentConfirmation != confirmation || !confirmation.isEnabled()
                || !confirmation.isShowing() || !confirmation.isDisplayable()) return false;
            return true;
        } catch (Throwable failure) {
            guardFailure.set(summarize(failure));
            return false;
        }
    }

    private boolean activeModelPresentOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "active model observation must run on EDT");
        return previewModelPresent(context.cubism());
    }

    static boolean previewModelPresent(final dev.turboism.sdk.cubism.CubismFacade facade) {
        // model().active() throws when no model exists; absence is expected before this chooser.
        return facade.activeDocument().isPresent() || facade.activeModel().isPresent();
    }

    private static String previewOptionLabel(final HostAccess host, final Object option)
        throws Exception {
        final Object value = host.previewLabel().invoke(option);
        if (!(value instanceof String text)) throw new IllegalStateException(
            "F1 preview chooser option label is not text");
        return text;
    }

    private static AbstractButton exactPreviewConfirmation(final Dialog dialog,
        final HostAccess host) {
        final List<AbstractButton> confirmations = new ArrayList<>();
        for (final AbstractButton button : buttons(dialog)) {
            final Action action = button.getAction();
            if (isExactButton(button, host) && action != null
                && action.getClass() == host.action()
                && "OK".equals(action.getValue(Action.NAME))) confirmations.add(button);
        }
        if (confirmations.size() != 1) throw new IllegalStateException(
            "F1 preview chooser confirmation action is unknown or ambiguous");
        return confirmations.get(0);
    }

    private void recordPreviewObservation(final PreviewChooserObservation observation,
        final List<String> labels) {
        properties.setProperty("prepare.previewChooser.status", "OBSERVED");
        properties.setProperty("prepare.previewChooser.ownerIdentity",
            windowIdentity(observation.currentOwner()));
        properties.setProperty("prepare.previewChooser.dialogIdentity",
            windowIdentity((Window) observation.dialog()));
        properties.setProperty("prepare.previewChooser.listClass",
            observation.listClass().getName());
        properties.setProperty("prepare.previewChooser.optionClass",
            observation.options().get(0).getClass().getName());
        properties.setProperty("prepare.previewChooser.optionCount",
            Integer.toString(observation.options().size()));
        properties.setProperty("prepare.previewChooser.ratios", observation.ratios().toString());
        properties.setProperty("prepare.previewChooser.labels", labels.toString());
        properties.setProperty("prepare.previewChooser.selectedIndex",
            Integer.toString(observation.targetIndex()));
        properties.setProperty("prepare.previewChooser.selectedRatio", "1");
    }

    private void recordPreviewAbsent(final String reason) {
        properties.setProperty("prepare.previewChooser.status", "NOT_PRESENT");
        properties.setProperty("prepare.previewChooser.selection", "NOT_ATTEMPTED");
        properties.setProperty("prepare.previewChooser.return", "NOT_PRESENT");
        properties.setProperty("prepare.previewChooser.reason", reason);
    }

    private void recordChooserTimeout() {
        final StringBuilder stack = new StringBuilder();
        for (final var entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("AWT-EventQueue-")) continue;
            stack.append(entry.getKey().getName()).append(' ').append(entry.getKey().getState());
            for (int index = 0; index < Math.min(64, entry.getValue().length); index++) {
                stack.append('\n').append(entry.getValue()[index]);
            }
        }
        properties.setProperty("prepare.chooser.timeout.edtStack", stack.toString());
    }

    private ChoiceObservation inspectAndChooseOnEdt(final HostAccess host,
        final InputIdentity input,
        final PreparationProfile profile,
        final AtomicReference<Window> owner) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "chooser inspection must run on EDT");
        // The worker may have stopped after enqueueing this EDT operation.  Recheck both the
        // stop signal and the task binding at execution time, immediately before any click.
        checkStoppedAndTask(input);
        final Window currentOwner = currentWindowOnEdt(host, owner.get() == null);
        final WindowBindingDecision binding = bindWindow(owner.get(), currentOwner);
        if (!binding.ready()) {
            if (binding.waiting()) return null;
            throw new IllegalStateException(binding.diagnostic());
        }
        if (owner.get() == null) owner.set((Window) binding.owner());

        final List<Dialog> candidates = new ArrayList<>();
        int observedLists = 0;
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog) || !dialog.isShowing() || !dialog.isDisplayable()) {
                continue;
            }
            final List<JList<?>> lists = exactLists(dialog, host.hostList());
            if (lists.isEmpty()) continue;
            final String observation = "prepare.chooser.observedDialog." + observedLists++;
            properties.setProperty(observation + ".class", dialog.getClass().getName());
            properties.setProperty(observation + ".title", String.valueOf(dialog.getTitle()));
            properties.setProperty(observation + ".renderers", lists.stream()
                .map(list -> list.getCellRenderer() == null ? "null"
                    : list.getCellRenderer().getClass().getName()).toList().toString());
            // q is Cubism's general Swing list, also used by other startup dialogs. Only
            // the reviewed PSD renderer identifies this chooser; validate its remaining
            // owner/title/options/action shape below before any selection or confirmation.
            if (!hasReviewedRenderer(lists, host.renderer())) continue;
            if (dialog.getOwner() != currentOwner) {
                throw new IllegalStateException("PSD chooser has an unknown/non-bound owner");
            }
            candidates.add(dialog);
        }
        properties.setProperty("prepare.chooser.candidateCount", Integer.toString(candidates.size()));
        if (candidates.isEmpty()) return null;
        if (candidates.size() != 1) throw new IllegalStateException(
            "multiple official PSD chooser candidates are visible");
        final Dialog dialog = candidates.get(0);
        if (!containsText(dialog, host.title()) || !containsText(dialog, host.message())) {
            throw new IllegalStateException("official PSD chooser title/message is not exact");
        }
        final List<JList<?>> lists = exactLists(dialog, host.hostList());
        if (lists.size() != 1) throw new IllegalStateException("official chooser list is ambiguous");
        final JList<?> list = lists.get(0);
        if (list.getCellRenderer() == null
            || list.getCellRenderer().getClass() != host.renderer()) {
            throw new IllegalStateException("official chooser renderer is not exact");
        }
        if (list.getModel().getSize() != 2) throw new IllegalStateException(
            "official new-model chooser must expose exactly two options");
        final Object first = list.getModel().getElementAt(0);
        final Object second = list.getModel().getElementAt(1);
        if (first == null || second == null || first.getClass() != host.option()
            || second.getClass() != host.option()) {
            throw new IllegalStateException("official chooser option element class is not exact");
        }
        if (host.optionModel().invoke(first) != null || host.optionModel().invoke(second) != null) {
            throw new IllegalStateException("chooser options are not the two new-model entries");
        }
        final String firstText = optionLabel(host, first);
        final String secondText = optionLabel(host, second);
        if (!host.firstLabel().equals(firstText) || !host.secondLabel().equals(secondText)) {
            throw new IllegalStateException("official chooser option labels are not exact");
        }
        final List<AbstractButton> confirmations = new ArrayList<>();
        for (final AbstractButton button : buttons(dialog)) {
            final Action action = button.getAction();
            if (!isExactButton(button, host)
                || action == null || action.getClass() != host.action()
                || !"OK".equals(action.getValue(Action.NAME))) continue;
            confirmations.add(button);
        }
        if (confirmations.size() != 1) throw new IllegalStateException(
            "official chooser confirmation action is unknown or ambiguous");
        final AbstractButton confirmation = confirmations.get(0);
        final ChooserGateObservation observation = new ChooserGateObservation(
            currentOwner, dialog.getOwner(), candidates.size(), list.getClass(),
            list.getCellRenderer().getClass(), list.getModel().getSize(), first.getClass(),
            host.optionModel().invoke(first), firstText, second.getClass(),
            host.optionModel().invoke(second), secondText, confirmations.size(),
            confirmation.getClass(), confirmation.getAction().getClass(),
            String.valueOf(confirmation.getAction().getValue(Action.NAME)),
            confirmation.isEnabled(), confirmation.isShowing(), confirmation.isDisplayable());
        final ChooserGateResult gate = verifyAndExecuteChooser(binding, observation,
            new ChooserGateExpectation(host.hostList(), host.renderer(), host.option(),
                host.hostButton(), host.hostButtonSubclass(), host.action(),
                host.firstLabel(), host.secondLabel()), profile.chooserIndex(),
            stopped, () -> isTaskBound(input), new ChooserGateActions() {
                @Override public boolean selectFirst() {
                    return selectIndex(0);
                }

                @Override public boolean select(final int index) {
                    return selectIndex(index);
                }

                private boolean selectIndex(final int index) {
                    final Object expected = index == 0 ? first : index == 1 ? second : null;
                    if (expected == null) return false;
                    list.setSelectedIndex(index);
                    return list.getSelectedIndex() == index && list.getSelectedValue() == expected;
                }

                @Override public void clickConfirm() {
                    properties.setProperty("prepare.chooser.confirmState", "DISPATCHED");
                    confirmation.doClick();
                    properties.setProperty("prepare.chooser.confirmState", "RETURNED");
                }
            });
        if (!gate.accepted()) throw new IllegalStateException(gate.diagnostic());
        return new ChoiceObservation(currentOwner, dialog, list.getClass().getName(),
            confirmation.getClass().getName(), profile.chooserIndex(), profile.chooserKey(),
            firstText, secondText, true);
    }

    private static String optionLabel(final HostAccess host, final Object option) throws Exception {
        final Object value = host.optionLabel().invoke(option);
        if (!(value instanceof String text)) throw new IllegalStateException(
            "official option label is not text");
        return text;
    }

    private static boolean isExactButton(final AbstractButton button, final HostAccess host) {
        final Class<?> actual = button.getClass();
        return actual == host.hostButton()
            || (host.hostButtonSubclass().isAssignableFrom(actual)
                && AbstractButton.class.isAssignableFrom(actual));
    }

    private static List<JList<?>> exactLists(final Component root, final Class<?> exactClass) {
        final List<JList<?>> result = new ArrayList<>();
        collect(root, exactClass, result);
        return result;
    }

    static boolean hasReviewedRenderer(final List<? extends JList<?>> lists,
        final Class<?> expectedRenderer) {
        return lists.stream().anyMatch(list -> list.getCellRenderer() != null
            && list.getCellRenderer().getClass() == expectedRenderer);
    }

    private static void collect(final Component component, final Class<?> exactListClass,
        final List<JList<?>> result) {
        if (component instanceof JList<?> list && component.getClass() == exactListClass) {
            result.add(list);
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) collect(child, exactListClass, result);
        }
    }

    private static List<AbstractButton> buttons(final Component root) {
        final List<AbstractButton> result = new ArrayList<>();
        collectButtons(root, result);
        return result;
    }

    private static void collectButtons(final Component component, final List<AbstractButton> result) {
        if (component instanceof AbstractButton button) result.add(button);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) collectButtons(child, result);
        }
    }

    private static boolean containsText(final Component root, final String expected) {
        if (root instanceof JLabel label && expected.equals(label.getText())) return true;
        if (root instanceof JTextComponent text && expected.equals(text.getText())) return true;
        if (root instanceof JDialog dialog && expected.equals(dialog.getTitle())) return true;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                if (containsText(child, expected)) return true;
            }
        }
        return false;
    }

    /**
     * Binds the chooser to one exact owner.  A null observation is a startup wait only before
     * the first owner has been bound; after binding it is a terminal identity failure.
     */
    static WindowBindingDecision bindWindow(final Object boundOwner, final Object observedOwner) {
        if (observedOwner == null) {
            return boundOwner == null
                ? new WindowBindingDecision(false, true, null, "main frame is not ready")
                : new WindowBindingDecision(false, false, boundOwner,
                    "bound main-frame window disappeared");
        }
        if (boundOwner == null) {
            return new WindowBindingDecision(true, false, observedOwner,
                "main-frame window bound");
        }
        if (boundOwner != observedOwner) {
            return new WindowBindingDecision(false, false, boundOwner,
                "bound main-frame window identity changed");
        }
        return new WindowBindingDecision(true, false, boundOwner,
            "bound main-frame window retained");
    }

    static record WindowBindingDecision(boolean ready, boolean waiting, Object owner,
        String diagnostic) { }

    /** Exact, side-effect-free observation consumed by the chooser action gate. */
    static record ChooserGateObservation(Object currentOwner, Object dialogOwner,
        int candidateCount, Class<?> listClass, Class<?> rendererClass, int optionCount,
        Class<?> firstOptionClass, Object firstOptionModel, String firstLabel,
        Class<?> secondOptionClass, Object secondOptionModel, String secondLabel,
        int confirmationCount, Class<?> confirmationClass, Class<?> actionClass,
        String actionName, boolean enabled, boolean showing, boolean displayable) { }

    /** Expected official classes/text captured by the off-EDT shape preflight. */
    static record ChooserGateExpectation(Class<?> listClass, Class<?> rendererClass,
        Class<?> optionClass, Class<?> exactButtonClass, Class<?> buttonSubclass,
        Class<?> actionClass, String firstLabel, String secondLabel) { }

    interface ChooserGateActions {
        boolean selectFirst();
        default boolean select(final int index) {
            return index == 0 && selectFirst();
        }
        void clickConfirm();
    }

    static record ChooserGateResult(boolean accepted, String diagnostic) {
        static ChooserGateResult acceptedResult() {
            return new ChooserGateResult(true, "official chooser action accepted");
        }
        static ChooserGateResult rejected(final String diagnostic) {
            return new ChooserGateResult(false, diagnostic);
        }
    }

    /**
     * Production chooser gate and action boundary.  Every shape/identity/stop check completes
     * before either injected action; the production caller supplies the real Swing actions and
     * focused tests supply counters, never a fallback UI implementation.
     */
    static ChooserGateResult verifyAndExecuteChooser(final WindowBindingDecision binding,
        final ChooserGateObservation observation, final ChooserGateExpectation expected,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final ChooserGateActions actions) {
        return verifyAndExecuteChooser(binding, observation, expected, 0, stopped, taskBound,
            actions);
    }

    static ChooserGateResult verifyAndExecuteChooser(final WindowBindingDecision binding,
        final ChooserGateObservation observation, final ChooserGateExpectation expected,
        final int selectedIndex, final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final ChooserGateActions actions) {
        if (binding == null || !binding.ready() || binding.owner() == null
            || observation == null || binding.owner() != observation.currentOwner()) {
            return ChooserGateResult.rejected("official main-frame window binding is not exact");
        }
        return verifyAndExecuteChooser(observation, expected, selectedIndex, stopped, taskBound,
            actions);
    }

    static ChooserGateResult verifyAndExecuteChooser(final ChooserGateObservation observation,
        final ChooserGateExpectation expected, final BooleanSupplier stopped,
        final BooleanSupplier taskBound, final ChooserGateActions actions) {
        return verifyAndExecuteChooser(observation, expected, 0, stopped, taskBound, actions);
    }

    static ChooserGateResult verifyAndExecuteChooser(final ChooserGateObservation observation,
        final ChooserGateExpectation expected, final int selectedIndex,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final ChooserGateActions actions) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(actions, "actions");
        if (selectedIndex < 0 || selectedIndex >= observation.optionCount()) {
            return ChooserGateResult.rejected("official chooser selection index is unknown");
        }
        final String shapeFailure = chooserShapeFailure(observation, expected);
        if (!shapeFailure.isEmpty()) return ChooserGateResult.rejected(shapeFailure);
        if (!chooserGateOpen(stopped, taskBound)) {
            return ChooserGateResult.rejected("chooser action was stopped or task-unbound");
        }
        try {
            if (!chooserGateOpen(stopped, taskBound)) {
                return ChooserGateResult.rejected("chooser selection was stopped or task-unbound");
            }
            if (!actions.select(selectedIndex)) {
                return ChooserGateResult.rejected("official chooser did not select the reviewed option");
            }
            if (!chooserGateOpen(stopped, taskBound)) {
                return ChooserGateResult.rejected("chooser confirmation was stopped or task-unbound");
            }
            actions.clickConfirm();
            return ChooserGateResult.acceptedResult();
        } catch (RuntimeException failure) {
            return ChooserGateResult.rejected("official chooser action failed: " + failure);
        }
    }

    /** Production F1 preview chooser gate; the caller supplies fresh owner/dialog observations. */
    static PreviewChooserGateResult verifyAndExecutePreviewChooser(
        final PreviewChooserObservation observation, final PreviewChooserGateExpectation expected,
        final int selectedIndex, final Object expectedOption, final BooleanSupplier contextOpen,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final BooleanSupplier deadlineOpen, final PreviewChooserGateActions actions) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(contextOpen, "contextOpen");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(deadlineOpen, "deadlineOpen");
        Objects.requireNonNull(actions, "actions");
        final String shapeFailure = previewChooserShapeFailure(observation, expected,
            selectedIndex, expectedOption);
        if (!shapeFailure.isEmpty()) return PreviewChooserGateResult.rejected(shapeFailure);
        if (!previewGateOpen(contextOpen, stopped, taskBound, deadlineOpen)) {
            return PreviewChooserGateResult.rejected(
                "F1 preview chooser action was stopped, unbound, or expired");
        }
        try {
            if (!previewGateOpen(contextOpen, stopped, taskBound, deadlineOpen)) {
                return PreviewChooserGateResult.rejected(
                    "F1 preview chooser selection was stopped or expired");
            }
            if (!actions.select(selectedIndex)) return PreviewChooserGateResult.rejected(
                "F1 preview chooser ratio=1 was not selected");
            if (!previewGateOpen(contextOpen, stopped, taskBound, deadlineOpen)) {
                return PreviewChooserGateResult.rejected(
                    "F1 preview chooser confirmation was stopped or expired");
            }
            actions.clickConfirm();
            return PreviewChooserGateResult.acceptedResult();
        } catch (RuntimeException failure) {
            return PreviewChooserGateResult.rejected(
                "F1 preview chooser action failed: " + failure);
        }
    }

    private static boolean previewGateOpen(final BooleanSupplier contextOpen,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final BooleanSupplier deadlineOpen) {
        try {
            return contextOpen.getAsBoolean() && !stopped.getAsBoolean()
                && taskBound.getAsBoolean() && deadlineOpen.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String previewChooserShapeFailure(
        final PreviewChooserObservation actual, final PreviewChooserGateExpectation expected,
        final int selectedIndex, final Object expectedOption) {
        if (actual.currentOwner() == null || actual.dialogOwner() != actual.currentOwner()) {
            return "F1 preview chooser owner is unknown or changed";
        }
        if (actual.candidateCount() != 1 || actual.listClass() != expected.listClass()) {
            return "F1 preview chooser candidate/list shape is not exact";
        }
        if (actual.options() == null || actual.optionClasses() == null
            || actual.ratios() == null || actual.options().size() != 4
            || actual.optionClasses().size() != 4 || actual.ratios().size() != 4) {
            return "F1 preview chooser option count is not four";
        }
        if (actual.optionClasses().stream().anyMatch(value -> value != expected.optionClass())) {
            return "F1 preview chooser option class is unknown";
        }
        if (!expected.ratios().equals(PREVIEW_RATIOS)
            || !new LinkedHashSet<>(actual.ratios()).equals(PREVIEW_RATIOS)
            || actual.ratios().stream().filter(value -> value == 1).count() != 1) {
            return "F1 preview chooser ratios are not exactly 1/2/4/8";
        }
        if (selectedIndex < 0 || selectedIndex >= actual.options().size()
            || actual.options().get(selectedIndex) != expectedOption
            || actual.ratios().get(selectedIndex) != 1) {
            return "F1 preview chooser ratio=1 option identity is unknown";
        }
        final boolean buttonClass = actual.confirmationClass() != null
            && (actual.confirmationClass() == expected.exactButtonClass()
                || (expected.buttonSubclass() != null
                    && expected.buttonSubclass().isAssignableFrom(actual.confirmationClass())));
        if (actual.confirmationClass() == null || actual.actionClass() == null
            || actual.confirmationCount() != 1 || !buttonClass) {
            return "F1 preview chooser confirmation button is unknown or ambiguous";
        }
        if (actual.actionClass() != expected.actionClass()
            || !"OK".equals(actual.actionName())) {
            return "F1 preview chooser confirmation action is not exact OK";
        }
        if (!actual.enabled() || !actual.showing() || !actual.displayable()) {
            return "F1 preview chooser confirmation is not operable";
        }
        return "";
    }

    private static boolean chooserGateOpen(final BooleanSupplier stopped,
        final BooleanSupplier taskBound) {
        try {
            return !stopped.getAsBoolean() && taskBound.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String chooserShapeFailure(final ChooserGateObservation actual,
        final ChooserGateExpectation expected) {
        if (actual.currentOwner() == null || actual.dialogOwner() != actual.currentOwner()) {
            return "official chooser owner is unknown or changed";
        }
        if (actual.candidateCount() != 1) return "official chooser candidate count is not one";
        if (actual.listClass() != expected.listClass()) return "official chooser list class is not exact";
        if (actual.rendererClass() != expected.rendererClass()) {
            return "official chooser renderer class is not exact";
        }
        if (actual.optionCount() != 2) return "official new-model chooser option count is not two";
        if (actual.firstOptionClass() != expected.optionClass()
            || actual.secondOptionClass() != expected.optionClass()
            || actual.firstOptionModel() != null || actual.secondOptionModel() != null) {
            return "official chooser options are not the two new-model entries";
        }
        if (!Objects.equals(actual.firstLabel(), expected.firstLabel())
            || !Objects.equals(actual.secondLabel(), expected.secondLabel())) {
            return "official chooser option labels are not exact";
        }
        final boolean buttonClass = actual.confirmationClass() == expected.exactButtonClass()
            || (expected.buttonSubclass() != null
                && expected.buttonSubclass().isAssignableFrom(actual.confirmationClass()));
        if (actual.confirmationCount() != 1 || !buttonClass) {
            return "official chooser confirmation button is unknown or ambiguous";
        }
        if (actual.actionClass() != expected.actionClass()
            || !"OK".equals(actual.actionName())) {
            return "official chooser confirmation action is not exact OK";
        }
        if (!actual.enabled() || !actual.showing() || !actual.displayable()) {
            return "official chooser confirmation is not operable";
        }
        return "";
    }

    private void waitForDialogGone(final Dialog dialog, final long deadline) throws Exception {
        while (System.nanoTime() < deadline) {
            checkStopped();
            final EdtCall<Boolean> call = invokeEdtBounded(() ->
                dialog.isShowing() && dialog.isDisplayable(), EDT_CALL_TIMEOUT_MILLIS);
            if (!call.completed()) throw new IllegalStateException("chooser close inspection timed out");
            if (call.failure() != null) throw asException(call.failure());
            if (!Boolean.TRUE.equals(call.value())) return;
            sleepPoll(deadline);
        }
        throw new IllegalStateException("official PSD chooser did not close after OK");
    }

    private ModelState awaitInitialModel(final InputIdentity input,
        final PreparationProfile profile, final long timeoutMillis,
        final Window boundWindow) throws Exception {
        final AtomicReference<String> last = new AtomicReference<>("no model yet");
        final InitialSourceObservation source = awaitVerifiedEdtObservation(timeoutMillis,
            () -> checkStoppedAndTask(input), () -> {
                try {
                    return currentModelObservationOnEdt();
                } catch (RuntimeException unavailable) {
                    last.set(summarize(unavailable));
                    recordModelWaitDialogsOnEdt(boundWindow);
                    return null;
                }
            }, current -> {
                final InitialSourceObservation verified = verifyInitialSourceOnEdt(
                    input, current.state(), profile);
                recordModelBlendVersionMode("model.beforeSave", current.model());
                return verified;
            }, "initial PSD source readiness timed out: ", last);
        recordSourceIdentity(source.state(), source.source());
        return source.state();
    }

    private ModelState awaitModel(final InputIdentity input, final long timeoutMillis,
        final String modePrefix) throws Exception {
        return awaitModel(input, timeoutMillis, modePrefix, null);
    }

    private ModelState awaitModel(final InputIdentity input, final long timeoutMillis,
        final String modePrefix, final Window diagnosticWindow) throws Exception {
        final AtomicReference<String> last = new AtomicReference<>("no model yet");
        final CurrentModelObservation observation = awaitEdtObservation(timeoutMillis,
            () -> checkStoppedAndTask(input), () -> {
                checkStoppedAndTask(input);
                try {
                    final CurrentModelObservation current = currentModelObservationOnEdt();
                    checkStoppedAndTask(input);
                    return current;
                } catch (RuntimeException unavailable) {
                    last.set(unavailable.getMessage() == null ? unavailable.toString()
                        : unavailable.getMessage());
                    if (diagnosticWindow != null) recordModelWaitDialogsOnEdt(diagnosticWindow);
                    return null;
                }
            }, "new PSD model relation readiness timed out: ", last);
        if (modePrefix != null) recordModelBlendVersionMode(modePrefix, observation.model());
        return observation.state();
    }

    /** Read-only failure diagnostics. Unknown import dialogs are never acted upon. */
    private void recordModelWaitDialogsOnEdt(final Window boundWindow) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "model wait diagnostics require EDT");
        final List<String> dialogs = new ArrayList<>();
        for (final Window candidate : Window.getWindows()) {
            if (!(candidate instanceof Dialog dialog) || !dialog.isShowing()
                || !dialog.isDisplayable()) continue;
            Window owner = dialog.getOwner();
            while (owner != null && owner != boundWindow) owner = owner.getOwner();
            if (owner != boundWindow) continue;
            final List<String> texts = new ArrayList<>();
            collectDiagnosticTexts(dialog, texts);
            dialogs.add("dialog=" + windowIdentity(dialog) + " owner="
                + windowIdentity(dialog.getOwner()) + " directOwner="
                + (dialog.getOwner() == boundWindow) + " title=" + dialog.getTitle()
                + " texts=" + texts + " buttons=" + buttons(dialog).stream().limit(20)
                    .map(button -> button.getClass().getName() + ":" + button.getText()
                        + ":" + button.getActionCommand()).toList());
        }
        properties.setProperty("prepare.model.wait.ownedDialogs", dialogs.toString());
    }

    private static void collectDiagnosticTexts(final Component component,
        final List<String> texts) {
        if (texts.size() >= 40) return;
        final String value = component instanceof JLabel label ? label.getText()
            : component instanceof JTextComponent text ? text.getText() : null;
        if (value != null && !value.isBlank()) {
            texts.add(value.substring(0, Math.min(value.length(), 512)));
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectDiagnosticTexts(child, texts);
                if (texts.size() >= 40) break;
            }
        }
    }

    private void recordModelWaitStacks() {
        final StringBuilder stacks = new StringBuilder();
        for (final var entry : Thread.getAllStackTraces().entrySet()) {
            final boolean relevant = entry.getKey().getName().startsWith("AWT-EventQueue-")
                || java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                    frame.getClassName().startsWith("com.live2d.cubism.process.psd.")
                        || frame.getClassName().startsWith("com.live2d.cubism.appCtrlImpl.O"));
            if (!relevant) continue;
            stacks.append(entry.getKey().getName()).append(' ').append(entry.getKey().getState());
            for (int index = 0; index < Math.min(64, entry.getValue().length); index++) {
                stacks.append('\n').append(entry.getValue()[index]);
            }
            stacks.append('\n');
        }
        properties.setProperty("prepare.model.wait.threadStacks", stacks.toString());
    }

    /** Shared total-budget coordinator for model readiness and its focused EDT regression seam. */
    private static <T> T awaitEdtObservation(final long timeoutMillis,
        final Runnable checkActive, final EdtOperation<T> operation,
        final String timeoutPrefix, final AtomicReference<String> last) throws Exception {
        if (timeoutMillis < 1L) throw new IllegalArgumentException(
            "EDT observation timeout must be positive");
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (true) {
            checkActive.run();
            final long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) break;
            final long remainingMillis = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            final EdtCall<T> call = invokeEdtBounded(operation, remainingMillis);
            if (!call.completed() || System.nanoTime() >= deadline) break;
            if (call.failure() != null) throw asException(call.failure());
            checkActive.run();
            if (call.value() != null) return call.value();
            sleepPoll(deadline);
        }
        throw new IllegalStateException(timeoutPrefix + last.get());
    }

    /** Readiness and its identity proof share one EDT turn and the original total deadline. */
    static <T, R> R awaitVerifiedEdtObservation(final long timeoutMillis,
        final Runnable checkActive, final EdtOperation<T> readiness,
        final java.util.function.Function<T, R> verify, final String timeoutPrefix,
        final AtomicReference<String> last) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final AtomicBoolean active = new AtomicBoolean(true);
        final Runnable checkWithinBudget = () -> {
            checkActive.run();
            if (!active.get()) throw new IllegalStateException("EDT observation cancelled");
            remainingEdtMillis(deadline);
        };
        try {
            return awaitEdtObservation(timeoutMillis, checkWithinBudget, () -> {
                checkWithinBudget.run();
                final T ready = readiness.call();
                if (ready == null) return null;
                checkWithinBudget.run();
                final R verified = Objects.requireNonNull(verify.apply(ready), "identity proof");
                checkWithinBudget.run();
                return verified;
            }, timeoutPrefix, last);
        } finally {
            active.set(false);
        }
    }

    private static long remainingEdtMillis(final long deadline) {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) throw new IllegalStateException("EDT observation deadline exhausted");
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** Package-private focused seam using the same total-budget EDT coordinator as awaitModel. */
    static <T> T awaitEdtObservationForTest(final long timeoutMillis,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final Supplier<T> operation) throws Exception {
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(operation, "operation");
        final AtomicReference<String> last = new AtomicReference<>("no observation");
        final Runnable checkActive = () -> {
            if (stopped.getAsBoolean()) throw new IllegalStateException("stopped");
            if (!taskBound.getAsBoolean()) throw new IllegalStateException("task-unbound");
        };
        return awaitEdtObservation(timeoutMillis, checkActive, () -> {
            checkActive.run();
            return operation.get();
        }, "EDT observation timed out: ", last);
    }

    private ModelState currentModelOnEdt() {
        return currentModelObservationOnEdt().state();
    }

    private CurrentModelObservation currentModelObservationOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "model identity observation must run on EDT");
        final var document = context.cubism().activeDocument().orElse(null);
        if (document == null || !document.isModelDocument()) {
            throw new IllegalStateException("active model document is unavailable");
        }
        final CubismModel model = context.cubism().model().active();
        if (model == null || model.id() == null || model.id().value() == null
            || model.id().value().isBlank()) {
            throw new IllegalStateException("active model identity is unavailable");
        }
        final TextureRelationsSnapshot relations = model.textures().relations();
        final RelationIdentity identity = validateRelationSnapshot(
            document.documentId(), model.id().value(), relations);
        return new CurrentModelObservation(model, relations,
            new ModelState(document.documentId(), document.contentId(),
                document.relativePath(), model.id().value(), identity));
    }

    private InitialSourceObservation verifyInitialSourceOnEdt(final InputIdentity input,
        final ModelState before, final PreparationProfile profile) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "initial PSD source verification must run on EDT");
        checkStoppedAndTask(input);
        final CurrentModelObservation current = currentModelObservationOnEdt();
        final SourceSnapshotIdentity source = validateInitialSourceGate(before, current.state(),
            current.relations(), current.model().psdDocuments(), input.fixtureName(),
            profile.requiresLeafLayerBinding());
        checkStoppedAndTask(input);
        return new InitialSourceObservation(current.state(), source);
    }

    /**
     * F1's only authoring operation after the official import.  The selected ArtMesh is copied
     * and pasted through the public command service; relation validation below is what decides
     * whether the command actually established shared ModelImage usage.
     */
    private F1SharingPreparation prepareF1Sharing(final InputIdentity input,
        final ModelState before, final Window window, final HostAccess host,
        final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final EdtCall<CurrentModelObservation> initialCall = invokeEdtBounded(
            () -> {
                checkStoppedAndTask(input);
                return currentModelObservationOnEdt();
            }, remainingEdtMillis(deadline));
        if (!initialCall.completed()) throw blocked("F1 initial relation observation timed out");
        if (initialCall.failure() != null) throw asException(initialCall.failure());
        final CurrentModelObservation initial = initialCall.value();
        if (initial == null || !sameModelIdentity(before, initial.state())) {
            throw blocked("F1 model/document/relation identity changed before COPY");
        }
        final F1Target target = selectF1Target(initial.relations());
        properties.setProperty("prepare.f1.modelImageId", target.modelImageId());
        properties.setProperty("prepare.f1.sourceRawId", target.rawId());
        properties.setProperty("prepare.f1.originalArtMeshId", target.artMeshId());
        properties.setProperty("prepare.f1.copyPaste.status", "WAITING");

        F1CopyPasteResult action = null;
        while (System.nanoTime() < deadline) {
            checkStoppedAndTask(input);
            final EdtCall<List<F1TableRef>> tablesCall = invokeEdtBounded(
                () -> {
                    checkStoppedAndTask(input);
                    return reviewedF1TablesOnEdt(window);
                }, remainingEdtMillis(deadline));
            if (!tablesCall.completed()) throw blocked("F1 host table observation timed out");
            if (tablesCall.failure() != null) throw asException(tablesCall.failure());
            final List<F1TableRef> tables = tablesCall.value() == null
                ? List.of() : tablesCall.value();
            if (tables.isEmpty()) {
                sleepPoll(deadline);
                continue;
            }
            final List<F1PreparedTable> preparedTables = prepareF1Tables(tables);
            final long remainingMillis = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            final EdtCall<F1CopyPasteResult> copyPasteCall = invokeEdtBounded(
                () -> copyPasteF1OnEdt(input, before, window, host, preparedTables,
                    target.artMeshId(), deadline), remainingMillis);
            if (!copyPasteCall.completed()) throw blocked("F1 COPY/PASTE EDT operation timed out");
            if (copyPasteCall.failure() != null) throw asException(copyPasteCall.failure());
            action = copyPasteCall.value();
            if (action == null) throw blocked("F1 COPY/PASTE returned no admission result");
            if (action.retryable()) {
                sleepPoll(deadline);
                continue;
            }
            if (!action.accepted()) throw blocked(action.diagnostic());
            break;
        }
        if (action == null || !action.accepted()) {
            throw blocked("F1 exact ArtMesh row was not available before timeout");
        }
        return awaitF1Sharing(input, before, window, host, target, action, deadline);
    }

    private static F1Target selectF1Target(final TextureRelationsSnapshot relations)
        throws PreparationBlockedException {
        if (relations == null || !relations.isAvailable() || relations.modelImages() == null) {
            throw blocked("F1 initial texture relations are unavailable");
        }
        final Map<String, ArtMeshTextureInputs> inputs = artMeshInputsById(relations);
        for (final ModelImageRelation image : relations.modelImages()) {
            if (image == null || image.id() == null || image.currentRawImageId().isEmpty()
                || image.usingArtMeshIds() == null || image.usingArtMeshIds().isEmpty()) continue;
            final String modelImageId = image.id().value();
            final String rawId = image.currentRawImageId().orElseThrow().value();
            for (final ArtMeshId artMesh : image.usingArtMeshIds()) {
                if (artMesh == null || artMesh.value() == null || artMesh.value().isBlank()) continue;
                final ArtMeshTextureInputs input = inputs.get(artMesh.value());
                try {
                    requireModelImageInput(input, modelImageId, artMesh.value(), "initial");
                    return new F1Target(modelImageId, rawId, artMesh.value(), relations);
                } catch (IllegalArgumentException ignored) {
                    // This candidate is not a resolved ArtMesh input; continue only across
                    // relation entries already exposed by the verified SDK projection.
                }
            }
        }
        throw blocked("F1 has no resolved ArtMesh using a current ModelImage");
    }

    private List<F1PreparedTable> prepareF1Tables(final List<F1TableRef> tables)
        throws PreparationBlockedException {
        final List<F1PreparedTable> prepared = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        for (final F1TableRef table : tables) {
            final ExactHostRowTarget.HostAccessPreparation access =
                ExactHostRowTarget.prepareHostAccess(table.modelClass());
            if (!access.available()) {
                failures.add(table.modelClass().getName() + ": " + access.reason());
                continue;
            }
            prepared.add(new F1PreparedTable(table.table(), access.context(),
                table.modelIdentity()));
        }
        if (prepared.isEmpty()) {
            throw blocked("F1 exact host row code-source/accessor proof unavailable: "
                + failures);
        }
        properties.setProperty("prepare.f1.hostTables", Integer.toString(prepared.size()));
        return List.copyOf(prepared);
    }

    private F1SharingPreparation awaitF1Sharing(final InputIdentity input,
        final ModelState before, final Window window, final HostAccess host,
        final F1Target target, final F1CopyPasteResult action, final long deadline)
        throws Exception {
        String lastFailure = "no post-PASTE relation observed";
        while (System.nanoTime() < deadline) {
            checkStoppedAndTask(input);
            final long remainingMillis = Math.max(1L,
                TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            final EdtCall<CurrentModelObservation> call = invokeEdtBounded(() -> {
                checkStoppedAndTask(input);
                final Window currentWindow = currentWindowOnEdt(host, false);
                if (currentWindow != window) throw new IllegalStateException(
                    "F1 bound main-frame window changed after PASTE");
                final CurrentModelObservation current = currentModelObservationOnEdt();
                if (!sameDocumentModelIdentity(before, current.state())) {
                    throw new IllegalStateException(
                        "F1 document/model identity changed after PASTE");
                }
                return current;
            }, remainingMillis);
            if (!call.completed()) throw blocked("F1 post-PASTE relation observation timed out");
            if (call.failure() != null) {
                lastFailure = summarize(call.failure());
                sleepPoll(deadline);
                continue;
            }
            final CurrentModelObservation current = call.value();
            try {
                final F1SharedIdentity sharing = validateF1SharedRelation(
                    target.beforeRelations(), current.relations(), target.modelImageId(),
                    target.rawId(), target.artMeshId());
                return new F1SharingPreparation(current.state(), sharing, action);
            } catch (IllegalArgumentException failure) {
                lastFailure = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                sleepPoll(deadline);
            }
        }
        throw blocked("F1 COPY/PASTE relation proof timed out: " + lastFailure);
    }

    private F1CopyPasteResult copyPasteF1OnEdt(final InputIdentity input,
        final ModelState before, final Window window, final HostAccess host,
        final List<F1PreparedTable> tables, final String artMeshId, final long deadline)
        throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "F1 COPY/PASTE must run on EDT");
        checkStoppedAndTask(input);
        if (currentWindowOnEdt(host, false) != window) throw blocked(
            "F1 bound main-frame window changed before row selection");
        final CurrentModelObservation current = currentModelObservationOnEdt();
        if (!sameModelIdentity(before, current.state())) throw blocked(
            "F1 document/model/relation identity changed before row selection");
        final List<F1ResolvedRow> matches = new ArrayList<>();
        for (final F1PreparedTable table : tables) {
            if (table.table().getModel() != table.modelIdentity()) continue;
            for (int row = 0; row < table.table().getRowCount(); row++) {
                final ExactHostRowTarget.Resolution resolution = ExactHostRowTarget.resolve(
                    table.table(), row, table.context());
                if (resolution.available()
                    && artMeshId.equals(resolution.target().domainId())) {
                    matches.add(new F1ResolvedRow(table.table(), table.modelIdentity(),
                        table.context(), resolution.target()));
                }
            }
        }
        if (matches.isEmpty()) return F1CopyPasteResult.retry(
            "F1 exact ArtMesh row is not visible in the bound window");
        final F1ResolvedRow row = selectF1ResolvedRow(matches);
        if (!row.target().visible() || row.target().locked()) throw blocked(
            "F1 exact ArtMesh row is hidden or locked");
        final Set<Window> dialogsBefore = visibleDialogsOnEdt();
        if (!dialogsBefore.isEmpty()) {
            // The official home/progress window may still be retiring when model
            // relations first become available. Keep all COPY/PASTE actions blocked
            // until it disappears, under the existing preparation deadline.
            recordModelWaitDialogsOnEdt(window);
            properties.setProperty("prepare.f1.copyPaste.waitingDialogs",
                Integer.toString(dialogsBefore.size()));
            closeExactHomeOnEdt(input, before, window, host, dialogsBefore, deadline);
            return F1CopyPasteResult.retry(
                "F1 awaits absence of visible dialogs before COPY: " + dialogsBefore.size());
        }
        final ModelState expected = current.state();
        return executeF1CopyPasteOnEdt(expected, artMeshId, window,
            () -> currentWindowForF1(host), this::currentModelOnEdt,
            stopped, () -> isTaskBound(input),
            new F1CopyPasteActions() {
                @Override public void leftClick() {
                    dispatchF1LeftClick(row.table(), row.target().nameClickPoint());
                }

                @Override public F1SelectionObservation selection() {
                    return observeF1NativeSelectionOnEdt(host);
                }

                @Override public EditorCommandResult copy() {
                    return context.editorCommands().execute(EditorCommand.COPY);
                }

                @Override public EditorCommandResult paste() {
                    return context.editorCommands().execute(EditorCommand.PASTE);
                }

                @Override public boolean unknownVisibleDialog() {
                    final Set<Window> after = visibleDialogsOnEdt();
                    return after.stream().anyMatch(dialog -> !dialogsBefore.contains(dialog));
                }
            });
    }

    private F1SelectionObservation observeF1NativeSelectionOnEdt(final HostAccess host) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "F1 native selection observation requires EDT");
        try {
            final NativeSelectionAccess access = host.selectionAccess();
            final Object app = host.appInstance().invoke(null);
            final Object document = access.document().invoke(app);
            if (document == null || document.getClass() != access.documentClass()) {
                throw new IllegalStateException("F1 native modeling document is unavailable");
            }
            final Object selector = access.selector().invoke(document);
            final Object value = access.selected().invoke(selector);
            if (!(value instanceof List<?> selected)
                || (Integer) access.count().invoke(selector) != selected.size()) {
                throw new IllegalStateException("F1 native selection list/count differ");
            }
            final List<String> guids = new ArrayList<>();
            for (final Object entry : selected) {
                if (entry == null || entry.getClass() != access.meshSelectionClass()) {
                    throw new IllegalStateException("F1 selection contains a non-ArtMesh object");
                }
                guids.add((String) access.uuid().invoke(access.guid().invoke(entry)));
            }
            final Map<String, String> idsByGuid = new java.util.LinkedHashMap<>();
            for (final var mesh : context.cubism().model().active().drawables().all()) {
                if (idsByGuid.put(mesh.guid(), mesh.id().value()) != null) {
                    throw new IllegalStateException("F1 SDK ArtMesh GUID is duplicated");
                }
            }
            final List<String> ids = bindSelectionGuids(guids, idsByGuid);
            properties.setProperty("prepare.f1.selection.source", "official ISelector.getSelected");
            properties.setProperty("prepare.f1.selection.nativeGuids", guids.toString());
            properties.setProperty("prepare.f1.selection.sdkIds", ids.toString());
            return new F1SelectionObservation(ids, Optional.empty());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("F1 native selection observation failed", failure);
        }
    }

    static List<String> bindSelectionGuids(final List<String> selected,
        final Map<String, String> sdkIdsByGuid) {
        final Set<String> unique = new LinkedHashSet<>(selected);
        if (unique.size() != selected.size()) throw new IllegalStateException(
            "F1 native selection has duplicated GUIDs");
        final List<String> ids = new ArrayList<>();
        for (final String guid : selected) {
            final String id = sdkIdsByGuid.get(guid);
            if (id == null || id.isBlank()) throw new IllegalStateException(
                "F1 native selection GUID has no SDK ArtMesh counterpart");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** Close only the official home singleton's own dialog, as its title-bar close does. */
    private void closeExactHomeOnEdt(final InputIdentity input, final ModelState before,
        final Window window, final HostAccess host, final Set<Window> visible,
        final long deadline) throws Exception {
        if (homeCloseAttempted || visible.size() != 1) return;
        final HomeAccess access = host.homeAccess();
        final Object home = access.instance().invoke(null);
        if (home == null || access.controller().invoke(home) != host.appInstance().invoke(null)) return;
        final Object nativeWindow = access.window().invoke(null, home);
        if (nativeWindow == null) return;
        final javax.swing.JDialog dialog = (javax.swing.JDialog) access.dialog().invoke(nativeWindow);
        if (dialog == null || !visible.contains(dialog) || dialog.getOwner() != window
            || !dialog.isShowing() || !dialog.isDisplayable()) return;
        final int operation = dialog.getDefaultCloseOperation();
        properties.setProperty("prepare.f1.home.closeOperation", Integer.toString(operation));
        if (operation != javax.swing.WindowConstants.HIDE_ON_CLOSE
            && operation != javax.swing.WindowConstants.DISPOSE_ON_CLOSE) return;
        checkStoppedAndTask(input);
        if (System.nanoTime() >= deadline || currentWindowOnEdt(host, false) != window
            || !sameModelIdentity(before, currentModelOnEdt())
            || access.instance().invoke(null) != home
            || access.window().invoke(null, home) != nativeWindow
            || access.dialog().invoke(nativeWindow) != dialog) return;
        homeCloseAttempted = true;
        properties.setProperty("prepare.f1.home.window", windowIdentity(dialog));
        properties.setProperty("prepare.f1.home.close", "DISPATCHED");
        dialog.dispatchEvent(new java.awt.event.WindowEvent(dialog,
            java.awt.event.WindowEvent.WINDOW_CLOSING));
        properties.setProperty("prepare.f1.home.close", "RETURNED");
    }

    private static F1ResolvedRow selectF1ResolvedRow(final List<F1ResolvedRow> matches)
        throws PreparationBlockedException {
        final Map<ExactHostRowTarget.RowFamily, List<F1ResolvedRow>> byFamily =
            new java.util.EnumMap<>(ExactHostRowTarget.RowFamily.class);
        for (final F1ResolvedRow match : matches) {
            byFamily.computeIfAbsent(match.target().rowFamily(), ignored -> new ArrayList<>())
                .add(match);
        }
        for (final Map.Entry<ExactHostRowTarget.RowFamily, List<F1ResolvedRow>> entry
            : byFamily.entrySet()) {
            if (entry.getValue().size() > 1) throw blocked(
                "F1 exact ArtMesh row is ambiguous within " + entry.getKey() + ": "
                    + entry.getValue().size());
        }
        final F1ResolvedRow deformer = byFamily.getOrDefault(
            ExactHostRowTarget.RowFamily.DEFORMER, List.of()).stream().findFirst().orElse(null);
        if (deformer != null) return deformer;
        final F1ResolvedRow parts = byFamily.getOrDefault(
            ExactHostRowTarget.RowFamily.PARTS, List.of()).stream().findFirst().orElse(null);
        if (parts != null) return parts;
        throw blocked("F1 exact ArtMesh row family is not reviewed");
    }

    private static List<F1TableRef> reviewedF1TablesOnEdt(final Window window) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "F1 table discovery must run on EDT");
        if (window == null || !window.isShowing() || !window.isDisplayable()) {
            return List.of();
        }
        final Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .getActiveWindow();
        if (active != null && active != window) throw new IllegalStateException(
            "F1 bound window is not the active window");
        final List<F1TableRef> tables = new ArrayList<>();
        collectF1Tables(window, window, tables);
        return List.copyOf(tables);
    }

    private static void collectF1Tables(final Component component, final Window window,
        final List<F1TableRef> tables) {
        if (component instanceof JTable table && table.isShowing() && table.isDisplayable()
            && SwingUtilities.getWindowAncestor(table) == window
            && ExactHostRowTarget.isReviewedTableModelClass(table.getModel().getClass())) {
            tables.add(new F1TableRef(table, table.getModel().getClass(), table.getModel()));
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                collectF1Tables(child, window, tables);
            }
        }
    }

    private static Set<Window> visibleDialogsOnEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "dialog observation must run on EDT");
        final Set<Window> result = java.util.Collections.newSetFromMap(
            new java.util.IdentityHashMap<>());
        for (final Window window : Window.getWindows()) {
            if (window instanceof Dialog && window.isShowing() && window.isDisplayable()) {
                result.add(window);
            }
        }
        return result;
    }

    private static void dispatchF1LeftClick(final Component target, final Point point) {
        final long now = System.currentTimeMillis();
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, now,
            InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_RELEASED, now,
            InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1));
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_CLICKED, now,
            InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1));
    }

    private SavedCopyIdentity saveAsAndConfirm(final InputIdentity input, final ModelState before,
        final Window window, final HostAccess host, final EventRecorder events) throws Exception {
        checkStopped();
        final String expectedContentId = requiredSaveContentId(before);
        final UserFileRequestResult granted = context.userFiles().request(new UserFileRequest(
            "external-psd-fixture-preparation", "Save official PSD control document",
            List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION))
            .toCompletableFuture().get(60, TimeUnit.SECONDS);
        properties.setProperty("prepare.save.grant.status", granted.status().name());
        final UserFileHandle handle = granted.handle().orElseThrow(() ->
            new IllegalStateException("fixed WRITE grant was not granted: " + granted.status()));
        try {
            // The final stop/task, model/relation, and bound-window checks and the command itself
            // are one EDT operation. The WRITE grant was acquired on the worker above; this
            // dispatch never waits for a grant or lifecycle event.
            final EdtCall<SaveExecution> executionCall = invokeEdtBounded(
                () -> executeSaveAsOnEdt(input, before, window, host, handle, events),
                SAVE_AS_EDT_CALL_TIMEOUT_MILLIS);
            if (!executionCall.completed()) throw new IllegalStateException(
                "SAVE_AS EDT dispatch timed out");
            if (executionCall.failure() != null) throw asException(executionCall.failure());
            final SaveExecution execution = executionCall.value();
            if (execution == null || execution.result() == null) throw new IllegalStateException(
                "SAVE_AS EDT dispatch returned no result");
            final EditorCommandResult saved = execution.result();
            properties.setProperty("prepare.saveAs.status", saved.status().name());
            properties.setProperty("prepare.saveAs.executed", Boolean.toString(saved.executed()));
            if (!saved.executed()) throw new IllegalStateException(
                "SAVE_AS did not execute: " + saved.status());
            final ProjectFileLifecycleEvent.After after = events.awaitSave(
                expectedContentId, execution.afterSequence(), 60_000L, stopped);
            final ModelState current = awaitModel(input, 30_000L, "model.afterSave");
            recordModelState("model.afterSave", current);
            if (!savedModelMatches(before, current, lastPathPart(input.savedCopyPath()))) {
                throw new IllegalStateException(
                    "SAVE_AS changed document/model/relation identity or target filename");
            }
            final Window currentWindow = currentWindow(host);
            final SaveAfterIdentity event = SaveAfterIdentity.from(after);
            if (!saveAfterMatches(event, before, current,
                lastPathPart(input.savedCopyPath()), windowIdentity(window),
                windowIdentity(currentWindow))) {
                throw new IllegalStateException(
                    "SAVE After identity does not match the prepared model");
            }
            properties.setProperty("prepare.saveAfter.observed", "true");
            properties.setProperty("prepare.saveAfter.succeeded", "true");
            properties.setProperty("prepare.saveAfter.operation", event.operation());
            properties.setProperty("prepare.saveAfter.fileName", eventRequestFileName(event.fileName()));
            properties.setProperty("prepare.saveAfter.requestFileName",
                eventRequestFileName(event.fileName()));
            properties.setProperty("prepare.saveAfter.targetFileName",
                lastPathPart(current.relativePath()));
            properties.setProperty("prepare.saveAfter.expectedContentId", expectedContentId);
            properties.setProperty("prepare.saveAfter.requestContentId",
                eventContentId(event.requestContentId()));
            properties.setProperty("prepare.saveAfter.contentId", event.contentId());
            return new SavedCopyIdentity(input.savedCopyPath(), event.fileName(), event.contentId(),
                current.identity().documentId(), current.identity().modelId(),
                current.identity().generation(), current.identity().binding(),
                windowIdentity(currentWindow), true, true);
        } finally {
            handle.close();
        }
    }

    private SaveExecution executeSaveAsOnEdt(final InputIdentity input, final ModelState before,
        final Window window, final HostAccess host, final UserFileHandle handle,
        final EventRecorder events) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "SAVE_AS execution must run on EDT");
        checkStoppedAndTask(input);
        final Window currentWindow = currentWindowOnEdt(host, false);
        if (currentWindow != window) throw new IllegalStateException(
            "official main-frame window changed before SAVE_AS");
        final ModelState currentModel = currentModelOnEdt();
        return executeBoundSaveOnEdt(before, currentModel, window, currentWindow,
            () -> checkStoppedAndTask(input), () -> {
                final int afterSequence = events.captureBeforeExecute();
                checkStoppedAndTask(input);
                final EditorCommandResult result = context.editorCommands().execute(
                    new EditorFileCommandRequest(EditorFileCommand.SAVE_AS, handle,
                        EditorOverwritePolicy.REPLACE_EXISTING));
                return new SaveExecution(result, afterSequence, currentModel);
            });
    }

    /** Final identity checks and the command share one EDT turn, including in regression tests. */
    static <T> T executeBoundSaveOnEdt(final ModelState expected, final ModelState current,
        final Object expectedWindow, final Object currentWindow, final Runnable checkActive,
        final Supplier<T> command) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "SAVE_AS execution must run on EDT");
        checkActive.run();
        if (expectedWindow == null || expectedWindow != currentWindow) {
            throw new IllegalStateException("official main-frame window changed before SAVE_AS");
        }
        if (!sameModelIdentity(expected, current)) throw new IllegalStateException(
            "active model/document/relation identity changed before SAVE_AS");
        checkActive.run();
        return command.get();
    }

    /**
     * Production COPY/PASTE admission boundary.  The live caller supplies only exact row UI
     * actions and SDK observations; focused tests use counters through the same boundary.  No
     * command is issued until the bound window, task, model identity, and selection are proven.
     */
    static F1CopyPasteResult executeF1CopyPasteOnEdt(final ModelState expected,
        final String artMeshId, final Object expectedWindow, final Supplier<Object> currentWindow,
        final Supplier<ModelState> currentState, final BooleanSupplier stopped,
        final BooleanSupplier taskBound, final F1CopyPasteActions actions) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(artMeshId, "artMeshId");
        Objects.requireNonNull(currentWindow, "currentWindow");
        Objects.requireNonNull(currentState, "currentState");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(actions, "actions");
        if (!SwingUtilities.isEventDispatchThread()) return F1CopyPasteResult.rejected(
            "F1 COPY/PASTE admission must run on EDT", null, false, false);
        F1SelectionObservation selection = null;
        boolean copyExecuted = false;
        boolean pasteExecuted = false;
        try {
            String failure = f1PreCommandFailure(expected, expectedWindow, currentWindow,
                currentState, stopped, taskBound);
            if (failure != null) return F1CopyPasteResult.rejected(
                failure, selection, copyExecuted, pasteExecuted);
            actions.leftClick();
            failure = f1PreCommandFailure(expected, expectedWindow, currentWindow,
                currentState, stopped, taskBound);
            if (failure != null) return F1CopyPasteResult.rejected(
                "F1 selection admission failed: " + failure, selection, copyExecuted,
                pasteExecuted);
            selection = actions.selection();
            if (!f1SelectionMatches(selection, artMeshId)) return F1CopyPasteResult.rejected(
                "F1 verified selection is empty or not exactly the selected ArtMesh", selection,
                copyExecuted, pasteExecuted);
            failure = f1PreCommandFailure(expected, expectedWindow, currentWindow,
                currentState, stopped, taskBound);
            if (failure != null) return F1CopyPasteResult.rejected(
                "F1 COPY admission failed: " + failure, selection, copyExecuted,
                pasteExecuted);
            final EditorCommandResult copied = actions.copy();
            copyExecuted = copied != null && copied.executed();
            if (!copyExecuted) return F1CopyPasteResult.rejected(
                "F1 COPY did not execute", selection, copyExecuted, pasteExecuted);
            failure = f1PreCommandFailure(expected, expectedWindow, currentWindow,
                currentState, stopped, taskBound);
            if (failure != null) return F1CopyPasteResult.rejected(
                "F1 PASTE admission failed: " + failure, selection, copyExecuted,
                pasteExecuted);
            final EditorCommandResult pasted = actions.paste();
            pasteExecuted = pasted != null && pasted.executed();
            if (!pasteExecuted) return F1CopyPasteResult.rejected(
                "F1 PASTE did not execute", selection, copyExecuted, pasteExecuted);
            failure = f1PostPasteFailure(expected, expectedWindow, currentWindow, currentState,
                stopped, taskBound);
            if (failure != null) return F1CopyPasteResult.rejected(
                "F1 post-PASTE identity failed: " + failure, selection, copyExecuted,
                pasteExecuted);
            if (actions.unknownVisibleDialog()) return F1CopyPasteResult.rejected(
                "F1 PASTE opened an unknown visible dialog", selection, copyExecuted,
                pasteExecuted);
            return F1CopyPasteResult.accepted(selection, copyExecuted, pasteExecuted);
        } catch (RuntimeException failure) {
            return F1CopyPasteResult.rejected("F1 COPY/PASTE action failed: "
                + summarize(failure), selection, copyExecuted, pasteExecuted);
        }
    }

    private static String f1PreCommandFailure(final ModelState expected,
        final Object expectedWindow, final Supplier<Object> currentWindow,
        final Supplier<ModelState> currentState, final BooleanSupplier stopped,
        final BooleanSupplier taskBound) {
        if (stopped.getAsBoolean()) return "preparation was stopped";
        if (!taskBound.getAsBoolean()) return "preparation task binding changed";
        final Object observedWindow = currentWindow.get();
        if (expectedWindow == null || observedWindow != expectedWindow) {
            return "bound window identity changed";
        }
        final ModelState state = currentState.get();
        if (!sameModelIdentity(expected, state)) return "document/model/relation identity changed";
        return null;
    }

    private static String f1PostPasteFailure(final ModelState expected,
        final Object expectedWindow, final Supplier<Object> currentWindow,
        final Supplier<ModelState> currentState, final BooleanSupplier stopped,
        final BooleanSupplier taskBound) {
        if (stopped.getAsBoolean()) return "preparation was stopped";
        if (!taskBound.getAsBoolean()) return "preparation task binding changed";
        if (expectedWindow == null || currentWindow.get() != expectedWindow) {
            return "bound window identity changed";
        }
        final ModelState state = currentState.get();
        if (!sameDocumentModelIdentity(expected, state)) {
            return "document/model identity changed";
        }
        return null;
    }

    private static boolean f1SelectionMatches(final F1SelectionObservation selection,
        final String artMeshId) {
        if (selection == null || selection.selectedObjectIds() == null
            || selection.selectedObjectIds().size() != 1
            || !artMeshId.equals(selection.selectedObjectIds().get(0))) return false;
        return selection.activeArtMeshId() == null
            || selection.activeArtMeshId().isEmpty()
            || artMeshId.equals(selection.activeArtMeshId().orElse(null));
    }

    private Window currentWindow(final HostAccess host) throws Exception {
        final EdtCall<Window> call = invokeEdtBounded(
            () -> currentWindowOnEdt(host, false), EDT_CALL_TIMEOUT_MILLIS);
        if (!call.completed()) throw new IllegalStateException(
            "official main-frame window inspection timed out");
        if (call.failure() != null) throw asException(call.failure());
        final Window window = call.value();
        if (window == null || !window.isDisplayable()) throw new IllegalStateException(
            "official main-frame Swing window is no longer displayable");
        return window;
    }

    private Window currentWindowOnEdt(final HostAccess host, final boolean allowNotReady)
        throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "official main-frame window inspection must run on EDT");
        checkStopped();
        final Object controller = host.appInstance().invoke(null);
        if (controller == null) return notReadyOrFail(allowNotReady,
            "official application controller is not ready");
        final Object frameController = host.mainFrame().invoke(controller);
        if (frameController == null) return notReadyOrFail(allowNotReady,
            "official main-frame controller is not ready");
        final Object cFrame = host.cFrameGetter().invoke(frameController);
        if (cFrame == null) return notReadyOrFail(allowNotReady,
            "official CFrame is not ready");
        final Window window = (Window) host.swingWindow().invoke(cFrame);
        if (window == null || !window.isShowing() || !window.isDisplayable()) {
            return notReadyOrFail(allowNotReady,
                "official main-frame Swing window is not showing/displayable");
        }
        final JFrame frame = (JFrame) host.swingFrame().invoke(cFrame);
        if (frame == null) {
            return notReadyOrFail(allowNotReady,
                "official main-frame JFrame is not ready");
        }
        if (window != frame) {
            throw new IllegalStateException("official main-frame Swing window is unavailable");
        }
        checkStopped();
        return window;
    }

    private Window currentWindowForF1(final HostAccess host) {
        try {
            return currentWindowOnEdt(host, false);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Window notReadyOrFail(final boolean allowNotReady, final String diagnostic) {
        if (allowNotReady) return null;
        throw new IllegalStateException(diagnostic);
    }

    private void recordChoice(final ChoiceObservation choice) {
        properties.setProperty("prepare.chooser.ownerIdentity", windowIdentity(choice.owner()));
        properties.setProperty("prepare.chooser.dialogIdentity", windowIdentity(choice.dialog()));
        properties.setProperty("prepare.chooser.listClass", choice.listClass());
        properties.setProperty("prepare.chooser.confirmClass", choice.confirmClass());
        properties.setProperty("prepare.chooser.optionCount", "2");
        properties.setProperty("prepare.chooser.optionLabel.0", choice.firstLabel());
        properties.setProperty("prepare.chooser.optionLabel.1", choice.secondLabel());
        properties.setProperty("prepare.chooser.selectedIndex",
            Integer.toString(choice.selectedIndex()));
        properties.setProperty("prepare.chooser.selectedKey", choice.selectedKey());
        properties.setProperty("prepare.chooser.confirmAction", "OK");
    }

    private void recordModelBlendVersionMode(final String prefix, final CubismModel model) {
        final String base = prefix + ".blendVersionMode";
        if (model == null) {
            properties.setProperty(base, MODE_UNAVAILABLE);
            properties.setProperty(base + ".reason", "active model object is unavailable");
            return;
        }
        // CubismModel and its public texture/PSD projections do not expose a reviewed
        // blend/version-mode getter.  The exact JAR has internal version methods, but no
        // verified relation from those methods to this model's imported PSD was established;
        // record the honest observation rather than inferring mode from the chooser index.
        properties.setProperty(base, MODE_UNAVAILABLE);
        properties.setProperty(base + ".reason",
            "public SDK exposes no reliable blend/version-mode getter for this model");
    }

    private void recordWindow(final Window window) {
        properties.setProperty("prepare.window.identity", windowIdentity(window));
        properties.setProperty("prepare.window.class", window.getClass().getName());
    }

    private void recordModelState(final String prefix, final ModelState state) {
        properties.setProperty(prefix + ".documentId", state.identity().documentId());
        properties.setProperty(prefix + ".modelId", state.identity().modelId());
        properties.setProperty(prefix + ".binding", state.identity().binding());
        properties.setProperty(prefix + ".generation", Long.toString(state.identity().generation()));
        properties.setProperty(prefix + ".relativePath", state.relativePath());
        properties.setProperty(prefix + ".modelImageIds", String.join(",", state.identity().modelImageIds()));
        properties.setProperty(prefix + ".currentRawIds", String.join(",", state.identity().currentRawIds()));
        properties.setProperty(prefix + ".linkedRawIds", String.join(",", state.identity().linkedRawIds()));
        properties.setProperty(prefix + ".rawLayerBindings", state.identity().rawLayerBindings());
    }

    private void recordF1Sharing(final F1SharingPreparation preparation) {
        final F1SharedIdentity sharing = preparation.sharing();
        properties.setProperty("prepare.f1.copyPaste.status", "PASS");
        properties.setProperty("prepare.f1.copyPaste.selection", preparation.action().selection() == null
            ? UNAVAILABLE : preparation.action().selection().selectedObjectIds().toString());
        properties.setProperty("prepare.f1.copyPaste.copyExecuted",
            Boolean.toString(preparation.action().copyExecuted()));
        properties.setProperty("prepare.f1.copyPaste.pasteExecuted",
            Boolean.toString(preparation.action().pasteExecuted()));
        properties.setProperty("prepare.f1.modelImageId", sharing.modelImageId());
        properties.setProperty("prepare.f1.sourceRawId", sharing.rawId());
        properties.setProperty("prepare.f1.originalArtMeshId", sharing.originalArtMeshId());
        properties.setProperty("prepare.f1.copiedArtMeshId", sharing.copiedArtMeshId());
        properties.setProperty("prepare.f1.usingArtMeshIds",
            String.join(",", sharing.usingArtMeshIds()));
        properties.setProperty("prepare.f1.relationGate", "old-model-image-and-raw-retained"
            + "+old-and-new-artmesh-resolve-to-old-model-image");
    }

    private void recordSourceIdentity(final ModelState state,
        final SourceSnapshotIdentity source) {
        final String documentPath = state.relativePath();
        properties.setProperty("prepare.model.documentMetadata.relativePath", documentPath);
        properties.setProperty("prepare.model.documentMetadata.filename", lastPathPart(documentPath));
        properties.setProperty("prepare.model.documentMetadata.isUntitled",
            Boolean.toString("untitled".equalsIgnoreCase(lastPathPart(documentPath))));
        properties.setProperty("prepare.model.documentMetadata.role", "metadata-only");
        properties.setProperty("prepare.model.sourceConfirmed", "true");
        properties.setProperty("prepare.model.fixtureNameMatched", "true");
        properties.setProperty("prepare.model.sourcePath", source.relativePath());
        properties.setProperty("prepare.model.sourceRawId", source.rawId());
        properties.setProperty("prepare.model.sourceSnapshot.documentId", source.rawId());
        properties.setProperty("prepare.model.sourceSnapshot.relativePath", source.relativePath());
        properties.setProperty("prepare.model.sourceSnapshot.filename", source.fileName());
        properties.setProperty("prepare.model.sourceSnapshot.leafLayerIds",
            String.join(",", source.leafLayerIds()));
    }

    private void recordSavedCopy(final SavedCopyIdentity saved) {
        properties.setProperty("prepare.savedCopy.eventFileName",
            eventRequestFileName(saved.eventFileName()));
        properties.setProperty("prepare.savedCopy.eventContentId", saved.eventContentId());
        properties.setProperty("prepare.savedCopy.documentId", saved.documentId());
        properties.setProperty("prepare.savedCopy.modelId", saved.modelId());
        properties.setProperty("prepare.savedCopy.generation", Long.toString(saved.generation()));
        properties.setProperty("prepare.savedCopy.binding", saved.binding());
        properties.setProperty("prepare.savedCopy.windowIdentity", saved.windowIdentity());
        properties.setProperty("prepare.savedCopy.saveAfter", Boolean.toString(saved.saveAfter()));
        properties.setProperty("prepare.savedCopy.identityVerified",
            Boolean.toString(saved.identityVerified()));
    }

    private void checkStopped() {
        if (stopped.getAsBoolean()) {
            properties.setProperty("prepare.terminal", "STOPPED");
            throw new IllegalStateException("official PSD preparation stopped");
        }
    }

    private static PreparationBlockedException blocked(final String diagnostic) {
        return new PreparationBlockedException(diagnostic);
    }

    private void checkStoppedAndTask(final InputIdentity input) {
        checkStopped();
        if (!isTaskBound(input)) {
            throw new IllegalStateException("official PSD preparation task binding changed");
        }
    }

    private boolean isTaskBound(final InputIdentity input) {
        return input.runId().equals(properties.getProperty("prepare.runId", ""))
            && input.taskId().equals(properties.getProperty("prepare.taskId", ""));
    }

    private static boolean sameModelIdentity(final ModelState before, final ModelState after) {
        return before != null && after != null
            && Objects.equals(before.documentId(), after.documentId())
            && Objects.equals(before.modelId(), after.modelId())
            && before.identity().equals(after.identity());
    }

    private static boolean sameDocumentModelIdentity(final ModelState before,
        final ModelState after) {
        return before != null && after != null
            && Objects.equals(before.documentId(), after.documentId())
            && Objects.equals(before.modelId(), after.modelId());
    }

    private static boolean savedModelMatches(final ModelState before, final ModelState after,
        final String expectedFileName) {
        return sameModelIdentity(before, after) && expectedFileName != null
            && !expectedFileName.isBlank() && after.relativePath() != null
            && expectedFileName.equals(lastPathPart(after.relativePath()));
    }

    /** Package-private seam for the focused test of the post-SAVE_AS document gate. */
    static boolean savedModelMatchesForTest(final ModelState before, final ModelState after,
        final String expectedFileName) {
        return savedModelMatches(before, after, expectedFileName);
    }

    private static void sleepPoll(final long deadline) throws InterruptedException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return;
        Thread.sleep(Math.min(POLL_MILLIS,
            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
    }

    private static <T> EdtCall<T> invokeEdtBounded(final EdtOperation<T> operation,
        final long timeoutMillis) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            try {
                return EdtCall.completed(operation.call());
            } catch (Throwable failure) {
                return EdtCall.failed(failure);
            }
        }
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            SwingUtilities.invokeLater(() -> {
                if (!active.get() || System.nanoTime() >= deadline) {
                    done.countDown();
                    return;
                }
                try {
                    value.set(operation.call());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
        } catch (RuntimeException error) {
            active.set(false);
            return EdtCall.failed(error);
        }
        try {
            if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)
                || System.nanoTime() >= deadline) {
                return EdtCall.timeout();
            }
            return failure.get() == null
                ? EdtCall.completed(value.get()) : EdtCall.failed(failure.get());
        } finally {
            // Interruption also revokes a queued operation; it must not run after its waiter left.
            active.set(false);
        }
    }

    private static String windowIdentity(final Window window) {
        if (window == null) return UNAVAILABLE;
        return window.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(window));
    }

    private static String summarize(final Throwable failure) {
        if (failure == null) return "unknown failure";
        final String text = failure.getClass().getName() + ": " + String.valueOf(failure.getMessage());
        return text.length() <= 2048 ? text : text.substring(0, 2048);
    }

    private static Exception asException(final Throwable failure) throws Error {
        final Throwable cause = failure instanceof InvocationTargetException invocation
            ? invocation.getCause() : failure;
        if (cause instanceof Error error) throw error;
        return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
    }

    static Class<?> loadHostApplication() throws ClassNotFoundException {
        return Class.forName(APP_CONTROLLER, false, ClassLoader.getSystemClassLoader());
    }

    private static Class<?> loadExact(final ClassLoader loader, final String name)
        throws ClassNotFoundException {
        final Class<?> type = Class.forName(name, false, loader);
        if (!name.equals(type.getName()) || type.getClassLoader() != loader) {
            throw new ClassNotFoundException("class loader/identity mismatch for " + name);
        }
        return type;
    }

    private static Method exactMethod(final Class<?> owner, final String name,
        final Class<?> returnType, final boolean staticRequired, final Class<?>... parameters)
        throws ReflectiveOperationException {
        final Method method = owner.getDeclaredMethod(name, parameters);
        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) != staticRequired
            || method.getReturnType() != returnType || !method.trySetAccessible()) {
            throw new IllegalArgumentException("method shape is not exact: " + owner.getName()
                + '.' + name);
        }
        return method;
    }

    private static Method exactPublicMethod(final Class<?> owner, final String name,
        final Class<?> returnType, final boolean staticRequired, final Class<?> declaringOwner)
        throws ReflectiveOperationException {
        final Method method = owner.getMethod(name);
        final int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) != staticRequired
            || method.getReturnType() != returnType || method.getDeclaringClass() != declaringOwner) {
            throw new IllegalArgumentException("inherited method shape is not exact: "
                + owner.getName() + '.' + name);
        }
        return method;
    }

    private static Path codeSourcePath(final Class<?> type) throws IOException {
        final CodeSource source = type.getProtectionDomain() == null
            ? null : type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw new IOException(
            "no code source for " + type.getName());
        try {
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("invalid code source for " + type.getName(), failure);
        }
    }

    private static void verifyClassArtifact(final Class<?> type, final ClassLoader loader,
        final Path artifact) throws IOException {
        if (type.getClassLoader() != loader || !artifact.equals(codeSourcePath(type))) {
            throw new IOException("official class identity/code source mismatch: " + type.getName());
        }
    }

    private static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
        try (var input = Files.newInputStream(path)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Exact relation gate used by the live helper and focused tests. */
    public static RelationIdentity validateRelationSnapshot(final String documentId,
        final String modelId, final TextureRelationsSnapshot relations) {
        if (documentId == null || documentId.isBlank() || modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("document/model identity is unavailable");
        }
        if (relations == null || !relations.isAvailable() || relations.binding().isBlank()) {
            throw new IllegalArgumentException("texture relations are unavailable");
        }
        final List<String> modelImages = new ArrayList<>();
        final Set<String> modelImageIds = new LinkedHashSet<>();
        final List<String> currentRaws = new ArrayList<>();
        final List<String> linkedRaws = new ArrayList<>();
        final StringBuilder bindings = new StringBuilder();
        if (relations.generation() < 0L || relations.modelImages() == null) {
            throw new IllegalArgumentException("texture relation generation/images are unavailable");
        }
        for (final ModelImageRelation image : relations.modelImages()) {
            if (image == null || image.id() == null || image.id().value() == null
                || image.id().value().isBlank() || !modelImageIds.add(image.id().value())) {
                throw new IllegalArgumentException("model-image identity is missing or duplicated");
            }
            modelImages.add(image.id().value());
            final String current = image.currentRawImageId().map(raw -> raw.value()).orElse("");
            if (current.isBlank() || !image.linkedRawImageIds().contains(
                image.currentRawImageId().orElseThrow())) {
                throw new IllegalArgumentException("model-image currentRaw is not linked");
            }
            currentRaws.add(current);
            final LinkedHashSet<String> linked = new LinkedHashSet<>();
            for (final var raw : image.linkedRawImageIds()) {
                if (raw == null || raw.value().isBlank() || !linked.add(raw.value())) {
                    throw new IllegalArgumentException("linkedRaw identity is missing or duplicated");
                }
                linkedRaws.add(raw.value());
            }
            final Set<String> inputKeys = new LinkedHashSet<>();
            if (image.inputsByRawImage().isEmpty()) throw new IllegalArgumentException(
                "model-image rawLayerBindings are empty");
            for (final Map.Entry<dev.turboism.sdk.cubism.id.RawImageId, List<RawLayerBinding>> entry
                : image.inputsByRawImage().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()
                    || !inputKeys.add(entry.getKey().value())) {
                    throw new IllegalArgumentException("rawLayerBindings are empty or duplicated");
                }
                for (final RawLayerBinding binding : entry.getValue()) {
                    if (binding == null || binding.rawImageId() == null
                        || binding.rawLayerId() == null || binding.rawLayerId().value().isBlank()
                        || !entry.getKey().equals(binding.rawImageId())) {
                        throw new IllegalArgumentException("rawLayerBinding identity is inconsistent");
                    }
                    bindings.append(image.id().value()).append('=').append(entry.getKey().value())
                        .append(':').append(binding.rawLayerId().value()).append('@')
                        .append(binding.inputOrder()).append(';');
                }
            }
            if (!inputKeys.equals(linked)) throw new IllegalArgumentException(
                "linkedRaw and rawLayerBindings do not agree");
        }
        if (modelImages.isEmpty()) throw new IllegalArgumentException("no model images are present");
        return new RelationIdentity(documentId, modelId, relations.binding(), relations.generation(),
            List.copyOf(modelImages), List.copyOf(currentRaws), List.copyOf(linkedRaws),
            bindings.toString());
    }

    /** Shared production source gate; tests supply captured SDK observations only. */
    static SourceSnapshotIdentity validateInitialSourceGate(
        final ModelState expected, final ModelState fresh,
        final TextureRelationsSnapshot relations,
        final List<PsdClipMaskDocumentSnapshot> documents, final String fixtureName) {
        return validateInitialSourceGate(expected, fresh, relations, documents, fixtureName, true);
    }

    /**
     * Shared source gate with an explicit profile switch.  The seven-layer control requires
     * every PSD leaf to equal a raw layer binding; F1 deliberately does not, because its
     * twenty-layer source is used to prove a shared ModelImage relation instead.
     */
    static SourceSnapshotIdentity validateInitialSourceGate(
        final ModelState expected, final ModelState fresh,
        final TextureRelationsSnapshot relations,
        final List<PsdClipMaskDocumentSnapshot> documents, final String fixtureName,
        final boolean requireLeafLayerBinding) {
        if (!sameModelIdentity(expected, fresh)) throw new IllegalArgumentException(
            "initial model/document/relation identity changed");
        if (fresh.documentId() == null || fresh.modelId() == null
            || !fresh.documentId().equals(fresh.identity().documentId())
            || !fresh.modelId().equals(fresh.identity().modelId())) {
            throw new IllegalArgumentException("fresh model/document identity is inconsistent");
        }
        final RelationIdentity observed = validateRelationSnapshot(
            fresh.documentId(), fresh.modelId(), relations);
        if (!observed.equals(fresh.identity())) throw new IllegalArgumentException(
            "fresh texture relation identity changed");
        return validateSourceSnapshot(fresh.identity(), relations, documents, fixtureName,
            requireLeafLayerBinding);
    }

    /**
     * The leaf/raw binding equality below is specific to this official seven-layer control input.
     * It must not be generalized into a rule for unrelated multi-layer PSD imports.
     */
    private static SourceSnapshotIdentity validateSourceSnapshot(
        final RelationIdentity identity, final TextureRelationsSnapshot relations,
        final List<PsdClipMaskDocumentSnapshot> documents, final String fixtureName,
        final boolean requireLeafLayerBinding) {
        if (identity == null || relations == null || fixtureName == null
            || !isPsdName(fixtureName)) throw new IllegalArgumentException(
            "initial PSD source identity is unavailable");
        final Set<String> rawIds = new LinkedHashSet<>();
        final Set<String> rawLayerIds = new LinkedHashSet<>();
        int rawBindingCount = 0;
        if (relations.modelImages() == null || relations.modelImages().isEmpty()) {
            throw new IllegalArgumentException("initial PSD source has no model-image relations");
        }
        for (final ModelImageRelation image : relations.modelImages()) {
            if (image == null || image.currentRawImageId() == null
                || image.currentRawImageId().isEmpty()) throw new IllegalArgumentException(
                "initial PSD source currentRaw identity is missing");
            addRawId(rawIds, image.currentRawImageId().orElseThrow(), "currentRaw");
            if (image.linkedRawImageIds() == null || image.linkedRawImageIds().isEmpty()) {
                throw new IllegalArgumentException("initial PSD source linkedRaw identity is missing");
            }
            for (final RawImageId raw : image.linkedRawImageIds()) {
                addRawId(rawIds, raw, "linkedRaw");
            }
            if (image.inputsByRawImage() == null || image.inputsByRawImage().isEmpty()) {
                throw new IllegalArgumentException("initial PSD source raw bindings are missing");
            }
            for (final Map.Entry<RawImageId, List<RawLayerBinding>> entry
                : image.inputsByRawImage().entrySet()) {
                addRawId(rawIds, entry.getKey(), "raw binding");
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    throw new IllegalArgumentException("initial PSD source raw bindings are empty");
                }
                for (final RawLayerBinding binding : entry.getValue()) {
                    if (binding == null || binding.rawImageId() == null
                        || !entry.getKey().equals(binding.rawImageId())
                        || binding.rawLayerId() == null
                        || binding.rawLayerId().value() == null
                        || binding.rawLayerId().value().isBlank()
                        || !rawLayerIds.add(binding.rawLayerId().value())) {
                        throw new IllegalArgumentException(
                            "initial PSD source raw layer binding is missing or duplicated");
                    }
                    rawBindingCount++;
                }
            }
        }
        if (rawIds.size() != 1) throw new IllegalArgumentException(
            "initial PSD source has multiple raw identities: " + rawIds);
        final Set<String> identityRawIds = new LinkedHashSet<>(identity.currentRawIds());
        identityRawIds.addAll(identity.linkedRawIds());
        if (!identityRawIds.equals(rawIds) || rawBindingCount == 0) throw new IllegalArgumentException(
            "initial PSD source raw identity does not match verified relations");
        if (documents == null || documents.size() != 1 || documents.get(0) == null) {
            throw new IllegalArgumentException(
                "initial PSD source must have exactly one snapshot");
        }
        final PsdClipMaskDocumentSnapshot document = documents.get(0);
        final String rawId = rawIds.iterator().next();
        if (!rawId.equals(document.documentId())) throw new IllegalArgumentException(
            "initial PSD source snapshot raw identity does not match relations");
        final String fileName = lastPathPart(document.relativePath());
        if (!fixtureName.equals(fileName)) throw new IllegalArgumentException(
            "initial PSD source snapshot filename does not match task fixture: " + fileName);
        final Set<String> allLayerIds = new LinkedHashSet<>();
        final Set<String> leafLayerIds = new LinkedHashSet<>();
        if (document.layers() == null || document.layers().isEmpty()) throw new IllegalArgumentException(
            "initial PSD source snapshot has no layers");
        for (final PsdLayerSnapshot layer : document.layers()) {
            collectPsdLayerIds(layer, allLayerIds, leafLayerIds);
        }
        if (requireLeafLayerBinding && !leafLayerIds.equals(rawLayerIds)) {
            throw new IllegalArgumentException(
                "initial PSD source leaf layers do not match raw bindings");
        }
        return new SourceSnapshotIdentity(rawId, document.relativePath(), fileName,
            List.copyOf(leafLayerIds));
    }

    private static void addRawId(final Set<String> rawIds, final RawImageId raw,
        final String source) {
        if (raw == null || raw.value() == null || raw.value().isBlank()) {
            throw new IllegalArgumentException("initial PSD source " + source + " identity is missing");
        }
        rawIds.add(raw.value());
    }

    private static void collectPsdLayerIds(final PsdLayerSnapshot layer,
        final Set<String> allLayerIds, final Set<String> leafLayerIds) {
        if (layer == null || layer.layerId() == null || layer.layerId().isBlank()
            || !allLayerIds.add(layer.layerId())) throw new IllegalArgumentException(
            "initial PSD source layer identity is missing or duplicated");
        if (layer.children() == null || layer.children().isEmpty()) {
            if (!leafLayerIds.add(layer.layerId())) throw new IllegalArgumentException(
                "initial PSD source leaf layer identity is duplicated");
            return;
        }
        for (final PsdLayerSnapshot child : layer.children()) {
            collectPsdLayerIds(child, allLayerIds, leafLayerIds);
        }
    }

    /**
     * F1-specific post-COPY/PASTE relation gate.  The seven-layer leaf/binding equality rule is
     * intentionally absent here; this gate proves only that the old ModelImage and raw remain
     * intact and that a new ArtMesh now resolves to that same ModelImage.
     */
    static F1SharedIdentity validateF1SharedRelation(
        final TextureRelationsSnapshot before, final TextureRelationsSnapshot after,
        final String modelImageId, final String rawId, final String originalArtMeshId) {
        validateRelationSnapshot("f1-document", "f1-model", before);
        validateRelationSnapshot("f1-document", "f1-model", after);
        requireText(modelImageId, "F1 modelImageId");
        requireText(rawId, "F1 rawId");
        requireText(originalArtMeshId, "F1 originalArtMeshId");
        final ModelImageRelation beforeImage = modelImage(before, modelImageId);
        final ModelImageRelation afterImage = modelImage(after, modelImageId);
        if (!rawId.equals(beforeImage.currentRawImageId().map(RawImageId::value).orElse(""))
            || !rawId.equals(afterImage.currentRawImageId().map(RawImageId::value).orElse(""))) {
            throw new IllegalArgumentException("F1 old ModelImage current raw changed or is missing");
        }
        if (beforeImage.inputsByRawImage().isEmpty()
            || afterImage.inputsByRawImage().isEmpty()
            || !beforeImage.inputsByRawImage().equals(afterImage.inputsByRawImage())
            || !beforeImage.linkedRawImageIds().equals(afterImage.linkedRawImageIds())) {
            throw new IllegalArgumentException("F1 old ModelImage raw bindings changed or are empty");
        }
        final List<String> beforeUsing = uniqueArtMeshIds(beforeImage.usingArtMeshIds(),
            "F1 before usingArtMeshIds");
        final List<String> afterUsing = uniqueArtMeshIds(afterImage.usingArtMeshIds(),
            "F1 after usingArtMeshIds");
        if (!beforeUsing.contains(originalArtMeshId) || !afterUsing.contains(originalArtMeshId)) {
            throw new IllegalArgumentException("F1 original ArtMesh is not retained by the old ModelImage");
        }
        final Set<String> beforeSet = new LinkedHashSet<>(beforeUsing);
        final List<String> added = afterUsing.stream().filter(id -> !beforeSet.contains(id)).toList();
        if (added.size() != 1) throw new IllegalArgumentException(
            "F1 COPY/PASTE must add exactly one new ArtMesh to the old ModelImage: " + added);
        final String copiedArtMeshId = added.get(0);
        final Map<String, ArtMeshTextureInputs> beforeInputs = artMeshInputsById(before);
        final Map<String, ArtMeshTextureInputs> afterInputs = artMeshInputsById(after);
        if (beforeInputs.containsKey(copiedArtMeshId)) throw new IllegalArgumentException(
            "F1 copied ArtMesh ID was already present before PASTE: " + copiedArtMeshId);
        requireModelImageInput(beforeInputs.get(originalArtMeshId), modelImageId,
            originalArtMeshId, "before");
        requireModelImageInput(afterInputs.get(originalArtMeshId), modelImageId,
            originalArtMeshId, "after original");
        requireModelImageInput(afterInputs.get(copiedArtMeshId), modelImageId,
            copiedArtMeshId, "after copied");
        return new F1SharedIdentity(modelImageId, rawId, originalArtMeshId, copiedArtMeshId,
            afterUsing);
    }

    private static ModelImageRelation modelImage(final TextureRelationsSnapshot relations,
        final String modelImageId) {
        return relations.modelImages().stream()
            .filter(image -> image != null && image.id() != null
                && modelImageId.equals(image.id().value()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "F1 ModelImage is missing: " + modelImageId));
    }

    private static List<String> uniqueArtMeshIds(final List<ArtMeshId> ids, final String field) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException(field + " is empty");
        final List<String> values = new ArrayList<>();
        final Set<String> unique = new LinkedHashSet<>();
        for (final ArtMeshId id : ids) {
            if (id == null || id.value() == null || id.value().isBlank() || !unique.add(id.value())) {
                throw new IllegalArgumentException(field + " contains a missing or duplicate ID");
            }
            values.add(id.value());
        }
        return List.copyOf(values);
    }

    private static Map<String, ArtMeshTextureInputs> artMeshInputsById(
        final TextureRelationsSnapshot relations) {
        if (relations.artMeshInputs() == null) throw new IllegalArgumentException(
            "F1 ArtMesh texture-input projection is unavailable");
        final Map<String, ArtMeshTextureInputs> result = new java.util.LinkedHashMap<>();
        for (final ArtMeshTextureInputs mesh : relations.artMeshInputs()) {
            if (mesh == null || mesh.id() == null || mesh.id().value() == null
                || mesh.id().value().isBlank() || result.put(mesh.id().value(), mesh) != null) {
                throw new IllegalArgumentException("F1 ArtMesh texture-input identity is missing or duplicated");
            }
        }
        return result;
    }

    private static void requireModelImageInput(final ArtMeshTextureInputs mesh,
        final String modelImageId, final String artMeshId, final String stage) {
        if (mesh == null || mesh.currentInputIndex().isEmpty()) throw new IllegalArgumentException(
            "F1 " + stage + " ArtMesh input is missing: " + artMeshId);
        final int index = mesh.currentInputIndex().getAsInt();
        if (index < 0 || index >= mesh.inputs().size()) throw new IllegalArgumentException(
            "F1 " + stage + " ArtMesh current input index is invalid: " + artMeshId);
        final TextureInputBinding input = mesh.inputs().get(index);
        if (input == null || input.kind() != TextureInputBinding.Kind.MODEL_IMAGE
            || !input.isResolved() || input.modelImageId().isEmpty()
            || !modelImageId.equals(input.modelImageId().orElseThrow().value())) {
            throw new IllegalArgumentException("F1 " + stage
                + " ArtMesh does not resolve to the old ModelImage: " + artMeshId);
        }
    }

    static boolean saveEventMatches(final SaveAfterIdentity event, final String expectedContentId) {
        if (event == null || !event.succeeded() || !"SAVE".equals(event.operation())
            || !usableContentId(expectedContentId)
            || !usableContentId(event.contentId())
            || !expectedContentId.equals(event.contentId())) return false;
        return event.requestContentId() == null || event.requestContentId().isBlank()
            || expectedContentId.equals(event.requestContentId());
    }

    /** SAVE identity gate: post-state target CMO, event content identity, relations, and window. */
    public static boolean saveAfterMatches(final SaveAfterIdentity event,
        final ModelState before, final ModelState after, final String expectedFileName,
        final String beforeWindow, final String afterWindow) {
        if (before == null || after == null || before.contentId() == null
            || before.contentId().isEmpty() || !usableContentId(before.contentId().get())
            || !saveEventMatches(event, before.contentId().get()) || !event.succeeded()
            || !sameModelIdentity(before, after)
            || !savedModelMatches(before, after, expectedFileName)
            || after.contentId() == null
            || after.contentId().isEmpty() || after.contentId().get() == null
            || after.contentId().get().isBlank()
            || !before.contentId().get().equals(after.contentId().get())
            || !event.contentId().equals(after.contentId().get())
            || beforeWindow == null || !beforeWindow.equals(afterWindow)) return false;
        return true;
    }

    private static String windowIdentity(final Window window, final boolean ignored) {
        return windowIdentity(window);
    }

    private static String windowIdentity(final Object window) {
        return window instanceof Window value ? windowIdentity(value) : UNAVAILABLE;
    }

    private static String windowIdentity(final Dialog dialog) {
        return windowIdentity((Window) dialog);
    }

    private static String requiredSaveContentId(final ModelState state) {
        if (state == null || state.contentId() == null || state.contentId().isEmpty()) {
            throw new IllegalStateException("SAVE source document content identity is unavailable");
        }
        final String contentId = state.contentId().get();
        if (!usableContentId(contentId)) {
            throw new IllegalStateException("SAVE source document content identity is unavailable");
        }
        return contentId;
    }

    private static boolean usableContentId(final String contentId) {
        return contentId != null && !contentId.isBlank() && !UNAVAILABLE.equals(contentId);
    }

    private static String eventRequestFileName(final String fileName) {
        return fileName == null || fileName.isBlank() ? "absent" : fileName;
    }

    private static String eventContentId(final String contentId) {
        return usableContentId(contentId) ? contentId : "absent";
    }

    private record EdtCall<T>(boolean completed, T value, Throwable failure) {
        static <T> EdtCall<T> completed(final T value) { return new EdtCall<>(true, value, null); }
        static <T> EdtCall<T> failed(final Throwable failure) {
            return new EdtCall<>(true, null, failure);
        }
        static <T> EdtCall<T> timeout() { return new EdtCall<>(false, null, null); }
    }

    @FunctionalInterface
    interface EdtOperation<T> { T call() throws Exception; }

    private record HostAccess(ClassLoader loader, Path artifact, String sha256,
        Class<?> app, Method appInstance, Method mainFrame, Method cFrameGetter,
        Method swingWindow, Method swingFrame, Class<?> option, Method optionModel,
        Method optionLabel, Class<?> previewOption, Method previewLabel, Method previewRatio,
        Class<?> renderer, Class<?> hostList, Class<?> hostButton, Class<?> hostButtonSubclass,
        Class<?> action, String firstLabel, String secondLabel, String title, String message,
        String previewTitle, String previewMessage, HomeAccess homeAccess,
        NativeSelectionAccess selectionAccess) { }

    private record HomeAccess(Method instance, Method controller, Method window,
        Method dialog) { }

    private record NativeSelectionAccess(Class<?> documentClass, Class<?> meshSelectionClass,
        Method document, Method selector, Method selected, Method count, Method guid,
        Method uuid) { }

    private record PreparedInput(InputIdentity identity, PreparationProfile profile) { }

    @FunctionalInterface
    interface F1CopyPasteActions {
        void leftClick();

        default F1SelectionObservation selection() { return null; }
        default EditorCommandResult copy() { return null; }
        default EditorCommandResult paste() { return null; }
        default boolean unknownVisibleDialog() { return false; }
    }

    static record F1SelectionObservation(List<String> selectedObjectIds,
        Optional<String> activeArtMeshId) {
        F1SelectionObservation {
            selectedObjectIds = selectedObjectIds == null ? List.of() : List.copyOf(selectedObjectIds);
            activeArtMeshId = activeArtMeshId == null ? Optional.empty() : activeArtMeshId;
        }
    }

    static record F1CopyPasteResult(boolean accepted, boolean retryable, String diagnostic,
        F1SelectionObservation selection, boolean copyExecuted, boolean pasteExecuted) {
        static F1CopyPasteResult retry(final String diagnostic) {
            return new F1CopyPasteResult(false, true, diagnostic, null, false, false);
        }

        static F1CopyPasteResult rejected(final String diagnostic,
            final F1SelectionObservation selection, final boolean copyExecuted,
            final boolean pasteExecuted) {
            return new F1CopyPasteResult(false, false, diagnostic, selection,
                copyExecuted, pasteExecuted);
        }

        static F1CopyPasteResult accepted(final F1SelectionObservation selection,
            final boolean copyExecuted, final boolean pasteExecuted) {
            return new F1CopyPasteResult(true, false, "F1 COPY/PASTE commands executed",
                selection, copyExecuted, pasteExecuted);
        }
    }

    static record F1SharedIdentity(String modelImageId, String rawId, String originalArtMeshId,
        String copiedArtMeshId, List<String> usingArtMeshIds) {
        F1SharedIdentity {
            usingArtMeshIds = List.copyOf(usingArtMeshIds);
        }
    }

    private record F1Target(String modelImageId, String rawId, String artMeshId,
        TextureRelationsSnapshot beforeRelations) { }

    private record F1SharingPreparation(ModelState afterState, F1SharedIdentity sharing,
        F1CopyPasteResult action) { }

    private record F1TableRef(JTable table, Class<?> modelClass, Object modelIdentity) { }

    private record F1PreparedTable(JTable table, ExactHostRowTarget.HostAccessContext context,
        Object modelIdentity) { }

    private record F1ResolvedRow(JTable table, Object modelIdentity,
        ExactHostRowTarget.HostAccessContext context, ExactHostRowTarget.Target target) { }

    private static final class PreparationBlockedException extends Exception {
        private static final long serialVersionUID = 1L;

        PreparationBlockedException(final String message) { super(message); }
    }

    private enum PreparationProfile {
        NORMAL("normal", 0, FIRST_LABEL_KEY, "prepared-control.cmo3",
            NATIVE_SEVEN_LAYER_SHA256, null, true, false),
        LEGACY("legacy", 1, SECOND_LABEL_KEY, "prepared-control-legacy.cmo3",
            NATIVE_SEVEN_LAYER_SHA256, null, true, false),
        F1("f1", 0, FIRST_LABEL_KEY, F1_SAVED_COPY_BASENAME,
            F1_FIXTURE_SHA256, F1_FIXTURE_NAME, false, true);

        private final String name;
        private final int chooserIndex;
        private final String chooserKey;
        private final String savedCopyBasename;
        private final String fixtureSha256;
        private final String expectedFixtureName;
        private final boolean requiresLeafLayerBinding;
        private final boolean f1Sharing;

        PreparationProfile(final String name, final int chooserIndex, final String chooserKey,
            final String savedCopyBasename, final String fixtureSha256,
            final String expectedFixtureName, final boolean requiresLeafLayerBinding,
            final boolean f1Sharing) {
            this.name = name;
            this.chooserIndex = chooserIndex;
            this.chooserKey = chooserKey;
            this.savedCopyBasename = savedCopyBasename;
            this.fixtureSha256 = fixtureSha256;
            this.expectedFixtureName = expectedFixtureName;
            this.requiresLeafLayerBinding = requiresLeafLayerBinding;
            this.f1Sharing = f1Sharing;
        }

        String profileName() { return name; }
        int chooserIndex() { return chooserIndex; }
        String chooserKey() { return chooserKey; }
        String savedCopyBasename() { return savedCopyBasename; }
        String fixtureSha256() { return fixtureSha256; }
        String expectedFixtureName() { return expectedFixtureName; }
        boolean requiresLeafLayerBinding() { return requiresLeafLayerBinding; }
        boolean requiresEmptyTargetRgb() { return f1Sharing; }
        boolean f1Sharing() { return f1Sharing; }
        boolean f1PreviewChooser() { return f1Sharing; }
    }

    private record ChoiceObservation(Window owner, Dialog dialog, String listClass,
        String confirmClass, int selectedIndex, String selectedKey, String firstLabel,
        String secondLabel, boolean chosen) { }

    static record PreviewChooserObservation(Object currentOwner, Object dialogOwner,
        Object dialog, Object list, int candidateCount, Class<?> listClass, List<?> options,
        List<Class<?>> optionClasses, List<Integer> ratios, int targetIndex,
        Object targetOption, int confirmationCount, Class<?> confirmationClass,
        Class<?> actionClass, String actionName, boolean enabled, boolean showing,
        boolean displayable) {
        PreviewChooserObservation {
            options = options == null ? List.of() : List.copyOf(options);
            optionClasses = optionClasses == null ? List.of() : List.copyOf(optionClasses);
            ratios = ratios == null ? List.of() : List.copyOf(ratios);
        }
    }

    static record PreviewChooserGateExpectation(Class<?> listClass, Class<?> optionClass,
        Class<?> exactButtonClass, Class<?> buttonSubclass, Class<?> actionClass,
        Set<Integer> ratios) {
        PreviewChooserGateExpectation {
            ratios = ratios == null ? Set.of() : Set.copyOf(ratios);
        }
    }

    interface PreviewChooserGateActions {
        boolean select(int index);
        void clickConfirm();
    }

    static record PreviewChooserGateResult(boolean accepted, String diagnostic) {
        static PreviewChooserGateResult acceptedResult() {
            return new PreviewChooserGateResult(true, "F1 preview chooser action accepted");
        }

        static PreviewChooserGateResult rejected(final String diagnostic) {
            return new PreviewChooserGateResult(false, diagnostic);
        }
    }

    private record PreviewProbe(boolean selected, boolean activeModelPresent, Dialog dialog) { }

    private record CurrentModelObservation(CubismModel model,
        TextureRelationsSnapshot relations, ModelState state) { }

    private record InitialSourceObservation(ModelState state, SourceSnapshotIdentity source) { }

    static record SourceSnapshotIdentity(String rawId, String relativePath, String fileName,
        List<String> leafLayerIds) {
        SourceSnapshotIdentity {
            leafLayerIds = List.copyOf(leafLayerIds);
        }
    }

    public record InputIdentity(String fixturePath, String fixtureSha256, String fixtureName,
        String runId, String taskId, String savedCopyPath, String targetRgbSha256,
        String hostVersion, long timeoutMillis) { }

    public record RelationIdentity(String documentId, String modelId, String binding,
        long generation, List<String> modelImageIds, List<String> currentRawIds,
        List<String> linkedRawIds, String rawLayerBindings) {
        public RelationIdentity {
            modelImageIds = List.copyOf(modelImageIds);
            currentRawIds = List.copyOf(currentRawIds);
            linkedRawIds = List.copyOf(linkedRawIds);
        }
    }

    public record ModelState(String documentId, Optional<String> contentId, String relativePath,
        String modelId, RelationIdentity identity) { }

    public record SaveAfterIdentity(String operation, boolean succeeded, String fileName,
        String requestContentId, String contentId) {
        public SaveAfterIdentity(final String operation, final boolean succeeded,
            final String fileName, final String contentId) {
            this(operation, succeeded, fileName, "", contentId);
        }

        /** The request file name is pre-SAVE evidence; it is never the SAVE_AS target proof. */
        public String requestFileName() {
            return fileName;
        }

        static SaveAfterIdentity from(final ProjectFileLifecycleEvent.After event) {
            final var result = event.result();
            return new SaveAfterIdentity(result.request().operation().name(), result.succeeded(),
                result.request().fileName().orElse(""), result.request().contentId().orElse(""),
                result.content().map(value -> value.contentId()).orElse(UNAVAILABLE));
        }
    }

    public record SavedCopyIdentity(String configuredPath, String eventFileName,
        String eventContentId, String documentId, String modelId, long generation,
        String binding, String windowIdentity, boolean saveAfter, boolean identityVerified) { }

    public record PreparationResult(Window window, SavedCopyIdentity savedCopy,
        RelationIdentity relation) { }

    private record SaveExecution(EditorCommandResult result, int afterSequence,
        ModelState currentModel) { }

    private static final class EventRecorder {
        private final Properties properties;
        private final List<ProjectFileLifecycleEvent.After> after =
            Collections.synchronizedList(new ArrayList<>());
        private int before;
        private int on;

        EventRecorder(final Properties properties) { this.properties = properties; }
        void before(final ProjectFileLifecycleEvent.Before ignored) { synchronized (this) { before++; } }
        void on(final ProjectFileLifecycleEvent.On ignored) { synchronized (this) { on++; } }
        void after(final ProjectFileLifecycleEvent.After event) { after.add(event); synchronized (this) {
            properties.setProperty("prepare.lifecycle.after.count", Integer.toString(after.size()));
        } }
        int captureBeforeExecute() {
            synchronized (after) {
                final int sequence = after.size();
                properties.setProperty("prepare.lifecycle.beforeExecute.afterSequence",
                    Integer.toString(sequence));
                return sequence;
            }
        }
        ProjectFileLifecycleEvent.After awaitSave(final String expectedContentId,
            final int afterSequence,
            final long timeout, final BooleanSupplier stopped) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (stopped.getAsBoolean()) throw new IllegalStateException("stopped while waiting SAVE After");
                final List<ProjectFileLifecycleEvent.After> snapshot;
                synchronized (after) {
                    snapshot = List.copyOf(after);
                }
                final Optional<ProjectFileLifecycleEvent.After> match = firstAfterSequence(snapshot,
                    afterSequence, event -> saveEventMatches(
                        SaveAfterIdentity.from(event), expectedContentId));
                if (match.isPresent()) return match.get();
                Thread.sleep(100L);
            }
            throw new IllegalStateException("SAVE After event was not observed");
        }
        void recordSummary() { synchronized (this) {
            properties.setProperty("prepare.lifecycle.before.count", Integer.toString(before));
            properties.setProperty("prepare.lifecycle.on.count", Integer.toString(on));
            properties.setProperty("prepare.lifecycle.openAfter", "NOT_ASSUMED");
        } }
    }

    private static <T> Optional<T> firstAfterSequence(final List<T> values,
        final int afterSequence, final Predicate<T> matches) {
        final int start = Math.max(0, Math.min(afterSequence, values.size()));
        for (int index = start; index < values.size(); index++) {
            final T value = values.get(index);
            if (matches.test(value)) return Optional.of(value);
        }
        return Optional.empty();
    }

    /** Package-private sequence seam keeps stale SAVE events out of the identity gate. */
    static Optional<SaveAfterIdentity> firstSaveAfterAfterSequenceForTest(
        final List<SaveAfterIdentity> events, final int afterSequence,
        final String expectedContentId) {
        return firstAfterSequence(events, afterSequence,
            event -> saveEventMatches(event, expectedContentId));
    }
}
