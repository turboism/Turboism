package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationBridge;
import dev.turboism.adapter.cubism.optimization.image.TextureUploadPreparationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Owns exact Editor/JOGL/JVM admission and restoration for native texture preparation.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * contract table and its JOGL-artifact gate. Its JVM predicate stays {@code == 17}: the launcher can
 * start a user-supplied GraalVM, and this weave was never measured on one.</p>
 */
final class VerifiedTextureUploadPreparationInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.texture-upload-preparation";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.EXACTLY_17);

    static final String JOGL_SHA256 = ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.sha256();

    /** The one factory method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final String PROFILE_OWNER = "com/jogamp/opengl/GLProfile";

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES = ReviewedHostContract.candidates(
            TextureUploadPreparationTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static final WeaveSpec<ReviewedHostContract.Bound<String>> SPEC = new WeaveSpec<ReviewedHostContract.Bound<String>>(
            ADMISSION,
            "texture upload preparation",
            VerifiedTextureUploadPreparationInstaller::resolve,
            new WeavePlan<>(VerifiedTextureUploadPreparationInstaller::targets, WeavePlan.PostCheck.NONE),
            (contract, launch) -> {
                final TextureUploadPreparationBridge bridge = new TextureUploadPreparationBridge(
                        Class.forName(PROFILE_OWNER.replace('/', '.'), false, launch.hostLoader()));
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    VerifiedTextureUploadPreparationInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvmFeature) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvmFeature, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<String>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES) instanceof ReviewedHostContract.Bound<String> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<String> contract, final WeaveSource source) throws Exception {
        final Path jogl = joglArtifact(source.artifact());
        if (!JOGL_SHA256.equals(sha256(jogl))) {
            throw new IllegalArgumentException("texture preparation requires reviewed JOGL artifact");
        }
        final String factoryOwner = TextureUploadPreparationTransformer.ownerForVersion(contract.sourceVersion());
        final Class<?> target = source.load(factoryOwner);
        final Class<?> profile = Class.forName(PROFILE_OWNER.replace('/', '.'), false, source.loader());
        VerifiedHookInstallerSupport.attestClassSource(
                profile, source.loader(), jogl, "texture factory/profile loader or code source mismatch");
        final TextureUploadPreparationTransformer transformer = new TextureUploadPreparationTransformer(
                source.loader(), source.artifact(), source.referenceBytes(target), factoryOwner);
        contract.requireUnchanged(source.artifact());
        return List.of(WeaveTarget.single(
                target,
                transformer,
                EXPECTED_MATCHES,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    private static Path joglArtifact(final Path artifact) {
        return artifact.toAbsolutePath().normalize().getParent().resolve("jogl/jogl-all.jar");
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted texture-preparation host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("texture upload preparation refused: host-not-supported-or-hook-disabled");
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

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
