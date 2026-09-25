package dev.turboism.adapter.cubism.optimization.image;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TextureUploadPreparationArtifactTest {
    @Test void exactFactoryTransformsAndOtherOverloadRemainsUnchanged() throws Exception {
        String configured=System.getProperty("turboism.test.cubism5302Jar","");
        assumeTrue(!configured.isBlank(),"exact local artifact not configured");
        Path artifact=Path.of(configured);
        assertTrue(Files.isRegularFile(artifact));
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
        URL[] urls;
        try(var files=Files.walk(artifact.getParent())) {
            urls=files.filter(path->path.toString().endsWith(".jar")).map(path->{try{return path.toUri().toURL();}catch(Exception failure){throw new IllegalStateException(failure);}}).toArray(URL[]::new);
        }
        try(var loader=new URLClassLoader(urls,getClass().getClassLoader());var jar=new JarFile(artifact.toFile())) {
            byte[] bytes;
            try(var input=jar.getInputStream(jar.getJarEntry(TextureUploadPreparationTransformer.TARGET+".class"))){bytes=input.readAllBytes();}
            var transformer=new TextureUploadPreparationTransformer(loader,artifact,bytes);
            var domain=new ProtectionDomain(new CodeSource(artifact.toUri().toURL(),(java.security.cert.Certificate[])null),null);
            byte[] transformed=transformer.transform(null,loader,TextureUploadPreparationTransformer.TARGET,null,domain,bytes);
            assertNotNull(transformed,transformer.failure());
            assertEquals(1,transformer.matches());
            String other="(Lcom/live2d/graphics3d/a;Lcom/live2d/graphics/CWritableImage;ZZLjava/lang/String;)I";
            var otherShape=ReviewedMethodShape.read(bytes,TextureUploadPreparationTransformer.TARGET,"a",other);
            assertNotNull(otherShape,"the unmodified overload must exist in the reviewed artifact");
            assertEquals(otherShape,ReviewedMethodShape.read(transformed,TextureUploadPreparationTransformer.TARGET,"a",other));
            ClassWriter changed=new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9,changed) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                    var original=super.visitMethod(access,name,desc,sig,exceptions);
                    if(!name.equals("a")||!desc.equals(TextureUploadPreparationTransformer.METHOD_DESCRIPTOR))return original;
                    return new MethodVisitor(Opcodes.ASM9,original) {
                        @Override public void visitCode(){super.visitCode();super.visitInsn(Opcodes.NOP);}
                    };
                }
            },0);
            assertNull(transformer.transform(null,loader,TextureUploadPreparationTransformer.TARGET,null,domain,changed.toByteArray()));
            assertNotNull(transformer.failure());
        }
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
    }
}
