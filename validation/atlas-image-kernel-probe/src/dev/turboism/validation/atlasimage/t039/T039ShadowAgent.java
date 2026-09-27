package dev.turboism.validation.atlasimage.t039;

import dev.turboism.validation.atlasimage.t035.T039ShadowPatchBridge;
import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validation-only, no-argument premain for a future 5303 shadow observation.
 * It is one-shot, non-retransformable, bounded, and never writes a file.
 */
public final class T039ShadowAgent {
    public static final String PROPERTY_PREFIX = "turboism.validation.t039.";
    public static final String OFFICIAL_PROFILE = "5303";
    public static final String OWNED_PROFILE = "owned";

    private static final String OFFICIAL_JAR =
        System.getProperty("user.home")
            + "/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03"
            + "/app/lib/Live2D_Cubism.jar";
    private static final String OFFICIAL_JAR_SHA256 =
        "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166";
    private static final String OFFICIAL_INTERNAL_NAME = "com/live2d/util/f/g";
    private static final String OFFICIAL_METHOD = "a";
    private static final String OFFICIAL_CLASS_SHA256 =
        "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6";
    private static final String OFFICIAL_SHAPE_SHA256 =
        "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f";
    private static final String OFFICIAL_DESCRIPTOR = "(II[III[IIIII)V";
    private static final String OWNED_INTERNAL_NAME =
        "dev/turboism/validation/atlasimage/t035/T035OwnedAtlasFixture";
    private static final String OWNED_METHOD = "render";
    private static final String OWNED_DESCRIPTOR = "(II[III[IIIII)V";
    private static final int MAX_SESSION_EVENTS = 64;
    private static final String DATA_ONLY_MODE = "data-only";
    private static final String SHADOW_READY_MODE = "shadow-ready";
    private static final String OWNED_MODE = "owned";
    private static final String SHADOW_OPT_IN_TOKEN = "T039_SHADOW_EXPLICIT_OPT_IN";
    private static final String[] PRIVATE_ASM_RUNTIME_CLASSES = {
        "dev.turboism.validation.atlasimage.shaded.asm97.ClassReader",
        "dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor",
        "dev.turboism.validation.atlasimage.shaded.asm97.ClassWriter",
        "dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor",
        "dev.turboism.validation.atlasimage.shaded.asm97.Opcodes",
        "dev.turboism.validation.atlasimage.shaded.asm97.Label"
    };


    @FunctionalInterface
    private interface TransformerRemover {
        boolean remove(Instrumentation instrumentation, ClassFileTransformer transformer);
    }

    /** The JDK Instrumentation API returns the removal result directly. */
    private static final TransformerRemover DEFAULT_REMOVER =
        Instrumentation::removeTransformer;
    private static volatile Session session;

    private T039ShadowAgent() {
    }

    /** The agent intentionally accepts no agent argument; all input is named JVM properties. */
    public static void premain(final String agentArgs, final Instrumentation instrumentation) {
        final Session next = new Session();
        session = next;
        next.arm(agentArgs, instrumentation);
    }

    public static Snapshot snapshot() {
        final Session current = session;
        return current == null ? Snapshot.unarmed() : current.snapshot();
    }

    /**
     * Freezes one eligible shadow run into an immutable scalar-only capture.
     * No file, class definition, host call, or array retention occurs here.
     */
    public static Map<String, String> freezeCapture(final String expectedRunId) {
        final Session current = session;
        if (current == null) {
            throw new IllegalStateException("T040 freeze rejected: session not armed");
        }
        return current.freezeCapture(expectedRunId);
    }

