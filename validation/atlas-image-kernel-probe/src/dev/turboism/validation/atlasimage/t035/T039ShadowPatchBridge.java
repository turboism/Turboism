package dev.turboism.validation.atlasimage.t035;

import java.io.IOException;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Narrow T039 bridge to the already verified T035 two-boundary patch.
 *
 * <p>This bridge is validation-only. It never defines or executes an official
 * class; official results remain transient byte arrays for profile checks.</p>
 */
public final class T039ShadowPatchBridge {
    public static final String SHADOW_HELPER_OWNER =
        "dev/turboism/validation/atlasimage/t039/T039ShadowHelper";
    public static final String BOUNDS_DESCRIPTOR = "(II[III[IIIIIZ)J";

    private static final String OFFICIAL_JAR =
        System.getProperty("user.home")
            + "/.proton/pfx/drive_c/Program Files/Live2D Cubism 5.3.03"
            + "/app/lib/Live2D_Cubism.jar";

    private T039ShadowPatchBridge() {
    }

    public static byte[] ownedFixtureBytes() {
        return T035OwnedFixtureGenerator.generate();
    }

    public static byte[] official5303Bytes() throws IOException {
        for (final T035OfficialProfile.Loaded loaded : T035OfficialProfile.loadAll()) {
            if (loaded.profile().label().equals("5303")) {
                return loaded.classBytes().clone();
            }
        }
        throw new IOException("5303 official profile missing");
    }

    public static String shapeSha256(final byte[] bytes) {
        return T035Shape.inspect(bytes).sha256();
    }

    public static ShapeInfo ownedShape(final byte[] bytes) {
        final T035Shape.Shape shape = T035Shape.inspect(bytes);
        return new ShapeInfo(
            T035Shape.sha256(bytes),
            shape.sha256(),
            shape.className(),
            shape.methodName(),
            shape.descriptor(),
            shape.instructionCount(),
            shape.handlerCount()
        );
    }

    public static OwnedResult patchOwned(
        final byte[] input,
        final String helperOwner,
        final String helperDescriptor
    ) {
        if (input == null) {
            return new OwnedResult(false, null, "fixture bytes missing");
        }
        final byte[] candidate = T035OwnedTransformer.applyForHelper(
            input, true, helperOwner, helperDescriptor);
        return new OwnedResult(
            candidate != input && !Arrays.equals(candidate, input),
            candidate,
            candidate == input || Arrays.equals(candidate, input)
                ? "owned shape gate"
                : "accepted"
        );
    }

    public static OfficialResult patchOfficial5303(
        final byte[] input,
        final String helperOwner,
        final String helperDescriptor
    ) {
        if (input == null) {
            return new OfficialResult(false, null, 0, "class bytes missing");
        }
        try {
            final T035OfficialProfile.Profile profile = officialProfile();
            final T035Shape.Shape shape = T035Shape.inspect(input);
            if (!T035Shape.sha256(input).equals(profile.classSha256())) {
                return new OfficialResult(false, input, 0, "class hash gate");
            }
            T035OfficialProfile.checkProfile(profile, shape);
            final T035OfficialProfile.Loaded loaded =
                new T035OfficialProfile.Loaded(profile, input, shape);
            final T035OfficialAdapter.Result result = T035OfficialAdapter.apply(
                loaded, helperOwner, helperDescriptor);
            return new OfficialResult(
                result.accepted(), result.candidate(), result.commonSuperQueries(), result.reason());
        } catch (final RuntimeException exception) {
            return new OfficialResult(
                false, input, 0, exception.getClass().getSimpleName());
        }
    }

    private static T035OfficialProfile.Profile officialProfile() {
        return new T035OfficialProfile.Profile(
            "5303",
            Path.of(OFFICIAL_JAR),
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
            "com/live2d/util/f/g.class",
            "com/live2d/util/f/g",
            "ff1d1ce9b4291212d255c6e84c8b57242234c1fa174af09e440c1162a4a1f8d6",
            "a75d64a1203e3e80be09bc616b7878d52d31e6a1327a62cbbbf1b5a334432c2f"
        );
    }

    public record ShapeInfo(
        String classSha256,
        String shapeSha256,
        String className,
        String methodName,
        String descriptor,
        int instructionCount,
        int handlerCount
    ) {
    }

    public record OwnedResult(boolean accepted, byte[] candidate, String reason) {
    }

    public record OfficialResult(
        boolean accepted,
        byte[] candidate,
        int commonSuperQueries,
        String reason
    ) {
    }
}
