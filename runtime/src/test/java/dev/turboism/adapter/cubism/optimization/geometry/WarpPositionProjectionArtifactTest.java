package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WarpPositionProjectionArtifactTest {
    @Test void exactConsumerTransformsWithoutChangingOtherMethods() throws Exception {
        String configured=System.getProperty("turboism.test.cubism5302Jar","");
        assumeTrue(!configured.isBlank(),"exact local artifact not configured");
        Path artifact=Path.of(configured);
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
        URL[] urls;
        try(var files=Files.walk(artifact.getParent())) {
            urls=files.filter(p->p.toString().endsWith(".jar")).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new IllegalStateException(e);}}).toArray(URL[]::new);
        }
        try(var loader=new URLClassLoader(urls,getClass().getClassLoader());var jar=new JarFile(artifact.toFile())) {
            byte[] bytes;
            try(var input=jar.getInputStream(jar.getJarEntry(WarpPositionProjectionTransformer.TARGET+".class"))){bytes=input.readAllBytes();}
            var transformer=new WarpPositionProjectionTransformer(loader,artifact,bytes);
            var domain=new ProtectionDomain(new CodeSource(artifact.toUri().toURL(),(java.security.cert.Certificate[])null),null);
            byte[] output=transformer.transform(null,loader,WarpPositionProjectionTransformer.TARGET,null,domain,bytes);
            assertNotNull(output,transformer.failure());assertEquals(1,transformer.matches());
            List<String[]> methods=new ArrayList<>();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] exceptions) {
                    if(!name.equals(WarpPositionProjectionTransformer.METHOD)||!desc.equals(WarpPositionProjectionTransformer.DESCRIPTOR))methods.add(new String[]{name,desc});
                    return null;
                }
            },0);
            for(String[] method:methods) assertEquals(
                ReviewedMethodShape.read(bytes,WarpPositionProjectionTransformer.TARGET,method[0],method[1]),
                ReviewedMethodShape.read(output,WarpPositionProjectionTransformer.TARGET,method[0],method[1]),method[0]);
            assertTrue(methods.size()>5);
        }
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02,HostArtifactDigest.from(artifact));
    }
}
