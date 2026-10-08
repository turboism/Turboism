package dev.turboism.adapter.cubism.interpolation;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

final class DerivedCacheSerializationTest {
    @Test void nestedContextIsThreadLocalAndDisabledProjectionReadsLiveProperties() throws Exception {
        DerivedCacheSerializationBridge.enabled(true);
        DerivedCacheSerializationBridge.enter();
        try {
            assertTrue(DerivedCacheSerializationBridge.projectCache());
            DerivedCacheSerializationBridge.enter();DerivedCacheSerializationBridge.exit();
            assertTrue(DerivedCacheSerializationBridge.projectCache());
            var observed=new AtomicReference<Boolean>();
            Thread thread=new Thread(()->observed.set(DerivedCacheSerializationBridge.projectCache()));
            thread.start();thread.join();assertEquals(false,observed.get());
            var field=Unrelated.class.getDeclaredField("normalExtendedKeyforms");field.setAccessible(true);
            var unrelated=new Unrelated();assertSame(unrelated.normalExtendedKeyforms,DerivedCacheSerializationBridge.fieldValue(field,unrelated));
            DerivedCacheSerializationBridge.enabled(false);assertFalse(DerivedCacheSerializationBridge.projectCache());
        } finally { DerivedCacheSerializationBridge.exit();DerivedCacheSerializationBridge.enabled(false); }
        assertFalse(DerivedCacheSerializationBridge.projectCache());
    }
    @Test void gridProjectionDoesNotTouchUnrelatedGetterResultsOrNullMetadata() throws Exception {
        Object value=new Object();var receiver=new Unrelated();
        var field=Unrelated.class.getDeclaredField("normalExtendedKeyforms");
        DerivedCacheSerializationBridge.enabled(true);DerivedCacheSerializationBridge.gridEnabled(true);DerivedCacheSerializationBridge.enter();
        try {
            assertSame(value,DerivedCacheSerializationBridge.getterValue(value,field,receiver));
            assertSame(value,DerivedCacheSerializationBridge.getterValue(value,null,receiver));
            assertSame(value,DerivedCacheSerializationBridge.getterValue(value,field,null));
            assertNull(DerivedCacheSerializationBridge.getterValue(null,field,receiver));
        } finally {
            DerivedCacheSerializationBridge.exit();DerivedCacheSerializationBridge.gridEnabled(false);DerivedCacheSerializationBridge.enabled(false);
        }
    }

    @Test void productScopesDeclineNonModelRootsAndDoNotLeakDocumentEligibility() {
        DerivedCacheSerializationBridge.enabled(true);DerivedCacheSerializationBridge.modelRootOnly(true);
        Object document=new Object();
        DerivedCacheSerializationBridge.enter(new Object());
        try {
            assertFalse(DerivedCacheSerializationBridge.projectCache());
            DerivedCacheSerializationBridge.documentResult(document);
            assertFalse(DerivedCacheSerializationBridge.mayStream(document));
        } finally { DerivedCacheSerializationBridge.exit(); }
        assertFalse(DerivedCacheSerializationBridge.availableForModel(new Object()));
        DerivedCacheSerializationBridge.modelRootOnly(false);DerivedCacheSerializationBridge.enter();
        try {
            DerivedCacheSerializationBridge.modelRootOnly(true);DerivedCacheSerializationBridge.enter(new Object());
            try { assertTrue(DerivedCacheSerializationBridge.projectCache());DerivedCacheSerializationBridge.documentResult(document); }
            finally { DerivedCacheSerializationBridge.exit(); }
            assertTrue(DerivedCacheSerializationBridge.mayStream(document));
        } finally { DerivedCacheSerializationBridge.exit(); }
        DerivedCacheSerializationBridge.enter(new Object());
        try { assertFalse(DerivedCacheSerializationBridge.mayStream(document)); }
        finally { DerivedCacheSerializationBridge.exit();DerivedCacheSerializationBridge.modelRootOnly(false);DerivedCacheSerializationBridge.enabled(false); }
    }

