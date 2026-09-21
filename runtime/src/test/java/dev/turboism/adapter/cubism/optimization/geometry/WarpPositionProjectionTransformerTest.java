package dev.turboism.adapter.cubism.optimization.geometry;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

public class WarpPositionProjectionTransformerTest {
    private static final String FORM="com/live2d/cubism/doc/model/ACForm";
    private static final String TYPE="com/live2d/cubism/doc/model/interpolator/extendedInterpolation/ExtendedInterpolationType";
    private static final String POINT=Point.class.getName().replace('.','/');
    public static class Point {
        public String value="native";
        public boolean fail;
        public Object getPos(){if(fail)throw new IllegalStateException("native failure");return value;}
        public void move(){value="written";}
    }
    private static final class Loader extends ClassLoader {
        Loader(){super(WarpPositionProjectionTransformerTest.class.getClassLoader());}
        Class<?> define(String name,byte[] bytes){return defineClass(name.replace('/','.'),bytes,0,bytes.length);}
    }

    @Test void fastProjectionSkipsOnlyFirstMapAndPreservesNativeWriteLoop() throws Exception {
        exercise(form->List.of("fast"),true,false);
    }
    @Test void absentNullWrongAndThrowingCallbacksUseOriginalBlock() throws Exception {
        exercise(null,false,false);
        exercise(form->null,false,false);
        exercise(form->"wrong",false,false);
        exercise(form->{throw new AssertionError("callback error");},false,false);
    }
    @Test void nativeExceptionIsNotSwallowedByCallbackFallback() throws Exception {
        exercise(form->{throw new IllegalStateException("callback error");},false,true);
    }
    @Test void changedNativeBodyIsNotTransformed() throws Exception {
        Loader loader=new Loader();byte[] original=target(false);
        var transformer=new WarpPositionProjectionTransformer(loader,null,original);
        assertNull(transformer.transform(null,loader,WarpPositionProjectionTransformer.TARGET,null,null,target(true)));
        assertNotNull(transformer.failure());
    }

    private static void exercise(Function<Object,Object> callback,boolean fast,boolean nativeFailure) throws Exception {
        String slot=WarpPositionProjectionBridge.CALLBACK_PROPERTY;
        Object old=System.getProperties().get(slot);
        try {
            if(callback==null)System.getProperties().remove(slot);else System.getProperties().put(slot,callback);
            Loader loader=new Loader();Class<?> formType=loader.define(FORM,form());Class<?> type=loader.define(TYPE,empty(TYPE));
            byte[] original=target(false);
            var transformer=new WarpPositionProjectionTransformer(loader,null,original);
            byte[] output=transformer.transform(null,loader,WarpPositionProjectionTransformer.TARGET,null,null,original);
            assertNotNull(output,transformer.failure());assertEquals(1,transformer.matches());
            Class<?> target=loader.define(WarpPositionProjectionTransformer.TARGET,output);
            Point point=new Point();point.fail=nativeFailure;
            Object form=formType.getConstructor(List.class).newInstance(List.of(point));
            List<Object> sink=new ArrayList<>();
            var method=target.getMethod(WarpPositionProjectionTransformer.METHOD,List.class,List.class,List.class,type,float.class);
            if(nativeFailure) {
                var failure=assertThrows(InvocationTargetException.class,()->method.invoke(target.getConstructor().newInstance(),List.of(form),List.of(),sink,null,1f));
                assertEquals("native failure",failure.getCause().getMessage());assertEquals("native",point.value);
            } else {
                method.invoke(target.getConstructor().newInstance(),List.of(form),List.of(),sink,null,1f);
                assertEquals(List.of(List.of(fast?"fast":"native")),sink);
                assertEquals("written",point.value,"the second reference-based write must still execute");
                assertEquals(fast?1:2,formType.getField("reads").getInt(form));
            }
        } finally {if(old==null)System.getProperties().remove(slot);else System.getProperties().put(slot,old);}
    }

