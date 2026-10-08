import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual sole-premain shared admission, SDK method and definition revocation. */
public final class PointTrianglePremainSelfCheck {
    static final String POINT="com.live2d.cubism.doc.model.drawable.artMesh.PointInTriangleD";
    static final String VECTOR="com.live2d.graphics3d.type.GVector2";
    static int checks;
    static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static TriangulationDefinitionLifecycle lifecycle()throws Exception {
        Class<?> shims=Class.forName("dev.turboism.bootstrap.JvmShims");
        for(String name:new String[]{"STARTUP_SUPPRESSION","PIPE_IMPL_SHIM"}) {
            Field holder=shims.getDeclaredField(name);holder.setAccessible(true);Object installation=((AtomicReference<?>)holder.get(null)).get();if(installation==null)continue;
            Field f=installation.getClass().getDeclaredField("instrumentation");f.setAccessible(true);Instrumentation owned=(Instrumentation)f.get(installation);
            if(owned!=null && TriangulationDefinitionLifecycle.ownedBy(owned)!=null)return TriangulationDefinitionLifecycle.ownedBy(owned);
        }
        throw new AssertionError("actual owned lifecycle unavailable");
    }
    static final class Arm {
        final Class<?> vector,owner;final Constructor<?> constructor;final Object companion;final Method query;
        Arm(ClassLoader loader)throws Exception {
            vector=Class.forName(VECTOR,true,loader);constructor=vector.getConstructor(float.class,float.class);
            Class<?> outer=Class.forName(POINT,true,loader);owner=Class.forName(POINT+"$a",true,loader);
            Field f=Arrays.stream(outer.getDeclaredFields()).filter(x->Modifier.isStatic(x.getModifiers()) && x.getType()==owner).findFirst().orElseThrow();f.setAccessible(true);companion=f.get(null);query=owner.getMethod("a",vector,float[].class,int[].class,int.class,boolean.class);
        }
        String run(float x,float y,float[] coords,int[] indices,int stride,boolean nullable)throws Exception {
            Object point=constructor.newInstance(x,y);float[] input=coords==null?null:coords.clone();int[] ids=indices==null?null:indices.clone();
            try {Object result=query.invoke(companion,point,input,ids,stride,nullable);if(result==null)return "null";List<String> fields=new ArrayList<>();for(Field f:result.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())) {f.setAccessible(true);Object v=f.get(result);fields.add(f.getName()+":"+(v instanceof Double d?Long.toHexString(Double.doubleToRawLongBits(d)):String.valueOf(v)));}Collections.sort(fields);return fields.toString();}
            catch(InvocationTargetException e){Throwable t=e.getCause();return "throw:"+t.getClass().getName()+":"+t.getMessage();}
            finally{require(Arrays.equals(input,coords) && Arrays.equals(ids,indices),"input changed");require(Float.floatToRawIntBits((float)vector.getMethod("getX").invoke(point))==Float.floatToRawIntBits(x) && Float.floatToRawIntBits((float)vector.getMethod("getY").invoke(point))==Float.floatToRawIntBits(y),"point changed");}
        }
    }
    static void compare(Arm nativeArm,Arm optimized,float x,float y,float[] coords,int[] indices,int stride,boolean nullable)throws Exception {
        require(nativeArm.run(x,y,coords,indices,stride,nullable).equals(optimized.run(x,y,coords,indices,stride,nullable)),"result/exception mismatch");
    }
    public static void main(String[] args)throws Exception {
        String mode=args[0];TriangulationDefinitionLifecycle life=lifecycle();require(life.startupReason().equals("SUPPORTED_OWNED_PREMAIN"),"startup protocol");
        URL location=Class.forName(VECTOR,false,ClassLoader.getSystemClassLoader()).getProtectionDomain().getCodeSource().getLocation();Path archive=Path.of(location.toURI());URL[] urls;
        try(var paths=Files.list(archive.getParent())) {urls=paths.filter(p->p.toString().endsWith(".jar")).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);}
        try(URLClassLoader original=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader())) {
            Arm nativeArm=new Arm(original),optimized=new Arm(ClassLoader.getSystemClassLoader());
            if(mode.equals("edge-first")){Class<?> h=Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h");try(AutoCloseable lease=LazyTriangulationEdgeBridge.enter(h)){require(lease!=null,"edge-first admission");}}
            try(AutoCloseable lease=LazyTriangulationEdgeBridge.enterPoint(optimized.owner)){require(lease!=null,"point shared gate missing");}
            Class<?> h=Class.forName("com.live2d.graphics3d.editableMesh.triangulation.h");try(AutoCloseable lease=LazyTriangulationEdgeBridge.enter(h)){require(lease!=null,"point capture revoked edge");}
            Class<?> mesh=Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2");try(AutoCloseable lease=LazyTriangulationEdgeBridge.enterMesh(mesh)){require(lease!=null,"point capture lost mesh gate");}
            Random random=new Random(104);for(int i=0;i<150;i++){float[] coords=new float[18];for(int j=0;j<coords.length;j++)coords[j]=(random.nextFloat()-.5f)*50;int[] indices=new int[18];for(int j=0;j<indices.length;j++)indices[j]=random.nextInt(9);for(boolean nullable:new boolean[]{true,false})compare(nativeArm,optimized,random.nextFloat()*10,random.nextFloat()*10,coords,indices,2,nullable);}
            float[] triangle={0,0,1,0,0,1};
            for(int[] ids:new int[][]{{0,1,2},{},{-1,-1,-1},{-1,0,1},{0,1},{0,1,99}})for(int stride:new int[]{-1,0,1,2,3,Integer.MAX_VALUE})for(boolean nullable:new boolean[]{true,false})compare(nativeArm,optimized,.2f,.2f,triangle,ids,stride,nullable);
            compare(nativeArm,optimized,Float.NaN,-0f,triangle,new int[]{0,1,2},2,false);
            compare(nativeArm,optimized,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,triangle,new int[]{0,1,2},2,true);
            if(mode.equals("revocation")) {
                java.lang.instrument.ClassFileTransformer noop=new java.lang.instrument.ClassFileTransformer() {};
                life.instrumentation().addTransformer(noop,false);
                require(LazyTriangulationEdgeBridge.enterPoint(optimized.owner)==null,"revoked point optimized");
                require(LazyTriangulationEdgeBridge.enter(h)==null,"revoked shared edge optimized");
                require(life.instrumentation().removeTransformer(noop),"remove owned test transformer");
                compare(nativeArm,optimized,.2f,.2f,triangle,new int[]{0,1,2},2,false);
                require(LazyTriangulationEdgeBridge.enterPoint(optimized.owner)==null,"permanent gate revocation reset");
            }
        }
        System.out.println("POINT_TRIANGLE_PREMAIN_PASS mode="+mode+" checks="+checks+" editorLaunched=false performanceAcceptance=NOT_GRANTED");
    }
}
