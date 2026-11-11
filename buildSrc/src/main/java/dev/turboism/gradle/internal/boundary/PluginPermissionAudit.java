package dev.turboism.gradle.internal.boundary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

/**
 * Plugin bytecode against the permissions its manifest declares. Pure: bytes in, messages out.
 *
 * <p>{@code checkPluginPermissionAudit} closes the manifest-as-audit trail (ARCHITECTURE, plugin
 * isolation is not a sandbox). Constant-pool member references to JDK risk-boundary APIs - network
 * transport, filesystem writes/reads, process spawning - must be backed by the matching
 * {@code turboism.*} permission id declared in the plugin manifest. Pure path/address objects
 * ({@link java.nio.file.Path}, {@link java.net.URI}, {@link java.net.InetAddress}) and
 * plugin-private jar resources carry no boundary and are not tracked.
 *
 * <p>Two error modes, deliberately different: a rule hit is a <em>violation</em>, returned as one
 * message per hit and accumulated by the caller so the gate fails once; an unparseable class file
 * is an <em>abort</em>, thrown immediately, because it must break the audit rather than contribute
 * zero references. A class file that half-parsed would make a plugin look cleaner than it is.
 *
 * <p>Message text is part of the interface: each message names the {@code pluginPath} and the
 * caller-supplied relative class path, and violations come back in {@code classes} order and, per
 * class, in constant-pool index order, so the gate's output and walk order are unchanged.
 */
public final class PluginPermissionAudit {

    /** The manifest the audited declarations must come from, as named in every violation. */
    private static final String PERMISSION_DECLARATION = "src/main/resources/META-INF/turboism/plugin.json";

    private static final byte[] CLASS_FILE_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    /*
     * The boundary permissions are named by the shared vocabulary resource rather than by a second
     * copy of the strings, so a rename in :sdk breaks this gate instead of silently disabling a rule.
     */
    private static final String NETWORK_PERMISSION = "turboism.network.fetch";

    private static final String PROCESS_PERMISSION = "turboism.process.run";

    private static final String FILE_READ_PERMISSION = "turboism.file.read";

    private static final String FILE_WRITE_PERMISSION = "turboism.file.write";

    private static final Pattern PERMISSION_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private static final List<String> NETWORK_OWNER_PREFIXES = List.of(
            "java/net/http/",
            "com/sun/net/httpserver/",
            "javax/net/ssl/SSLSocket",
            "javax/net/ssl/SSLServerSocket",
            "javax/net/ssl/HttpsURLConnection",
            "java/net/Socket",
            "java/net/ServerSocket",
            "java/net/DatagramSocket",
            "java/net/MulticastSocket",
            "java/net/URLConnection",
            "java/net/HttpURLConnection",
            "java/net/JarURLConnection",
            "java/nio/channels/SocketChannel",
            "java/nio/channels/ServerSocketChannel",
            "java/nio/channels/DatagramChannel",
            "java/nio/channels/AsynchronousSocketChannel",
            "java/nio/channels/AsynchronousServerSocketChannel");

    private static final Map<String, Tracked> NETWORK_MEMBERS = Map.of(
            "java/net/URL", Tracked.of("openConnection", "openStream"),
            "java/net/InetAddress", Tracked.of("getByName", "getAllByName"));

    private static final Map<String, Tracked> PROCESS_MEMBERS = Map.of(
            // Any member: construction already commits a spawn plan.
            "java/lang/ProcessBuilder",
            Tracked.any(),
            "java/lang/Runtime",
            Tracked.of("exec"),
            "java/awt/Desktop",
            Tracked.of("browse", "browseFileDirectory", "open", "edit", "mail", "print"));

