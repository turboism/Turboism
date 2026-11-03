package dev.turboism.adapter.cubism.optimization.pair;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReinitPairElisionTransformerTest {
    @TempDir
    Path root;

    @Test
    void removesEveryTransientPairAllocationAndKeepsTheReuseMaps() throws Exception {
        final Fixture fixture = fixture("""
            package com.live2d.cubism.doc.model;
            import java.util.*;
            import kotlin.Pair;
            public final class CModel {
              public static final class Item {
                private final Object guid;
                public Item(Object guid) { this.guid = guid; }
                public Object getGuid() { return guid; }
              }
              public final List<Item> allObjects = new ArrayList<>();
              public final List<Item> allDrawables = new ArrayList<>();
              public final List<Item> parameters = new ArrayList<>();
              public Map<Object,Object> objectsByGuid, drawablesByGuid, parametersByGuid;
              public Object escaped;
              public void reinitModelInstance_exe() {
                Map<Object,Object> objectMap = new LinkedHashMap<>();
                for (Item item : allObjects) {
                  Pair pair;
                  try { pair = new Pair(item.getGuid(), item); }
                  catch (Throwable failure) { failure.printStackTrace(); pair = new Pair(null, null); }
                  Pair copy = pair;
                  objectMap.put(copy.getFirst(), copy.getSecond());
                }
                objectsByGuid = objectMap;
                Map<Object,Object> drawableMap = new LinkedHashMap<>();
                for (Item item : allDrawables) {
                  Pair pair = new Pair(item.getGuid(), item);
                  drawableMap.put(pair.getFirst(), pair.getSecond());
                }
                drawablesByGuid = drawableMap;
                Map<Object,Object> parameterMap = new LinkedHashMap<>();
                for (Item item : parameters) {
                  Pair pair = new Pair(item.getGuid(), item);
                  parameterMap.put(pair.getFirst(), pair.getSecond());
                }
                parametersByGuid = parameterMap;
              }
              public Object escape() { return new Pair("escaped", null); }
            }
            """);

        fixture.item("allObjects", "a");
        fixture.item("allObjects", "b");
        fixture.item("allObjects", "c");
        fixture.item("allDrawables", "d");
        fixture.item("parameters", "p");

        fixture.reinit();
        assertEquals(5, fixture.pairAllocations(), "three associateBy sites allocate one Pair per element");

        final byte[] rewritten = fixture.rewrite();
        assertNotNull(rewritten, fixture.transformer.failure());
        assertEquals(1, fixture.transformer.matches());
        final Fixture transformed = fixture.reload(rewritten);
        final Object a = transformed.item("allObjects", "a");
        final Object b = transformed.item("allObjects", "b");
        final Object c = transformed.item("allObjects", "c");
        final Object d = transformed.item("allDrawables", "d");
        final Object p = transformed.item("parameters", "p");
        transformed.reinit();
        assertEquals(0, transformed.pairAllocations(), "the rewritten method must allocate no Pair");
        assertSame(a, transformed.map("objects").get("a"));
        assertSame(b, transformed.map("objects").get("b"));
        assertSame(c, transformed.map("objects").get("c"));
        assertSame(d, transformed.map("drawables").get("d"));
        assertSame(p, transformed.map("parameters").get("p"));
        assertEquals(3, transformed.map("objects").size());
        transformed.reinit();
        assertEquals(0, transformed.pairAllocations(), "repeated reinitialization stays allocation-free");
        transformed.escape();
        assertEquals(1, transformed.pairAllocations(), "unrelated Pair users in the same class stay untouched");
    }

    @Test
    void refusesAPairThatEscapesToTheHost() throws Exception {
        final Fixture fixture = fixture("""
            package com.live2d.cubism.doc.model;
            import java.util.*;
            import kotlin.Pair;
            public final class CModel {
              public final List<Object> objects = new ArrayList<>();
              public Object escaped;
              public void reinitModelInstance_exe() {
                Map<Object,Object> map = new LinkedHashMap<>();
                for (Object object : objects) {
                  Pair pair = new Pair(object, object);
                  map.put(pair.getFirst(), pair.getSecond());
                  escaped = pair;
                }
              }
            }
            """);
        assertNull(fixture.rewrite(), "an escaping Pair must fail closed instead of being reused as scratch state");
        assertTrue(fixture.transformer.failure().contains("Pair"));
    }

    @Test
    void constructorRejectsAMethodWithoutPairSites() throws Exception {
        final Fixture fixture = fixture("""
            package com.live2d.cubism.doc.model;
            public final class CModel {
              public void reinitModelInstance_exe() {}
            }
            """);
        final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ReinitPairElisionTransformer(fixture.loader, fixture.classes, fixture.original));
        assertTrue(failure.getMessage().contains("Pair"));
    }

    @Test
    void refusesWrongLoaderSourceAndChangedMethodWithoutTransforming() throws Exception {
        final Fixture fixture = fixture(baseSource());
        final ReinitPairElisionTransformer transformer = fixture.transformer();
        final ProtectionDomain wrongDomain = new ProtectionDomain(
                new CodeSource(root.resolve("other").toUri().toURL(), (Certificate[]) null), null);
        assertNull(transformer.transform(
                null, fixture.loader, ReinitPairElisionTransformer.OWNER, null, wrongDomain, fixture.original));
        assertNull(transformer.transform(
                null,
                getClass().getClassLoader(),
                ReinitPairElisionTransformer.OWNER,
                null,
                fixture.domain,
                fixture.original));
        final Fixture changed = fixture(baseSource()
                .replace(
                        "Map<Object,Object> map = new LinkedHashMap<>();",
                        "Map<Object,Object> map = new LinkedHashMap<>(8);"));
        assertNull(transformer.transform(
                null, fixture.loader, ReinitPairElisionTransformer.OWNER, null, fixture.domain, changed.original));
    }

    private static String baseSource() {
        return """
            package com.live2d.cubism.doc.model;
            import java.util.*;
            import kotlin.Pair;
            public final class CModel {
              public final List<Object> objects = new ArrayList<>();
              public void reinitModelInstance_exe() {
                Map<Object,Object> map = new LinkedHashMap<>();
                for (Object object : objects) {
                  Pair pair = new Pair(object, object);
                  map.put(pair.getFirst(), pair.getSecond());
                }
              }
            }
            """;
    }

    private Fixture fixture(final String source) throws Exception {
        final Path base = Files.createTempDirectory(root, "fixture");
        final Path src = base.resolve("src");
        final Path classes = base.resolve("classes");
        Files.createDirectories(src.resolve("kotlin"));
        Files.createDirectories(src.resolve("com/live2d/cubism/doc/model"));
        Files.createDirectories(classes);
        Files.writeString(src.resolve("kotlin/Pair.java"), """
                package kotlin;
                public final class Pair {
                  public static int allocations;
                  private final Object first, second;
                  public Pair(Object first, Object second) { allocations++; this.first = first; this.second = second; }
                  public Object getFirst() { return first; }
                  public Object getSecond() { return second; }
                }
                """);
        final Path model = src.resolve("com/live2d/cubism/doc/model/CModel.java");
        Files.writeString(model, source);
        final List<String> args = new java.util.ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
        args.add(src.resolve("kotlin/Pair.java").toString());
        args.add(model.toString());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        final byte[] original = Files.readAllBytes(classes.resolve(ReinitPairElisionTransformer.OWNER + ".class"));
        final URLClassLoader loader =
                new URLClassLoader(new URL[] {classes.toUri().toURL()}, loaderParent());
        final ProtectionDomain domain =
                new ProtectionDomain(new CodeSource(classes.toUri().toURL(), (Certificate[]) null), null);
        return new Fixture(base, classes, loader, domain, original);
    }

    private static ClassLoader loaderParent() {
        // Pair and CModel must come from the fixture directory, never the parent.
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (name.equals("kotlin.Pair") || name.equals("com.live2d.cubism.doc.model.CModel")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static final class Fixture {
        final Path root;
        final Path classes;
        final ClassLoader loader;
        final ProtectionDomain domain;
        final byte[] original;
        ReinitPairElisionTransformer transformer;
        Class<?> model;

        Fixture(
                final Path root,
                final Path classes,
                final ClassLoader loader,
                final ProtectionDomain domain,
                final byte[] original) {
            this.root = root;
            this.classes = classes;
            this.loader = loader;
            this.domain = domain;
            this.original = original;
        }

        ReinitPairElisionTransformer transformer() {
            if (transformer == null) {
                transformer = new ReinitPairElisionTransformer(loader, classes, original);
            }
            return transformer;
        }

        byte[] rewrite() {
            return transformer().transform(null, loader, ReinitPairElisionTransformer.OWNER, null, domain, original);
        }

        Object item(final String field, final String guid) throws Exception {
            load();
            final var list = (List<Object>) model.getField(field).get(instance);
            final Class<?> item = Class.forName("com.live2d.cubism.doc.model.CModel$Item", true, loader);
            final Object value = item.getConstructor(Object.class).newInstance(guid);
            list.add(value);
            return value;
        }

        Object itemValue(final String field, final String guid) throws Exception {
            final var list = (List<Object>) model.getField(field).get(instance);
            for (Object value : list) {
                if (guid.equals(value.getClass().getMethod("getGuid").invoke(value))) return value;
            }
            throw new AssertionError("missing item " + guid);
        }

        Object instance;

        private void load() throws Exception {
            if (model == null) {
                model = Class.forName("com.live2d.cubism.doc.model.CModel", true, loader);
                instance = model.getConstructor().newInstance();
            }
        }

        void reinit() throws Exception {
            load();
            model.getMethod("reinitModelInstance_exe").invoke(instance);
        }

        int pairAllocations() throws Exception {
            return Class.forName("kotlin.Pair", true, loader)
                    .getField("allocations")
                    .getInt(null);
        }

        java.util.Map<?, ?> map(final String field) throws Exception {
            load();
            return (java.util.Map<?, ?>) model.getField(field + "ByGuid").get(instance);
        }

        void escape() throws Exception {
            load();
            model.getMethod("escape").invoke(instance);
        }

        Fixture reload(final byte[] rewritten) throws Exception {
            final URLClassLoader reloaded =
                    new URLClassLoader(new URL[] {classes.toUri().toURL()}, loaderParent()) {
                        @Override
                        protected Class<?> findClass(final String name) throws ClassNotFoundException {
                            if (!name.equals("com.live2d.cubism.doc.model.CModel")) return super.findClass(name);
                            return defineClass(name, rewritten, 0, rewritten.length, domain);
                        }
                    };
            final Fixture result = new Fixture(root, classes, reloaded, domain, rewritten);
            result.model = Class.forName("com.live2d.cubism.doc.model.CModel", true, reloaded);
            result.instance = result.model.getConstructor().newInstance();
            return result;
        }
    }
}
