package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageRelation;
import dev.turboism.sdk.cubism.model.RawLayerBinding;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.event.cubism.ProjectFileLifecycleEvent;
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
import javax.swing.text.JTextComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
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

    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String MAIN_FRAME_CONTROLLER =
        "com.live2d.cubism.view.CEMainFrameCtrl";
    private static final String C_FRAME = "com.live2d.ui.window.CFrame";
    private static final String WINDOW_BASE = "com.live2d.ui.window.V";
    private static final String OPTION = "com.live2d.cubism.process.psd.a$a";
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
    private static final long DEFAULT_TIMEOUT_MILLIS = 180_000L;
    private static final long EDT_CALL_TIMEOUT_MILLIS = 2_000L;
    private static final long SAVE_AS_EDT_CALL_TIMEOUT_MILLIS = 60_000L;
    private static final long POLL_MILLIS = 150L;
    private static final String UNAVAILABLE = "unavailable";

    private final PluginContext context;
    private final BooleanSupplier stopped;
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
        EventRecorder events = null;
        final List<Registration> registrations = new ArrayList<>();
        try {
            input = readInput(properties);
            recordInput(input);
            final HostAccess host = preflightHostAccess();
            recordHost(host);

            // These subscriptions intentionally happen before the chooser is touched.  OPEN is
            // not assumed for a new document; model/relation identity is the required gate.
            events = new EventRecorder(properties);
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.Before.class, events::before));
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.On.class, events::on));
            registrations.add(context.eventBus().subscribe(
                ProjectFileLifecycleEvent.After.class, events::after));

            final Window window = chooseNewModel(host, input, input.timeoutMillis());
            recordWindow(window);
            final ModelState state = awaitInitialModel(input, input.timeoutMillis());
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
            properties.setProperty("prepare.status", "FAIL");
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

    private InputIdentity readInput(final Properties values) {
        final String fixture = configured(values, FIXTURE_PROPERTY, "fixture");
        final String fixtureSha = configured(values, FIXTURE_SHA256_PROPERTY, "fixtureSha256");
        final String fixtureName = configured(values, FIXTURE_NAME_PROPERTY, "fixtureName");
        final String runId = configured(values, RUN_ID_PROPERTY, "runId");
        final String taskId = configured(values, TASK_ID_PROPERTY, "taskId");
        final String savedCopy = configured(values, SAVED_COPY_PROPERTY, "savedCopy");
        final String targetRgb = configured(values, TARGET_RGB_SHA256_PROPERTY, "targetRgbSha256");
        final String hostVersion = configured(values, HOST_VERSION_PROPERTY, "hostVersion");
        final long timeout = timeoutMillis(configured(values, TIMEOUT_MILLIS_PROPERTY,
            "timeoutMillis"));
        return validateInput(fixture, fixtureSha, fixtureName, runId, taskId, savedCopy,
            targetRgb, hostVersion, timeout);
    }

    private static String configured(final Properties values, final String key,
        final String shortKey) {
        String value = values.getProperty(key, "");
        if (value.isBlank()) value = values.getProperty(shortKey, "");
        if (value.isBlank()) value = System.getProperty(key, "");
        if (value.isBlank()) value = System.getProperty(shortKey, "");
        return value == null ? "" : value.trim();
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
        if (!NATIVE_SEVEN_LAYER_SHA256.equals(fixtureSha)) {
            throw new IllegalArgumentException("fixtureSha256 is not the reviewed seven-layer PSD");
        }
        requireWindowsPath(savedCopy, "savedCopy");
        if (!lastPathPart(savedCopy).equals("prepared-control.cmo3")) {
            throw new IllegalArgumentException("savedCopy must be prepared-control.cmo3");
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

    private void recordInput(final InputIdentity input) {
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
    }

    private HostAccess preflightHostAccess() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("official host preflight must run off EDT");
        }
        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader == null) throw new IllegalStateException(
            "host context classloader unavailable");
        // TCCL is only the lookup root.  Parent delegation may legitimately return the official
        // classes from a defining loader above it, so identity is rooted at CEAppCtrl's loader.
        final Class<?> app = loadFromContext(contextLoader, APP_CONTROLLER);
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
        final Class<?> modelDocument = loadExact(loader, MODEL_DOCUMENT);
        final Class<?> renderer = loadExact(loader, RENDERER);
        final Class<?> hostList = loadExact(loader, HOST_LIST);
        final Class<?> hostButton = loadExact(loader, HOST_BUTTON);
        final Class<?> hostButtonSubclass = loadExact(loader, HOST_BUTTON_SUBCLASS);
        final Class<?> action = loadExact(loader, HOST_ACTION);
        final Class<?> localizer = loadExact(loader, LOCALIZER);
        for (final Class<?> type : List.of(app, mainFrameController, cFrame, windowBase, option,
            modelDocument, renderer, hostList, hostButton, hostButtonSubclass, action, localizer)) {
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
        if (firstLabel.isBlank() || secondLabel.isBlank() || title.isBlank() || message.isBlank()
            || firstLabel.equals(secondLabel)) {
            throw new IllegalArgumentException("official chooser localization is unavailable");
        }
        return new HostAccess(loader, artifact, digest, app, appInstance, mainFrame, cFrameGetter,
            swingWindow, swingFrame, option, optionModel, optionLabel, renderer, hostList, hostButton,
            hostButtonSubclass, action, firstLabel, secondLabel, title, message);
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
    }

    private Window chooseNewModel(final HostAccess host, final InputIdentity input,
        final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final AtomicReference<Window> owner = new AtomicReference<>();
        while (System.nanoTime() < deadline) {
            checkStoppedAndTask(input);
            final EdtCall<ChoiceObservation> call = invokeEdtBounded(() ->
                inspectAndChooseOnEdt(host, input, owner), EDT_CALL_TIMEOUT_MILLIS);
            if (!call.completed()) throw new IllegalStateException(
                "official chooser EDT inspection timed out");
            if (call.failure() != null) throw asException(call.failure());
            if (call.value() != null && call.value().chosen()) {
                final ChoiceObservation chosen = call.value();
                recordChoice(chosen);
                waitForDialogGone(chosen.dialog(), deadline);
                return chosen.owner();
            }
            sleepPoll(deadline);
        }
        throw new IllegalStateException("official PSD chooser did not appear before timeout");
    }

    private ChoiceObservation inspectAndChooseOnEdt(final HostAccess host,
        final InputIdentity input,
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
        for (final Window window : Window.getWindows()) {
            if (!(window instanceof Dialog dialog) || !dialog.isShowing() || !dialog.isDisplayable()) {
                continue;
            }
            final List<JList<?>> lists = exactLists(dialog, host.hostList());
            if (lists.isEmpty()) continue;
            if (dialog.getOwner() != currentOwner) {
                throw new IllegalStateException("PSD chooser has an unknown/non-bound owner");
            }
            candidates.add(dialog);
        }
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
                host.firstLabel(), host.secondLabel()),
            stopped, () -> isTaskBound(input), new ChooserGateActions() {
                @Override public boolean selectFirst() {
                    list.setSelectedIndex(0);
                    return list.getSelectedValue() == first;
                }

                @Override public void clickConfirm() {
                    confirmation.doClick();
                }
            });
        if (!gate.accepted()) throw new IllegalStateException(gate.diagnostic());
        return new ChoiceObservation(currentOwner, dialog, list.getClass().getName(),
            confirmation.getClass().getName(), firstText, secondText, true);
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
        if (binding == null || !binding.ready() || binding.owner() == null
            || observation == null || binding.owner() != observation.currentOwner()) {
            return ChooserGateResult.rejected("official main-frame window binding is not exact");
        }
        return verifyAndExecuteChooser(observation, expected, stopped, taskBound, actions);
    }

    static ChooserGateResult verifyAndExecuteChooser(final ChooserGateObservation observation,
        final ChooserGateExpectation expected, final BooleanSupplier stopped,
        final BooleanSupplier taskBound, final ChooserGateActions actions) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(stopped, "stopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(actions, "actions");
        final String shapeFailure = chooserShapeFailure(observation, expected);
        if (!shapeFailure.isEmpty()) return ChooserGateResult.rejected(shapeFailure);
        if (!chooserGateOpen(stopped, taskBound)) {
            return ChooserGateResult.rejected("chooser action was stopped or task-unbound");
        }
        try {
            if (!chooserGateOpen(stopped, taskBound)) {
                return ChooserGateResult.rejected("chooser selection was stopped or task-unbound");
            }
            if (!actions.selectFirst()) {
                return ChooserGateResult.rejected(
                    "official chooser did not select the first new-model option");
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

    private ModelState awaitInitialModel(final InputIdentity input, final long timeoutMillis)
        throws Exception {
        final ModelState state = awaitModel(timeoutMillis);
        final boolean fixtureNameMatched = !input.fixtureName().isBlank()
            && input.fixtureName().equals(lastPathPart(state.relativePath()));
        properties.setProperty("prepare.model.fixtureNameMatched",
            Boolean.toString(fixtureNameMatched));
        if (!fixtureNameMatched) throw new IllegalStateException(
            "new model relative path is not the task PSD copy: " + state.relativePath());
        properties.setProperty("prepare.model.sourceConfirmed", "true");
        properties.setProperty("prepare.model.sourcePath", state.relativePath());
        return state;
    }

    private ModelState awaitModel(final long timeoutMillis) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        final AtomicReference<String> last = new AtomicReference<>("no model yet");
        while (System.nanoTime() < deadline) {
            checkStopped();
            final EdtCall<ModelState> call = invokeEdtBounded(() -> {
                try {
                    return currentModelOnEdt();
                } catch (RuntimeException unavailable) {
                    last.set(unavailable.getMessage() == null ? unavailable.toString()
                        : unavailable.getMessage());
                    return null;
                }
            }, EDT_CALL_TIMEOUT_MILLIS);
            if (!call.completed()) throw new IllegalStateException("model readiness EDT timed out");
            if (call.failure() != null) throw asException(call.failure());
            if (call.value() != null) {
                return call.value();
            }
            sleepPoll(deadline);
        }
        throw new IllegalStateException("new PSD model relation readiness timed out: " + last.get());
    }

    private ModelState currentModelOnEdt() {
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
        return new ModelState(document.documentId(), document.contentId(),
            document.relativePath(), model.id().value(), identity);
    }

    private SavedCopyIdentity saveAsAndConfirm(final InputIdentity input, final ModelState before,
        final Window window, final HostAccess host, final EventRecorder events) throws Exception {
        checkStopped();
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
                lastPathPart(input.savedCopyPath()), execution.afterSequence(), 60_000L, stopped);
            final ModelState current = awaitModel(30_000L);
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
            properties.setProperty("prepare.saveAfter.fileName", event.fileName());
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
        properties.setProperty("prepare.chooser.selectedKey", FIRST_LABEL_KEY);
        properties.setProperty("prepare.chooser.confirmAction", "OK");
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

    private void recordSavedCopy(final SavedCopyIdentity saved) {
        properties.setProperty("prepare.savedCopy.eventFileName", saved.eventFileName());
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
        return before != null && after != null && before.identity().equals(after.identity());
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

    private <T> EdtCall<T> invokeEdtBounded(final EdtOperation<T> operation,
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
        try {
            SwingUtilities.invokeLater(() -> {
                if (!active.get()) {
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
        if (!done.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            active.set(false);
            return EdtCall.timeout();
        }
        return failure.get() == null ? EdtCall.completed(value.get()) : EdtCall.failed(failure.get());
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

    private static Class<?> loadFromContext(final ClassLoader contextLoader, final String name)
        throws ClassNotFoundException {
        final Class<?> type = Class.forName(name, false, contextLoader);
        if (!name.equals(type.getName())) {
            throw new ClassNotFoundException("class identity mismatch for " + name);
        }
        return type;
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

    static boolean saveEventMatches(final SaveAfterIdentity event, final String expectedFileName) {
        return event != null && "SAVE".equals(event.operation()) && expectedFileName != null
            && expectedFileName.equals(event.fileName());
    }

    /** SAVE identity gate: event, target CMO, model/relation state, content ID, and window. */
    public static boolean saveAfterMatches(final SaveAfterIdentity event,
        final ModelState before, final ModelState after, final String expectedFileName,
        final String beforeWindow, final String afterWindow) {
        if (!saveEventMatches(event, expectedFileName) || !event.succeeded()
            || before == null || after == null || !sameModelIdentity(before, after)
            || !savedModelMatches(before, after, expectedFileName)
            || event.contentId() == null || event.contentId().isBlank()
            || UNAVAILABLE.equals(event.contentId()) || after.contentId() == null
            || after.contentId().isEmpty() || after.contentId().get() == null
            || after.contentId().get().isBlank()
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

    private record EdtCall<T>(boolean completed, T value, Throwable failure) {
        static <T> EdtCall<T> completed(final T value) { return new EdtCall<>(true, value, null); }
        static <T> EdtCall<T> failed(final Throwable failure) {
            return new EdtCall<>(true, null, failure);
        }
        static <T> EdtCall<T> timeout() { return new EdtCall<>(false, null, null); }
    }

    @FunctionalInterface
    private interface EdtOperation<T> { T call() throws Exception; }

    private record HostAccess(ClassLoader loader, Path artifact, String sha256,
        Class<?> app, Method appInstance, Method mainFrame, Method cFrameGetter,
        Method swingWindow, Method swingFrame, Class<?> option, Method optionModel,
        Method optionLabel, Class<?> renderer, Class<?> hostList, Class<?> hostButton,
        Class<?> hostButtonSubclass, Class<?> action, String firstLabel, String secondLabel,
        String title, String message) { }

    private record ChoiceObservation(Window owner, Dialog dialog, String listClass,
        String confirmClass, String firstLabel, String secondLabel, boolean chosen) { }

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
        String contentId) {
        static SaveAfterIdentity from(final ProjectFileLifecycleEvent.After event) {
            final var result = event.result();
            return new SaveAfterIdentity(result.request().operation().name(), result.succeeded(),
                result.request().fileName().orElse(""), result.content().map(value -> value.contentId())
                    .orElse(UNAVAILABLE));
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
        ProjectFileLifecycleEvent.After awaitSave(final String fileName, final int afterSequence,
            final long timeout, final BooleanSupplier stopped) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (stopped.getAsBoolean()) throw new IllegalStateException("stopped while waiting SAVE After");
                final List<ProjectFileLifecycleEvent.After> snapshot;
                synchronized (after) {
                    snapshot = List.copyOf(after);
                }
                final Optional<ProjectFileLifecycleEvent.After> match = firstAfterSequence(snapshot,
                    afterSequence, event -> saveEventMatches(SaveAfterIdentity.from(event), fileName));
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

    /** Package-private sequence seam keeps the old same-name SAVE regression deterministic. */
    static Optional<SaveAfterIdentity> firstSaveAfterAfterSequenceForTest(
        final List<SaveAfterIdentity> events, final int afterSequence,
        final String expectedFileName) {
        return firstAfterSequence(events, afterSequence,
            event -> saveEventMatches(event, expectedFileName));
    }
}