    private static final Map<String, Tracked> FILE_WRITE_MEMBERS = Map.ofEntries(
            Map.entry(
                    "java/nio/file/Files",
                    Tracked.of(
                            "write",
                            "writeString",
                            "delete",
                            "deleteIfExists",
                            "move",
                            "copy",
                            "createDirectory",
                            "createDirectories",
                            "createFile",
                            "createTempFile",
                            "createTempDirectory",
                            "newOutputStream",
                            "newBufferedWriter",
                            "setAttribute",
                            "setPosixFilePermissions",
                            "setLastModifiedTime",
                            "setOwner",
                            "createLink",
                            "createSymbolicLink")),
            Map.entry("java/io/FileOutputStream", Tracked.any()),
            Map.entry("java/io/FileWriter", Tracked.any()),
            Map.entry(
                    "java/io/File",
                    Tracked.of(
                            "delete",
                            "mkdir",
                            "mkdirs",
                            "createNewFile",
                            "renameTo",
                            "setWritable",
                            "setReadable",
                            "setExecutable",
                            "setLastModified",
                            "deleteOnExit")),
            Map.entry("java/io/PrintWriter", Tracked.of("<init>")),
            Map.entry("java/io/PrintStream", Tracked.of("<init>")),
            // Only the File overloads of ImageIO.write cross the file boundary.
            Map.entry("javax/imageio/ImageIO", Tracked.of("write")));

    private static final Map<String, Tracked> FILE_READ_MEMBERS = Map.ofEntries(
            Map.entry(
                    "java/nio/file/Files",
                    Tracked.of(
                            "exists",
                            "notExists",
                            "isDirectory",
                            "isRegularFile",
                            "isSymbolicLink",
                            "isHidden",
                            "isReadable",
                            "isWritable",
                            "isExecutable",
                            "size",
                            "list",
                            "walk",
                            "walkFileTree",
                            "find",
                            "lines",
                            "readAllBytes",
                            "readAllLines",
                            "readString",
                            "readSymbolicLink",
                            "newInputStream",
                            "newBufferedReader",
                            "newDirectoryStream",
                            "getLastModifiedTime",
                            "readAttributes",
                            "getAttribute",
                            "getOwner",
                            "getPosixFilePermissions",
                            "getFileAttributeView",
                            "probeContentType",
                            "getFileStore",
                            "isSameFile",
                            "mismatch")),
            Map.entry("java/io/FileInputStream", Tracked.any()),
            Map.entry("java/io/FileReader", Tracked.any()),
            Map.entry(
                    "java/io/File",
                    Tracked.of(
                            "exists",
                            "isFile",
                            "isDirectory",
                            "isHidden",
                            "isAbsolute",
                            "canRead",
                            "canWrite",
                            "canExecute",
                            "length",
                            "lastModified",
                            "list",
                            "listFiles",
                            "listRoots",
                            "getFreeSpace",
                            "getTotalSpace",
                            "getUsableSpace")),
            // Constructing a ZipFile opens the archive for reading.
            Map.entry("java/util/zip/ZipFile", Tracked.of("<init>")),
            // Constructing a JarFile over a File or String path opens the archive for reading.
            Map.entry("java/util/jar/JarFile", Tracked.of("<init>")),
            // Only the File overloads of ImageIO.read cross the file boundary.
            Map.entry("javax/imageio/ImageIO", Tracked.of("read")));

    /*
     * Channel open modes and RandomAccessFile "r"/"rw" flags are runtime arguments: a member
     * reference here crosses the file boundary on both ends.
     */
    private static final Map<String, Tracked> FILE_READ_WRITE_MEMBERS = Map.of(
            "java/io/RandomAccessFile",
            Tracked.any(),
            "java/nio/channels/FileChannel",
            Tracked.of("open"),
            "java/nio/file/Files",
            Tracked.of("newByteChannel"));

    private PluginPermissionAudit() {
    }

