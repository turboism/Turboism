import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Reflection helper for scripts/sdk_api_since.py.
 *
 * <pre>
 * enumerate SDK_CLASSES_DIR
 *     prints every SDK class and its public/protected members as stable keys
 * refs SDK_CLASSES_DIR NAME=PLUGIN_CLASSES_DIR...
 *     prints the SDK members each plugin links against, with every declaration
 *     in the SDK hierarchy that would satisfy the link
 * </pre>
 *
 * Keys: {@code pkg.Class}, {@code pkg.Class#method:(desc)ret}, {@code pkg.Class#<init>:(desc)V},
 * {@code pkg.Class#field}.
 */
public final class SdkApiRefs {
    private static final String SDK_PREFIX = "dev/turboism/sdk/";

    public static void main(String[] args) throws Exception {
        switch (args.length == 0 ? "" : args[0]) {
            case "enumerate" -> enumerate(Path.of(args[1]));
            case "refs" -> refs(Path.of(args[1]), List.of(args).subList(2, args.length));
            default -> {
                System.err.println("usage: enumerate DIR | refs DIR NAME=DIR...");
                System.exit(2);
            }
        }
    }

    private static List<String> classNames(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".class") && !p.endsWith("module-info.class"))
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .map(s -> s.substring(0, s.length() - ".class".length()).replace('/', '.'))
                .sorted().toList();
        }
    }

    private static boolean exposed(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String descriptor(Executable executable) {
        Class<?> returned = executable instanceof Method method ? method.getReturnType() : void.class;
        return MethodType.methodType(returned, executable.getParameterTypes()).toMethodDescriptorString();
    }

    private static List<String> declared(Class<?> type) {
        List<String> keys = new ArrayList<>();
        String owner = type.getName();
        for (Method method : type.getDeclaredMethods()) {
            if (!method.isSynthetic() && exposed(method.getModifiers())) {
                keys.add(owner + "#" + method.getName() + ":" + descriptor(method));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isSynthetic() && exposed(constructor.getModifiers())) {
                keys.add(owner + "#<init>:" + descriptor(constructor));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (!field.isSynthetic() && exposed(field.getModifiers())) {
                keys.add(owner + "#" + field.getName());
            }
        }
        return keys;
    }

    private static void enumerate(Path root) throws Exception {
        try (URLClassLoader loader = loader(root)) {
            Set<String> keys = new TreeSet<>();
            for (String name : classNames(root)) {
                Class<?> type = Class.forName(name, false, loader);
                keys.add(name);
                keys.addAll(declared(type));
            }
            keys.forEach(System.out::println);
        }
    }

    private static URLClassLoader loader(Path root) throws IOException {
        return new URLClassLoader(new URL[] {root.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    private static void refs(Path sdkRoot, List<String> plugins) throws Exception {
        try (URLClassLoader loader = loader(sdkRoot)) {
            for (String plugin : plugins) {
                int split = plugin.indexOf('=');
                String name = plugin.substring(0, split);
                Set<String> links = new TreeSet<>();
                for (String ref : pluginRefs(Path.of(plugin.substring(split + 1)))) {
                    links.add(resolve(loader, ref));
                }
                for (String link : links) {
                    System.out.println(name + "\t" + link);
                }
            }
        }
    }

    /** Output: owner class, then space-joined candidate keys (empty when nothing satisfies the link). */
    private static String resolve(URLClassLoader loader, String ref) {
        String[] parts = ref.split("\t", -1);
        String owner = parts[0].replace('/', '.');
        if (parts.length == 1) {
            return owner + "\t" + owner;
        }
        String member = parts[1];
        String descriptor = parts[2];
        List<String> candidates = new ArrayList<>();
        candidates.add(owner);
        try {
            collect(Class.forName(owner, false, loader), member, descriptor, candidates, new java.util.HashSet<>());
        } catch (ClassNotFoundException | LinkageError e) {
            return owner + "\t" + owner + " UNRESOLVED:" + member + descriptor;
        }
        if (candidates.size() == 1) {
            candidates.add("UNRESOLVED:" + owner + "#" + member + ":" + descriptor);
        }
        return owner + "\t" + String.join(" ", candidates);
    }

    private static void collect(Class<?> type, String member, String descriptor, List<String> out, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) {
            return;
        }
        String wanted = type.getName() + "#" + member + (descriptor.startsWith("(") ? ":" + descriptor : "");
        if (declared(type).contains(wanted)) {
            out.add(wanted);
        }
        collect(type.getSuperclass(), member, descriptor, out, seen);
        for (Class<?> parent : type.getInterfaces()) {
            collect(parent, member, descriptor, out, seen);
        }
    }

    /** Tab-separated {@code owner[\tname\tdescriptor]} for every SDK class and member the class files link to. */
    private static Set<String> pluginRefs(Path classesDir) throws IOException {
        Set<String> refs = new TreeSet<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    parse(new DataInputStream(in), refs);
                }
            }
        }
        return refs;
    }

    private static void parse(DataInputStream in, Set<String> refs) throws IOException {
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        int[] tag = new int[count];
        int[] a = new int[count];
        int[] b = new int[count];
        for (int i = 1; i < count; i++) {
            int t = in.readUnsignedByte();
            tag[i] = t;
            switch (t) {
                case 1 -> utf[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> {
                    a[i] = in.readUnsignedShort();
                    b[i] = in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("Unknown constant pool tag " + t);
            }
        }
        for (int i = 1; i < count; i++) {
            if (tag[i] == 7) {
                String owner = classOwner(utf[a[i]]);
                if (owner != null) {
                    refs.add(owner);
                }
            } else if (tag[i] == 9 || tag[i] == 10 || tag[i] == 11) {
                String owner = classOwner(utf[a[a[i]]]);
                if (owner != null) {
                    refs.add(owner + "\t" + utf[a[b[i]]] + "\t" + utf[b[b[i]]]);
                }
            }
        }
    }

    private static String classOwner(String internalName) {
        String name = internalName;
        if (name.startsWith("[")) {
            int start = name.indexOf('L');
            if (start < 0) {
                return null;
            }
            name = name.substring(start + 1, name.length() - 1);
        }
        return name.startsWith(SDK_PREFIX) ? name : null;
    }
}
