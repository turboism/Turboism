package dev.turboism.gradle.internal.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The permission rules, the constant-pool reader and the two error modes of the audit.
 *
 * <p>The rule table is asserted member by member, because the gate's whole claim is that a member
 * reference to a risk-boundary API is backed by a declared permission: a silently dropped member
 * turns into a plugin that reads the filesystem with no declaration in its manifest.
 *
 * <p>Every abort case asserts the exact message the gate prints, including the class path the
 * caller supplied, so a refactor cannot quietly turn "cannot parse" into "no references found".
 */
class PluginPermissionAuditTest {

    private static final String PLUGIN = ":plugins:selection-brush";

    private static final String CLASS = "plugins/selection-brush/build/classes/java/main/Fixture.class";

    private static final Set<String> NO_PERMISSIONS = Set.of();

    @ParameterizedTest(name = "{0}#{1}")
    @CsvSource(
            delimiter = '|',
            value = {
                // Network transport: owner prefixes first, then the member-scoped owners.
                "java/net/http/HttpClient              |*                       |()V                    |turboism.network.fetch",
                "com/sun/net/httpserver/HttpServer     |*                       |()V                    |turboism.network.fetch",
                "javax/net/ssl/SSLSocket               |*                       |()V                    |turboism.network.fetch",
                "javax/net/ssl/SSLSocketFactory        |*                       |()V                    |turboism.network.fetch",
                "javax/net/ssl/SSLServerSocket         |*                       |()V                    |turboism.network.fetch",
                "javax/net/ssl/HttpsURLConnection      |*                       |()V                    |turboism.network.fetch",
                "java/net/Socket                       |*                       |()V                    |turboism.network.fetch",
                "java/net/ServerSocket                 |*                       |()V                    |turboism.network.fetch",
                "java/net/DatagramSocket               |*                       |()V                    |turboism.network.fetch",
                "java/net/MulticastSocket              |*                       |()V                    |turboism.network.fetch",
                "java/net/URLConnection                |*                       |()V                    |turboism.network.fetch",
                "java/net/HttpURLConnection            |*                       |()V                    |turboism.network.fetch",
                "java/net/JarURLConnection             |*                       |()V                    |turboism.network.fetch",
                "java/nio/channels/SocketChannel       |*                       |()V                    |turboism.network.fetch",
                "java/nio/channels/ServerSocketChannel |*                       |()V                    |turboism.network.fetch",
                "java/nio/channels/DatagramChannel     |*                       |()V                    |turboism.network.fetch",
                "java/nio/channels/AsynchronousSocketChannel       |*           |()V                    |turboism.network.fetch",
                "java/nio/channels/AsynchronousServerSocketChannel |*           |()V                    |turboism.network.fetch",
                "java/net/URL                          |openConnection,openStream|()Ljava/net/URLConnection;|turboism.network.fetch",
                "java/net/InetAddress                  |getByName,getAllByName   |(Ljava/lang/String;)Ljava/net/InetAddress;|turboism.network.fetch",
                // Process spawning: ProcessBuilder is tracked for every member.
                "java/lang/ProcessBuilder              |*                       |(Ljava/util/List;)V    |turboism.process.run",
                "java/lang/Runtime                     |exec                    |(Ljava/lang/String;)Ljava/lang/Process;|turboism.process.run",
                "java/awt/Desktop                      |browse,browseFileDirectory,open,edit,mail,print|(Ljava/net/URI;)V|turboism.process.run",
                // Filesystem writes.
                "java/nio/file/Files                   |write,writeString,delete,deleteIfExists,move,copy,createDirectory,createDirectories,createFile,createTempFile,createTempDirectory,newOutputStream,newBufferedWriter,setAttribute,setPosixFilePermissions,setLastModifiedTime,setOwner,createLink,createSymbolicLink|(Ljava/nio/file/Path;[B)V|turboism.file.write",
                "java/io/FileOutputStream              |*                       |(Ljava/io/File;)V      |turboism.file.write",
                "java/io/FileWriter                    |*                       |(Ljava/io/File;)V      |turboism.file.write",
                "java/io/File                          |delete,mkdir,mkdirs,createNewFile,renameTo,setWritable,setReadable,setExecutable,setLastModified,deleteOnExit|()Z|turboism.file.write",
                "java/io/PrintWriter                   |<init>                  |(Ljava/io/File;Ljava/lang/String;)V|turboism.file.write",
                "java/io/PrintWriter                   |<init>                  |(Ljava/lang/String;Ljava/lang/String;)V|turboism.file.write",
                "java/io/PrintStream                   |<init>                  |(Ljava/io/File;Ljava/lang/String;)V|turboism.file.write",
                "javax/imageio/ImageIO                 |write                   |(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/File;)Z|turboism.file.write",
                // Filesystem reads.
                "java/nio/file/Files                   |exists,notExists,isDirectory,isRegularFile,isSymbolicLink,isHidden,isReadable,isWritable,isExecutable,size,list,walk,walkFileTree,find,lines,readAllBytes,readAllLines,readString,readSymbolicLink,newInputStream,newBufferedReader,newDirectoryStream,getLastModifiedTime,readAttributes,getAttribute,getOwner,getPosixFilePermissions,getFileAttributeView,probeContentType,getFileStore,isSameFile,mismatch|(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)B|turboism.file.read",
                "java/io/FileInputStream               |*                       |(Ljava/io/File;)V      |turboism.file.read",
                "java/io/FileReader                    |*                       |(Ljava/io/File;)V      |turboism.file.read",
                "java/io/File                          |exists,isFile,isDirectory,isHidden,isAbsolute,canRead,canWrite,canExecute,length,lastModified,list,listFiles,listRoots,getFreeSpace,getTotalSpace,getUsableSpace|()Z|turboism.file.read",
                "java/util/zip/ZipFile                 |<init>                  |(Ljava/io/File;)V      |turboism.file.read",
                "java/util/jar/JarFile                 |<init>                  |(Ljava/io/File;)V      |turboism.file.read",
                "java/util/jar/JarFile                 |<init>                  |(Ljava/lang/String;)V  |turboism.file.read",
                "javax/imageio/ImageIO                 |read                    |(Ljava/io/File;)Ljava/awt/image/BufferedImage;|turboism.file.read",
                // Both ends at once: the caller picks the open mode, so neither direction is provable.
                "java/io/RandomAccessFile              |*                       |(Ljava/io/File;Ljava/lang/String;)V|turboism.file.read,turboism.file.write",
                "java/nio/channels/FileChannel         |open                    |(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;|turboism.file.read,turboism.file.write",
                "java/nio/file/Files                   |newByteChannel          |(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/SeekableByteChannel;|turboism.file.read,turboism.file.write",
            })
    void requiresDeclaredPermissionsForBoundaryMembers(
            String owner, String members, String descriptor, String permissions) {
        List<String> expected = List.of(permissions.split(","));
        List<String> names = members.equals("*") ? List.of("anyMember") : List.of(members.split(","));
        for (String name : names) {
            assertIterableEquals(
                    expected,
                    PluginPermissionAudit.requiredPermissions(new MemberRef(owner, name, descriptor)),
                    owner + "#" + name);
        }
    }

