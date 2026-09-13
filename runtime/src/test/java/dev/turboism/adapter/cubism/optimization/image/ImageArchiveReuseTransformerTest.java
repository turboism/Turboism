package dev.turboism.adapter.cubism.optimization.image;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

class ImageArchiveReuseTransformerTest {
    private static final String RESOURCE = "com/live2d/graphics/CImageResource";
    private static final String IMAGE = "com/live2d/graphics/CWritableImage";
    private static final String KEY = "turboism.image-archive-reuse.callback";

    @Test
    void executesNativeCleanupAndFallsBackWhenCallbackIsMissingMalformedOrThrowing() throws Exception {
        var loader = new FixtureLoader();
        loader.define(IMAGE, imageBytes());
        var transformer = new ImageArchiveReuseTransformer(loader, null);
        byte[] transformed = transformer.transform(null, loader, RESOURCE, null, null, resourceBytes());
        assertNotNull(transformed);
        var resourceType = loader.define(RESOURCE, transformed);
        var decoded = resourceType.getDeclaredMethod("getImage_exe"); decoded.setAccessible(true);
        var archive = resourceType.getMethod("archive");
        AtomicInteger decodeCalls = new AtomicInteger();
        Object original = System.getProperties().get(KEY);
        try {
            System.getProperties().put(KEY, (BiFunction<Object,Object,Object>) (owner, image) -> {
                assertTrue(Thread.holdsLock(owner));
                if (image == null) { decodeCalls.incrementAndGet(); return null; }
                return new byte[]{9};
            });
            Object resource = resourceType.getConstructor().newInstance();
            Object image = decoded.invoke(resource);
            assertSame(image, decoded.invoke(resource));
            assertEquals(1, decodeCalls.get(), "only actual decode assignments are observed");
            archive.invoke(resource);
            assertArrayEquals(new byte[]{9}, ((ByteArrayOutputStream)resourceType.getField("output").get(resource)).toByteArray());
            assertTrue(resourceType.getField("cleanup").getBoolean(resource));
            assertEquals(0, image.getClass().getField("encodes").getInt(image));

            for (Object bad : new Object[]{"malformed", (BiFunction<Object,Object,Object>)(a,b) -> { throw new AssertionError("probe failure"); }}) {
                System.getProperties().put(KEY, bad);
                resource = resourceType.getConstructor().newInstance(); image = decoded.invoke(resource);
                archive.invoke(resource);
                assertEquals(1, image.getClass().getField("encodes").getInt(image));
                assertArrayEquals(new byte[]{7}, ((ByteArrayOutputStream)resourceType.getField("output").get(resource)).toByteArray());
                assertTrue(resourceType.getField("cleanup").getBoolean(resource));
                assertFalse(resourceType.getField("nativeFailure").getBoolean(resource), "callback guard must precede the native outer catch");
            }
            System.getProperties().remove(KEY);
            resource = resourceType.getConstructor().newInstance(); image = decoded.invoke(resource); archive.invoke(resource);
            assertEquals(1, image.getClass().getField("encodes").getInt(image));
        } finally {
            if (original == null) System.getProperties().remove(KEY); else System.getProperties().put(KEY, original);
        }
    }

    @Test
    void ignoresOtherLoadersAndClasses() throws Exception {
        var loader = new FixtureLoader();
        var transformer = new ImageArchiveReuseTransformer(loader, null);
        assertNull(transformer.transform(null, getClass().getClassLoader(), RESOURCE, null, null, resourceBytes()));
        assertNull(transformer.transform(null, loader, "other/Resource", null, null, resourceBytes()));
    }

