import com.live2d.cubism.doc.model.deformer.warp.o;
import java.io.*;

/** Offline math only. No document, editor, UI, or native acceptance. */
public final class OfficialWarpKernelProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(args[0])));
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[1])))) {
                int batches = in.readInt();
                for (int b = 0; b < batches; b++) {
                    int columns = in.readInt(), rows = in.readInt(), count = in.readInt();
                    boolean quad = in.readBoolean();
                    float[] controls = new float[2 * (columns + 1) * (rows + 1)];
                    float[] source = new float[count * 2], result = new float[count * 2];
                    for (int i = 0; i < controls.length; i++) controls[i] = in.readFloat();
                    for (int i = 0; i < source.length; i++) source[i] = in.readFloat();
                    o.a.a(source, result, count, 0, 2, controls, columns, rows, quad);
                    for (float value : result) out.writeFloat(value);
                }
                if (in.read() != -1) throw new AssertionError("trailing input");
            }
            return;
        }
        float[] points = {0,0, 1,0, 0,1, 1,1, .25f,.5f, .75f,.5f, -.1f,.4f, 1.1f,.4f};
        float[] identity = {0,0, 1,0, 0,1, 1,1};
        for (boolean quad : new boolean[]{false,true}) {
            float[] result = new float[points.length];
            o.a.a(points, result, points.length/2, 0, 2, identity, 1, 1, quad);
            for (int i=0;i<points.length;i++)
                if (Math.abs(result[i]-points[i]) > 1e-6) throw new AssertionError("identity/boundary");
            float[] affine = {-2,4, 1,6, 3,3, 6,5};
            o.a.a(points, result, points.length/2, 0, 2, affine, 1, 1, quad);
            for (int i=0;i<points.length/2;i++) {
                float u=points[2*i],v=points[2*i+1];
                if (Math.abs(result[2*i]-(-2+3*u+5*v))>2e-6
                        || Math.abs(result[2*i+1]-(4+2*u-v))>2e-6)
                    throw new AssertionError("affine/boundary");
            }
            float[] impulse = {0,0, 0,0, 0,0, 1,0};
            o.a.a(points, result, points.length/2, 0, 2, impulse, 1, 1, quad);
            for (int i=0;i<6;i++) {
                float u=points[2*i], v=points[2*i+1];
                float expected=quad ? u*v : Math.max(0,u+v-1);
                if (Math.abs(result[2*i]-expected)>1e-6 || result[2*i+1]!=0)
                    throw new AssertionError("interpolation basis");
            }
        }
        System.out.println("OFFICIAL_JAVA_KERNEL_OFFLINE_PASS quad=true:BILINEAR quad=false:TRIANGLE_LINEAR identity+affine+boundary+impulse; hostExecuted=false");
    }
}