    @ParameterizedTest(name = "{0}#{1}")
    @CsvSource(
            delimiter = '#',
            value = {
                // Pure path/address objects and untracked members carry no boundary.
                "java/net/URL#toURI#()Ljava/net/URI;",
                "java/net/InetAddress#getLocalHost#()Ljava/net/InetAddress;",
                "java/lang/Runtime#gc#()V",
                "java/lang/Process#waitFor#()I",
                "java/awt/Desktop#isDesktopSupported#()Z",
                "java/io/File#getName#()Ljava/lang/String;",
                "java/io/File#toPath#()Ljava/nio/file/Path;",
                "java/nio/file/Path#toAbsolutePath#()Ljava/nio/file/Path;",
                "java/nio/file/Files#toPath#([Ljava/lang/String;)Ljava/nio/file/Path;",
                "java/util/zip/ZipFile#getEntry#(Ljava/lang/String;)Ljava/util/zip/ZipEntry;",
                "java/io/PrintWriter#<init>#(Ljava/io/Writer;)V",
                "java/io/PrintStream#<init>#(Ljava/io/OutputStream;)V",
                "java/io/PrintStream#println#(Ljava/lang/String;)V",
                "java/util/jar/JarFile#<init>#(Ljava/io/InputStream;)V",
                "javax/imageio/ImageIO#write#(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/OutputStream;)Z",
                "javax/imageio/ImageIO#read#(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;",
                "dev/turboism/sdk/Plugin#log#(Ljava/lang/String;)V",
            })
    void leavesNonBoundaryReferencesAlone(String owner, String name, String descriptor) {
        assertTrue(PluginPermissionAudit.requiredPermissions(new MemberRef(owner, name, descriptor))
                .isEmpty());
    }