    /**
     * The constant-pool member references of one class file, in constant-pool index order.
     *
     * @throws UnparseableClassException when the bytes are not a class file this parser understands
     */
    public static List<MemberRef> memberReferences(ClassFile file) {
        byte[] bytes = file.bytes();
        // Fail closed: a .class file that cannot be parsed must break the audit rather than
        // silently contribute zero member references to it.
        if (bytes.length < 10 || !startsWithMagic(bytes)) {
            throw new UnparseableClassException(file, "not a valid class file header");
        }
        int count = u2(bytes, 8);
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        int[] nameAndTypeName = new int[count];
        int[] nameAndTypeDescriptor = new int[count];
        int[] memberRefOwner = new int[count];
        int[] memberRefNameAndType = new int[count];
        int position = 10;
        int index = 1;
        try {
            while (index < count) {
                switch (bytes[position] & 0xFF) {
                    case 1 -> {
                        int length = u2(bytes, position + 1);
                        utf8[index] = new String(bytes, position + 3, length, StandardCharsets.ISO_8859_1);
                        position += 3 + length;
                    }
                    case 3, 4 -> position += 5;
                    case 5, 6 -> {
                        position += 9;
                        index++;
                    }
                    case 7 -> {
                        classNameIndex[index] = u2(bytes, position + 1);
                        position += 3;
                    }
                    case 8 -> position += 3;
                    case 9, 10, 11 -> {
                        memberRefOwner[index] = u2(bytes, position + 1);
                        memberRefNameAndType[index] = u2(bytes, position + 3);
                        position += 5;
                    }
                    case 12 -> {
                        nameAndTypeName[index] = u2(bytes, position + 1);
                        nameAndTypeDescriptor[index] = u2(bytes, position + 3);
                        position += 5;
                    }
                    case 15 -> position += 4;
                    case 16 -> position += 3;
                    case 17, 18 -> position += 5;
                    case 19, 20 -> position += 3;
                    default -> throw new UnparseableClassException(
                            file, "unknown constant-pool tag " + (bytes[position] & 0xFF) + " at entry " + index);
                }
                index++;
            }
        } catch (IndexOutOfBoundsException truncated) {
            throw new UnparseableClassException(file, "truncated constant pool", truncated);
        }
        List<MemberRef> members = new ArrayList<>();
        for (int entry = 1; entry < count; entry++) {
            int ownerIndex = memberRefOwner[entry];
            if (ownerIndex == 0) {
                continue;
            }
            String owner = utf8[classNameIndex[ownerIndex]];
            int nameAndType = memberRefNameAndType[entry];
            String name = utf8[nameAndTypeName[nameAndType]];
            String descriptor = utf8[nameAndTypeDescriptor[nameAndType]];
            if (owner != null && name != null) {
                members.add(new MemberRef(owner, name, descriptor == null ? "" : descriptor));
            }
        }
        return members;
    }

    /** The permission ids one member reference requires; empty when it crosses no boundary. */
    public static Set<String> requiredPermissions(MemberRef reference) {
        String owner = reference.owner();
        String name = reference.name();
        Set<String> required = new LinkedHashSet<>();
        if (NETWORK_OWNER_PREFIXES.stream().anyMatch(owner::startsWith) || crosses(NETWORK_MEMBERS, owner, name)) {
            required.add(NETWORK_PERMISSION);
        }
        if (crosses(PROCESS_MEMBERS, owner, name)) {
            required.add(PROCESS_PERMISSION);
        }
        if (crosses(FILE_WRITE_MEMBERS, owner, name) && targetsFile(owner, reference.descriptor())) {
            required.add(FILE_WRITE_PERMISSION);
        }
        if (crosses(FILE_READ_MEMBERS, owner, name) && targetsFile(owner, reference.descriptor())) {
            required.add(FILE_READ_PERMISSION);
        }
        if (crosses(FILE_READ_WRITE_MEMBERS, owner, name)) {
            required.add(FILE_READ_PERMISSION);
            required.add(FILE_WRITE_PERMISSION);
        }
        return required;
    }

    /**
     * Every undeclared boundary crossing of one plugin, in {@code classes} order.
     *
     * <p>{@code waivers} maps {@code <owner>.<member>} evidence to the documented reason it may
     * cross the boundary without a manifest declaration; the reason itself is not printed.
     *
     * @throws UnparseableClassException when any class file cannot be parsed; the caller aborts
     */
    public static List<String> audit(
            String pluginPath,
            List<ClassFile> classes,
            Set<String> declaredPermissions,
            Map<String, String> waivers) {
        List<String> violations = new ArrayList<>();
        for (ClassFile classFile : classes) {
            for (MemberRef member : memberReferences(classFile)) {
                String evidence = member.owner() + "." + member.name();
                for (String permission : requiredPermissions(member)) {
                    if (!declaredPermissions.contains(permission) && !waivers.containsKey(evidence)) {
                        violations.add("Plugin " + pluginPath + " class " + classFile.relativePath() + " calls "
                                + evidence + " which requires declared permission " + permission + " in "
                                + PERMISSION_DECLARATION);
                    }
                }
            }
        }
        return violations;
    }