    private static ClassWriter writer(String name) {
        ClassWriter w=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        w.visit(V17,ACC_PUBLIC,name,null,"java/lang/Object",null);return w;
    }
    private static void constructor(ClassWriter w) {
        var m=w.visitMethod(ACC_PUBLIC,"<init>","()V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);
        m.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
    }
    private static byte[] empty(String name){var w=writer(name);constructor(w);w.visitEnd();return w.toByteArray();}
    private static byte[] form() {
        var w=writer(FORM);w.visitField(ACC_PUBLIC,"reads","I",null,null).visitEnd();w.visitField(ACC_PUBLIC,"points","Ljava/util/List;",null,null).visitEnd();
        var m=w.visitMethod(ACC_PUBLIC,"<init>","(Ljava/util/List;)V",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitMethodInsn(INVOKESPECIAL,"java/lang/Object","<init>","()V",false);
        m.visitVarInsn(ALOAD,0);m.visitVarInsn(ALOAD,1);m.visitFieldInsn(PUTFIELD,FORM,"points","Ljava/util/List;");m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(ACC_PUBLIC,"getAllPointRef","()Ljava/util/List;",null,null);m.visitCode();m.visitVarInsn(ALOAD,0);m.visitInsn(DUP);m.visitFieldInsn(GETFIELD,FORM,"reads","I");m.visitInsn(ICONST_1);m.visitInsn(IADD);m.visitFieldInsn(PUTFIELD,FORM,"reads","I");
        m.visitVarInsn(ALOAD,0);m.visitFieldInsn(GETFIELD,FORM,"points","Ljava/util/List;");m.visitInsn(ARETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static byte[] target(boolean changed) {
        var w=writer(WarpPositionProjectionTransformer.TARGET);constructor(w);
        var m=w.visitMethod(ACC_PUBLIC,WarpPositionProjectionTransformer.METHOD,WarpPositionProjectionTransformer.DESCRIPTOR,null,null);m.visitCode();
        if(changed)m.visitInsn(NOP);
        m.visitVarInsn(ALOAD,1);m.visitInsn(ICONST_0);m.visitMethodInsn(INVOKEINTERFACE,"java/util/List","get","(I)Ljava/lang/Object;",true);m.visitTypeInsn(CHECKCAST,FORM);m.visitVarInsn(ASTORE,6);
        m.visitVarInsn(ALOAD,6);m.visitMethodInsn(INVOKEVIRTUAL,FORM,"getAllPointRef","()Ljava/util/List;",false);m.visitVarInsn(ASTORE,7);
        m.visitTypeInsn(NEW,"java/util/ArrayList");m.visitInsn(DUP);m.visitMethodInsn(INVOKESPECIAL,"java/util/ArrayList","<init>","()V",false);m.visitVarInsn(ASTORE,8);
        m.visitVarInsn(ALOAD,7);m.visitMethodInsn(INVOKEINTERFACE,"java/util/List","iterator","()Ljava/util/Iterator;",true);m.visitVarInsn(ASTORE,9);
        Label loop=new Label(),end=new Label();m.visitLabel(loop);
        m.visitVarInsn(ALOAD,9);m.visitMethodInsn(INVOKEINTERFACE,"java/util/Iterator","hasNext","()Z",true);m.visitJumpInsn(IFEQ,end);
        m.visitVarInsn(ALOAD,8);m.visitVarInsn(ALOAD,9);m.visitMethodInsn(INVOKEINTERFACE,"java/util/Iterator","next","()Ljava/lang/Object;",true);m.visitTypeInsn(CHECKCAST,POINT);
        m.visitMethodInsn(INVOKEVIRTUAL,POINT,"getPos","()Ljava/lang/Object;",false);m.visitMethodInsn(INVOKEINTERFACE,"java/util/List","add","(Ljava/lang/Object;)Z",true);m.visitInsn(POP);m.visitJumpInsn(GOTO,loop);
        m.visitLabel(end);m.visitVarInsn(ALOAD,8);m.visitTypeInsn(CHECKCAST,"java/util/List");m.visitVarInsn(ASTORE,10);
        m.visitVarInsn(ALOAD,3);m.visitVarInsn(ALOAD,10);m.visitMethodInsn(INVOKEINTERFACE,"java/util/List","add","(Ljava/lang/Object;)Z",true);m.visitInsn(POP);
        m.visitVarInsn(ALOAD,6);m.visitMethodInsn(INVOKEVIRTUAL,FORM,"getAllPointRef","()Ljava/util/List;",false);m.visitInsn(ICONST_0);m.visitMethodInsn(INVOKEINTERFACE,"java/util/List","get","(I)Ljava/lang/Object;",true);m.visitTypeInsn(CHECKCAST,POINT);
        m.visitMethodInsn(INVOKEVIRTUAL,POINT,"move","()V",false);m.visitInsn(RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
}