    @Test
    void readsMemberReferencesInConstantPoolOrder() {
        ClassFixture pool = new ClassFixture()
                .member("java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B")
                .member("java/net/URL", "openStream", "()Ljava/io/InputStream;")
                .member("dev/turboism/sdk/Plugin", "log", "(Ljava/lang/String;)V");

        List<MemberRef> references = PluginPermissionAudit.memberReferences(
                new PluginPermissionAudit.ClassFile(CLASS, pool.build()));

        assertEquals(
                List.of(
                        new MemberRef("java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B"),
                        new MemberRef("java/net/URL", "openStream", "()Ljava/io/InputStream;"),
                        new MemberRef("dev/turboism/sdk/Plugin", "log", "(Ljava/lang/String;)V")),
                references);
    }

    @Test
    void keepsThePoolWalkInSyncAcrossTwoSlotEntries() {
        // Long and Double entries occupy two pool slots; a reader that forgets to skip the second
        // slot misreads every following entry, so the member ref after them must still be exact.
        ClassFixture pool = new ClassFixture()
                .wideEntry(5)
                .wideEntry(6)
                .integerConstant()
                .stringConstant()
                .raw(15, 3)
                .raw(16, 2)
                .raw(17, 4)
                .raw(18, 4)
                .raw(19, 2)
                .raw(20, 2)
                .member("java/nio/file/Files", "delete", "(Ljava/nio/file/Path;)V");

        assertEquals(
                List.of(new MemberRef("java/nio/file/Files", "delete", "(Ljava/nio/file/Path;)V")),
                PluginPermissionAudit.memberReferences(
                        new PluginPermissionAudit.ClassFile(CLASS, pool.build())));
    }

    @Test
    void reportsEveryViolationInClassOrder() {
        List<PluginPermissionAudit.ClassFile> classes = List.of(
                classFile("plugins/selection-brush/build/classes/java/main/Z.class", "java/net/URL", "openStream"),
                classFile("plugins/selection-brush/build/classes/java/main/A.class", "java/io/File", "delete"),
                classFile(
                        "plugins/selection-brush/build/classes/java/main/M.class",
                        "java/lang/ProcessBuilder",
                        "<init>"));

        List<String> violations =
                PluginPermissionAudit.audit(PLUGIN, classes, NO_PERMISSIONS, Map.of());

        assertEquals(
                List.of(
                        "Plugin :plugins:selection-brush class "
                                + "plugins/selection-brush/build/classes/java/main/Z.class calls java/net/URL.openStream"
                                + " which requires declared permission turboism.network.fetch in "
                                + "src/main/resources/META-INF/turboism/plugin.json",
                        "Plugin :plugins:selection-brush class "
                                + "plugins/selection-brush/build/classes/java/main/A.class calls java/io/File.delete"
                                + " which requires declared permission turboism.file.write in "
                                + "src/main/resources/META-INF/turboism/plugin.json",
                        "Plugin :plugins:selection-brush class "
                                + "plugins/selection-brush/build/classes/java/main/M.class calls "
                                + "java/lang/ProcessBuilder.<init> which requires declared permission "
                                + "turboism.process.run in src/main/resources/META-INF/turboism/plugin.json"),
                violations);
    }

    @Test
    void reportsBothDirectionsOfAReadWriteMemberInRuleOrder() {
        List<String> violations = PluginPermissionAudit.audit(
                PLUGIN,
                List.of(classFile(CLASS, "java/io/RandomAccessFile", "<init>")),
                NO_PERMISSIONS,
                Map.of());

        assertEquals(
                List.of(
                        "Plugin :plugins:selection-brush class " + CLASS + " calls java/io/RandomAccessFile.<init>"
                                + " which requires declared permission turboism.file.read in "
                                + "src/main/resources/META-INF/turboism/plugin.json",
                        "Plugin :plugins:selection-brush class " + CLASS + " calls java/io/RandomAccessFile.<init>"
                                + " which requires declared permission turboism.file.write in "
                                + "src/main/resources/META-INF/turboism/plugin.json"),
                violations);
    }

    @Test
    void acceptsDeclaredPermissions() {
        List<String> violations = PluginPermissionAudit.audit(
                PLUGIN,
                List.of(classFile(CLASS, "java/nio/file/Files", "readAllBytes")),
                Set.of("turboism.file.read"),
                Map.of());

        assertTrue(violations.isEmpty());
    }