    /** Small pure gate used by the no-agent negative harness. */
    static boolean targetNameAlreadyLoaded(final String binaryName, final Class<?>[] loaded) {
        if (binaryName == null || loaded == null) {
            return false;
        }
        for (final Class<?> value : loaded) {
            if (value != null && binaryName.equals(value.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Reads and patches only the official 5303 bytes in memory; it never defines them. */
    static OfficialDataCheck checkOfficialDataOnly() throws IOException {
        final byte[] official = T039ShadowPatchBridge.official5303Bytes();
        final T039ShadowPatchBridge.OfficialResult result =
            T039ShadowPatchBridge.patchOfficial5303(
                official,
                T039ShadowPatchBridge.SHADOW_HELPER_OWNER,
                T039ShadowPatchBridge.BOUNDS_DESCRIPTOR
            );
        if (!result.accepted() || result.commonSuperQueries() != 0
                || result.candidate() == official) {
            throw new IllegalStateException("official T039 data-only patch was rejected");
        }
        return new OfficialDataCheck(
            OFFICIAL_JAR, OFFICIAL_JAR_SHA256, OFFICIAL_INTERNAL_NAME,
            OFFICIAL_CLASS_SHA256, OFFICIAL_SHAPE_SHA256, result.commonSuperQueries(),
            result.candidate().length
        );
    }

    /**
     * Calls the real Session.transform path on official bytes only. The class is
     * never defined or executed; data-only discards the candidate, while the
     * explicit shadow-ready case returns it to this test method.
     */
    static OfficialModesCheck checkOfficialModes() throws Exception {
        final byte[] original = T039ShadowPatchBridge.official5303Bytes();
        final PropertyState saved = saveSessionProperties();
        final OfficialRuntimeData runtime = createOfficialRuntimeData();
        try {
            final RemovalScenarioResult data = runOfficialScenario(
                DATA_ONLY_MODE, false, original, runtime);
            final RemovalScenarioResult shadow = runOfficialScenario(
                SHADOW_READY_MODE, true, original, runtime);
            final Snapshot dataSnapshot = data.terminal();
            final Snapshot shadowSnapshot = shadow.terminal();
            if (!"REMOVED".equals(dataSnapshot.removalStatus())
                    || dataSnapshot.transformerRegistered()
                    || !"REMOVED".equals(shadowSnapshot.removalStatus())
                    || shadowSnapshot.transformerRegistered()
                    || data.firstReturned()
                    || !shadow.firstReturned()
                    || !data.lateReturnedNull()
                    || !shadow.lateReturnedNull()
                    || !State.DATA_ONLY_VERIFIED.name().equals(dataSnapshot.state())
                    || !State.SHADOW_READY.name().equals(shadowSnapshot.state())
                    || dataSnapshot.candidateCount() != 1
                    || shadowSnapshot.candidateCount() != 1
                    || dataSnapshot.candidateReturnedCount() != 0
                    || shadowSnapshot.candidateReturnedCount() != 1) {
                throw new IllegalStateException("official fake-removal mode gate mismatch");
            }
            return new OfficialModesCheck(
                dataSnapshot.state(), shadowSnapshot.state(), data.firstReturned(),
                shadow.firstReturned(), dataSnapshot.candidateCount(),
                shadowSnapshot.candidateCount(), shadowSnapshot.candidateReturnedCount());
        } finally {
            cleanupOfficialRuntimeData(runtime);
            restoreSessionProperties(saved);
        }
    }

    /**
     * Manual official-bytes-only T040 check. It never defines or executes the
     * official class; the returned map is produced by the real Session path.
     */
    static Map<String, String> checkOfficialFreezeCapture() throws Exception {
        final byte[] original = T039ShadowPatchBridge.official5303Bytes();
        final PropertyState saved = saveSessionProperties();
        final OfficialRuntimeData runtime = createOfficialRuntimeData();
        try {
            final String helperHash = sha256Resource(T039ShadowHelper.class);
            final String t038Hash = sha256Resource(T038ArrayHelper.class);
            System.setProperty(PROPERTY_PREFIX + "profile", OFFICIAL_PROFILE);
            System.setProperty(PROPERTY_PREFIX + "runId", "t040-official");
            System.setProperty(PROPERTY_PREFIX + "sourceBinding",
                T040RuntimeSourceBinding.TARGET_PROTECTION_DOMAIN);
            System.setProperty(PROPERTY_PREFIX + "trustedSourcePaths",
                runtime.source().toString());
            System.setProperty(PROPERTY_PREFIX + "jarSha256", OFFICIAL_JAR_SHA256);
            System.setProperty(PROPERTY_PREFIX + "classSha256", OFFICIAL_CLASS_SHA256);
            System.setProperty(PROPERTY_PREFIX + "shapeSha256", OFFICIAL_SHAPE_SHA256);
            System.setProperty(PROPERTY_PREFIX + "loaderClass", runtime.loader().getClass().getName());
            System.setProperty(PROPERTY_PREFIX + "helperSha256", helperHash);
            System.setProperty(PROPERTY_PREFIX + "t038HelperSha256", t038Hash);
            System.setProperty(PROPERTY_PREFIX + "maxEvents", "16");
            System.setProperty(PROPERTY_PREFIX + "shadowMode", SHADOW_READY_MODE);
            System.setProperty(PROPERTY_PREFIX + "shadowOptIn", SHADOW_OPT_IN_TOKEN);
            final FakeInstrumentation fake = fakeInstrumentation(FakeRemovalMode.TRUE);
            final Session next = new Session();
            next.arm("", fake.instrumentation(), (instrumentation, transformer) ->
                instrumentation.removeTransformer(transformer));
            final ClassFileTransformer callback = fake.transformer().get();
            if (callback == null || !State.ARMED.name().equals(next.snapshot().state())
                    || !fake.registered().get()) {
                throw new IllegalStateException("T040 official fake session did not arm");
            }
            final byte[] candidate = callback.transform(
                null, runtime.loader(), OFFICIAL_INTERNAL_NAME, null, runtime.domain(), original);
            if (candidate == null) {
                throw new IllegalStateException("T040 official shadow candidate was not returned");
            }
            session = next;
            return freezeCapture("t040-official");
        } finally {
            session = null;
            cleanupOfficialRuntimeData(runtime);
            restoreSessionProperties(saved);
        }
    }

    /**
     * Installs an owned-only Session through the real arm/transform/unregister path
     * for the T040 freeze test. It transforms bytes but never defines the fixture.
     */
    static Snapshot installOwnedFreezeSession(final Path fixtureJar) throws Exception {
        final Path absoluteFixture = fixtureJar.toAbsolutePath().normalize();
        final byte[] original = T039ShadowPatchBridge.ownedFixtureBytes();
        final T039ShadowPatchBridge.ShapeInfo shape =
            T039ShadowPatchBridge.ownedShape(original);
        final String jarHash = sha256File(absoluteFixture);
        final PropertyState saved = saveSessionProperties();
        try {
            final T039OwnedTargetLoader targetLoader = new T039OwnedTargetLoader(
                absoluteFixture, T039ShadowAgent.class.getClassLoader());
            System.setProperty(PROPERTY_PREFIX + "profile", OWNED_PROFILE);
            System.setProperty(PROPERTY_PREFIX + "runId", "t040-owned");
            System.setProperty(PROPERTY_PREFIX + "codeSource", absoluteFixture.toString());
            System.setProperty(PROPERTY_PREFIX + "jarSha256", jarHash);
            System.setProperty(PROPERTY_PREFIX + "classSha256", shape.classSha256());
            System.setProperty(PROPERTY_PREFIX + "shapeSha256", shape.shapeSha256());
            System.setProperty(PROPERTY_PREFIX + "loaderClass", targetLoader.getClass().getName());
            System.setProperty(PROPERTY_PREFIX + "helperSha256", sha256Resource(T039ShadowHelper.class));
            System.setProperty(PROPERTY_PREFIX + "t038HelperSha256", sha256Resource(T038ArrayHelper.class));
            System.setProperty(PROPERTY_PREFIX + "maxEvents", "16");
            System.setProperty(PROPERTY_PREFIX + "shadowMode", OWNED_MODE);
            System.clearProperty(PROPERTY_PREFIX + "shadowOptIn");
            final FakeInstrumentation fake = fakeInstrumentation(FakeRemovalMode.TRUE);
            final Session next = new Session();
            next.arm("", fake.instrumentation(), (instrumentation, transformer) ->
                instrumentation.removeTransformer(transformer));
            final ClassFileTransformer callback = fake.transformer().get();
            if (callback == null || !State.ARMED.name().equals(next.snapshot().state())
                    || !fake.registered().get()) {
                throw new IllegalStateException("T040 owned fake session did not arm");
            }
            final byte[] candidate = callback.transform(
                null, targetLoader, OWNED_INTERNAL_NAME, null,
                fileProtectionDomain(absoluteFixture, targetLoader), original);
            final Snapshot terminal = next.snapshot();
            if (candidate == null || !State.PATCHED.name().equals(terminal.state())
                    || !RemovalStatus.REMOVED.name().equals(terminal.removalStatus())
                    || terminal.transformerRegistered()
                    || terminal.candidateReturnedCount() != 1) {
                throw new IllegalStateException("T040 owned fake session did not reach PATCHED");
            }
            session = next;
            return terminal;
        } finally {
            restoreSessionProperties(saved);
        }
    }

    /** Rejection-only checks for the public one-shot freeze gate. */
    static FreezeRejectionCheck checkFreezeRejections() {
        final Session saved = session;
        try {
            final Session unarmed = new Session();
            session = unarmed;
            final boolean unarmedRejected = rejectsFreeze("unknown");

            final Session armed = new Session();
            armed.profile = OWNED_PROFILE;
            armed.runId = "t040-armed";
            armed.shadowMode = OWNED_MODE;
            armed.state = State.ARMED;
            session = armed;
            final boolean unpatchedRejected = rejectsFreeze("t040-armed");

            final Session blocked = new Session();
            blocked.profile = OWNED_PROFILE;
            blocked.runId = "t040-blocked";
            blocked.shadowMode = OWNED_MODE;
            blocked.state = State.BLOCKED;
            session = blocked;
            final boolean blockedRejected = rejectsFreeze("t040-blocked");
            return new FreezeRejectionCheck(
                unarmedRejected, unpatchedRejected, blockedRejected);
        } finally {
            session = saved;
        }
    }

    private static boolean rejectsFreeze(final String runId) {
        try {
            freezeCapture(runId);
            return false;
        } catch (final IllegalArgumentException | IllegalStateException expected) {
            return true;
        }
    }

    static InstrumentationRemovalCheck checkInstrumentationRemovalScenarios(
        final Path fixtureJar
    ) throws Exception {
        final byte[] original = T039ShadowPatchBridge.ownedFixtureBytes();
        final T039ShadowPatchBridge.ShapeInfo shape =
            T039ShadowPatchBridge.ownedShape(original);
        final String jarHash = sha256File(fixtureJar);
        final PropertyState saved = saveSessionProperties();
        try {
            final RemovalScenarioResult trueResult = runOwnedScenario(
                FakeRemovalMode.TRUE, fixtureJar, jarHash, shape, original);
            final RemovalScenarioResult falseResult = runOwnedScenario(
                FakeRemovalMode.FALSE, fixtureJar, jarHash, shape, original);
            final RemovalScenarioResult throwsResult = runOwnedScenario(
                FakeRemovalMode.THROWS, fixtureJar, jarHash, shape, original);
            return new InstrumentationRemovalCheck(
                trueResult.firstReturned(), trueResult.lateReturnedNull(),
                trueResult.terminal(), trueResult.afterLate(),
                falseResult.firstReturned(), falseResult.lateReturnedNull(),
                falseResult.terminal(), falseResult.afterLate(),
                throwsResult.firstReturned(), throwsResult.lateReturnedNull(),
                throwsResult.terminal(), throwsResult.afterLate());
        } finally {
            restoreSessionProperties(saved);
        }
    }

    private static RemovalScenarioResult runOfficialScenario(
        final String mode,
        final boolean optIn,
        final byte[] original,
        final OfficialRuntimeData runtime
    ) throws Exception {
        final String helperHash = sha256Resource(T039ShadowHelper.class);
        final String t038Hash = sha256Resource(T038ArrayHelper.class);
        System.setProperty(PROPERTY_PREFIX + "profile", OFFICIAL_PROFILE);
        System.setProperty(PROPERTY_PREFIX + "runId", "t039-official-" + mode);
        System.setProperty(PROPERTY_PREFIX + "sourceBinding",
            T040RuntimeSourceBinding.TARGET_PROTECTION_DOMAIN);
        System.setProperty(PROPERTY_PREFIX + "trustedSourcePaths", runtime.source().toString());
        System.setProperty(PROPERTY_PREFIX + "jarSha256", OFFICIAL_JAR_SHA256);
        System.setProperty(PROPERTY_PREFIX + "classSha256", OFFICIAL_CLASS_SHA256);
        System.setProperty(PROPERTY_PREFIX + "shapeSha256", OFFICIAL_SHAPE_SHA256);
        System.setProperty(PROPERTY_PREFIX + "loaderClass", runtime.loader().getClass().getName());
        System.setProperty(PROPERTY_PREFIX + "helperSha256", helperHash);
        System.setProperty(PROPERTY_PREFIX + "t038HelperSha256", t038Hash);
        System.setProperty(PROPERTY_PREFIX + "maxEvents", "16");
        System.setProperty(PROPERTY_PREFIX + "shadowMode", mode);
        if (optIn) {
            System.setProperty(PROPERTY_PREFIX + "shadowOptIn", SHADOW_OPT_IN_TOKEN);
        } else {
            System.clearProperty(PROPERTY_PREFIX + "shadowOptIn");
        }
        return driveScenario(
            FakeRemovalMode.TRUE, runtime.loader(), runtime.domain(), OFFICIAL_INTERNAL_NAME, original);
    }

    private static RemovalScenarioResult runOwnedScenario(
        final FakeRemovalMode mode,
        final Path fixtureJar,
        final String jarHash,
        final T039ShadowPatchBridge.ShapeInfo shape,
        final byte[] original
    ) throws Exception {
        final T039OwnedTargetLoader targetLoader = new T039OwnedTargetLoader(
            fixtureJar, T039ShadowAgent.class.getClassLoader());
        System.setProperty(PROPERTY_PREFIX + "profile", OWNED_PROFILE);
        System.setProperty(PROPERTY_PREFIX + "runId", "t039-owned-remove-" + mode.name());
        System.setProperty(PROPERTY_PREFIX + "codeSource", fixtureJar.toAbsolutePath().normalize().toString());
        System.setProperty(PROPERTY_PREFIX + "jarSha256", jarHash);
        System.setProperty(PROPERTY_PREFIX + "classSha256", shape.classSha256());
        System.setProperty(PROPERTY_PREFIX + "shapeSha256", shape.shapeSha256());
        System.setProperty(PROPERTY_PREFIX + "loaderClass", targetLoader.getClass().getName());
        System.setProperty(PROPERTY_PREFIX + "helperSha256", sha256Resource(T039ShadowHelper.class));
        System.setProperty(PROPERTY_PREFIX + "t038HelperSha256", sha256Resource(T038ArrayHelper.class));
        System.setProperty(PROPERTY_PREFIX + "maxEvents", "16");
        System.setProperty(PROPERTY_PREFIX + "shadowMode", OWNED_MODE);
        System.clearProperty(PROPERTY_PREFIX + "shadowOptIn");
        return driveScenario(
            mode, targetLoader, fileProtectionDomain(fixtureJar, targetLoader),
            OWNED_INTERNAL_NAME, original);
    }

    private static RemovalScenarioResult driveScenario(
        final FakeRemovalMode mode,
        final ClassLoader targetLoader,
        final ProtectionDomain domain,
        final String targetName,
        final byte[] original
    ) throws Exception {
        final FakeInstrumentation fake = fakeInstrumentation(mode);
        final Session next = new Session();
        next.arm("", fake.instrumentation(), (instrumentation, transformer) -> {
            instrumentation.removeTransformer(transformer);
            return mode == FakeRemovalMode.TRUE;
        });
        final Snapshot armed = next.snapshot();
        if (!State.ARMED.name().equals(armed.state())
                || !armed.transformerRegistered() || fake.transformer().get() == null) {
            throw new IllegalStateException("fake instrumentation did not arm");
        }
        final ClassFileTransformer callback = fake.transformer().get();
        final byte[] first = callback.transform(
            null, targetLoader, targetName, null, domain, original);
        final Snapshot terminal = next.snapshot();
        if (fake.registered().get() != (mode != FakeRemovalMode.TRUE)) {
            throw new IllegalStateException("fake instrumentation removal state mismatch");
        }
        final byte[] late = callback.transform(
            null, targetLoader, targetName, null, domain, original);
        final Snapshot afterLate = next.snapshot();
        return new RemovalScenarioResult(
            first != null, late == null, terminal, afterLate);
    }

    private static FakeInstrumentation fakeInstrumentation(final FakeRemovalMode mode) {
        final AtomicReference<ClassFileTransformer> transformer = new AtomicReference<>();
        final AtomicBoolean registered = new AtomicBoolean();
        final InvocationHandler handler = (proxy, method, args) -> {
            final String name = method.getName();
            if ("getAllLoadedClasses".equals(name)) {
                return new Class<?>[0];
            }
            if ("addTransformer".equals(name)) {
                transformer.set((ClassFileTransformer) args[0]);
                registered.set(true);
                return null;
            }
            if ("removeTransformer".equals(name)) {
                if (mode == FakeRemovalMode.THROWS) {
                    throw new IllegalStateException("fake-remove-throws");
                }
                if (mode == FakeRemovalMode.TRUE) {
                    registered.set(false);
                }
                return Boolean.valueOf(mode == FakeRemovalMode.TRUE);
            }
            if ("toString".equals(name)) {
                return "T039FakeInstrumentation[" + mode + "]";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            return defaultReturn(method.getReturnType());
        };
        final Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
            T039ShadowAgent.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            handler);
        return new FakeInstrumentation(instrumentation, transformer, registered);
    }

    private static Object defaultReturn(final Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        return 0.0d;
    }

    private static ProtectionDomain fileProtectionDomain(
        final Path path,
        final ClassLoader loader
    ) throws IOException {
        return new ProtectionDomain(
            new CodeSource(path.toUri().toURL(), (java.security.cert.Certificate[]) null),
            null, loader, null);
    }

    private static OfficialRuntimeData createOfficialRuntimeData() throws IOException {
        final Path root = Files.createTempDirectory("t040-official-runtime-");
        final Path source = root.resolve("task-local-5303-clone.jar");
        try {
            Files.copy(officialReferenceJarPath(), source);
            final ClassLoader loader = T039ShadowAgent.class.getClassLoader();
            return new OfficialRuntimeData(root, source, loader, fileProtectionDomain(source, loader));
        } catch (final IOException | RuntimeException exception) {
            Files.deleteIfExists(source);
            Files.deleteIfExists(root);
            throw exception;
        }
    }

    private static void cleanupOfficialRuntimeData(final OfficialRuntimeData runtime) {
        try {
            Files.deleteIfExists(runtime.source());
            Files.deleteIfExists(runtime.root());
        } catch (final IOException exception) {
            throw new IllegalStateException("official runtime fixture cleanup failed", exception);
        }
    }

    private static PropertyState saveSessionProperties() {
        final String[] keys = {
            "profile", "runId", "sourceBinding", "trustedSourcePaths", "codeSource",
            "jarSha256", "classSha256", "shapeSha256", "loaderClass", "helperSha256",
            "t038HelperSha256", "maxEvents", "shadowMode", "shadowOptIn"
        };
        final String[] values = new String[keys.length];
        for (int index = 0; index < keys.length; index++) {
            values[index] = System.getProperty(PROPERTY_PREFIX + keys[index]);
        }
        return new PropertyState(keys, values);
    }

    private static void restoreSessionProperties(final PropertyState saved) {
        for (int index = 0; index < saved.keys().length; index++) {
            final String key = PROPERTY_PREFIX + saved.keys()[index];
            final String value = saved.values()[index];
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    /** Test-only model for an absent result; it is not the JDK Instrumentation API. */
    static RemovalCheck checkRemovalOutcomes() {
        return new RemovalCheck(
            classifyRemoval(Boolean.TRUE, null) == RemovalStatus.REMOVED,
            classifyRemoval(Boolean.FALSE, null) == RemovalStatus.NOT_REMOVED,
            classifyRemoval(null, null) == RemovalStatus.UNKNOWN,
            classifyRemoval(null, new RuntimeException("remove")) == RemovalStatus.FAILED
        );
    }

    private static RemovalStatus classifyRemoval(
        final Boolean reportedRemoved,
        final RuntimeException failure
    ) {
        if (failure != null) {
            return RemovalStatus.FAILED;
        }
        if (reportedRemoved == null) {
            return RemovalStatus.UNKNOWN;
        }
        return reportedRemoved ? RemovalStatus.REMOVED : RemovalStatus.NOT_REMOVED;
    }

    static LateCallbackCheck checkLateCallbackInert() throws IOException {
        final Session next = new Session();
        next.prepareManual(manualOwnedConfig());
        next.unregister(State.BLOCKED, "test-terminal");
        final byte[] returned = next.transform(
            null, T039ShadowAgent.class.getClassLoader(), OWNED_INTERNAL_NAME,
            null, null, new byte[0]);
        final Snapshot result = next.snapshot();
        return new LateCallbackCheck(
            returned == null,
            State.BLOCKED.name().equals(result.state()),
            result.lateCallbacks() == 1,
            result.candidateCount() == 0,
            result.rejectionCount() == 0);
    }

    static ArmRaceCheck checkArmRaceGuards() throws IOException {
        final Session next = new Session();
        next.prepareManual(manualOwnedConfig());
        next.state = State.ARMING;
        final byte[] callbackResult = next.transform(
            null, T039ShadowAgent.class.getClassLoader(), OWNED_INTERNAL_NAME,
            null, null, new byte[0]);
        final Snapshot callbackSnapshot = next.snapshot();
        return new ArmRaceCheck(
            armShouldBlock(false, true, 0),
            callbackResult == null
                && State.ARMING.name().equals(callbackSnapshot.state())
                && callbackSnapshot.lateCallbacks() == 1
                && callbackSnapshot.candidateCount() == 0,
            !armShouldBlock(false, false, 0));
    }

    private static boolean armShouldBlock(
        final boolean wasLoadedBefore,
        final boolean wasLoadedAfter,
        final int callbacksDuringArm
    ) {
        return !wasLoadedBefore && (wasLoadedAfter || callbacksDuringArm > 0);
    }

    private static Config manualOwnedConfig() throws IOException {
        final String helperHash = sha256Resource(T039ShadowHelper.class);
        final String t038Hash = sha256Resource(T038ArrayHelper.class);
        final String currentDirectory = Path.of(".").toAbsolutePath().normalize().toString();
        final String zeros = "0".repeat(64);
        return new Config(
            OWNED_PROFILE, "t039-late-callback", currentDirectory, zeros,
            OWNED_INTERNAL_NAME, OWNED_INTERNAL_NAME.replace('/', '.'), OWNED_METHOD,
            OWNED_DESCRIPTOR, zeros, zeros,
            T039ShadowAgent.class.getClassLoader().getClass().getName(),
            helperHash, t038Hash, 16, OWNED_MODE, false, "fixed-pd", "");
    }
    private static Config manualOfficialConfig(
        final String mode,
        final boolean optIn,
        final String loaderClass,
        final String helperHash,
        final String t038Hash
    ) {
        return new Config(
            OFFICIAL_PROFILE, "t039-manual-" + mode, "",
            OFFICIAL_JAR_SHA256, OFFICIAL_INTERNAL_NAME,
            OFFICIAL_INTERNAL_NAME.replace('/', '.'), OFFICIAL_METHOD,
            OFFICIAL_DESCRIPTOR, OFFICIAL_CLASS_SHA256, OFFICIAL_SHAPE_SHA256,
            loaderClass, helperHash, t038Hash, 16, mode, optIn,
            T040RuntimeSourceBinding.TARGET_PROTECTION_DOMAIN, "");
    }

    static Path officialReferenceJarPath() {
        return Path.of(OFFICIAL_JAR);
    }

    static String officialJarSha256() {
        return OFFICIAL_JAR_SHA256;
    }

    static String ownedInternalName() {
        return OWNED_INTERNAL_NAME;
    }

    static String ownedDescriptor() {
        return OWNED_DESCRIPTOR;
    }

    private static final class Session {
        private final Object lifecycleLock = new Object();
        private final AtomicBoolean targetSeen = new AtomicBoolean();
        private final AtomicInteger targetEvents = new AtomicInteger();
        private final AtomicInteger candidateCount = new AtomicInteger();
        private final AtomicInteger candidateReturnedCount = new AtomicInteger();
        private final AtomicInteger rejectionCount = new AtomicInteger();
        private final AtomicInteger lateCallbacks = new AtomicInteger();
        private volatile State state = State.UNARMED;
        private volatile String reason = "not armed";
        private volatile String profile = "unknown";
        private volatile String runId = "unknown";
        private volatile String shadowMode = "unknown";
        private volatile String helperHash = "";
        private volatile String t038HelperHash = "";
        private volatile boolean helperPrewarmed;
        private volatile boolean t038HelperPrewarmed;
        private volatile boolean transformerRegistered;
        private volatile boolean methodExecuted;
        private volatile RemovalStatus removalStatus = RemovalStatus.NOT_ATTEMPTED;
        private volatile Instrumentation instrumentation;
        private volatile TransformerRemover transformerRemover = DEFAULT_REMOVER;
        private volatile ClassFileTransformer transformer;
        private volatile Config config;
        private volatile T040RuntimeSourceBinding.Binding runtimeSourceBinding;
        private Map<String, String> frozenCapture;
        private volatile Class<?> helperClass;
        private volatile Class<?> t038HelperClass;

        private void prepareManual(final Config loaded) throws IOException {
            profile = loaded.profile();
            runId = loaded.runId();
            shadowMode = loaded.shadowMode();
            config = loaded;
            helperClass = T039ShadowHelper.class;
            t038HelperClass = T038ArrayHelper.class;
            helperHash = sha256Resource(helperClass);
            t038HelperHash = sha256Resource(t038HelperClass);
            T039ShadowHelper.prewarm(loaded.maxEvents());
            T038ArrayHelper.bounds(
                1, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0, false);
            helperPrewarmed = true;
            t038HelperPrewarmed = true;
            state = State.ARMED;
            reason = "manual-test-armed";
        }

        private void arm(final String agentArgs, final Instrumentation suppliedInstrumentation) {
            arm(agentArgs, suppliedInstrumentation, DEFAULT_REMOVER);
        }

        private void arm(
            final String agentArgs,
            final Instrumentation suppliedInstrumentation,
            final TransformerRemover suppliedRemover
        ) {
            transformerRemover = suppliedRemover == null ? DEFAULT_REMOVER : suppliedRemover;
            try {
                if (agentArgs != null && !agentArgs.isEmpty()) {
                    reject(State.REJECTED, "agent-arguments-forbidden");
                    return;
                }
                if (suppliedInstrumentation == null) {
                    reject(State.REJECTED, "instrumentation-missing");
                    return;
                }
                final Config loaded = Config.read();
                profile = loaded.profile();
                runId = loaded.runId();
                shadowMode = loaded.shadowMode();
                if (OWNED_PROFILE.equals(loaded.profile())) {
                    if (!Files.isRegularFile(Path.of(loaded.codeSource()))) {
                        reject(State.REJECTED, "code-source-missing");
                        return;
                    }
                    if (!sha256File(Path.of(loaded.codeSource())).equals(loaded.jarSha256())) {
                        reject(State.REJECTED, "jar-hash-gate");
                        return;
                    }
                } else if (!T040RuntimeSourceBinding.TARGET_PROTECTION_DOMAIN.equals(
                    loaded.sourceBinding())) {
                    reject(State.REJECTED, "runtime-source-binding-config");
                    return;
                }
                final HelperGate helperGate = HelperGate.prewarm(
                    loaded.maxEvents(), loaded.helperSha256(), loaded.t038HelperSha256());
                helperClass = helperGate.helperClass();
                helperHash = helperGate.actualHash();
                t038HelperClass = helperGate.t038HelperClass();
                t038HelperHash = helperGate.t038ActualHash();
                helperPrewarmed = true;
                t038HelperPrewarmed = true;
                config = loaded;
                instrumentation = suppliedInstrumentation;
                synchronized (lifecycleLock) {
                    state = State.ARMING;
                    reason = "arming";
                    if (containsLoadedTarget(suppliedInstrumentation, loaded.binaryName())) {
                        reject(State.REJECTED, "target-already-loaded");
                        return;
                    }
                    transformer = new ShadowTransformer(this);
                    suppliedInstrumentation.addTransformer(transformer, false);
                    transformerRegistered = true;
                    if (lateCallbacks.get() > 0
                            || containsLoadedTarget(suppliedInstrumentation, loaded.binaryName())) {
                        unregister(State.BLOCKED, lateCallbacks.get() > 0
                            ? "target-callback-during-arm"
                            : "target-loaded-during-arm");
                    } else {
                        state = State.ARMED;
                        reason = "armed";
                        System.out.println(
                            "t039Premain=ARMED profile=" + profile + " runId=" + runId);
                    }
                }
            } catch (final RuntimeException | IOException exception) {
                reject(State.REJECTED, shortReason(exception));
            }
        }

        private boolean containsLoadedTarget(
            final Instrumentation suppliedInstrumentation,
            final String binaryName
        ) {
            for (final Class<?> loaded : suppliedInstrumentation.getAllLoadedClasses()) {
                if (loaded != null && binaryName.equals(loaded.getName())) {
                    return true;
                }
            }
            return false;
        }

        private byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer
        ) {
            final Config current = config;
            if (current == null || className == null
                    || !current.internalName().equals(className)) {
                return null;
            }
            if (state != State.ARMED) {
                incrementBounded(lateCallbacks, MAX_SESSION_EVENTS);
                return null;
            }
            incrementBounded(targetEvents, MAX_SESSION_EVENTS);
            if (!targetSeen.compareAndSet(false, true)) {
                reject(State.BLOCKED, "repeat-target-definition");
                return null;
            }
            if (classBeingRedefined != null) {
                reject(State.BLOCKED, "redefinition-not-supported");
                return null;
            }
            if (loader == null || !current.loaderClass().equals(loader.getClass().getName())) {
                reject(State.BLOCKED, "loader-gate");
                return null;
            }
            if (OFFICIAL_PROFILE.equals(current.profile())) {
                try {
                    final T040RuntimeSourceBinding.Binding binding =
                        T040RuntimeSourceBinding.bind(
                            protectionDomain, loader, current.loaderClass(), current.jarSha256(),
                            current.trustedSourcePaths());
                    T040RuntimeSourceBinding.verify(
                        binding, protectionDomain, loader, current.loaderClass(), current.jarSha256());
                    runtimeSourceBinding = binding;
                } catch (final IOException | RuntimeException exception) {
                    reject(State.BLOCKED, "runtime-source-gate");
                    return null;
                }
            } else {
                final String source = codeSourcePath(protectionDomain);
                if (source == null || !current.codeSource().equals(source)) {
                    reject(State.BLOCKED, "code-source-gate");
                    return null;
                }
            }
            final String classHash = sha256(classfileBuffer);
            if (!current.classSha256().equals(classHash)) {
                reject(State.BLOCKED, "class-hash-gate");
                return null;
            }
            if (!privateAsmRuntimeAvailable()) {
                reject(State.BLOCKED, "bytecode-dependency-gate");
                return null;
            }
            final String shapeHash;
            try {
                shapeHash = shapeHash(classfileBuffer);
            } catch (final RuntimeException exception) {
                reject(State.BLOCKED, "shape-parse-gate");
                return null;
            }
            if (!current.shapeSha256().equals(shapeHash)) {
                reject(State.BLOCKED, "cfg-shape-gate");
                return null;
            }
            final String helperError = HelperGate.verifyVisible(
                current, loader, helperClass, helperHash, t038HelperClass, t038HelperHash);
            if (helperError != null) {
                reject(State.BLOCKED, helperError);
                return null;
            }
            try {
                if (OFFICIAL_PROFILE.equals(current.profile())) {
                    final T039ShadowPatchBridge.OfficialResult result =
                        T039ShadowPatchBridge.patchOfficial5303(
                            classfileBuffer,
                            T039ShadowPatchBridge.SHADOW_HELPER_OWNER,
                            T039ShadowPatchBridge.BOUNDS_DESCRIPTOR
                        );
                    if (!result.accepted() || result.candidate() == null
                            || result.commonSuperQueries() != 0) {
                        reject(State.BLOCKED, "official-two-boundary-patch-gate");
                        return null;
                    }
                    candidateCount.incrementAndGet();
                    if (DATA_ONLY_MODE.equals(current.shadowMode())) {
                        unregister(State.DATA_ONLY_VERIFIED, "official-data-only");
                        return null;
                    }
                    if (SHADOW_READY_MODE.equals(current.shadowMode())
                            && current.shadowOptIn()) {
                        unregister(State.SHADOW_READY, "official-shadow-ready-candidate");
                        if (state != State.SHADOW_READY
                                || removalStatus != RemovalStatus.REMOVED) {
                            return null;
                        }
                        if (!verifyRuntimeSourceBeforeReturn(
                                current, protectionDomain, loader)) {
                            return null;
                        }
                        candidateReturnedCount.incrementAndGet();
                        return result.candidate();
                    }
                    reject(State.BLOCKED, "shadow-mode-gate");
                    return null;
                }
                final T039ShadowPatchBridge.OwnedResult result =
                    T039ShadowPatchBridge.patchOwned(
                        classfileBuffer,
                        T039ShadowPatchBridge.SHADOW_HELPER_OWNER,
                        T039ShadowPatchBridge.BOUNDS_DESCRIPTOR
                    );
                if (!result.accepted() || result.candidate() == null) {
                    reject(State.BLOCKED, "owned-two-boundary-patch-gate");
                    return null;
                }
                candidateCount.incrementAndGet();
                unregister(State.PATCHED, "owned-shadow-patched");
                if (state != State.PATCHED || removalStatus != RemovalStatus.REMOVED) {
                    return null;
                }
                candidateReturnedCount.incrementAndGet();
                return result.candidate();
            } catch (final LinkageError error) {
                reject(State.BLOCKED, "bytecode-dependency-gate");
                return null;
            }
        }

        private boolean verifyRuntimeSourceBeforeReturn(
            final Config current,
            final ProtectionDomain protectionDomain,
            final ClassLoader loader
        ) {
            try {
                T040RuntimeSourceBinding.verify(
                    runtimeSourceBinding, protectionDomain, loader, current.loaderClass(),
                    current.jarSha256());
                return true;
            } catch (final IOException | RuntimeException exception) {
                synchronized (lifecycleLock) {
                    incrementBounded(rejectionCount, MAX_SESSION_EVENTS);
                    state = State.BLOCKED;
                    reason = "runtime-source-return-gate";
                }
                return false;
            }
        }

        private static boolean privateAsmRuntimeAvailable() {
            final ClassLoader loader = T039ShadowAgent.class.getClassLoader();
            try {
                for (final String className : PRIVATE_ASM_RUNTIME_CLASSES) {
                    Class.forName(className, false, loader);
                }
                return true;
            } catch (final ClassNotFoundException | LinkageError exception) {
                return false;
            }
        }

        private void reject(final State nextState, final String nextReason) {
            if (isTerminal(state)) {
                return;
            }
            incrementBounded(rejectionCount, MAX_SESSION_EVENTS);
            unregister(nextState, nextReason);
        }

        private void unregister(final State nextState, final String nextReason) {
            synchronized (lifecycleLock) {
                if (isTerminal(state)) {
                    return;
                }
                final Instrumentation currentInstrumentation = instrumentation;
                final ClassFileTransformer currentTransformer = transformer;
                String finalReason = nextReason;
                // Freeze callbacks before removal; removal does not drain callbacks already dispatched.
                state = nextState;
                if (currentInstrumentation != null && currentTransformer != null
                        && transformerRegistered) {
                    try {
                        final boolean removed = transformerRemover.remove(
                            currentInstrumentation, currentTransformer);
                        removalStatus = classifyRemoval(Boolean.valueOf(removed), null);
                        transformerRegistered = !removed;
                        if (!removed) {
                            state = State.BLOCKED;
                            finalReason = nextReason + ":remove-not-confirmed";
                        }
                    } catch (final RuntimeException exception) {
                        removalStatus = RemovalStatus.FAILED;
                        state = State.BLOCKED;
                        finalReason = nextReason + ":remove-failed";
                    }
                } else if (removalStatus == RemovalStatus.NOT_ATTEMPTED) {
                    removalStatus = RemovalStatus.NOT_ATTEMPTED;
                }
                reason = finalReason;
            }
        }

        private Snapshot snapshot() {
            return new Snapshot(
                state.name(), profile, runId, shadowMode, reason, helperPrewarmed,
                helperHash, t038HelperPrewarmed, t038HelperHash,
                removalStatus.name(), transformerRegistered, methodExecuted,
                targetEvents.get(), lateCallbacks.get(), candidateCount.get(),
                candidateReturnedCount.get(), rejectionCount.get()
            );
        }

        private Map<String, String> freezeCapture(final String expectedRunId) {
            synchronized (lifecycleLock) {
                if (frozenCapture != null) {
                    throw new IllegalStateException("T040 freeze rejected: run already frozen");
                }
                if (expectedRunId == null || !expectedRunId.equals(runId)) {
                    throw new IllegalArgumentException("T040 freeze rejected: runId mismatch");
                }
                final Snapshot current = snapshot();
                requireFreezeEligible(current);
                final T039ShadowHelper.Stats helperStats =
                    T039ShadowHelper.freezeSnapshot();
                final Snapshot frozenSession = snapshot();
                final Map<String, String> result = createFrozenCapture(
                    frozenSession, helperStats);
                frozenCapture = result;
                return result;
            }
        }

        private static boolean isTerminal(final State value) {
            return value == State.PATCHED || value == State.DATA_ONLY_VERIFIED
                || value == State.SHADOW_READY || value == State.BLOCKED
                || value == State.REJECTED;
        }
    }

    private static final class ShadowTransformer implements ClassFileTransformer {
        private final Session session;

        private ShadowTransformer(final Session session) {
            this.session = session;
        }

        @Override
        public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer
        ) {
            return session.transform(
                module, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
        }
    }

    private record Config(
        String profile,
        String runId,
        String codeSource,
        String jarSha256,
        String internalName,
        String binaryName,
        String methodName,
        String descriptor,
        String classSha256,
        String shapeSha256,
        String loaderClass,
        String helperSha256,
        String t038HelperSha256,
        int maxEvents,
        String shadowMode,
        boolean shadowOptIn,
        String sourceBinding,
        String trustedSourcePaths
    ) {
        private static Config read() {
            final String profile = required("profile");
            final String runId = bounded("runId", 64);
            final String loaderClass = bounded("loaderClass", 160);
            final String helperSha = hexProperty("helperSha256");
            final String t038Sha = hexProperty("t038HelperSha256");
            final int maxEvents = integerProperty("maxEvents", 1, 64);
            final String defaultMode = OFFICIAL_PROFILE.equals(profile)
                ? DATA_ONLY_MODE : OWNED_MODE;
            final String shadowMode = modeProperty(defaultMode);
            final String optInValue = System.getProperty(
                PROPERTY_PREFIX + "shadowOptIn", "");
            if (optInValue.length() > 64) {
                throw new IllegalArgumentException("oversized property shadowOptIn");
            }
            final boolean shadowOptIn = SHADOW_OPT_IN_TOKEN.equals(optInValue);
            if (OFFICIAL_PROFILE.equals(profile)) {
                if (!DATA_ONLY_MODE.equals(shadowMode)
                        && !SHADOW_READY_MODE.equals(shadowMode)) {
                    throw new IllegalArgumentException("unknown official shadow mode");
                }
                if (SHADOW_READY_MODE.equals(shadowMode) && !shadowOptIn) {
                    throw new IllegalArgumentException("shadow-ready opt-in missing");
                }
                final String sourceBinding = required("sourceBinding");
                requireEqual(sourceBinding, T040RuntimeSourceBinding.TARGET_PROTECTION_DOMAIN,
                    "official source binding");
                final String trustedSourcePaths = bounded("trustedSourcePaths", 4096);
                final String jarSha = hexProperty("jarSha256");
                final String classSha = hexProperty("classSha256");
                final String shapeSha = hexProperty("shapeSha256");
                requireEqual(jarSha, OFFICIAL_JAR_SHA256, "official JAR hash");
                requireEqual(classSha, OFFICIAL_CLASS_SHA256, "official class hash");
                requireEqual(shapeSha, OFFICIAL_SHAPE_SHA256, "official shape hash");
                return new Config(
                    profile, runId, "", jarSha, OFFICIAL_INTERNAL_NAME,
                    OFFICIAL_INTERNAL_NAME.replace('/', '.'), OFFICIAL_METHOD,
                    OFFICIAL_DESCRIPTOR, classSha, shapeSha, loaderClass,
                    helperSha, t038Sha, maxEvents, shadowMode, shadowOptIn,
                    sourceBinding, trustedSourcePaths
                );
            }
            if (OWNED_PROFILE.equals(profile)) {
                requireEqual(shadowMode, OWNED_MODE, "owned shadow mode");
                final String codeSource = absoluteProperty("codeSource");
                return new Config(
                    profile, runId, codeSource, hexProperty("jarSha256"),
                    OWNED_INTERNAL_NAME, OWNED_INTERNAL_NAME.replace('/', '.'),
                    OWNED_METHOD, OWNED_DESCRIPTOR, hexProperty("classSha256"),
                    hexProperty("shapeSha256"), loaderClass, helperSha, t038Sha,
                    maxEvents, shadowMode, shadowOptIn, "fixed-pd", ""
                );
            }
            throw new IllegalArgumentException("unknown T039 profile");
        }

        private static String required(final String key) {
            final String value = System.getProperty(PROPERTY_PREFIX + key, "");
            if (value.isEmpty() || value.length() > 512) {
                throw new IllegalArgumentException("missing or oversized property " + key);
            }
            return value;
        }

        private static String bounded(final String key, final int maximum) {
            final String value = required(key);
            if (value.length() > maximum) {
                throw new IllegalArgumentException("oversized property " + key);
            }
            return value;
        }

        private static String modeProperty(final String defaultValue) {
            final String value = System.getProperty(PROPERTY_PREFIX + "shadowMode", defaultValue);
            if (value.isEmpty() || value.length() > 32) {
                throw new IllegalArgumentException("invalid property shadowMode");
            }
            return value;
        }

        private static String absoluteProperty(final String key) {
            final String value = required(key);
            final Path path = Path.of(value).toAbsolutePath().normalize();
            if (!path.toString().equals(value)) {
                throw new IllegalArgumentException("property is not normalized " + key);
            }
            return value;
        }

        private static String hexProperty(final String key) {
            final String value = required(key);
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("property is not lowercase SHA-256 " + key);
            }
            return value;
        }

        private static int integerProperty(final String key, final int minimum, final int maximum) {
            final String value = required(key);
            try {
                final int parsed = Integer.parseInt(value);
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException("property outside range " + key);
                }
                return parsed;
            } catch (final NumberFormatException exception) {
                throw new IllegalArgumentException("property is not an integer " + key, exception);
            }
        }

        private static void requireEqual(
            final String actual,
            final String expected,
            final String label
        ) {
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(label + " mismatch");
            }
        }
    }

    private static final class HelperGate {
        private final Class<?> helperClass;
        private final String actualHash;
        private final Class<?> t038HelperClass;
        private final String t038ActualHash;

        private HelperGate(
            final Class<?> helperClass,
            final String actualHash,
            final Class<?> t038HelperClass,
            final String t038ActualHash
        ) {
            this.helperClass = helperClass;
            this.actualHash = actualHash;
            this.t038HelperClass = t038HelperClass;
            this.t038ActualHash = t038ActualHash;
        }

        private static HelperGate prewarm(
            final int maxEvents,
            final String expectedHash,
            final String expectedT038Hash
        ) {
            try {
                final ClassLoader agentLoader = T039ShadowAgent.class.getClassLoader();
                final Class<?> helper = Class.forName(
                    T039ShadowHelper.BINARY_NAME, true, agentLoader);
                final Class<?> t038Helper = Class.forName(
                    T038ArrayHelper.BINARY_NAME, true, agentLoader);
                checkClassShape(helper, T039ShadowHelper.class, "T039 helper");
                checkClassShape(t038Helper, T038ArrayHelper.class, "T038 helper");
                checkBoundsMethod(helper, "T039 helper");
                checkBoundsMethod(t038Helper, "T038 helper");
                checkAdmissionMethod(t038Helper);
                final String actualHash = sha256Resource(helper);
                final String t038ActualHash = sha256Resource(t038Helper);
                if (!expectedHash.equals(actualHash)) {
                    throw new IllegalStateException("helper code hash gate");
                }
                if (!expectedT038Hash.equals(t038ActualHash)) {
                    throw new IllegalStateException("T038 helper code hash gate");
                }
                T039ShadowHelper.prewarm(maxEvents);
                T038ArrayHelper.bounds(
                    1, 1, new int[1], 1, 1, new int[1], 1, 1, 1, 0, false);
                return new HelperGate(helper, actualHash, t038Helper, t038ActualHash);
            } catch (final ReflectiveOperationException | IOException exception) {
                throw new IllegalStateException("helper closure prewarm failed", exception);
            }
        }

        private static String verifyVisible(
            final Config config,
            final ClassLoader targetLoader,
            final Class<?> prewarmed,
            final String expectedHash,
            final Class<?> prewarmedT038,
            final String expectedT038Hash
        ) {
            try {
                final Class<?> visible = Class.forName(
                    T039ShadowHelper.BINARY_NAME, false, targetLoader);
                final Class<?> visibleT038 = Class.forName(
                    T038ArrayHelper.BINARY_NAME, false, targetLoader);
                if (visible != prewarmed
                        || visible.getClassLoader() != prewarmed.getClassLoader()) {
                    return "helper-loader-identity-gate";
                }
                if (visibleT038 != prewarmedT038
                        || visibleT038.getClassLoader() != prewarmedT038.getClassLoader()) {
                    return "T038-helper-loader-identity-gate";
                }
                checkClassShape(visible, prewarmed, "visible T039 helper");
                checkClassShape(visibleT038, prewarmedT038, "visible T038 helper");
                checkBoundsMethod(visible, "visible T039 helper");
                checkBoundsMethod(visibleT038, "visible T038 helper");
                checkAdmissionMethod(visibleT038);
                if (!config.helperSha256().equals(expectedHash)
                        || !expectedHash.equals(sha256Resource(visible))) {
                    return "helper-visible-code-hash-gate";
                }
                if (!config.t038HelperSha256().equals(expectedT038Hash)
                        || !expectedT038Hash.equals(sha256Resource(visibleT038))) {
                    return "T038-helper-visible-code-hash-gate";
                }
                return null;
            } catch (final ReflectiveOperationException | IOException | RuntimeException exception) {
                return "helper-closure-not-visible";
            }
        }

        private static void checkClassShape(
            final Class<?> actual,
            final Class<?> expected,
            final String label
        ) {
            if (actual != expected || !Modifier.isPublic(actual.getModifiers())
                    || !Modifier.isFinal(actual.getModifiers())) {
                throw new IllegalStateException(label + " identity/shape gate");
            }
        }

        private static void checkBoundsMethod(final Class<?> type, final String label)
            throws NoSuchMethodException {
            final Method bounds = type.getDeclaredMethod(
                "bounds", int.class, int.class, int[].class, int.class, int.class,
                int[].class, int.class, int.class, int.class, int.class, boolean.class);
            if (!Modifier.isPublic(bounds.getModifiers())
                    || !Modifier.isStatic(bounds.getModifiers())
                    || bounds.getReturnType() != long.class) {
                throw new IllegalStateException(label + " bounds method gate");
            }
        }

        private static void checkAdmissionMethod(final Class<?> type)
            throws NoSuchMethodException {
            final Method admitted = type.getDeclaredMethod(
                "isAdmitted", int.class, int.class, int[].class, int.class, int.class,
                int[].class, int.class, int.class, int.class, int.class);
            if (!Modifier.isPublic(admitted.getModifiers())
                    || !Modifier.isStatic(admitted.getModifiers())
                    || admitted.getReturnType() != boolean.class) {
                throw new IllegalStateException("T038 admission method gate");
            }
        }

        private Class<?> helperClass() {
            return helperClass;
        }

        private String actualHash() {
            return actualHash;
        }

        private Class<?> t038HelperClass() {
            return t038HelperClass;
        }

        private String t038ActualHash() {
            return t038ActualHash;
        }
    }

    private static String shapeHash(final byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            throw new IllegalArgumentException("empty class identity");
        }
        return T039ShadowPatchBridge.shapeSha256(classBytes);
    }

    private static String codeSourcePath(final ProtectionDomain domain) {
        if (domain == null) {
            return null;
        }
        final CodeSource source = domain.getCodeSource();
        if (source == null) {
            return null;
        }
        final URL location = source.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            return null;
        }
        try {
            final URI uri = location.toURI();
            return Path.of(uri).toAbsolutePath().normalize().toString();
        } catch (final Exception exception) {
            return null;
        }
    }

