package dev.turboism.adapter.cubism.optimization.image;

import java.awt.image.BufferedImage;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.BiFunction;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TextureUploadPreparationTransformerTest {
    @TempDir Path root;

    @Test void preservesProfileMipmapNativeErrorsAndCallbackFallback() throws Exception {
        compileFixture();
        Path owner = root.resolve(TextureUploadPreparationTransformer.TARGET + ".class");
        byte[] original = Files.readAllBytes(owner);
        try (var loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, getClass().getClassLoader())) {
            var transformer = new TextureUploadPreparationTransformer(loader, null, original);
            byte[] changed = transformer.transform(null, loader, TextureUploadPreparationTransformer.TARGET, null, null, original);
            assertNotNull(changed, transformer.failure());
            Files.write(owner, changed);
            Class<?> profileType = loader.loadClass("com.jogamp.opengl.GLProfile");
            Object profile = profileType.getConstructor(boolean.class).newInstance(true);
            Class<?> graphicsType = loader.loadClass("com.live2d.graphics3d.a");
            Object graphics = graphicsType.getConstructor(profileType).newInstance(profile);
            Class<?> imageType = loader.loadClass("com.live2d.graphics.CWritableImage");
            BufferedImage source = new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0,0,0x80ff8040);
            Object image = imageType.getConstructor(BufferedImage.class).newInstance(source);
            Class<?> factoryType = loader.loadClass(TextureUploadPreparationTransformer.TARGET.replace('/','.'));
            Object factory = factoryType.getConstructor().newInstance();
            var create = factoryType.getMethod("a", graphicsType, imageType, int.class, String.class);
            Class<?> io = loader.loadClass("com.jogamp.opengl.util.texture.awt.AWTTextureIO");
            String previous = System.getProperty(TextureUploadPreparationBridge.ENABLE_PROPERTY);
            try (var bridge = new TextureUploadPreparationBridge(profileType)) {
                System.setProperty(TextureUploadPreparationBridge.ENABLE_PROPERTY,"true"); bridge.install();
                Object pair = create.invoke(factory,graphics,image,0,"test");
                Object texture = pair.getClass().getField("first").get(pair);
                BufferedImage actual = (BufferedImage) texture.getClass().getField("image").get(texture);
                assertNotSame(source,actual); assertTrue(actual.isAlphaPremultiplied());
                assertSame(profile,texture.getClass().getField("profile").get(texture));
                assertTrue(texture.getClass().getField("mipmap").getBoolean(texture));
                assertEquals(0x80ff8040,source.getRGB(0,0));
                Object callback = System.getProperties().get(TextureUploadPreparationBridge.CALLBACK_PROPERTY);
                try {
                    for (BiFunction<Object,Object,Object> broken : java.util.List.<BiFunction<Object,Object,Object>>of(
                        (a,b)->{throw new IllegalStateException("callback only");},
                        (a,b)->new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB), (a,b)->"wrong type")) {
                        System.getProperties().put(TextureUploadPreparationBridge.CALLBACK_PROPERTY,broken);
                        pair = create.invoke(factory,graphics,image,0,"test");
                        assertNotNull(pair,"callback exception must not reach the native outer handler");
                        texture = pair.getClass().getField("first").get(pair);
                        assertSame(source,texture.getClass().getField("image").get(texture));
                    }
                } finally { System.getProperties().put(TextureUploadPreparationBridge.CALLBACK_PROPERTY,callback); }
                int before = io.getField("calls").getInt(null);
                io.getField("fail").setBoolean(null,true);
                assertNull(create.invoke(factory,graphics,image,0,"test"));
                assertEquals(before+1,io.getField("calls").getInt(null),"native failures must never replay a partially executed upload");
                io.getField("fail").setBoolean(null,false);
                System.setProperty(TextureUploadPreparationBridge.ENABLE_PROPERTY,"false");
                pair = create.invoke(factory,graphics,image,0,"test"); texture = pair.getClass().getField("first").get(pair);
                assertSame(source,texture.getClass().getField("image").get(texture));
            } finally {
                if(previous==null)System.clearProperty(TextureUploadPreparationBridge.ENABLE_PROPERTY);
                else System.setProperty(TextureUploadPreparationBridge.ENABLE_PROPERTY,previous);
            }
            assertNull(System.getProperties().get(TextureUploadPreparationBridge.CALLBACK_PROPERTY));
            assertNull(System.getProperties().get(TextureUploadPreparationBridge.STATS_PROPERTY));
        }
    }

    private void compileFixture() throws Exception {
        Map<String,String> sources = Map.of(
            "com.jogamp.opengl.GLProfile", "package com.jogamp.opengl; public class GLProfile { public final boolean desktop; public GLProfile(boolean d){desktop=d;} public boolean isGL2GL3(){return desktop;} }",
            "com.live2d.graphics3d.a", "package com.live2d.graphics3d; public class a { public final com.jogamp.opengl.GLProfile profile; public a(com.jogamp.opengl.GLProfile p){profile=p;} }",
            "com.live2d.graphics.CWritableImage", "package com.live2d.graphics; public class CWritableImage { public final java.awt.image.BufferedImage image; public CWritableImage(java.awt.image.BufferedImage i){image=i;} }",
            "kotlin.Pair", "package kotlin; public class Pair {public final Object first,second; public Pair(Object a,Object b){first=a;second=b;} }",
            "com.jogamp.opengl.util.texture.Texture", "package com.jogamp.opengl.util.texture; public class Texture { public final com.jogamp.opengl.GLProfile profile; public final java.awt.image.BufferedImage image; public final boolean mipmap; public Texture(com.jogamp.opengl.GLProfile p,java.awt.image.BufferedImage i,boolean m){profile=p;image=i;mipmap=m;} }",
            "com.jogamp.opengl.util.texture.awt.AWTTextureIO", "package com.jogamp.opengl.util.texture.awt; public class AWTTextureIO { public static int calls; public static boolean fail; public static com.jogamp.opengl.util.texture.Texture newTexture(com.jogamp.opengl.GLProfile p,java.awt.image.BufferedImage i,boolean m) {calls++;if(fail)throw new IllegalStateException(\"native upload\");return new com.jogamp.opengl.util.texture.Texture(p,i,m);} }",
            "com.live2d.graphics3d.shader.A", "package com.live2d.graphics3d.shader; public final class A { public kotlin.Pair a(com.live2d.graphics3d.a g,com.live2d.graphics.CWritableImage i,int level,String name){try {com.jogamp.opengl.util.texture.Texture t=com.jogamp.opengl.util.texture.awt.AWTTextureIO.newTexture(g.profile,i.image,true);return new kotlin.Pair(t,null);}catch(IllegalStateException nativeFailure){return null;}} }"
        );
        var args = new ArrayList<String>(java.util.List.of("--release","17","-d",root.toString()));
        for(var source:sources.entrySet()) {
            Path file=root.resolve(source.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());args.add(file.toString());
        }
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
    }
}