    @Test
    void acceptsAWaiverByMemberEvidence() {
        List<String> violations = PluginPermissionAudit.audit(
                PLUGIN,
                List.of(
                        classFile(CLASS, "java/nio/file/Files", "readAllBytes"),
                        classFile(CLASS, "java/nio/file/Files", "write")),
                NO_PERMISSIONS,
                Map.of("java/nio/file/Files.readAllBytes", "the path was issued by an SDK service"));

        // A waiver covers exactly the evidence it names, never the owner or the permission.
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("java/nio/file/Files.write"));
    }

    @Test
    void keepsDuplicateReferencesAsSeparateViolations() {
        // Constant-pool entries are not deduplicated: the gate reports what the class contains.
        List<String> violations = PluginPermissionAudit.audit(
                PLUGIN,
                List.of(
                        classFile(CLASS, "java/net/URL", "openStream"),
                        classFile(CLASS, "java/net/URL", "openStream")),
                NO_PERMISSIONS,
                Map.of());

        assertEquals(2, violations.size());
        assertEquals(violations.get(0), violations.get(1));
    }

    @Test
    void abortsTheAuditOnAnUnparseableClass() {
        // Fail closed: an unreadable class must not contribute zero references to the audit.
        PluginPermissionAudit.ClassFile unparseable =
                new PluginPermissionAudit.ClassFile(CLASS, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

        PluginPermissionAudit.UnparseableClassException aborted = assertThrows(
                PluginPermissionAudit.UnparseableClassException.class,
                () -> PluginPermissionAudit.audit(
                        PLUGIN,
                        List.of(
                                classFile("plugins/selection-brush/build/classes/java/main/A.class", "java/net/URL",
                                        "openStream"),
                                unparseable),
                        NO_PERMISSIONS,
                        Map.of()));

        assertEquals(
                "Plugin permission audit cannot parse " + CLASS + ": not a valid class file header",
                aborted.getMessage());
        // The message is user-facing already: the gate surfaces it without rewording.
        assertTrue(aborted instanceof GradleException);
    }

    @Test
    void rejectsBadMagic() {
        byte[] bytes = new ClassFixture().member("java/net/URL", "openStream", "()V").build();
        bytes[0] = 0;

        assertAbortMessage(bytes, "not a valid class file header");
    }

    @Test
    void rejectsShortHeader() {
        assertAbortMessage(
                new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61, 0},
                "not a valid class file header");
    }

    @Test
    void rejectsUnknownConstantPoolTag() {
        assertAbortMessage(raw(new byte[] {21}, 2), "unknown constant-pool tag 21 at entry 1");
    }

    @Test
    void rejectsReservedConstantPoolTag() {
        assertAbortMessage(raw(new byte[] {0}, 2), "unknown constant-pool tag 0 at entry 1");
    }

    @Test
    void rejectsTruncatedUtf8Payload() {
        // Utf8 entry claiming 9 bytes with 1 present.
        assertAbortMessage(raw(new byte[] {1, 0, 9, 'a'}, 2), "truncated constant pool");
    }

    @Test
    void rejectsShortOperandRead() {
        // Class entry with a single operand byte instead of the u2 name index.
        assertAbortMessage(raw(new byte[] {7, 0}, 2), "truncated constant pool");
    }

    @Test
    void rejectsAMissingConstantPool() {
        assertAbortMessage(raw(new byte[] {}, 2), "truncated constant pool");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"permissions\": [{\"id\": \"turboism.file.read\"}]}",
                "{\n  \"permissions\" : [ {\"id\":\"a\"}, {\"id\":\"b\"} ]\n}",
                "{\"permissions\":[{\"id\":\"a\"}],\"id\":\"manifest\"}",
            })
    void readsDeclaredPermissionsAcrossFormatting(String manifest) {
        assertFalse(PluginPermissionAudit.declaredPermissionIds(manifest).isEmpty());
    }

    @Test
    void readsEveryDeclaredPermissionId() {
        String manifest = """
                {
                  "id": "dev.turboism.plugin.mcp",
                  "permissions": [
                    {"id": "turboism.network.fetch", "reason": "calls the model endpoint"},
                    {"id": "turboism.file.read"}
                  ]
                }
                """;

        assertEquals(
                List.of("turboism.network.fetch", "turboism.file.read"),
                List.copyOf(PluginPermissionAudit.declaredPermissionIds(manifest)));
    }

    @Test
    void ignoresPermissionIdsOutsideThePermissionsArray() {
        String manifest = """
                {"id": "dev.turboism.plugin.mcp", "permissions": [], "extra": [{"id": "turboism.file.write"}]}
                """;

        assertTrue(PluginPermissionAudit.declaredPermissionIds(manifest).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"id\": \"dev.turboism.plugin.mcp\"}",
                "{\"permissions\": \"turboism.file.read\"}",
                "{\"permissions\": [{\"id\": \"turboism.file.read\"}",
                "{\"permissions\": []}",
                "{}",
            })
    void declaresNothingWithoutAWellFormedPermissionsArray(String manifest) {
        assertTrue(PluginPermissionAudit.declaredPermissionIds(manifest).isEmpty());
    }

    @Test
    void collapsesDuplicateDeclarations() {
        String manifest = "{\"permissions\": [{\"id\": \"turboism.file.read\"}, {\"id\": \"turboism.file.read\"}]}";

        assertEquals(
                List.of("turboism.file.read"),
                List.copyOf(PluginPermissionAudit.declaredPermissionIds(manifest)));
    }

    private static void assertAbortMessage(byte[] bytes, String reason) {
        PluginPermissionAudit.ClassFile file = new PluginPermissionAudit.ClassFile(CLASS, bytes);

        PluginPermissionAudit.UnparseableClassException aborted = assertThrows(
                PluginPermissionAudit.UnparseableClassException.class,
                () -> PluginPermissionAudit.memberReferences(file));

        assertEquals("Plugin permission audit cannot parse " + CLASS + ": " + reason, aborted.getMessage());
    }

    private static PluginPermissionAudit.ClassFile classFile(String relativePath, String owner, String name) {
        return new PluginPermissionAudit.ClassFile(
                relativePath, new ClassFixture().member(owner, name, "(Ljava/io/File;)V").build());
    }

    private static byte[] raw(byte[] poolBytes, int poolCount) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61});
        bytes.writeBytes(new byte[] {(byte) (poolCount >> 8), (byte) poolCount});
        bytes.writeBytes(poolBytes);
        return bytes.toByteArray();
    }

    /**
     * A minimal constant pool: the audit reads the pool and nothing after it, so a fixture is a
     * header plus the entries under test.
     */
    private static final class ClassFixture {

        private final ByteArrayOutputStream entries = new ByteArrayOutputStream();
        private int next = 1;

        ClassFixture member(String owner, String name, String descriptor) {
            int ownerIndex = className(owner);
            int nameAndType = nameAndType(name, descriptor);
            return write(10, ownerIndex, nameAndType);
        }

        ClassFixture integerConstant() {
            next++;
            entries.write(3);
            entries.writeBytes(new byte[4]);
            return this;
        }

        ClassFixture stringConstant() {
            next++;
            entries.write(8);
            entries.writeBytes(new byte[] {0, 1});
            return this;
        }

        /** A Long or Double entry, which occupies two constant-pool slots. */
        ClassFixture wideEntry(int tag) {
            next += 2;
            entries.write(tag);
            entries.writeBytes(new byte[8]);
            return this;
        }

        /** Any other tag with a zeroed operand field, to prove the reader's stride. */
        ClassFixture raw(int tag, int operandBytes) {
            next++;
            entries.write(tag);
            entries.writeBytes(new byte[operandBytes]);
            return this;
        }

        byte[] build() {
            ByteArrayOutputStream classFile = new ByteArrayOutputStream();
            classFile.writeBytes(new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61});
            classFile.writeBytes(new byte[] {(byte) (next >> 8), (byte) next});
            classFile.writeBytes(entries.toByteArray());
            return classFile.toByteArray();
        }

        private ClassFixture write(int tag, int first, int second) {
            next++;
            entries.write(tag);
            entries.writeBytes(new byte[] {(byte) (first >> 8), (byte) first, (byte) (second >> 8), (byte) second});
            return this;
        }

        private int utf8(String value) {
            int index = next++;
            byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
            entries.write(1);
            entries.writeBytes(new byte[] {(byte) (bytes.length >> 8), (byte) bytes.length});
            entries.writeBytes(bytes);
            return index;
        }

        private int className(String internalName) {
            int nameIndex = utf8(internalName);
            int index = next++;
            entries.write(7);
            entries.writeBytes(new byte[] {(byte) (nameIndex >> 8), (byte) nameIndex});
            return index;
        }

        private int nameAndType(String name, String descriptor) {
            int nameIndex = utf8(name);
            int descriptorIndex = utf8(descriptor);
            int index = next++;
            entries.write(12);
            entries.writeBytes(new byte[] {(byte) (nameIndex >> 8), (byte) nameIndex});
            entries.writeBytes(new byte[] {(byte) (descriptorIndex >> 8), (byte) descriptorIndex});
            return index;
        }
    }
}
