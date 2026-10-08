import dev.turboism.adapter.cubism.mesh.LazyTriangulationEdgeBridge;
import dev.turboism.adapter.cubism.mesh.TriangulationDefinitionLifecycle;
import dev.turboism.agent.shaded.asm.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Real cold logger initialization and logger-body mutation callbacks after lease release. */
public final class PointTriangleLoggerCallbackSelfCheck {
    static TriangulationDefinitionLifecycle life;
    static int initCallbacks,logCallbacks,nestedCalls;
    static PointTrianglePremainSelfCheck.Arm actual;
    public static void callback(String phase) {
        try {
            ClassFileTransformer noop=new ClassFileTransformer() {};
            // Would throw a read-to-write upgrade error if the point lease remains held.
            life.instrumentation().addTransformer(noop,false);
            if(!life.instrumentation().removeTransformer(noop))throw new AssertionError("callback removal failed");
            if(phase.equals("init"))initCallbacks++;else {logCallbacks++;actual.run(.2f,.2f,new float[]{0,0,1,0,0,1},new int[]{0,1,2},2,true);nestedCalls++;}
        }catch(Exception e){e.printStackTrace(System.err);throw new AssertionError("callback could not mutate/reenter",e);}
    }
    public static void main(String[] args)throws Exception {
        life=PointTrianglePremainSelfCheck.lifecycle();
        ClassFileTransformer loggerProbe=new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] raw) {
                if(type!=null || loader!=ClassLoader.getSystemClassLoader() || !"com/live2d/util/log/a".equals(name))return null;
                ClassReader reader=new ClassReader(raw);ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);int[] sites={0,0};
                reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        MethodVisitor out=super.visitMethod(access,name,descriptor,signature,exceptions);
                        String phase=name.equals("<clinit>")?"init":name.equals("c") && descriptor.equals("(Lcom/live2d/util/log/a;Ljava/lang/Object;ZLjava/lang/String;ILjava/lang/Object;)V")?"log":null;
                        if(phase==null)return out;
                        sites[phase.equals("init")?0:1]++;
                        return new MethodVisitor(Opcodes.ASM9,out) {
                            @Override public void visitCode(){super.visitCode();mv.visitLdcInsn(phase);mv.visitMethodInsn(Opcodes.INVOKESTATIC,"PointTriangleLoggerCallbackSelfCheck","callback","(Ljava/lang/String;)V",false);}
                        };
                    }
                },0);
                if(sites[0]!=1 || sites[1]!=1)throw new AssertionError("exact cold logger seam missing");
                return writer.toByteArray();
            }
        };
        life.instrumentation().addTransformer(loggerProbe,false);
        actual=new PointTrianglePremainSelfCheck.Arm(ClassLoader.getSystemClassLoader());
        try(AutoCloseable lease=LazyTriangulationEdgeBridge.enterPoint(actual.owner)){PointTrianglePremainSelfCheck.require(lease!=null,"callback test shared admission");}
        PointTrianglePremainSelfCheck.require(initCallbacks==0 && logCallbacks==0,"logger was not cold");
        URL location=actual.vector.getProtectionDomain().getCodeSource().getLocation();URL[] urls;
        try(var paths=Files.list(Path.of(location.toURI()).getParent())){urls=paths.filter(p->p.toString().endsWith(".jar")).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);}
        try(URLClassLoader loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader())) {
            PointTrianglePremainSelfCheck.Arm original=new PointTrianglePremainSelfCheck.Arm(loader);
            // Nondegenerate misses then degenerate containment enter the actual logger route.
            float[] coords={100,0,101,0,100,1,0,0,0,0,0,0};int[] ids={0,1,2,3,4,5};
            String expected=original.run(.2f,.2f,coords,ids,2,true), observed=actual.run(.2f,.2f,coords,ids,2,true);
            System.out.println("POINT_LOGGER_OUTPUT expected="+expected+" observed="+observed);
            PointTrianglePremainSelfCheck.require(expected.equals(observed),"logger output/exception mismatch");
            PointTrianglePremainSelfCheck.require(initCallbacks==1 && logCallbacks==1 && nestedCalls==1,"cold/body/reentry callbacks missing");
            PointTrianglePremainSelfCheck.require(LazyTriangulationEdgeBridge.enterPoint(actual.owner)==null,"logger callback did not permanently revoke gate");
        }
        PointTrianglePremainSelfCheck.require(life.instrumentation().removeTransformer(loggerProbe),"remove callback transformer");
        System.out.println("POINT_TRIANGLE_LOGGER_CALLBACK_PASS init="+initCallbacks+" log="+logCallbacks+" nested="+nestedCalls+" nativeOutputParity=true editorLaunched=false");
    }
}
