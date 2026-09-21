package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Validation-only baseline for one official Cubism PSD replacement.
 *
 * <p>This class drives only the reviewed public {@code CEAppCtrl.command_open(File, true)}
 * entry point and its two reviewed Swing choosers.  It deliberately does not claim that the
 * native replacement was applied: the caller must use the public SDK relations, dirty state,
 * and Undo observations after this helper returns.</p>
 *
 * <p>The helper is intentionally current-document only.  It has no document-switching API and
 * does not manufacture a second document or a native selector.</p>
 */
public final class OfficialPsdReplacementBaseline {
    public static final String HOST_JAR_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";

    private static final String APP_CONTROLLER = "com.live2d.cubism.CEAppCtrl";
    private static final String MAIN_FRAME_CONTROLLER =
        "com.live2d.cubism.view.CEMainFrameCtrl";
    private static final String C_FRAME = "com.live2d.ui.window.CFrame";
    private static final String WINDOW_BASE = "com.live2d.ui.window.V";
    private static final String DIALOG = "com.live2d.ui.window.E";
    private static final String MODEL_DOCUMENT =
        "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String LAYERED_IMAGE =
        "com.live2d.cubism.doc.resources.CLayeredImage";
    private static final String LAYERED_IMAGE_GUID = "com.live2d.type.CLayeredImageGuid";
    private static final String GUID = "com.live2d.type.Guid";
    private static final String MODEL_OPTION = "com.live2d.cubism.process.psd.a$a";
    private static final String RAW_OPTION = "com.live2d.cubism.process.psd.a$b";
    private static final String MODEL_RENDERER = "com.live2d.cubism.process.psd.e";
    private static final String RAW_RENDERER = "com.live2d.cubism.process.psd.f";
    private static final String HOST_LIST = "com.live2d.ui.swingImpl.q";
    private static final String HOST_BUTTON = "com.live2d.ui.swingImpl.j";
    private static final String HOST_BUTTON_SUBCLASS = "com.live2d.ui.control.CButton$b";
    private static final String HOST_ACTION = "com.live2d.ui.event.CAction";
    private static final String LOCALIZER = "b.c";

    private static final String MODEL_TITLE_KEY = "CUB3-0421";
    private static final String MODEL_MESSAGE_KEY = "CUB3-0420";
    private static final String RAW_TITLE_KEY = "CUB3-0428";
    private static final String RAW_MESSAGE_KEY = "CUB3-0427";
    private static final long MIN_TIMEOUT_MILLIS = 1_000L;
    private static final long MAX_TIMEOUT_MILLIS = 600_000L;
    private static final long POLL_MILLIS = 100L;

    private final PluginContext context;
    private final BooleanSupplier stopped;
    private final BooleanSupplier taskBound;

    public OfficialPsdReplacementBaseline(final PluginContext context,
        final BooleanSupplier stopped, final BooleanSupplier taskBound) {
        this.context = Objects.requireNonNull(context, "context");
        this.stopped = Objects.requireNonNull(stopped, "stopped");
        this.taskBound = Objects.requireNonNull(taskBound, "taskBound");
    }

    /** Run the current-document official baseline once. */
    public static ReplacementResult replace(final PluginContext context,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final ReplacementRequest request) throws Exception {
        return new OfficialPsdReplacementBaseline(context, stopped, taskBound).run(request);
    }

    /** Alias for phase coordinators that name this operation {@code run}. */
    public static ReplacementResult run(final PluginContext context,
        final BooleanSupplier stopped, final BooleanSupplier taskBound,
        final ReplacementRequest request) throws Exception {
        return replace(context, stopped, taskBound, request);
    }

