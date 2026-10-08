import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Random;

/** Actual native minus freshness, raw-field copy and no-alias controls on owned values. */
public final class VectorCopyIdentitySelfCheck {
    private static int checks;
    private VectorCopyIdentitySelfCheck() { }
    private static void require(boolean value, String reason) {
        checks++; if (!value) throw new AssertionError(reason);
    }
    private static void one(Path jar) throws Exception {
        int start = checks;
        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL(),
                jar.getParent().resolve("kotlin-stdlib-1.7.21.jar").toUri().toURL()},
                VectorCopyIdentitySelfCheck.class.getClassLoader())) {
            Class<?> vector = loader.loadClass("com.live2d.graphics3d.type.GVector2");
            var scalar = vector.getConstructor(float.class, float.class);
            var copy = vector.getConstructor(vector);
            var minus = vector.getMethod("minus", vector);
            var x = vector.getMethod("getX");
            var y = vector.getMethod("getY");
            var setX = vector.getMethod("setX", float.class);
            var setY = vector.getMethod("setY", float.class);
            Random random = new Random(0x620c0fL);
            int[] special = {0, 0x80000000, 1, 0x80000001, 0x00800000, 0x80800000, 0x3f800000,
                    0xbf800000, 0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000, 0x7fc00000, 0x7f800001, 0xffc00001};
            for (int fixture = 0; fixture < 10000; fixture++) {
                float[] values = new float[4];
                for (int i = 0; i < values.length; i++) values[i] = Float.intBitsToFloat(fixture < 4 * special.length
                        ? special[(fixture + i) % special.length] : random.nextInt());
                Object a = scalar.newInstance(values[0], values[1]), b = scalar.newInstance(values[2], values[3]);
                Object result = minus.invoke(a, fixture % 17 == 0 ? a : b);
                Object copied = copy.newInstance(result), another = minus.invoke(a, fixture % 17 == 0 ? a : b);
                require(result != null && result.getClass() == vector && result != a && result != b
                        && result != another && copied != result, "fresh exact native result/copy identities");
                int rx = Float.floatToRawIntBits((float) x.invoke(result));
                int ry = Float.floatToRawIntBits((float) y.invoke(result));
                require(rx == Float.floatToRawIntBits((float) x.invoke(copied))
                        && ry == Float.floatToRawIntBits((float) y.invoke(copied)), "outer copy preserves raw fields");
                setX.invoke(a, 123.0f); setY.invoke(b, -456.0f);
                require(rx == Float.floatToRawIntBits((float) x.invoke(result))
                        && ry == Float.floatToRawIntBits((float) y.invoke(result)), "minus result never aliases inputs");
                setX.invoke(copied, 789.0f); setY.invoke(copied, -123.0f);
                require(rx == Float.floatToRawIntBits((float) x.invoke(result))
                        && ry == Float.floatToRawIntBits((float) y.invoke(result)), "copy/result independence");
            }
        }
        System.out.println("VECTOR_COPY_IDENTITY_PASS jar=" + jar + " fixtures=10000 checks=" + (checks - start));
    }
    public static void main(String[] args) throws Exception {
        for (String arg : args) one(Path.of(arg));
        System.out.println("VECTOR_COPY_IDENTITY_FINISHED checks=" + checks);
    }
}
