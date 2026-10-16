package dev.turboism.validation.modelimport3d.host;
import java.util.*;
public class NativeVisibilityScanProof {
 static long[][] before(float[][] z,int pixels){
  int n=z.length;long[][] votes=new long[n][n];
  for(int k=0;k<pixels;k++){int best=-1;float dep=Float.NEGATIVE_INFINITY;
   for(int i=0;i<n;i++)if(z[i][k]>dep){dep=z[i][k];best=i;}
   if(best<0)continue;
   for(int i=0;i<n;i++)if(i!=best&&Float.isFinite(z[i][k])&&z[i][k]<dep-1e-6)votes[i][best]++;
  }return votes;
 }
 public static void main(String[] args){
  int n=940,pixels=42500;var rng=new Random(66);float[][] z=new float[n][pixels];
  for(var row:z)for(int k=0;k<pixels;k++)row[k]=rng.nextInt(20)==0?rng.nextInt(10):Float.NEGATIVE_INFINITY;
  long[] old=new long[5],now=new long[5];
  for(int pass=0;pass<7;pass++){
   long t=System.nanoTime();var a=before(z,pixels);long mid=System.nanoTime();var b=NativeCharacterVisibility.coverageVotes(z,pixels);long end=System.nanoTime();
   if(!Arrays.deepEquals(a,b))throw new AssertionError("different votes");
   if(pass>=2){old[pass-2]=mid-t;now[pass-2]=end-mid;}
  }
  Arrays.sort(old);Arrays.sort(now);
  System.out.printf(Locale.ROOT,"partitions=%d pixels=%d entries=%d exact=true baselineMedianMs=%.3f contiguousMedianMs=%.3f speedup=%.2f%n",n,pixels,(long)n*pixels,old[2]/1e6,now[2]/1e6,(double)old[2]/now[2]);
 }
}