    /**
     * The declared permission ids of a plugin manifest, in declaration order.
     *
     * <p>The scan is deliberately a brace-depth reader rather than a JSON parser: it was extracted
     * from the gate verbatim so a differential comparison over the tracked manifests stays
     * meaningful. The permissions array is located by its key and read to its matching
     * {@code ]}; a manifest without the key, without the array or with an unterminated array
     * declares nothing.
     */
    public static Set<String> declaredPermissionIds(String descriptorJson) {
        int key = descriptorJson.indexOf("\"permissions\"");
        if (key < 0) {
            return Set.of();
        }
        int arrayStart = descriptorJson.indexOf('[', key);
        if (arrayStart < 0) {
            return Set.of();
        }
        int depth = 0;
        int end = -1;
        for (int index = arrayStart; index < descriptorJson.length(); index++) {
            char character = descriptorJson.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']') {
                depth--;
                if (depth == 0) {
                    end = index;
                    break;
                }
            }
        }
        if (end < 0) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = PERMISSION_ID.matcher(descriptorJson.substring(arrayStart, end));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /**
     * Every boundary permission this audit can require, in vocabulary order.
     *
     * <p>The gate asserts that each one is exported by the SDK vocabulary, so a rule cannot name an
     * id the framework stopped publishing without failing the build.
     *
     * @return the immutable boundary permission set
     */
    public static Set<String> boundaryPermissions() {
        return Set.of(NETWORK_PERMISSION, PROCESS_PERMISSION, FILE_READ_PERMISSION, FILE_WRITE_PERMISSION);
    }

    /** True when {@code owner}'s {@code name} is one of the tracked members of {@code rules}. */
    private static boolean crosses(Map<String, Tracked> rules, String owner, String name) {
        Tracked tracked = rules.get(owner);
        return tracked != null && tracked.matches(name);
    }

    /*
     * Some owners only cross the file boundary for a subset of their tracked members' signatures:
     * PrintWriter/PrintStream write a file only when constructed over a File or a String path
     * (Writer/OutputStream delegates stay off the boundary), JarFile opens an archive only for its
     * File/String-first constructors, and ImageIO.read/write only touch files for the File
     * overloads.
     */
    private static boolean targetsFile(String owner, String descriptor) {
        return switch (owner) {
            case "java/io/PrintWriter", "java/io/PrintStream", "java/util/jar/JarFile" ->
                descriptor.startsWith("(Ljava/io/File;") || descriptor.startsWith("(Ljava/lang/String;");
            case "javax/imageio/ImageIO" -> descriptor.contains("java/io/File");
            default -> true;
        };
    }

    private static boolean startsWithMagic(byte[] bytes) {
        for (int index = 0; index < CLASS_FILE_MAGIC.length; index++) {
            if (bytes[index] != CLASS_FILE_MAGIC[index]) {
                return false;
            }
        }
        return true;
    }

    private static int u2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    /** A class file already read from disk; the caller owns IO, order and relative paths. */
    public record ClassFile(String relativePath, byte[] bytes) {
    }

    /**
     * The audit cannot decide what a class file requires.
     *
     * <p>A {@link GradleException} because the message is already user-facing: an aborted audit
     * surfaces from the gate exactly as it did before the scanner was extracted.
     */
    public static final class UnparseableClassException extends GradleException {

        private static final long serialVersionUID = 1L;

        UnparseableClassException(ClassFile file, String reason) {
            super("Plugin permission audit cannot parse " + file.relativePath() + ": " + reason);
        }

        UnparseableClassException(ClassFile file, String reason, Throwable cause) {
            super("Plugin permission audit cannot parse " + file.relativePath() + ": " + reason, cause);
        }
    }

    /** The member names of one owner that cross a boundary; an empty name list means every member. */
    private record Tracked(List<String> names) {

        static Tracked any() {
            return new Tracked(List.of());
        }

        static Tracked of(String... names) {
            return new Tracked(List.of(names));
        }

        boolean matches(String member) {
            return names.isEmpty() || names.contains(member);
        }
    }
}