    private static String sha256Resource(final Class<?> type) throws IOException {
        final String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("helper class resource missing");
            }
            return sha256(stream);
        }
    }

    private static String sha256File(final Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            return sha256(stream);
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    private static String sha256(final InputStream stream) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String shortReason(final Exception exception) {
        final String message = exception.getMessage();
        return exception.getClass().getSimpleName()
            + (message == null || message.isEmpty() ? "" : ":" + message);
    }

    private static void incrementBounded(final AtomicInteger counter, final int limit) {
        for (;;) {
            final int current = counter.get();
            if (current >= limit || counter.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private static void requireFreezeEligible(final Snapshot current) {
        final boolean official = OFFICIAL_PROFILE.equals(current.profile());
        final boolean owned = OWNED_PROFILE.equals(current.profile());
        if (!official && !owned) {
            throw new IllegalStateException("T040 freeze rejected: unsupported profile");
        }
        if (official && (!SHADOW_READY_MODE.equals(current.shadowMode())
                || !State.SHADOW_READY.name().equals(current.state()))) {
            throw new IllegalStateException("T040 freeze rejected: official run is not shadow-ready");
        }
        if (owned && (!OWNED_MODE.equals(current.shadowMode())
                || !State.PATCHED.name().equals(current.state()))) {
            throw new IllegalStateException("T040 freeze rejected: owned run is not patched");
        }
        if (!RemovalStatus.REMOVED.name().equals(current.removalStatus())
                || current.transformerRegistered()
                || current.candidateReturnedCount() != 1
                || current.candidateCount() != 1
                || current.targetEvents() != 1
                || current.rejectionCount() != 0
                || current.lateCallbacks() != 0
                || current.methodExecuted()
                || !current.helperPrewarmed()
                || !current.t038HelperPrewarmed()) {
            throw new IllegalStateException("T040 freeze rejected: preflight failure");
        }
    }

    private static Map<String, String> createFrozenCapture(
        final Snapshot sessionSnapshot,
        final T039ShadowHelper.Stats helperStats
    ) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("schemaVersion", "1");
        values.put("runId", sessionSnapshot.runId());
        values.put("profile", sessionSnapshot.profile());
        values.put("shadowMode", sessionSnapshot.shadowMode());
        values.put("state", sessionSnapshot.state());
        values.put("removalStatus", sessionSnapshot.removalStatus());
        values.put("transformerRegistered", Boolean.toString(
            sessionSnapshot.transformerRegistered()));
        values.put("candidateReturnedCount",
            Integer.toString(sessionSnapshot.candidateReturnedCount()));
        values.put("frozen", "true");
        values.put("fullBoundsPreserved", "true");
        values.put("sampleOutcome", sampleOutcome(helperStats));
        values.put("reason", sessionSnapshot.reason());
        values.put("helperPrewarmed", Boolean.toString(sessionSnapshot.helperPrewarmed()));
        values.put("helperHash", sessionSnapshot.helperHash());
        values.put("t038HelperPrewarmed",
            Boolean.toString(sessionSnapshot.t038HelperPrewarmed()));
        values.put("t038HelperHash", sessionSnapshot.t038HelperHash());
        values.put("stats.recorded", Long.toString(helperStats.recorded()));
        values.put("stats.dropped", Long.toString(helperStats.dropped()));
        values.put("stats.admitted", Long.toString(helperStats.admitted()));
        values.put("stats.rejected", Long.toString(helperStats.rejected()));
        values.put("stats.aliased", Long.toString(helperStats.aliased()));
        values.put("stats.potentialTrim", Long.toString(helperStats.potentialTrim()));
        values.put("stats.eventLimit", Long.toString(helperStats.eventLimit()));
        values.put("stats.lastKx", Integer.toString(helperStats.lastKx()));
        values.put("stats.lastKy", Integer.toString(helperStats.lastKy()));
        values.put("stats.lastSourceWidth", Integer.toString(helperStats.lastSourceWidth()));
        values.put("stats.lastSourceHeight", Integer.toString(helperStats.lastSourceHeight()));
        values.put("stats.lastStride", Integer.toString(helperStats.lastStride()));
        values.put("stats.lastFullWidth", Integer.toString(helperStats.lastFullWidth()));
        values.put("stats.lastFullHeight", Integer.toString(helperStats.lastFullHeight()));
        values.put("stats.lastPadding", Integer.toString(helperStats.lastPadding()));
        values.put("stats.lastSourceLength", Integer.toString(helperStats.lastSourceLength()));
        values.put("stats.lastDestinationLength",
            Integer.toString(helperStats.lastDestinationLength()));
        values.put("stats.lastAliased", Boolean.toString(helperStats.lastAliased()));
        values.put("stats.lastAdmitted", Boolean.toString(helperStats.lastAdmitted()));
        values.put("stats.lastPotentialTrim",
            Boolean.toString(helperStats.lastPotentialTrim()));
        values.put("stats.lastOptimizationRequested",
            Boolean.toString(helperStats.lastOptimizationRequested()));
        values.put("stats.targetEvents", Integer.toString(sessionSnapshot.targetEvents()));
        values.put("stats.lateCallbacks", Integer.toString(sessionSnapshot.lateCallbacks()));
        values.put("stats.candidateCount", Integer.toString(sessionSnapshot.candidateCount()));
        values.put("stats.candidateReturnedCount",
            Integer.toString(sessionSnapshot.candidateReturnedCount()));
        values.put("stats.rejectionCount", Integer.toString(sessionSnapshot.rejectionCount()));
        values.put("stats.methodExecuted", Boolean.toString(sessionSnapshot.methodExecuted()));
        return Collections.unmodifiableMap(values);
    }

    private static String sampleOutcome(final T039ShadowHelper.Stats stats) {
        if (stats.dropped() > 0L) {
            return "TRUNCATED";
        }
        if (stats.recorded() == 0L) {
            return "NO_CALLS";
        }
        if (stats.admitted() == 0L) {
            return "NO_ELIGIBLE_CALLS";
        }
        return stats.potentialTrim() > 0L ? "POTENTIAL_TRIM" : "NO_ELIGIBLE_CALLS";
    }

    public record Snapshot(
        String state,
        String profile,
        String runId,
        String shadowMode,
        String reason,
        boolean helperPrewarmed,
        String helperHash,
        boolean t038HelperPrewarmed,
        String t038HelperHash,
        String removalStatus,
        boolean transformerRegistered,
        boolean methodExecuted,
        int targetEvents,
        int lateCallbacks,
        int candidateCount,
        int candidateReturnedCount,
        int rejectionCount
    ) {
        private static Snapshot unarmed() {
            return new Snapshot(
                State.UNARMED.name(), "unknown", "unknown", "unknown", "not armed",
                false, "", false, "", RemovalStatus.NOT_ATTEMPTED.name(), false,
                false, 0, 0, 0, 0, 0
            );
        }
    }

    record OfficialDataCheck(
        String jarPath,
        String jarSha256,
        String className,
        String classSha256,
        String shapeSha256,
        int commonSuperQueries,
        int candidateLength
    ) {
    }

    record OfficialModesCheck(
        String dataState,
        String shadowState,
        boolean dataReturned,
        boolean shadowReturned,
        int dataCandidateCount,
        int shadowCandidateCount,
        int shadowCandidateReturnedCount
    ) {
    }

    record FreezeRejectionCheck(
        boolean unarmedRejected,
        boolean unpatchedRejected,
        boolean blockedRejected
    ) {
    }

    record InstrumentationRemovalCheck(
        boolean trueFirstReturned,
        boolean trueLateReturnedNull,
        Snapshot trueTerminal,
        Snapshot trueAfterLate,
        boolean falseFirstReturned,
        boolean falseLateReturnedNull,
        Snapshot falseTerminal,
        Snapshot falseAfterLate,
        boolean throwsFirstReturned,
        boolean throwsLateReturnedNull,
        Snapshot throwsTerminal,
        Snapshot throwsAfterLate
    ) {
    }

    private enum FakeRemovalMode {
        TRUE,
        FALSE,
        THROWS
    }

    private record FakeInstrumentation(
        Instrumentation instrumentation,
        AtomicReference<ClassFileTransformer> transformer,
        AtomicBoolean registered
    ) {
    }

    private record PropertyState(String[] keys, String[] values) {
    }

    private record OfficialRuntimeData(
        Path root,
        Path source,
        ClassLoader loader,
        ProtectionDomain domain
    ) {
    }

    private record RemovalScenarioResult(
        boolean firstReturned,
        boolean lateReturnedNull,
        Snapshot terminal,
        Snapshot afterLate
    ) {
    }

    record RemovalCheck(
        boolean trueMeansRemoved,
        boolean falseMeansNotRemoved,
        boolean missingResultMeansUnknown,
        boolean exceptionMeansFailed
    ) {
    }

    record LateCallbackCheck(
        boolean callbackReturnedNull,
        boolean stateStayedBlocked,
        boolean callbackCounted,
        boolean candidateCountStayedZero,
        boolean rejectionCountStayedZero
    ) {
    }

    record ArmRaceCheck(
        boolean loadedAfterInitialScanBlocks,
        boolean callbackDuringArmBlocks,
        boolean noLateSignalDoesNotBlock
    ) {
    }

    private enum RemovalStatus {
        NOT_ATTEMPTED,
        REMOVED,
        UNKNOWN,
        NOT_REMOVED,
        FAILED
    }

    private enum State {
        UNARMED,
        ARMING,
        ARMED,
        PATCHED,
        DATA_ONLY_VERIFIED,
        SHADOW_READY,
        BLOCKED,
        REJECTED
    }

}