    @Test void streamingFallsBackBeforeAnyWriteForDisabledOrUnreviewedObjects() {
        var writer=new Object();
        NativeXmlStreamingBridge.enabled(false);
        assertFalse(NativeXmlStreamingBridge.archive(writer,new Object(),new Object()));
        NativeXmlStreamingBridge.enabled(true);
        try {
            assertFalse(NativeXmlStreamingBridge.archive(writer,new Object(),new Object()));
            assertFalse(NativeXmlStreamingBridge.archive(null,new Object(),new Object()));
        } finally { NativeXmlStreamingBridge.enabled(false); }
    }
    static final class Unrelated { private final Object normalExtendedKeyforms=new Object(); }

    @Test void serializerWrapperRetainsBodyAndRestoresContextOnBothExitPaths() {
        var writer=new ClassWriter(0);writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,DerivedCacheSerializationPatch.OWNER,null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PRIVATE|Opcodes.ACC_FINAL,"serializeToDoc_exe","(Ljava/lang/Object;Lcom/live2d/serialize/XmlWriter;)Lorg/jdom/Document;",null,null);
        method.visitCode();method.visitLdcInsn("official-body");method.visitInsn(Opcodes.POP);method.visitInsn(Opcodes.ACONST_NULL);method.visitInsn(Opcodes.ARETURN);method.visitMaxs(1,3);method.visitEnd();writer.visitEnd();
        byte[] patched=DerivedCacheSerializationPatch.serializer(writer.toByteArray());
        var calls=new ArrayList<String>();var literals=new ArrayList<Object>();var handlers=new ArrayList<String>();
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String signature,String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode,String owner,String method,String descriptor,boolean iface) { calls.add(method); }
                    @Override public void visitLdcInsn(Object value) { literals.add(value); }
                    @Override public void visitTryCatchBlock(Label start,Label end,Label handler,String type) { handlers.add(type); }
                };
            }
        },0);
        assertEquals(java.util.List.of("enter","turboism$serializeToDoc_original","documentResult","exit","exit"),calls);
        assertEquals(java.util.List.of("java/lang/Throwable"),handlers);assertEquals(java.util.List.of("official-body"),literals);
        assertThrows(IllegalArgumentException.class,()->DerivedCacheSerializationPatch.serializer(patched));
    }

    @Test void serializationOnlyModeDoesNotTransformInterpolationClass() throws Exception {
        var reports=new ArrayList<String>();
        var transformer=new MeshInterpolationTransformer(reports::add,true,false);
        assertNull(transformer.transform(getClass().getClassLoader(),MeshInterpolationTransformer.OWNER,null,null,new byte[]{1}));
        assertTrue(reports.isEmpty());assertFalse(transformer.patched());
        assertNull(transformer.transform(getClass().getClassLoader(),DerivedCacheSerializationPatch.OWNER,null,null,new byte[]{1}));
        assertEquals(java.util.List.of("MESH_INTERPOLATION_DECLINED_DEFINITION"),reports);
    }

    @Test void projectionIsOptInAndUnreviewedSerializerBytesStayUnchanged() throws Exception {
        var reports=new ArrayList<String>();
        var transformer=new MeshInterpolationTransformer(reports::add);
        assertNull(transformer.transform(getClass().getClassLoader(),DerivedCacheSerializationPatch.OWNER,null,null,new byte[]{1}));
        assertTrue(reports.isEmpty());
        var optedIn=new MeshInterpolationTransformer(reports::add,true);
        var source=new java.security.CodeSource(java.net.URI.create("file:/tmp/unreviewed.jar").toURL(),(java.security.cert.Certificate[])null);
        var domain=new java.security.ProtectionDomain(source,null);
        assertNull(optedIn.transform(getClass().getClassLoader(),DerivedCacheSerializationPatch.OWNER,null,domain,new byte[]{1}));
        assertEquals(java.util.List.of("MESH_INTERPOLATION_DECLINED_CLASS_HASH"),reports);
        assertFalse(optedIn.serializationPatched());
    }
}