    /**
     * Preflights a read-only native identity bridge off EDT. The returned reader must be
     * invoked on EDT, alongside the caller's SDK task/window checks. It resolves the raw
     * afresh by GUID; neither a basename nor a list position establishes native identity.
     */
    public static IdentityReader prepareIdentityReader(final PluginContext context,
        final String rawGuid) throws Exception {
        Objects.requireNonNull(context, "context");
        requireText(rawGuid, "rawGuid");
        final HostAccess host = new OfficialPsdReplacementBaseline(
            context, () -> false, () -> true).preflightOfficialHost();
        final TargetAccess target = preflightTargetAccess(host);
        return () -> {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
                "official PSD target observation requires EDT");
            final var document = context.cubism().activeDocument().orElseThrow();
            final var model = context.cubism().model().active();
            final var relations = model.textures().relations();
            if (!relations.isAvailable() || relations.rawImages().stream()
                .filter(raw -> rawGuid.equals(raw.id().value())).count() != 1L) {
                throw new IllegalStateException("SDK raw target is unavailable or ambiguous");
            }
            final Object app = host.appInstance().invoke(null);
            final Object nativeDocument = target.currentDocument().invoke(app);
            if (nativeDocument == null || nativeDocument.getClass() != target.documentClass()) {
                throw new IllegalStateException("official current document is not a modeling document");
            }
            final Object source = target.modelSource().invoke(nativeDocument);
            final String nativeModelId = (String) host.guidStringGetter().invoke(
                target.modelGuid().invoke(source));
            if (!model.id().value().equals(nativeModelId)) throw new IllegalStateException(
                "SDK and official current model GUID differ");
            final Object manager = target.textureManager().invoke(source);
            final Object wrappers = target.rawImages().invoke(manager);
            if (!(wrappers instanceof List<?> values)) throw new IllegalStateException(
                "official raw collection is unavailable");
            final Object image = uniqueRawImage(values, target.wrapperClass(),
                target.wrapperImage(), host.rawGuidGetter(), host.guidStringGetter(), rawGuid);
            // Recheck SDK identity after native traversal within this same EDT operation.
            if (!document.documentId().equals(context.cubism().activeDocument()
                    .orElseThrow().documentId())
                || !model.id().equals(context.cubism().model().active().id())
                || target.currentDocument().invoke(app) != nativeDocument) {
                throw new IllegalStateException("official PSD target changed during observation");
            }
            return new IdentityObservation(document.documentId(), nativeModelId, rawGuid,
                nativeDocument, image);
        };
    }

    private static TargetAccess preflightTargetAccess(final HostAccess host) throws Exception {
        final ClassLoader loader = host.loader();
        final Class<?> document = loadExact(loader, MODEL_DOCUMENT);
        final Class<?> documentInterface = loadExact(loader, "com.live2d.cubism.doc.IDocument");
        final Class<?> source = loadExact(loader, "com.live2d.cubism.doc.model.CModelSource");
        final Class<?> modelGuid = loadExact(loader, "com.live2d.type.CModelGuid");
        final Class<?> manager = loadExact(loader,
            "com.live2d.cubism.doc.model.texture.CTextureManager");
        final Class<?> wrapper = loadExact(loader,
            "com.live2d.cubism.doc.model.texture.LayeredImageWrapper");
        final Class<?> image = loadExact(loader, LAYERED_IMAGE);
        for (final Class<?> type : List.of(document, documentInterface, source, modelGuid,
            manager, wrapper, image)) verifyClassArtifact(type, loader, host.artifact());
        return new TargetAccess(document, wrapper,
            exactMethod(host.app(), "getCurrentDoc", documentInterface, false),
            exactMethod(document, "getModelSource", source, false),
            exactMethod(source, "getGuid", modelGuid, false),
            exactMethod(source, "getTextureManager", manager, false),
            exactMethod(manager, "getRawImages", List.class, false),
            exactMethod(wrapper, "getImage", image, false));
    }

    /** Read actual native composition enums; SDK BlendMode collapses old/new variants. */
    public static CompositionReader prepareCompositionReader(final PluginContext context)
        throws Exception {
        Objects.requireNonNull(context, "context");
        final HostAccess host = new OfficialPsdReplacementBaseline(
            context, () -> false, () -> true).preflightOfficialHost();
        final TargetAccess target = preflightTargetAccess(host);
        final CompositionAccess access = preflightCompositionAccess(host);
        return () -> {
            if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
                "official composition observation requires EDT");
            final var document = context.cubism().activeDocument().orElseThrow();
            final var model = context.cubism().model().active();
            final Object app = host.appInstance().invoke(null);
            final Object nativeDocument = target.currentDocument().invoke(app);
            if (nativeDocument == null || nativeDocument.getClass() != target.documentClass()) {
                throw new IllegalStateException("official current modeling document unavailable");
            }
            final Object source = target.modelSource().invoke(nativeDocument);
            final String modelGuid = (String) host.guidStringGetter().invoke(
                target.modelGuid().invoke(source));
            if (!model.id().value().equals(modelGuid)) throw new IllegalStateException(
                "SDK and composition model GUID differ");
            final Map<String, String> sdkMeshes = new java.util.TreeMap<>();
            for (final var mesh : model.drawables().all()) {
                if (sdkMeshes.put(mesh.guid(), mesh.id().value()) != null) {
                    throw new IllegalStateException("SDK ArtMesh GUID is ambiguous");
                }
            }
            final Map<String, Composition> meshes = new java.util.TreeMap<>();
            final Object all = access.allMeshes().invoke(source);
            if (!(all instanceof List<?> values)) throw new IllegalStateException(
                "official ArtMesh collection is unavailable");
            for (final Object mesh : values) {
                if (mesh == null || mesh.getClass() != access.meshClass()) {
                    throw new IllegalStateException("official ArtMesh shape is unknown");
                }
                final String guid = (String) host.guidStringGetter().invoke(
                    access.meshGuid().invoke(mesh));
                final String id = sdkMeshes.remove(guid);
                if (id == null) throw new IllegalStateException(
                    "official ArtMesh has no unique SDK counterpart");
                final String color = exactEnumName(access.color().invoke(mesh), access.colorClass());
                final String alpha = exactEnumName(access.alpha().invoke(mesh), access.alphaClass());
                if (meshes.put(id, new Composition(guid, color, alpha)) != null) {
                    throw new IllegalStateException("official ArtMesh ID is ambiguous");
                }
            }
            if (!sdkMeshes.isEmpty() || meshes.isEmpty()) throw new IllegalStateException(
                "official/SDK ArtMesh coverage differs or is empty");
            final int version = (Integer) access.versionNumber().invoke(
                access.version().invoke(source));
            if (!document.documentId().equals(context.cubism().activeDocument()
                    .orElseThrow().documentId())
                || !model.id().equals(context.cubism().model().active().id())
                || target.currentDocument().invoke(app) != nativeDocument) {
                throw new IllegalStateException("composition identity changed during observation");
            }
            return new CompositionObservation(document.documentId(), modelGuid, version,
                Map.copyOf(meshes));
        };
    }

    private static CompositionAccess preflightCompositionAccess(final HostAccess host)
        throws Exception {
        final ClassLoader loader = host.loader();
        final Class<?> source = loadExact(loader, "com.live2d.cubism.doc.model.CModelSource");
        final Class<?> mesh = loadExact(loader,
            "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
        final Class<?> drawable = loadExact(loader,
            "com.live2d.cubism.doc.model.drawable.ACDrawableSource");
        final Class<?> guid = loadExact(loader, "com.live2d.type.CDrawableGuid");
        final Class<?> color = loadExact(loader,
            "com.live2d.cubism.doc.model.drawable.ColorComposition");
        final Class<?> alpha = loadExact(loader,
            "com.live2d.cubism.doc.model.drawable.AlphaComposition");
        final Class<?> version = loadExact(loader, "com.live2d.cubism.CETargetVersion$a");
        for (final Class<?> type : List.of(source, mesh, drawable, guid, color, alpha, version)) {
            verifyClassArtifact(type, loader, host.artifact());
        }
        if (!color.isEnum() || !alpha.isEnum() || !version.isEnum()) {
            throw new IllegalStateException("official composition/version enum shape differs");
        }
        return new CompositionAccess(mesh, color, alpha,
            exactMethod(source, "getAllArtMeshes", List.class, false),
            exactMethod(drawable, "getGuid", guid, false),
            exactMethod(mesh, "getColorComposition", color, false),
            exactMethod(mesh, "getAlphaComposition", alpha, false),
            exactMethod(source, "getTargetVersion", version, false),
            exactMethod(version, "a", int.class, false));
    }

    static String exactEnumName(final Object value, final Class<?> type) {
        if (value == null || value.getClass() != type || !(value instanceof Enum<?> entry)) {
            throw new IllegalStateException("official enum value is unavailable or unverified");
        }
        return entry.name();
    }

    static Object uniqueRawImage(final List<?> wrappers, final Class<?> wrapperClass,
        final Method imageGetter, final Method guidGetter, final Method guidString,
        final String expectedGuid) throws Exception {
        Object found = null;
        for (final Object wrapper : wrappers) {
            if (wrapper == null || wrapper.getClass() != wrapperClass) {
                throw new IllegalStateException("official raw wrapper shape is unknown");
            }
            final Object image = imageGetter.invoke(wrapper);
            if (image == null) throw new IllegalStateException("official raw image is missing");
            final String guid = (String) guidString.invoke(guidGetter.invoke(image));
            if (expectedGuid.equals(guid)) {
                if (found != null) throw new IllegalStateException("official raw GUID is ambiguous");
                found = image;
            }
        }
        if (found == null) throw new IllegalStateException("official raw GUID is missing");
        return found;
    }

    public ReplacementResult run(final ReplacementRequest request) throws Exception {
        final ValidatedSource source = validateTaskSource(request);
        final HostAccess host = preflightOfficialHost();
        final long deadline = deadline(source.timeoutMillis());
        final AtomicBoolean live = new AtomicBoolean(true);
        final AtomicBoolean commandStarted = new AtomicBoolean(false);
        final AtomicBoolean commandReturned = new AtomicBoolean(false);
        final AtomicInteger commandCalls = new AtomicInteger();
        final AtomicReference<Throwable> commandFailure = new AtomicReference<>();

        try {
            final EdtCall<Void> initial = invokeEdtBounded(() -> {
                verifyWriteContext(host, request, deadline, live);
                return null;
            }, deadline, live);
            if (!initial.completed()) throw new IllegalStateException(
                "official PSD replacement initial EDT identity check timed out");
            if (initial.failure() != null) throw asException(initial.failure());

            scheduleCommandOpen(host, request, source, deadline, live, commandStarted,
                commandReturned, commandCalls, commandFailure);

            boolean modelConfirmed = false;
            boolean rawConfirmed = false;
            while (true) {
                checkWorker(live, deadline, commandFailure);
                if (commandReturned.get() && modelConfirmed && rawConfirmed) {
                    return new ReplacementResult(source.path(), source.sha256(),
                        request.documentId(), request.modelId(), request.rawGuid(),
                        request.boundWindow(), true, modelConfirmed, rawConfirmed,
                        commandCalls.get(), Map.of(
                            "source.path", source.path().toString(),
                            "source.sha256", source.sha256(),
                            "host.jar.sha256", host.sha256(),
                            "host.codeSource", host.artifact().toString(),
                            "command", "CEAppCtrl.command_open(File,true)",
                            "command.invocations", Integer.toString(commandCalls.get()),
                            "replacementApplied", "UNAVAILABLE: caller SDK gate required"));
                }
                if (modelConfirmed && rawConfirmed) {
                    // Native import is now allowed to change the current raw. No further
                    // chooser action or pre-replacement raw identity read is appropriate.
                    sleepPoll(deadline);
                    continue;
                }
                final Stage stage = modelConfirmed ? Stage.RAW : Stage.MODEL;
                final EdtCall<Progress> call = invokeEdtBounded(
                    () -> inspectAndChooseOnEdt(host, request, source, deadline, live,
                        commandStarted, stage),
                    deadline, live);
                if (!call.completed()) {
                    throw new IllegalStateException("official PSD replacement chooser EDT timed out");
                }
                if (call.failure() != null) throw asException(call.failure());
                final Progress progress = call.value();
                if (progress != null) {
                    modelConfirmed |= progress.modelConfirmed();
                    rawConfirmed |= progress.rawConfirmed();
                }
                if (commandReturned.get() && modelConfirmed && rawConfirmed) continue;
                sleepPoll(deadline);
            }
        } catch (Throwable failure) {
            live.set(false);
            if (failure instanceof Error error) throw error;
            if (failure instanceof Exception exception) throw exception;
            throw new IllegalStateException(failure);
        } finally {
            live.set(false);
        }
    }

    private void scheduleCommandOpen(final HostAccess host, final ReplacementRequest request,
        final ValidatedSource source, final long deadline, final AtomicBoolean live,
        final AtomicBoolean commandStarted, final AtomicBoolean commandReturned,
        final AtomicInteger commandCalls, final AtomicReference<Throwable> commandFailure) {
        try {
            SwingUtilities.invokeLater(() -> {
                if (!writeActionOpen(live, deadline)) return;
                try {
                    verifyWriteContext(host, request, deadline, live);
                    if (!commandCalls.compareAndSet(0, 1)) {
                        throw new IllegalStateException("official PSD command_open was scheduled twice");
                    }
                    commandStarted.set(true);
                    final Object app = host.appInstance().invoke(null);
                    if (app == null) throw new IllegalStateException(
                        "official CEAppCtrl instance is unavailable");
                    if (!writeActionOpen(live, deadline)) return;
                    // command_open is the only native/UI entry point issued by this helper.
                    host.commandOpen().invoke(app, source.path().toFile(), true);
                    commandReturned.set(true);
                } catch (Throwable failure) {
                    commandFailure.set(failure);
                    live.set(false);
                }
            });
        } catch (RuntimeException failure) {
            commandFailure.set(failure);
            live.set(false);
        }
    }

    private Progress inspectAndChooseOnEdt(final HostAccess host,
        final ReplacementRequest request, final ValidatedSource source, final long deadline,
        final AtomicBoolean live, final AtomicBoolean commandStarted, final Stage stage)
        throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "official PSD chooser inspection must run on EDT");
        verifyWriteContext(host, request, deadline, live);
        if (!commandStarted.get()) return Progress.none();

        final List<DialogCandidate> model = new ArrayList<>();
        final List<DialogCandidate> raw = new ArrayList<>();
        for (final Window candidateWindow : Window.getWindows()) {
            if (!(candidateWindow instanceof Dialog dialog)
                || !dialog.isShowing() || !dialog.isDisplayable()) continue;
            final List<JList<?>> lists = exactLists(dialog, host.listClass());
            final boolean isModel = hasRenderer(lists, host.modelRenderer());
            final boolean isRaw = hasRenderer(lists, host.rawRenderer());
            // Official progress/startup windows may coexist with a chooser. Only the
            // reviewed chooser renderer admits any action; other dialogs remain untouched.
            if (!isModel && !isRaw) continue;
            if (isModel && isRaw) throw new IllegalStateException(
                "official PSD chooser renderer is ambiguous");
            if (dialog.getOwner() != request.boundWindow()) {
                throw new IllegalStateException("official PSD chooser has a wrong owner");
            }
            final DialogCandidate observed = inspectDialog(host, dialog, lists,
                isModel ? Stage.MODEL : Stage.RAW);
            (isModel ? model : raw).add(observed);
        }
        if (model.size() > 1 || raw.size() > 1 || (!model.isEmpty() && !raw.isEmpty())) {
            throw new IllegalStateException("multiple official PSD chooser dialogs are visible");
        }
        if (stage == Stage.MODEL) {
            if (!raw.isEmpty()) throw new IllegalStateException(
                "raw chooser appeared before the model chooser was confirmed");
            if (model.isEmpty()) return Progress.none();
            return chooseModelOnEdt(host, request, deadline, live, model.get(0));
        }
        if (!model.isEmpty()) return Progress.none();
        if (raw.isEmpty()) return Progress.none();
        return chooseRawOnEdt(host, request, deadline, live, raw.get(0));
    }

    private DialogCandidate inspectDialog(final HostAccess host, final Dialog dialog,
        final List<JList<?>> lists, final Stage stage) throws Exception {
        if (lists.size() != 1) throw new IllegalStateException(
            "official PSD chooser list is ambiguous");
        final String title = stage == Stage.MODEL ? host.modelTitle() : host.rawTitle();
        final String message = stage == Stage.MODEL ? host.modelMessage() : host.rawMessage();
        if (!containsText(dialog, title) || !containsText(dialog, message)) {
            throw new IllegalStateException("official PSD chooser title/message is not exact");
        }
        final JList<?> list = lists.get(0);
        final Class<?> renderer = stage == Stage.MODEL ? host.modelRenderer() : host.rawRenderer();
        if (list.getCellRenderer() == null || list.getCellRenderer().getClass() != renderer) {
            throw new IllegalStateException("official PSD chooser renderer is not exact");
        }
        final Class<?> option = stage == Stage.MODEL ? host.modelOption() : host.rawOption();
        final List<Object> options = new ArrayList<>();
        for (int index = 0; index < list.getModel().getSize(); index++) {
            final Object value = list.getModel().getElementAt(index);
            if (value == null || value.getClass() != option) throw new IllegalStateException(
                "official PSD chooser contains an unknown option");
            options.add(value);
        }
        final AbstractButton confirmation = confirmation(dialog, host);
        return new DialogCandidate(dialog, list, List.copyOf(options), confirmation,
            stage, dialog.getOwner());
    }

    private Progress chooseModelOnEdt(final HostAccess host, final ReplacementRequest request,
        final long deadline, final AtomicBoolean live, final DialogCandidate candidate)
        throws Exception {
        final int targetIndex = uniqueIdentityIndex(candidate.options(), host.modelOption(),
            request.expectedCModelingDocument(), host.modelGetter());
        return executeChooserOnEdt(host, request, deadline, live, candidate, targetIndex,
            request.expectedCModelingDocument(), true);
    }

    private Progress chooseRawOnEdt(final HostAccess host, final ReplacementRequest request,
        final long deadline, final AtomicBoolean live, final DialogCandidate candidate)
        throws Exception {
        final int targetIndex = uniqueRawIndex(candidate.options(), host, request);
        return executeChooserOnEdt(host, request, deadline, live, candidate, targetIndex,
            request.expectedCLayeredImage(), false);
    }

    private Progress executeChooserOnEdt(final HostAccess host,
        final ReplacementRequest request, final long deadline, final AtomicBoolean live,
        final DialogCandidate candidate, final int targetIndex, final Object expectedTarget,
        final boolean model) throws Exception {
        // The list contains a$a/a$b wrappers. The native document/raw returned by a()
        // identifies the target, but is never itself a selectable list element.
        final Object expectedOption = candidate.options().get(targetIndex);
        final ChooserObservation observation = new ChooserObservation(
            candidate.owner(), candidate.dialog().getOwner(), candidate.dialog(), candidate.list(),
            candidate.list().getClass(), candidate.list().getCellRenderer().getClass(),
            candidate.optionClass(), candidate.options(), candidate.confirmation().getClass(),
            candidate.confirmation().getAction() == null ? null
                : candidate.confirmation().getAction().getClass(),
            candidate.confirmation().getAction() == null ? null
                : String.valueOf(candidate.confirmation().getAction().getValue(Action.NAME)),
            candidate.confirmation().isEnabled(), candidate.confirmation().isShowing(),
            candidate.confirmation().isDisplayable());
        final AtomicReference<String> guardFailure = new AtomicReference<>();
        final ChooserActionResult action = executeChooserGate(observation,
            new ChooserShape(host.listClass(), candidate.rendererClass(), candidate.optionClass(),
                host.hostButton(), host.hostButtonSubclass(), host.actionClass()),
            targetIndex, expectedOption, () -> {
                try {
                    verifyWriteContext(host, request, deadline, live);
                    if (candidate.dialog() != observation.dialog()
                        || candidate.list() != observation.list()
                        || !candidate.dialog().isShowing()
                        || candidate.dialog().getOwner() != request.boundWindow()) {
                        throw new IllegalStateException("official PSD chooser identity changed");
                    }
                    final DialogCandidate fresh = inspectDialog(host, candidate.dialog(),
                        exactLists(candidate.dialog(), host.listClass()), candidate.stage());
                    if (fresh.list() != candidate.list()
                        || fresh.confirmation() != candidate.confirmation()
                        || !sameOptions(fresh.options(), candidate.options())) {
                        throw new IllegalStateException("official PSD chooser options/action changed");
                    }
                    final int freshIndex = model
                        ? uniqueIdentityIndex(fresh.options(), host.modelOption(),
                            expectedTarget, host.modelGetter())
                        : uniqueRawIndex(fresh.options(), host, request);
                    if (freshIndex != targetIndex) throw new IllegalStateException(
                        "official PSD chooser target changed");
                    return true;
                } catch (Throwable failure) {
                    guardFailure.set(failure.getMessage() == null
                        ? failure.toString() : failure.getMessage());
                    return false;
                }
            }, () -> !stopped.getAsBoolean(), () -> taskBound.getAsBoolean(),
            () -> actionOpen(live, deadline), new ChooserActions() {
                @Override public boolean select(final int index) {
                    return selectExactOption(candidate.list(), candidate.options(), index);
                }

                @Override public void confirm() {
                    candidate.confirmation().doClick();
                }
            });
        if (!action.accepted()) {
            final String suffix = guardFailure.get();
            throw new IllegalStateException(action.diagnostic()
                + (suffix == null ? "" : ": " + suffix));
        }
        return model ? new Progress(true, false) : new Progress(false, true);
    }

    private static boolean sameOptions(final List<?> left, final List<?> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) return false;
        }
        return true;
    }

    /** Uses the actual Swing option object; selection listeners may change the list. */
    static boolean selectExactOption(final JList<?> list, final List<?> observed,
        final int index) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "official chooser selection requires EDT");
        if (index < 0 || index >= observed.size() || list.getModel().getSize() != observed.size()
            || list.getModel().getElementAt(index) != observed.get(index)) return false;
        list.setSelectedIndex(index);
        return list.getSelectedIndex() == index && list.getSelectedValue() == observed.get(index);
    }

    private void verifyWriteContext(final HostAccess host, final ReplacementRequest request,
        final long deadline, final AtomicBoolean live) throws Exception {
        if (!writeActionOpen(live, deadline)) throw new IllegalStateException(
            "official PSD replacement action is stopped, task-unbound, or expired");
        final Window current = currentWindowOnEdt(host);
        if (current != request.boundWindow()) throw new IllegalStateException(
            "official PSD replacement bound window identity changed");
        final IdentityObservation observed = request.identityReader().observe();
        if (observed == null || !sameIdentity(request, observed)) throw new IllegalStateException(
            "official PSD replacement document/model/raw identity changed");
        if (!writeActionOpen(live, deadline)) throw new IllegalStateException(
            "official PSD replacement action expired before UI write");
    }

    private static boolean sameIdentity(final ReplacementRequest request,
        final IdentityObservation observed) {
        return request.documentId().equals(observed.documentId())
            && request.modelId().equals(observed.modelId())
            && request.rawGuid().equals(observed.rawGuid())
            && request.expectedCModelingDocument() == observed.modelingDocument()
            && request.expectedCLayeredImage() == observed.layeredImage();
    }

    private Window currentWindowOnEdt(final HostAccess host) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "official main-frame observation must run on EDT");
        final Object app = host.appInstance().invoke(null);
        if (app == null) return nullWindow("official CEAppCtrl instance is unavailable");
        final Object frameController = host.mainFrame().invoke(app);
        if (frameController == null) return nullWindow("official main-frame controller is unavailable");
        final Object cFrame = host.cFrameGetter().invoke(frameController);
        if (cFrame == null) return nullWindow("official CFrame is unavailable");
        final Window window = (Window) host.swingWindow().invoke(cFrame);
        final JFrame frame = (JFrame) host.swingFrame().invoke(cFrame);
        if (window == null || frame == null || window != frame
            || !window.isShowing() || !window.isDisplayable()) {
            return nullWindow("official main-frame Swing window is not showing/displayable");
        }
        return window;
    }

    private static Window nullWindow(final String diagnostic) {
        throw new IllegalStateException(diagnostic);
    }

    private HostAccess preflightOfficialHost() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "official host shape preflight must run off EDT");
        final Class<?> app = loadHostApplication();
        final ClassLoader loader = app.getClassLoader();
        if (loader == null) throw new IOException("official host defining loader is unavailable");
        final Path artifact = codeSourcePath(app);
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("official host code source is not a JAR");
        }
        final String sha256 = sha256(artifact);
        if (!HOST_JAR_SHA256.equals(sha256)) throw new IOException(
            "official host JAR SHA-256 mismatch: " + sha256);

        final Class<?> mainFrame = loadExact(loader, MAIN_FRAME_CONTROLLER);
        final Class<?> cFrame = loadExact(loader, C_FRAME);
        final Class<?> windowBase = loadExact(loader, WINDOW_BASE);
        final Class<?> dialog = loadExact(loader, DIALOG);
        final Class<?> modelDocument = loadExact(loader, MODEL_DOCUMENT);
        final Class<?> layeredImage = loadExact(loader, LAYERED_IMAGE);
        final Class<?> layeredImageGuid = loadExact(loader, LAYERED_IMAGE_GUID);
        final Class<?> guid = loadExact(loader, GUID);
        final Class<?> modelOption = loadExact(loader, MODEL_OPTION);
        final Class<?> rawOption = loadExact(loader, RAW_OPTION);
        final Class<?> modelRenderer = loadExact(loader, MODEL_RENDERER);
        final Class<?> rawRenderer = loadExact(loader, RAW_RENDERER);
        final Class<?> list = loadExact(loader, HOST_LIST);
        final Class<?> button = loadExact(loader, HOST_BUTTON);
        final Class<?> buttonSubclass = loadExact(loader, HOST_BUTTON_SUBCLASS);
        final Class<?> action = loadExact(loader, HOST_ACTION);
        final Class<?> localizer = loadExact(loader, LOCALIZER);
        for (final Class<?> type : List.of(app, mainFrame, cFrame, windowBase, dialog,
            modelDocument, layeredImage, layeredImageGuid, guid, modelOption, rawOption,
            modelRenderer, rawRenderer, list, button, buttonSubclass, action, localizer)) {
            verifyClassArtifact(type, loader, artifact);
        }
        if (!JList.class.isAssignableFrom(list)
            || !javax.swing.ListCellRenderer.class.isAssignableFrom(modelRenderer)
            || !javax.swing.ListCellRenderer.class.isAssignableFrom(rawRenderer)
            || !AbstractButton.class.isAssignableFrom(button)
            || !button.isAssignableFrom(buttonSubclass)) {
            throw new IllegalArgumentException("official PSD Swing shape is not exact");
        }
        final Method appInstance = exactMethod(app, "access$get_instance$cp", app, true);
        final Method commandOpen = exactMethod(app, "command_open", void.class, false,
            java.io.File.class, boolean.class);
        final Method frameGetter = exactMethod(app, "getMainFrameCtrl", mainFrame, false);
        final Method cFrameGetter = exactMethod(mainFrame, "getMainFrame", cFrame, false);
        final Method swingWindow = exactPublicMethod(cFrame, "getJWindow", Window.class,
            false, windowBase);
        final Method swingFrame = exactMethod(cFrame, "getJFrame", JFrame.class, false);
        final Method modelGetter = exactMethod(modelOption, "a", modelDocument, false);
        final Method rawGetter = exactMethod(rawOption, "a", layeredImage, false);
        final Method rawGuidGetter = exactMethod(layeredImage, "getGuid", layeredImageGuid, false);
        final Method guidStringGetter = exactMethod(guid, "getUuidString", String.class, false);
        exactMethod(modelOption, "b", String.class, false);
        exactMethod(rawOption, "b", String.class, false);
        final Field localizerInstance = localizer.getDeclaredField("a");
        if (!Modifier.isPublic(localizerInstance.getModifiers())
            || !Modifier.isStatic(localizerInstance.getModifiers())
            || !Modifier.isFinal(localizerInstance.getModifiers())
            || localizerInstance.getType() != localizer
            || !localizerInstance.trySetAccessible()) {
            throw new IllegalArgumentException("official localizer field shape is not exact");
        }
        final Method localize = exactMethod(localizer, "a", String.class, false,
            String.class, String[].class);
        final Object localizerObject = localizerInstance.get(null);
        final String modelTitle = localized(localize, localizerObject, MODEL_TITLE_KEY);
        final String modelMessage = localized(localize, localizerObject, MODEL_MESSAGE_KEY);
        final String rawTitle = localized(localize, localizerObject, RAW_TITLE_KEY);
        final String rawMessage = localized(localize, localizerObject, RAW_MESSAGE_KEY);
        if (modelTitle.isBlank() || modelMessage.isBlank() || rawTitle.isBlank()
            || rawMessage.isBlank()) throw new IllegalArgumentException(
            "official PSD chooser localization is unavailable");
        return new HostAccess(loader, artifact, sha256, app, appInstance, commandOpen,
            frameGetter, cFrameGetter, swingWindow, swingFrame, dialog, modelOption, rawOption,
            modelGetter, rawGetter, rawGuidGetter, guidStringGetter, modelRenderer, rawRenderer,
            list, button, buttonSubclass, action, modelTitle, modelMessage, rawTitle, rawMessage);
    }

    /** Package-private shape entry used by the independent offline test. */
    static HostShapeSummary officialJarShapeForTest() throws Exception {
        final HostAccess host = new OfficialPsdReplacementBaseline(
            new ShapeOnlyContext(), () -> false, () -> true).preflightOfficialHost();
        preflightTargetAccess(host);
        preflightCompositionAccess(host);
        return new HostShapeSummary(host.artifact().toString(), host.sha256(),
            host.commandOpen().toGenericString(), host.modelGetter().toGenericString(),
            host.rawGetter().toGenericString(), host.rawGuidGetter().toGenericString());
    }

    /** Small context implementation used only by the shape method; no host state is queried. */
    private static final class ShapeOnlyContext implements PluginContext {
        @Override public dev.turboism.sdk.plugin.PluginDescriptor descriptor() { return null; }
        @Override public dev.turboism.sdk.plugin.PluginLogger logger() { return null; }
        @Override public dev.turboism.sdk.plugin.PluginPaths paths() { return null; }
        @Override public dev.turboism.sdk.cubism.CubismFacade cubism() { return null; }
        @Override public java.util.List<dev.turboism.sdk.permission.PluginPermission> permissions() {
            return List.of();
        }
        @Override public dev.turboism.sdk.event.EventBus eventBus() { return null; }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() { return null; }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() { return null; }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() { return null; }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() { return null; }
        @Override public dev.turboism.sdk.plugin.DisposableScope disposableScope() { return null; }
    }

    static int uniqueIdentityIndex(final List<?> options, final Class<?> optionClass,
        final Object expected, final Method getter) throws Exception {
        if (expected == null || options == null || options.isEmpty()) throw new IllegalArgumentException(
            "official chooser target is unavailable");
        int match = -1;
        for (int index = 0; index < options.size(); index++) {
            final Object option = options.get(index);
            if (option == null || option.getClass() != optionClass) throw new IllegalArgumentException(
                "official chooser option class is unknown");
            if (getter.invoke(option) == expected) {
                if (match >= 0) throw new IllegalArgumentException(
                    "official chooser target option is duplicated");
                match = index;
            }
        }
        if (match < 0) throw new IllegalArgumentException(
            "official chooser target option is missing");
        return match;
    }

    static int uniqueRawIndex(final List<?> options, final HostAccess host,
        final ReplacementRequest request) throws Exception {
        return uniqueRawIndex(options, host.rawOption(), request.expectedCLayeredImage(),
            request.rawGuid(), image -> {
                try {
                    return host.rawGetter().invoke(image);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            }, image -> {
                try {
                    return rawGuid(host, image);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
    }

    static int uniqueRawIndexForTest(final List<?> options, final Class<?> optionClass,
        final Object expectedImage, final String expectedGuid,
        final java.util.function.Function<Object, Object> imageGetter,
        final java.util.function.Function<Object, String> guidGetter) {
        return uniqueRawIndex(options, optionClass, expectedImage, expectedGuid, imageGetter,
            guidGetter);
    }

    private static int uniqueRawIndex(final List<?> options, final Class<?> optionClass,
        final Object expectedImage, final String expectedGuid,
        final java.util.function.Function<Object, Object> imageGetter,
        final java.util.function.Function<Object, String> guidGetter) {
        if (expectedImage == null || expectedGuid == null || expectedGuid.isBlank()) {
            throw new IllegalArgumentException("official raw chooser target is unavailable");
        }
        int target = -1;
        int guidMatches = 0;
        for (int index = 0; index < options.size(); index++) {
            final Object option = options.get(index);
            if (option == null || option.getClass() != optionClass) throw new IllegalArgumentException(
                "official raw chooser option class is unknown");
            final Object image = imageGetter.apply(option);
            // The two official add/new-image entries are intentionally ignored here.  They are
            // never selectable for this baseline because their a() value is null.
            if (image == null) continue;
            final String guid = guidGetter.apply(image);
            if (guid == null || guid.isBlank()) throw new IllegalArgumentException(
                "official raw chooser GUID is unavailable");
            if (expectedGuid.equals(guid)) guidMatches++;
            if (image == expectedImage) {
                if (target >= 0) throw new IllegalArgumentException(
                    "official raw chooser target option is duplicated");
                target = index;
            }
        }
        if (target < 0 || guidMatches != 1) throw new IllegalArgumentException(
            target < 0 ? "official raw chooser target option is missing"
                : "official raw chooser target GUID is missing or duplicated");
        if (!expectedGuid.equals(guidGetter.apply(expectedImage))) throw new IllegalArgumentException(
            "official raw chooser target GUID does not match the requested raw");
        return target;
    }

    private static String rawGuid(final HostAccess host, final Object image) throws Exception {
        final Object guid = host.rawGuidGetter().invoke(image);
        if (guid == null) throw new IllegalArgumentException("official raw chooser GUID is unavailable");
        final Object value = host.guidStringGetter().invoke(guid);
        if (!(value instanceof String string) || string.isBlank()) throw new IllegalArgumentException(
            "official raw chooser GUID is unavailable");
        return string;
    }

    /** Production action boundary, also used directly by the focused tests. */
    static ChooserActionResult executeChooserGate(final ChooserObservation observation,
        final ChooserShape expected, final int targetIndex, final Object expectedTarget,
        final BooleanSupplier contextOpen, final BooleanSupplier notStopped,
        final BooleanSupplier taskBound, final BooleanSupplier deadlineOpen,
        final ChooserActions actions) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(contextOpen, "contextOpen");
        Objects.requireNonNull(notStopped, "notStopped");
        Objects.requireNonNull(taskBound, "taskBound");
        Objects.requireNonNull(deadlineOpen, "deadlineOpen");
        Objects.requireNonNull(actions, "actions");
        final String shapeFailure = chooserShapeFailure(observation, expected, targetIndex,
            expectedTarget);
        if (!shapeFailure.isEmpty()) return ChooserActionResult.rejected(shapeFailure);
        if (!gateOpen(contextOpen, notStopped, taskBound, deadlineOpen)) {
            return ChooserActionResult.rejected("chooser action was stopped, unbound, or expired");
        }
        try {
            if (!gateOpen(contextOpen, notStopped, taskBound, deadlineOpen)) {
                return ChooserActionResult.rejected("chooser selection was stopped or expired");
            }
            if (!actions.select(targetIndex)) return ChooserActionResult.rejected(
                "official chooser target was not selected");
            if (!gateOpen(contextOpen, notStopped, taskBound, deadlineOpen)) {
                return ChooserActionResult.rejected("chooser confirmation was stopped or expired");
            }
            actions.confirm();
            return ChooserActionResult.acceptedResult();
        } catch (RuntimeException failure) {
            return ChooserActionResult.rejected("official chooser action failed: " + failure);
        }
    }

    private static boolean gateOpen(final BooleanSupplier contextOpen,
        final BooleanSupplier notStopped, final BooleanSupplier taskBound,
        final BooleanSupplier deadlineOpen) {
        try {
            return contextOpen.getAsBoolean() && notStopped.getAsBoolean()
                && taskBound.getAsBoolean() && deadlineOpen.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String chooserShapeFailure(final ChooserObservation observation,
        final ChooserShape expected, final int targetIndex, final Object expectedTarget) {
        if (observation.currentOwner() == null || observation.dialogOwner() == null
            || observation.currentOwner() != observation.dialogOwner()) return
            "official chooser owner identity is unknown";
        if (observation.listClass() != expected.listClass()
            || observation.rendererClass() != expected.rendererClass()
            || observation.optionClass() != expected.optionClass()) return
            "official chooser list/renderer/option shape is unknown";
        if (observation.options() == null || expectedTarget == null || targetIndex < 0
            || targetIndex >= observation.options().size()
            || observation.options().get(targetIndex) != expectedTarget) return
            "official chooser target option identity is unknown";
        final boolean buttonClass = observation.confirmationClass() != null
            && (observation.confirmationClass() == expected.buttonClass()
                || expected.buttonSubclass().isAssignableFrom(observation.confirmationClass()));
        if (!buttonClass || observation.actionClass() != expected.actionClass()
            || !"OK".equals(observation.actionName()) || !observation.enabled()
            || !observation.showing() || !observation.displayable()) return
            "official chooser confirmation action shape is unknown";
        return "";
    }

    private static AbstractButton confirmation(final Dialog dialog, final HostAccess host) {
        // Isolate matching buttons without relying on their order.  This avoids selecting
        // Cancel or an unrelated JButton.
        final List<AbstractButton> exact = new ArrayList<>();
        for (final AbstractButton button : buttons(dialog)) {
            final Action action = button.getAction();
            final boolean classMatch = button.getClass() == host.hostButton()
                || host.hostButtonSubclass().isAssignableFrom(button.getClass());
            if (classMatch && action != null && action.getClass() == host.actionClass()
                && "OK".equals(action.getValue(Action.NAME))) exact.add(button);
        }
        if (exact.size() != 1) throw new IllegalStateException(
            "official chooser confirmation action is unknown or ambiguous");
        return exact.get(0);
    }

    private static List<AbstractButton> buttons(final Component root) {
        final List<AbstractButton> result = new ArrayList<>();
        collectButtons(root, result);
        return result;
    }

    private static void collectButtons(final Component component,
        final List<AbstractButton> result) {
        if (component instanceof AbstractButton button) result.add(button);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) collectButtons(child, result);
        }
    }

    private static List<JList<?>> exactLists(final Component root, final Class<?> expected) {
        final List<JList<?>> result = new ArrayList<>();
        collectLists(root, expected, result);
        return result;
    }

    private static void collectLists(final Component component, final Class<?> expected,
        final List<JList<?>> result) {
        if (component instanceof JList<?> list && component.getClass() == expected) result.add(list);
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) collectLists(child, expected, result);
        }
    }

    private static boolean hasRenderer(final List<? extends JList<?>> lists,
        final Class<?> expected) {
        return lists.stream().anyMatch(list -> list.getCellRenderer() != null
            && list.getCellRenderer().getClass() == expected);
    }

    private static boolean containsText(final Component root, final String expected) {
        if (root instanceof JLabel label && expected.equals(label.getText())) return true;
        if (root instanceof JTextComponent text && expected.equals(text.getText())) return true;
        if (root instanceof Dialog dialog && expected.equals(dialog.getTitle())) return true;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                if (containsText(child, expected)) return true;
            }
        }
        return false;
    }

    private static ValidatedSource validateTaskSource(final ReplacementRequest request)
        throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.psdPath() == null || request.taskRoot() == null) throw new IllegalArgumentException(
            "task-owned PSD and task root are required");
        if (!request.psdPath().isAbsolute() || !request.taskRoot().isAbsolute()) {
            throw new IllegalArgumentException("task-owned PSD and task root must be absolute");
        }
        requireSha(request.expectedSha256(), "expectedSha256");
        requireText(request.fixtureName(), "fixtureName");
        requireText(request.documentId(), "documentId");
        requireText(request.modelId(), "modelId");
        requireText(request.rawGuid(), "rawGuid");
        if (request.expectedCModelingDocument() == null || request.expectedCLayeredImage() == null
            || request.boundWindow() == null || request.identityReader() == null) {
            throw new IllegalArgumentException("expected document, raw image, window, and identity reader are required");
        }
        if (!request.fixtureName().toLowerCase(java.util.Locale.ROOT).endsWith(".psd")
            || !request.psdPath().getFileName().toString().equals(request.fixtureName())) {
            throw new IllegalArgumentException("task PSD basename is not the fixed fixture name");
        }
        final Path root = request.taskRoot().toAbsolutePath().normalize();
        final Path file = request.psdPath().toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("PSD is outside the caller-provided task root");
        }
        rejectSymlinkPath(root, file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("task PSD is not a regular non-symlink file");
        }
        final long sizeBefore = Files.size(file);
        final String digest = sha256(file);
        final long sizeAfter = Files.size(file);
        if (sizeBefore != sizeAfter || !request.expectedSha256().equals(digest)) {
            throw new IllegalArgumentException("task PSD SHA-256 or size changed during validation");
        }
        return new ValidatedSource(file, digest, request.timeoutMillis());
    }

    /** Package-private pure source gate for the focused test. */
    static ValidatedSource validateTaskSourceForTest(final ReplacementRequest request)
        throws IOException {
        return validateTaskSource(request);
    }

    /** Path-only seam; it still uses the production containment, symlink, and digest gate. */
    static ValidatedSource validateTaskSourceForTest(final Path psdPath, final Path taskRoot,
        final String expectedSha256, final String fixtureName, final long timeoutMillis)
        throws IOException {
        requireSha(expectedSha256, "expectedSha256");
        requireText(fixtureName, "fixtureName");
        if (psdPath == null || taskRoot == null || !psdPath.isAbsolute()
            || !taskRoot.isAbsolute()) throw new IllegalArgumentException(
            "task-owned PSD and task root must be absolute");
        if (!fixtureName.toLowerCase(java.util.Locale.ROOT).endsWith(".psd")
            || !psdPath.getFileName().toString().equals(fixtureName)) {
            throw new IllegalArgumentException("task PSD basename is not the fixed fixture name");
        }
        return validateTaskSourcePath(psdPath, taskRoot, expectedSha256, fixtureName,
            timeoutMillis);
    }

    private static void rejectSymlinkPath(final Path root, final Path file) {
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IllegalArgumentException(
            "task root must not be a symlink");
        try {
            if (!root.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))) {
                throw new IllegalArgumentException(
                    "task root real path differs from the caller-provided path");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("task root real path is unavailable", failure);
        }
        for (Path parent = root.getParent(); parent != null; parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IllegalArgumentException(
                "task root parent must not be a symlink");
        }
        final Path relative = root.relativize(file);
        for (final Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException(
                "task PSD path must not traverse a symlink");
        }
    }

    private static ValidatedSource validateTaskSourcePath(final Path psdPath,
        final Path taskRoot, final String expectedSha256, final String fixtureName,
        final long timeoutMillis) throws IOException {
        final Path root = taskRoot.toAbsolutePath().normalize();
        final Path file = psdPath.toAbsolutePath().normalize();
        if (!file.startsWith(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("PSD is outside the caller-provided task root");
        }
        rejectSymlinkPath(root, file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("task PSD is not a regular non-symlink file");
        }
        final long sizeBefore = Files.size(file);
        final String digest = sha256(file);
        final long sizeAfter = Files.size(file);
        if (sizeBefore != sizeAfter || !expectedSha256.equals(digest)) {
            throw new IllegalArgumentException("task PSD SHA-256 or size changed during validation");
        }
        return new ValidatedSource(file, digest, timeoutMillis);
    }

    private static long deadline(final long timeoutMillis) {
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("timeoutMillis is outside 1s..600s");
        }
        final long now = System.nanoTime();
        final long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        if (Long.MAX_VALUE - now < nanos) return Long.MAX_VALUE;
        return now + nanos;
    }

    private static boolean actionOpen(final AtomicBoolean live, final long deadline) {
        try {
            return live.get() && !Thread.currentThread().isInterrupted()
                && System.nanoTime() < deadline;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean writeActionOpen(final AtomicBoolean live, final long deadline) {
        try {
            return actionOpen(live, deadline) && !stopped.getAsBoolean()
                && taskBound.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void checkWorker(final AtomicBoolean live, final long deadline,
        final AtomicReference<Throwable> commandFailure) throws Exception {
        if (commandFailure.get() != null) throw asException(commandFailure.get());
        if (!live.get() || stopped.getAsBoolean() || !taskBound.getAsBoolean()
            || System.nanoTime() >= deadline) {
            throw new IllegalStateException("official PSD replacement stopped, unbound, or expired");
        }
    }

    private static void sleepPoll(final long deadline) throws InterruptedException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) return;
        Thread.sleep(Math.min(POLL_MILLIS,
            Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))));
    }

    private static <T> EdtCall<T> invokeEdtBounded(final EdtOperation<T> operation,
        final long deadline, final AtomicBoolean live) throws InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!actionOpen(live, deadline)) return EdtCall.timeout();
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
                if (!active.get() || !actionOpen(live, deadline)) {
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
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0L || !done.await(remaining, TimeUnit.NANOSECONDS)) {
            active.set(false);
            return EdtCall.timeout();
        }
        return failure.get() == null ? EdtCall.completed(value.get()) : EdtCall.failed(failure.get());
    }

    private static Class<?> loadHostApplication() throws ClassNotFoundException {
        final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            try {
                return Class.forName(APP_CONTROLLER, false, tccl);
            } catch (ClassNotFoundException ignored) {
                // The exact application loader may be a parent of the plugin TCCL.
            }
        }
        return Class.forName(APP_CONTROLLER, false, ClassLoader.getSystemClassLoader());
    }

    private static Class<?> loadExact(final ClassLoader loader, final String name)
        throws ClassNotFoundException {
        final Class<?> type = Class.forName(name, false, loader);
        if (!name.equals(type.getName()) || type.getClassLoader() != loader) {
            throw new ClassNotFoundException("official class loader/identity mismatch for " + name);
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
            throw new IllegalArgumentException("official method shape is not exact: "
                + owner.getName() + '.' + name);
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
            throw new IllegalArgumentException("official inherited method shape is not exact: "
                + owner.getName() + '.' + name);
        }
        return method;
    }

    private static Path codeSourcePath(final Class<?> type) throws IOException {
        final CodeSource source = type.getProtectionDomain() == null
            ? null : type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) throw new IOException(
            "official code source is unavailable for " + type.getName());
        try {
            return Path.of(source.getLocation().toURI()).toRealPath();
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("official code source is invalid for " + type.getName(), failure);
        }
    }

    private static void verifyClassArtifact(final Class<?> type, final ClassLoader loader,
        final Path artifact) throws IOException {
        if (type.getClassLoader() != loader || !artifact.equals(codeSourcePath(type))) {
            throw new IOException("official class identity/code source mismatch: " + type.getName());
        }
    }

    private static String localized(final Method method, final Object instance,
        final String key) throws Exception {
        final Object value = method.invoke(instance, key, new String[0]);
        if (!(value instanceof String text)) throw new IllegalArgumentException(
            "official localizer returned non-text for " + key);
        return text;
    }

    private static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireSha(final String value, final String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(
            name + " must be lowercase SHA-256");
    }

    private static void requireText(final String value, final String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(
            name + " must not be blank");
    }

    private static Exception asException(final Throwable failure) throws Error {
        final Throwable cause = failure instanceof InvocationTargetException invocation
            ? invocation.getCause() : failure;
        if (cause instanceof Error error) throw error;
        return cause instanceof Exception exception ? exception : new IllegalStateException(cause);
    }

    public record ReplacementRequest(Path psdPath, Path taskRoot, String expectedSha256,
        String fixtureName, String documentId, String modelId, String rawGuid,
        Object expectedCModelingDocument, Object expectedCLayeredImage, Window boundWindow,
        IdentityReader identityReader, long timeoutMillis) { }

    @FunctionalInterface
    public interface IdentityReader {
        IdentityObservation observe() throws Exception;
    }

    public record IdentityObservation(String documentId, String modelId, String rawGuid,
        Object modelingDocument, Object layeredImage) { }

    @FunctionalInterface
    public interface CompositionReader {
        CompositionObservation observe() throws Exception;
    }

    public record Composition(String guid, String color, String alpha) { }

    public record CompositionObservation(String documentId, String modelId, int targetVersion,
        Map<String, Composition> meshes) { }

    public record ReplacementResult(Path sourcePath, String sourceSha256, String documentId,
        String modelId, String rawGuid, Window window, boolean chooserOperationComplete,
        boolean modelChooserConfirmed, boolean rawChooserConfirmed, int commandOpenInvocations,
        Map<String, String> evidence) { }

    record ValidatedSource(Path path, String sha256, long timeoutMillis) { }

    static record HostShapeSummary(String artifact, String sha256, String commandOpenShape,
        String modelOptionShape, String rawOptionShape, String rawGuidShape) { }

    static record ChooserShape(Class<?> listClass, Class<?> rendererClass, Class<?> optionClass,
        Class<?> buttonClass, Class<?> buttonSubclass, Class<?> actionClass) { }

    static record ChooserObservation(Object currentOwner, Object dialogOwner, Object dialog,
        Object list, Class<?> listClass, Class<?> rendererClass, Class<?> optionClass,
        List<?> options, Class<?> confirmationClass, Class<?> actionClass, String actionName,
        boolean enabled, boolean showing, boolean displayable) { }

    interface ChooserActions {
        boolean select(int index);
        void confirm();
    }

    static record ChooserActionResult(boolean accepted, String diagnostic) {
        static ChooserActionResult acceptedResult() {
            return new ChooserActionResult(true, "official chooser action completed");
        }

        static ChooserActionResult rejected(final String diagnostic) {
            return new ChooserActionResult(false, diagnostic);
        }
    }

    private enum Stage { MODEL, RAW }

    private record Progress(boolean modelConfirmed, boolean rawConfirmed) {
        static Progress none() { return new Progress(false, false); }
    }

    private record DialogCandidate(Dialog dialog, JList<?> list, List<Object> options,
        AbstractButton confirmation, Stage stage, Object owner) {
        Class<?> optionClass() { return options.isEmpty() ? Object.class : options.get(0).getClass(); }
        Class<?> rendererClass() { return list.getCellRenderer().getClass(); }
    }

    private record HostAccess(ClassLoader loader, Path artifact, String sha256, Class<?> app,
        Method appInstance, Method commandOpen, Method mainFrame, Method cFrameGetter,
        Method swingWindow, Method swingFrame, Class<?> dialogClass, Class<?> modelOption,
        Class<?> rawOption, Method modelGetter, Method rawGetter, Method rawGuidGetter,
        Method guidStringGetter, Class<?> modelRenderer, Class<?> rawRenderer, Class<?> listClass,
        Class<?> hostButton, Class<?> hostButtonSubclass, Class<?> actionClass,
        String modelTitle, String modelMessage, String rawTitle, String rawMessage) { }

    private record TargetAccess(Class<?> documentClass, Class<?> wrapperClass,
        Method currentDocument, Method modelSource, Method modelGuid, Method textureManager,
        Method rawImages, Method wrapperImage) { }

    private record CompositionAccess(Class<?> meshClass, Class<?> colorClass, Class<?> alphaClass,
        Method allMeshes, Method meshGuid, Method color, Method alpha, Method version,
        Method versionNumber) { }

    private record EdtCall<T>(boolean completed, T value, Throwable failure) {
        static <T> EdtCall<T> completed(final T value) { return new EdtCall<>(true, value, null); }
        static <T> EdtCall<T> failed(final Throwable failure) {
            return new EdtCall<>(true, null, failure);
        }
        static <T> EdtCall<T> timeout() { return new EdtCall<>(false, null, null); }
    }

    @FunctionalInterface
    private interface EdtOperation<T> { T call() throws Exception; }
}
