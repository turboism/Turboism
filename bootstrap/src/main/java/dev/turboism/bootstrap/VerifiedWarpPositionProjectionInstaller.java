package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact native pure-accessor/consumer admission and verified restoration.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * contract table and its dependency-body proof. Its JVM predicate stays {@code == 17} rather than
 * {@code >= 17}: the launcher can start a user-supplied GraalVM, and this weave was never measured on
 * one.</p>
 */
final class VerifiedWarpPositionProjectionInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.warp-position-projection";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.EXACTLY_17);

    /** The one consumer method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES = ReviewedHostContract.candidates(
            WarpPositionProjectionTransformer.reviewedClassSha256(), version -> HOOK_ID);

    private static final String FORM_OWNER = "com/live2d/cubism/doc/model/deformer/warp/CWarpDeformerForm";
    private static final String VECTOR_OWNER = "com/live2d/graphics3d/type/GVector2";
    private static final String REFS_OWNER = "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef";
    private static final String HELPER_OWNER = "com/live2d/graphics3d/type/f";

    static final WeaveSpec<ReviewedHostContract.Bound<String>> SPEC = new WeaveSpec<ReviewedHostContract.Bound<String>>(
            ADMISSION,
            "warp position projection",
            VerifiedWarpPositionProjectionInstaller::resolve,
            new WeavePlan<>(VerifiedWarpPositionProjectionInstaller::targets, WeavePlan.PostCheck.NONE),
            (contract, context) -> {
                final WarpPositionProjectionBridge bridge =
                        new WarpPositionProjectionBridge(load(context, FORM_OWNER), load(context, VECTOR_OWNER));
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    VerifiedWarpPositionProjectionInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<String>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES) instanceof ReviewedHostContract.Bound<String> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    private static Class<?> load(final InstallContext context, final String internalName) throws Exception {
        return Class.forName(internalName.replace('/', '.'), false, context.hostLoader());
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<String> contract, final WeaveSource source) throws Exception {
        final Class<?> target = source.load(WarpPositionProjectionTransformer.TARGET);
        final WarpPositionProjectionTransformer transformer = new WarpPositionProjectionTransformer(
                source.loader(), source.artifact(), source.referenceBytes(target));
        final Class<?> form = Class.forName(FORM_OWNER.replace('/', '.'), false, source.loader());
        final Class<?> vector = Class.forName(VECTOR_OWNER.replace('/', '.'), false, source.loader());
        final Class<?> refs = Class.forName(REFS_OWNER.replace('/', '.'), false, source.loader());
        final Class<?> helper = Class.forName(HELPER_OWNER.replace('/', '.'), false, source.loader());
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        for (final Method method : new Method[] {
            form.getMethod("getPositions"),
            form.getMethod("getSource"),
            form.getMethod("get_source$cubism"),
            form.getMethod("getAllPointRef"),
            refs.getMethod("getPos"),
            vector.getMethod("setX", float.class),
            vector.getMethod("setY", float.class),
            helper.getMethod("a", float[].class, int.class, int.class, vector, int.class, Object.class),
            helper.getMethod("a", float[].class, int.class, int.class, vector)
        }) {
            final Class<?> owner = method.getDeclaringClass();
            VerifiedHookInstallerSupport.attestClassSource(
                    owner, source.loader(), source.artifact(), "warp dependency loader/source mismatch");
            verifyMethod(
                    source,
                    owner,
                    method.getName(),
                    MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                            .descriptorString(),
                    observed);
        }
        VerifiedHookInstallerSupport.attestClassSource(
                vector, source.loader(), source.artifact(), "warp dependency loader/source mismatch");
        verifyMethod(source, vector, "<init>", "(FF)V", observed);
        verifyMethod(source, vector, "<init>", "()V", observed);
        VerifiedHookInstallerSupport.attestClassSource(
                refs, source.loader(), source.artifact(), "warp dependency loader/source mismatch");
        final Class<?> origin = form.getMethod("getSource").getReturnType();
        verifyMethod(
                source,
                refs,
                "<init>",
                MethodType.methodType(void.class, origin, int.class, form).descriptorString(),
                observed);
        verifyMethod(source, refs, "a", MethodType.methodType(void.class, form).descriptorString(), observed);
        verifyMethod(source, refs, "a", "()I", observed);
        contract.requireUnchanged(source.artifact());
        return List.of(WeaveTarget.single(
                target,
                transformer,
                EXPECTED_MATCHES,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    private static void verifyMethod(
            final WeaveSource source,
            final Class<?> type,
            final String name,
            final String descriptor,
            final Map<Class<?>, byte[]> observed)
            throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    source.instrumentation(),
                    type,
                    "warp dependency unmodifiable",
                    "warp dependency inspection absent");
            observed.put(type, actual);
        }
        final String owner = WeaveTarget.internalName(type);
        final var expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, name, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("warp dependency body changed: " + owner + "." + name);
        }
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted warp-projection host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("warp position projection refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
