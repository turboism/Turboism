package dev.turboism.diagnostic;

import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import com.live2d.graphics3d.type.GVector2;
import dev.turboism.adapter.cubism.interpolation.EllipseWeights;
import java.util.*;

/** Exact official-library coefficient differential, without a model or Editor. */
public final class EllipseWeightProof {
    public static void main(String[] args) {
        Random random=new Random(5302);int checked=0;
        for(int n:new int[]{2,3,5,9}) for(int scenario=0;scenario<4;scenario++) {
            float[] knots=new float[n];
            for(int i=0;i<n;i++)knots[i]=scenario==0?-180+i*360f/(n-1):scenario==1?i*37.5f:scenario==2?0f:random.nextFloat()*360-180;
            var vectors=new ArrayList<GVector2>();var values=new ArrayList<Float>();
            float[][] src=new float[n][2];
            for(int i=0;i<n;i++){vectors.add(new GVector2());values.add(knots[i]);}
            for(int interval=0;interval<n-1;interval++) for(float t:new float[]{-1f,0f,.125f,.25f,.5f,.75f,1f,2f,Float.MAX_VALUE}) {
                var weights=EllipseWeights.prepare(knots,interval,t,jp.noids.util.ap.c);
                for(int round=0;round<20;round++) {
                    for(int i=0;i<n;i++){
                        src[i][0]=round==0?0f:round==1?-0f:random.nextFloat()*2000-1000;
                        src[i][1]=round==0?-0f:round==1?0f:random.nextFloat()*2000-1000;
                        vectors.get(i).set(src[i][0],src[i][1]);
                    }
                    var curve=ExtendedInterpolationType.ELLIPSE.b();curve.a(vectors,true,null,values);
                    GVector2 official=curve.a(interval,t,new GVector2());
                    for(int axis=0;axis<2;axis++) {
                        float expected=axis==0?official.getX():official.getY();
                        float actual=weights.evaluate(src,axis);
                        // NaN payload is implementation dependent; all finite/signed-zero bits must match.
                        if(!(Float.isNaN(expected)&&Float.isNaN(actual)) && Float.floatToRawIntBits(expected)!=Float.floatToRawIntBits(actual))
                            throw new AssertionError("ellipse bits n="+n+" scenario="+scenario+" interval="+interval+" t="+t+" round="+round+" expected="+expected+" actual="+actual);
                        checked++;
                    }
                }
            }
        }
        System.out.println("PASS exact official ellipse coefficients scalarComparisons="+checked+" includes boundaries, irregular/degenerate knots and signed zero");
    }
}
