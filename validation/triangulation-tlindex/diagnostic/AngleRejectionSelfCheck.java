import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Random;

/** T055 owned predicate feasibility; never replaces native angle values or launches Editor. */
public final class AngleRejectionSelfCheck {
    private static final float EPSILON = 1.0e-6f;
    private static long checks;
    private AngleRejectionSelfCheck() { }

    // Retain the exact native float products and their operation order.
    private static boolean reject(float ax, float ay, float bx, float by) {
        float cross = ax * by - ay * bx;
        float dot = ax * bx + ay * by;
        if (!Float.isFinite(cross) || !Float.isFinite(dot)) return false;
        if (Math.abs(cross) < Float.MIN_NORMAL || Math.abs(dot) < Float.MIN_NORMAL) return false;
        if (dot < 0.0f) return true;
        // A factor-two margin leaves threshold rounding and signed-zero near rays native.
        return Math.abs((double) cross) > (double) dot * 2.0e-6;
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class Native implements AutoCloseable {
        final URLClassLoader loader;
        final MethodHandle construct, angle;
        final Object utility;
        final float branchThreshold;
        long inputs, rejected, assertions, fallback;
        Native(Path jar, float branchThreshold) throws Throwable {
            this.branchThreshold = branchThreshold;
            loader = new URLClassLoader(new URL[] {jar.toUri().toURL(),
                    jar.getParent().resolve("kotlin-stdlib-1.7.21.jar").toUri().toURL()},
                    AngleRejectionSelfCheck.class.getClassLoader());
            loader.setDefaultAssertionStatus(true);
            Class<?> vector = loader.loadClass("com.live2d.graphics3d.type.GVector2");
            Class<?> type = loader.loadClass("com.live2d.graphics3d.editableMesh.triangulation.r");
            utility = type.getField("a").get(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            construct = lookup.findConstructor(vector, MethodType.methodType(void.class, float.class, float.class))
                    .asType(MethodType.methodType(Object.class, float.class, float.class));
            angle = lookup.findVirtual(type, "a", MethodType.methodType(float.class, vector, vector))
                    .asType(MethodType.methodType(float.class, Object.class, Object.class, Object.class));
            Class<?> constants = loader.loadClass("com.live2d.util.L");
            Object singleton = constants.getField("a").get(null);
            float threshold = (float) constants.getMethod("f").invoke(singleton);
            require(Float.floatToRawIntBits(threshold) == Float.floatToRawIntBits(EPSILON), "native threshold");
            require(loader.loadClass("kotlin._Assertions").getField("ENABLED").getBoolean(null), "native assertions enabled");
        }
        void check(float ax, float ay, float bx, float by) throws Throwable {
            inputs++;
            boolean early = reject(ax, ay, bx, by);
            Object a = (Object) construct.invokeExact(ax, ay);
            Object b = (Object) construct.invokeExact(bx, by);
            float nativeAngle;
            try {
                nativeAngle = (float) angle.invokeExact(utility, a, b);
            } catch (AssertionError failure) {
                assertions++;
                require(!early, "guard must preserve native angle assertion");
                return;
            }
            // h.d uses fcmpl/ifgt: NaN also takes the native rejection branch.
            boolean nativeRejected = !(nativeAngle <= branchThreshold);
            require(!early || nativeRejected, "false rejection ax=" + ax + " ay=" + ay + " bx=" + bx + " by=" + by);
            if (early) rejected++;
            else fallback++;
            // The fallback calls the same unmodified method; it must retain exact bits.
            float guarded = early ? Float.NaN : (float) angle.invokeExact(utility, a, b);
            require(early || Float.floatToRawIntBits(guarded) == Float.floatToRawIntBits(nativeAngle), "fallback bits");
        }
        @Override public void close() throws java.io.IOException { loader.close(); }
    }

    private static void one(String profile, Path jar) throws Throwable {
        long start = checks;
        float branchThreshold = switch (profile) {
            case "5203" -> 0.0f;
            case "5302", "5303" -> EPSILON;
            default -> throw new IllegalArgumentException("unreviewed profile " + profile);
        };
        try (Native nativeMethod = new Native(jar, branchThreshold)) {
            float[] special = {0.0f, -0.0f, Float.MIN_VALUE, -Float.MIN_VALUE,
                    Float.MIN_NORMAL, -Float.MIN_NORMAL, 1.0f, -1.0f,
                    Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, Float.NaN, EPSILON};
            for (float ax : special) for (float ay : special)
                for (float bx : special) for (float by : special) nativeMethod.check(ax, ay, bx, by);
            for (float center : new float[] {0.0f, EPSILON, -EPSILON, 2.0e-6f, -2.0e-6f}) {
                float y = center;
                for (int i = 0; i < 128; i++) y = Math.nextDown(y);
                for (int i = 0; i < 257; i++, y = Math.nextUp(y)) {
                    nativeMethod.check(1.0f, 0.0f, 1.0f, y);
                    nativeMethod.check(1.0f, -0.0f, -1.0f, y);
                }
            }
            Random random = new Random(0x553a11L);
            for (int i = 0; i < 100000; i++) nativeMethod.check(
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()),
                    Float.intBitsToFloat(random.nextInt()), Float.intBitsToFloat(random.nextInt()));
            // A distinct finite, editor-like range; synthetic rejection fraction is not a host metric.
            for (int i = 0; i < 100000; i++) nativeMethod.check(
                    random.nextFloat() * 2000.0f - 1000.0f, random.nextFloat() * 2000.0f - 1000.0f,
                    random.nextFloat() * 2000.0f - 1000.0f, random.nextFloat() * 2000.0f - 1000.0f);
            require(nativeMethod.rejected > 0 && nativeMethod.fallback > 0 && nativeMethod.assertions > 0,
                    "guard/fallback/assertion paths exercised");
            System.out.printf("NATIVE_ANGLE_PREDICATE_PASS profile=%s threshold=%s jar=%s inputs=%d checks=%d rejected=%d fallback=%d nativeAssertions=%d%n",
                    profile, branchThreshold, jar, nativeMethod.inputs, checks - start, nativeMethod.rejected,
                    nativeMethod.fallback, nativeMethod.assertions);
        }
    }

    public static void main(String[] arguments) throws Throwable {
        require(arguments.length > 0 && arguments.length % 2 == 0, "profile/official jar pairs required");
        for (int i = 0; i < arguments.length; i += 2) one(arguments[i], Path.of(arguments[i + 1]));
        System.out.println("ANGLE_REJECTION_FINISHED checks=" + checks + " scope=OWNED_PREDICATE_ONLY hostGain=UNPROVEN");
    }
}
