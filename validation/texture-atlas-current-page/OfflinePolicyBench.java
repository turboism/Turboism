package dev.turboism.validation.texture;

import dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Offline only. Synthetic inputs, no host/fixture/production Agent loading. */
public final class OfflinePolicyBench {
    private static volatile Object sink;
    public static void main(String[] args) throws Exception {
        System.out.println("variant,shape,count,density,mode,parallel,medianMs,scale,placed,overflow,outputHash");
        var planner = new CurrentPageTextureAtlasPlanner();
        for (String shape : List.of("square", "mixed", "wide"))
        for (int count : new int[]{16,32,64,128,500}) {
            var random = new Random(51 + count);
            var items = new ArrayList<TextureAtlasLayoutItem>();
            long area = 0;
            for (int i=0;i<count;i++) {
                int w=16+random.nextInt(81), h=16+random.nextInt(81);
                if (shape.equals("square")) w=h=48;
                if (shape.equals("wide")) { w*=3; h=Math.max(4,h/3); }
                items.add(new TextureAtlasLayoutItem(String.format(Locale.ROOT,"item-%05d",i),w,h));
                area+=(long)w*h;
            }
            for (double density : new double[]{0.45,1.15})
            for (double mode : new double[]{0,1})
            for (boolean parallel : new boolean[]{false,true}) {
                int side=(int)Math.ceil(Math.sqrt(area/density))+6;
                var c=TextureAtlasLayoutConstraints.currentPage(side,side,3,true,mode);
                var samples=new double[5];
                TextureAtlasLayoutPlan reference=null;
                for (int iteration=0;iteration<8;iteration++) {
                    long start=System.nanoTime();
                    var plan=planner.plan(items,c,parallel);
                    double elapsed=(System.nanoTime()-start)/1e6;
                    sink=plan;
                    validate(items,c,plan,mode);
                    if (reference!=null && !reference.equals(plan)) throw new AssertionError("nondeterministic");
                    reference=plan;
                    if(iteration>=3) samples[iteration-3]=elapsed;
                }
                Collections.reverse(items);
                if (!reference.equals(planner.plan(items,c,parallel))) throw new AssertionError("input order dependence");
                Collections.reverse(items);
                Arrays.sort(samples);
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference.toString().getBytes(StandardCharsets.UTF_8)));
                System.out.printf(Locale.ROOT,"%s,%s,%d,%.2f,%.0f,%s,%.6f,%.12f,%d,%d,%s%n",args[0],shape,count,density,mode,parallel,samples[2],reference.scale(),reference.placements().size(),count-reference.placements().size(),hash);
            }
        }
    }
    static void validate(List<TextureAtlasLayoutItem> items,TextureAtlasLayoutConstraints c,TextureAtlasLayoutPlan p,double mode) {
        if(p.pageCount()!=1 || !Double.isFinite(p.scale()) || p.scale()<=0 || (mode==0?p.scale()>1:p.scale()!=mode)) throw new AssertionError("scale/scope");
        var byId=new HashMap<String,TextureAtlasLayoutItem>();items.forEach(i->byId.put(i.textureId(),i));
        var ids=new HashSet<String>();
        for(var a:p.placements()) {
            var item=byId.get(a.textureId());
            if(item==null || !ids.add(a.textureId()) || a.pageIndex()!=0) throw new AssertionError("identity");
            int w=(int)Math.ceil(item.width()*p.scale()),h=(int)Math.ceil(item.height()*p.scale());
            if(a.width()!=(a.rotated()?h:w)||a.height()!=(a.rotated()?w:h)) throw new AssertionError("dimensions");
            if(a.x()<c.edgeMargin()||a.y()<c.edgeMargin()||(long)a.x()+a.width()>c.pageWidth()-c.edgeMargin()||(long)a.y()+a.height()>c.pageHeight()-c.edgeMargin()) throw new AssertionError("bounds");
        }
        var ps=p.placements();
        for(int i=0;i<ps.size();i++) for(int j=i+1;j<ps.size();j++) {
            var a=ps.get(i);var b=ps.get(j);int pad=c.itemPadding();
            if((long)a.x()+a.width()+pad>b.x() && (long)b.x()+b.width()+pad>a.x()
                && (long)a.y()+a.height()+pad>b.y() && (long)b.y()+b.height()+pad>a.y()) throw new AssertionError("padding/overlap");
        }
    }
}