    private static byte[] imageBytes() {
        var w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, IMAGE, null, "java/lang/Object", null); constructor(w, IMAGE);
        w.visitField(Opcodes.ACC_PUBLIC, "encodes", "I", null, null).visitEnd();
        var m = w.visitMethod(Opcodes.ACC_PUBLIC, "writeImageAsPng", "(Ljava/io/OutputStream;)V", null, new String[]{"java/io/IOException"});
        m.visitCode(); m.visitVarInsn(Opcodes.ALOAD,0); m.visitInsn(Opcodes.DUP); m.visitFieldInsn(Opcodes.GETFIELD,IMAGE,"encodes","I");
        m.visitInsn(Opcodes.ICONST_1);m.visitInsn(Opcodes.IADD);m.visitFieldInsn(Opcodes.PUTFIELD,IMAGE,"encodes","I");
        m.visitVarInsn(Opcodes.ALOAD,1);m.visitIntInsn(Opcodes.BIPUSH,7);m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"java/io/OutputStream","write","(I)V",false);
        m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }

    private static byte[] resourceBytes() {
        var w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, RESOURCE, null, "java/lang/Object", null); constructor(w,RESOURCE);
        w.visitField(Opcodes.ACC_PRIVATE,"image","L"+IMAGE+";",null,null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC,"output","Ljava/io/ByteArrayOutputStream;",null,null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC,"cleanup","Z",null,null).visitEnd();
        w.visitField(Opcodes.ACC_PUBLIC,"nativeFailure","Z",null,null).visitEnd();
        var m = w.visitMethod(Opcodes.ACC_PRIVATE|Opcodes.ACC_SYNCHRONIZED,"getImage_exe","()L"+IMAGE+";",null,null);
        m.visitCode();var exists=new Label();m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,RESOURCE,"image","L"+IMAGE+";");m.visitJumpInsn(Opcodes.IFNONNULL,exists);
        m.visitVarInsn(Opcodes.ALOAD,0);m.visitTypeInsn(Opcodes.NEW,IMAGE);m.visitInsn(Opcodes.DUP);m.visitMethodInsn(Opcodes.INVOKESPECIAL,IMAGE,"<init>","()V",false);m.visitFieldInsn(Opcodes.PUTFIELD,RESOURCE,"image","L"+IMAGE+";");
        m.visitLabel(exists);m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,RESOURCE,"image","L"+IMAGE+";");m.visitInsn(Opcodes.ARETURN);m.visitMaxs(0,0);m.visitEnd();
        m=w.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_SYNCHRONIZED,"archive","()V",null,null);m.visitCode();
        var start=new Label();var end=new Label();var handler=new Label();
        m.visitTryCatchBlock(start,end,handler,"java/lang/Throwable");m.visitLabel(start);
        m.visitVarInsn(Opcodes.ALOAD,0);m.visitTypeInsn(Opcodes.NEW,"java/io/ByteArrayOutputStream");m.visitInsn(Opcodes.DUP);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/io/ByteArrayOutputStream","<init>","()V",false);m.visitFieldInsn(Opcodes.PUTFIELD,RESOURCE,"output","Ljava/io/ByteArrayOutputStream;");
        m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,RESOURCE,"image","L"+IMAGE+";");m.visitVarInsn(Opcodes.ALOAD,0);m.visitFieldInsn(Opcodes.GETFIELD,RESOURCE,"output","Ljava/io/ByteArrayOutputStream;");m.visitMethodInsn(Opcodes.INVOKEVIRTUAL,IMAGE,"writeImageAsPng","(Ljava/io/OutputStream;)V",false);
        m.visitVarInsn(Opcodes.ALOAD,0);m.visitInsn(Opcodes.ICONST_1);m.visitFieldInsn(Opcodes.PUTFIELD,RESOURCE,"cleanup","Z");m.visitLabel(end);m.visitInsn(Opcodes.RETURN);
        m.visitLabel(handler);m.visitInsn(Opcodes.POP);m.visitVarInsn(Opcodes.ALOAD,0);m.visitInsn(Opcodes.ICONST_1);m.visitFieldInsn(Opcodes.PUTFIELD,RESOURCE,"nativeFailure","Z");m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0,0);m.visitEnd();w.visitEnd();return w.toByteArray();
    }
    private static void constructor(ClassWriter w,String owner) {
        var m=w.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);m.visitCode();m.visitVarInsn(Opcodes.ALOAD,0);m.visitMethodInsn(Opcodes.INVOKESPECIAL,"java/lang/Object","<init>","()V",false);m.visitInsn(Opcodes.RETURN);m.visitMaxs(0,0);m.visitEnd();
    }
    private static final class FixtureLoader extends ClassLoader {
        Class<?> define(String name,byte[] bytes){return defineClass(name.replace('/','.'),bytes,0,bytes.length);}
    }
}
