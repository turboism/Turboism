"""Extract the reviewed serial, fixed-scale legacy kernel; never load Cubism classes.

Usage: python3 extract-legacy.py /path/to/CubismTextureAtlasLayoutTool.java /tmp/LegacyTextureBench.java
This is not a native benchmark, nor coverage of legacy automatic/parallel writeback.
"""
from pathlib import Path
import hashlib
import sys

source = Path(sys.argv[1]).read_bytes()
expected = "0171475d7df32be11d9f281e23838972222e2cd8c1f7687cf969304a9edf9620"
if hashlib.sha256(source).hexdigest() != expected:
    raise SystemExit("Legacy source is not the reviewed 3c2f0fb artifact; refusing line-based extraction")
src = source.decode().splitlines()


def lines(first, last):
    return "\n".join(src[first - 1:last]) + "\n"


head = '''import java.util.*;
import java.util.concurrent.*;
public class LegacyTextureBench {
 static final int FORK_GRANULARITY=64, PARALLEL_THRESHOLD=16;
 static final boolean parallelEnabled=false;
 static PackingResult run(List<ItemInfo> items,int atlasWidth,int atlasHeight) {
 int margin=0; boolean allowRotate=false; double layoutScale=1.0;
'''
body = lines(443, 443) + lines(452, 476) + lines(483, 486) + lines(514, 597)
body = body.replace("return false;", "return null;")
tail = '''return bestResult;
 }
 public static void main(String[] args) {
 int n=Integer.parseInt(args[0]), side=Integer.parseInt(args[1]);
 Random rand=new Random(51); List<ItemInfo> input=new ArrayList<>();
 for(int i=0;i<n;i++)input.add(new ItemInfo(null,i,16+rand.nextInt(81),16+rand.nextInt(81),1.0));
 for(int iter=0;iter<10;iter++) {
 List<ItemInfo> items=new ArrayList<>(); for(var x:input) items.add(new ItemInfo(null,x.index,x.w,x.h,x.scale));
 long t=System.nanoTime(); var result=run(items,side,side); double ms=(System.nanoTime()-t)/1e6;
 int placed=0; for(boolean b:result.placed)if(b)placed++;
 if(placed!=n)throw new AssertionError("not all placed "+placed);
 for(int i=0;i<n;i++) {var x=items.get(i); if(result.placedX[i]<0||result.placedY[i]<0||result.placedX[i]+x.w>side||result.placedY[i]+x.h>side)throw new AssertionError("bounds");
 for(int j=0;j<i;j++){var y=items.get(j); if(result.placedX[i]<result.placedX[j]+y.w && result.placedX[j]<result.placedX[i]+x.w && result.placedY[i]<result.placedY[j]+y.h && result.placedY[j]<result.placedY[i]+x.h)throw new AssertionError("overlap");}}
 System.out.printf(Locale.ROOT,"legacy n=%d side=%d iteration=%d ms=%.3f placed=%d%n",n,side,iter,ms,placed);
 }
 }
'''
Path(sys.argv[2]).write_text(head + body + tail + lines(661, 768) + lines(1272, 1477) + '}\n')
