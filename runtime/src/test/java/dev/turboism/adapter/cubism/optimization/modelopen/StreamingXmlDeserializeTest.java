package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V17;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies {@link StreamingXmlDeserializePatcher}, {@link StreamingXmlDeserializeBridge}, and
 * {@link Utf8XmlPullScanner} across synthetic host contracts and all 21 official Cubism JARs.
 */
final class StreamingXmlDeserializeTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String VERSION_5305 = "5.3.05";
    private static final String CLASS_SUFFIX = ".class";

    private static final String BRIDGE_OWNER =
            "dev/turboism/adapter/cubism/optimization/modelopen/StreamingXmlDeserializeBridge";
    private static final String SYSTEM_OWNER = "java/lang/System";
    private static final String GC_NAME = "gc";
    private static final String VOID_DESC = "()V";
    private static final String DESERIALIZE_FROM_FILE = "deserializeFromFile";
    private static final String DESERIALIZE_FROM_BYTES = "deserializeFromBytes";
    private static final String DESERIALIZE_EXE = "deserialize_exe";
    private static final String DESERIALIZE_EXE_DESC =
            "(Lcom/live2d/serialize/archiver/ArchiveReader;Ljava/util/HashMap;IZ)Ljava/lang/Object;";
    private static final int EXPECTED_RAW_GC_CALLS = 4;
    private static final int EXPECTED_BRIDGE_CALLS = 1;
    private static final int APP_VERSION_OLD = 50000;
    private static final int APP_VERSION_CURRENT = 50305;

    @Test
    void patchesAllCubismJarsAndCoexistsInBothOrdersWithGcElision() throws Exception {
        final List<Path> jars = discoverHostJars();
        Assumptions.assumeFalse(jars.isEmpty(), "No Cubism host JARs present under ~/cubism-host-artifacts");

        for (final Path jarPath : jars) {
            final String version = versionLabel(jarPath);
            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                final byte[] raw = readClassEntry(jar, StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME);
                assertNotNull(raw, "Missing UUSerialize in " + version);
                assertEquals(EXPECTED_RAW_GC_CALLS, countGcCalls(raw), "Raw GC calls in " + version);
                assertEquals(0, countBridgeCalls(raw), "Raw bridge calls in " + version);

                // Order 1: StreamingXmlDeserializePatcher -> DeserializeGcElisionPatcher.
                final byte[] streamFirst = StreamingXmlDeserializePatcher.tryPatch(
                        StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, hostLoader, raw);
                assertNotNull(streamFirst, "Streaming patch failed on " + version);
                assertEquals(EXPECTED_BRIDGE_CALLS, countBridgeCalls(streamFirst), "Bridge calls in " + version);
                assertNull(
                        StreamingXmlDeserializePatcher.tryPatch(
                                StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, hostLoader, streamFirst),
                        "Streaming patch must be idempotent on " + version);

                final byte[] streamThenGc = DeserializeGcElisionPatcher.tryPatch(
                        DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, streamFirst);
                assertNotNull(streamThenGc, "GC elision after streaming failed on " + version);
                assertEquals(0, countGcCalls(streamThenGc), "GC calls after order 1 in " + version);
                assertEquals(EXPECTED_BRIDGE_CALLS, countBridgeCalls(streamThenGc), "Bridge calls order 1 " + version);
                assertNull(StreamingXmlDeserializePatcher.tryPatch(
                        StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, hostLoader, streamThenGc));
                assertNull(DeserializeGcElisionPatcher.tryPatch(
                        DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, streamThenGc));

                // Order 2: DeserializeGcElisionPatcher -> StreamingXmlDeserializePatcher.
                final byte[] gcFirst = DeserializeGcElisionPatcher.tryPatch(
                        DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, raw);
                assertNotNull(gcFirst, "GC elision failed on " + version);
                final byte[] gcThenStream = StreamingXmlDeserializePatcher.tryPatch(
                        StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, hostLoader, gcFirst);
                assertNotNull(gcThenStream, "Streaming after GC elision failed on " + version);
                assertEquals(0, countGcCalls(gcThenStream), "GC calls after order 2 in " + version);
                assertEquals(EXPECTED_BRIDGE_CALLS, countBridgeCalls(gcThenStream), "Bridge calls order 2 " + version);
                assertNull(StreamingXmlDeserializePatcher.tryPatch(
                        StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, hostLoader, gcThenStream));
                assertNull(DeserializeGcElisionPatcher.tryPatch(
                        DeserializeGcElisionPatcher.TARGET_INTERNAL_NAME, hostLoader, gcThenStream));
            }
        }
    }

    @Test
    void patcherFailsClosedOnUnrelatedOrMismatchedClasses() {
        final byte[] minimal = buildMinimalClass("com/example/Unrelated");
        assertNull(StreamingXmlDeserializePatcher.tryPatch("com/example/Unrelated", null, minimal));
        assertNull(StreamingXmlDeserializePatcher.tryPatch(
                StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, null, minimal));
        assertNull(StreamingXmlDeserializePatcher.tryPatch(
                StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, null, null));
    }

    @Test
    void streamingDeserializesFullGraphWithSharedCyclesArraysCollectionsAndHybridFallback() throws Throwable {
        final SyntheticHostEnv env = new SyntheticHostEnv();
        final StreamingXmlDeserializeBridge.HostBinding binding =
                StreamingXmlDeserializeBridge.resolveHostBinding(env.loader);
        assertTrue(binding.available, () -> "HostBinding failed: " + binding.resolutionFailure);
        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!-- leading comment -->\n"
                + "<?version SerializeFormatVersion:2?>\n"
                + "<?import " + ModelNode.class.getName() + "?>\n"
                + "<?import " + BaseNode.class.getName() + "?>\n"
                + "<?import " + CustomPayload.class.getName() + "?>\n"
                + "<?import " + PostProcessNode.class.getName() + "?>\n"
                + "<?import " + SampleMode.class.getName() + "?>\n"
                + "<root fileFormatVersion=\"50304\">\n"
                + "  <shared>\n"
                + "    <ModelNode xs.id=\"n1\">\n"
                + "      <s xs.n=\"label\">alpha &lt;1&gt; &amp; &quot;q&quot; &apos;a&apos; &#65; &#x3042; &#x1F600;<![CDATA[ <raw>&tag ]]></s>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n2\"/>\n"
                + "    </ModelNode>\n"
                + "    <ModelNode xs.id=\"n2\">\n"
                + "      <s xs.n=\"label\">beta</s>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n1\"/>\n"
                + "    </ModelNode>\n"
                + "    <CustomPayload xs.id=\"c1\" attr=\"hello &amp; world\">\n"
                + "      <nested k=\"v\"><!-- inner comment -->body<![CDATA[ !raw! ]]></nested>\n"
                + "    </CustomPayload>\n"
                + "  </shared>\n"
                + "  <main>\n"
                + "    <ModelNode>\n"
                + "      <BaseNode xs.n=\"super\">\n"
                + "        <i xs.n=\"baseId\">77</i>\n"
                + "      </BaseNode>\n"
                + "      <s xs.n=\"label\">root-node</s>\n"
                + "      <i xs.n=\"ignoredUnknownProp\">999</i>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n1\"/>\n"
                + "      <CustomPayload xs.n=\"custom\" xs.ref=\"c1\"/>\n"
                + "      <PostProcessNode xs.n=\"postNode\" tag=\"pp-attr\">\n"
                + "        <s xs.n=\"value\">pp-val</s>\n"
                + "      </PostProcessNode>\n"
                + "      <SampleMode xs.n=\"mode\" v=\"BETA\"/>\n"
                + "      <float-array xs.n=\"floats\" count=\"6\">0.0 -0.0 1.5 -3.25 1.4E-45 NaN</float-array>\n"
                + "      <float-array xs.n=\"emptyFloats\" count=\"0\"/>\n"
                + "      <int-array xs.n=\"ints\" count=\"4\">-2147483648 -1 0 2147483647</int-array>\n"
                + "      <array_list xs.n=\"list\" count=\"2\">\n"
                + "        <s xs.id=\"inlineS\">first</s>\n"
                + "        <s xs.ref=\"inlineS\"/>\n"
                + "      </array_list>\n"
                + "      <carray_list xs.n=\"clist\" count=\"2\">\n"
                + "        <i>10</i>\n"
                + "        <i>20</i>\n"
                + "      </carray_list>\n"
                + "      <hash_map xs.n=\"strMap\" count=\"2\" keyType=\"string\">\n"
                + "        <i xs.n=\"one\">1</i>\n"
                + "        <null xs.n=\"empty\"/>\n"
                + "      </hash_map>\n"
                + "      <linked_map xs.n=\"objMap\" count=\"1\">\n"
                + "        <entry>\n"
                + "          <ModelNode xs.n=\"key\" xs.ref=\"n2\"/>\n"
                + "          <s xs.n=\"value\">mapped-beta</s>\n"
                + "        </entry>\n"
                + "      </linked_map>\n"
                + "      <linked_set xs.n=\"set\" count=\"2\">\n"
                + "        <s>s1</s>\n"
                + "        <s>s2</s>\n"
                + "      </linked_set>\n"
                + "      <array xs.n=\"nodeArray\" count=\"2\" type=\"ModelNode\">\n"
                + "        <ModelNode xs.ref=\"n1\"/>\n"
                + "        <null/>\n"
                + "      </array>\n"
                + "    </ModelNode>\n"
                + "  </main>\n"
                + "</root>";

        final Object rawResult = StreamingXmlDeserializeBridge.tryDeserialize(
                xml.getBytes(StandardCharsets.UTF_8),
                env.newArchiveReader(),
                new HashMap<>(),
                APP_VERSION_CURRENT,
                true);

        assertTrue(rawResult instanceof ModelNode, "Expected ModelNode, got: " + rawResult);
        final ModelNode root = (ModelNode) rawResult;

        assertEquals(77, root.baseId);
        assertEquals("root-node", root.label);
        assertEquals(SampleMode.BETA, root.mode);

        // Verify <shared> forward and cyclic references plus entity/CDATA/UTF-8 unescaping.
        assertNotNull(root.peer);
        assertEquals("alpha <1> & \"q\" 'a' A \u3042 \uD83D\uDE00 <raw>&tag ", root.peer.label);
        assertNotNull(root.peer.peer);
        assertEquals("beta", root.peer.peer.label);
        assertSame(root.peer, root.peer.peer.peer, "Cyclic <shared> reference must preserve identity");

        // Verify float[] bit-exact parity and int[] edge values.
        assertEquals(6, root.floats.length);
        assertEquals(Float.floatToRawIntBits(0.0f), Float.floatToRawIntBits(root.floats[0]));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(root.floats[1]));
        assertEquals(Float.floatToRawIntBits(1.5f), Float.floatToRawIntBits(root.floats[2]));
        assertEquals(Float.floatToRawIntBits(-3.25f), Float.floatToRawIntBits(root.floats[3]));
        assertEquals(Float.floatToRawIntBits(Float.MIN_VALUE), Float.floatToRawIntBits(root.floats[4]));
        assertTrue(Float.isNaN(root.floats[5]));
        assertEquals(0, root.emptyFloats.length);
        assertArrayEquals(new int[] {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE}, root.ints);

        // Verify lists, inline xs.id/xs.ref, string-keyed and deferred object-keyed maps, sets, arrays.
        assertEquals(List.of("first", "first"), root.list);
        assertSame(root.list.get(0), root.list.get(1));
        assertEquals(List.of(10, 20), root.clist);
        assertEquals(1, root.strMap.get("one"));
        assertTrue(root.strMap.containsKey("empty"));
        assertNull(root.strMap.get("empty"));
        assertEquals("mapped-beta", root.objMap.get(root.peer.peer));
        assertEquals(new LinkedHashSet<>(List.of("s1", "s2")), root.set);
        assertEquals(2, root.nodeArray.length);
        assertSame(root.peer, root.nodeArray[0]);
        assertNull(root.nodeArray[1]);

        // Verify hybrid local JDOM materialization for CustomClassSerializer and IXmlSerializable_postProcess.
        assertNotNull(root.custom);
        assertEquals("hello & world", root.custom.attrValue);
        assertEquals("v:body !raw! ", root.custom.nestedSummary);
        assertNotNull(root.postNode);
        assertEquals("pp-val", root.postNode.value);
        assertEquals("pp-attr", root.postNode.postProcessedTag);

        // Verify PI version was recorded on XmlReader.
        assertEquals(Integer.valueOf(2), env.lastVersionFor("SerializeFormatVersion"));
    }

    @Test
    void failsClosedToUnhandledOnNewerVersionUnsupportedXmlOrMalformedInput() throws Throwable {
        final SyntheticHostEnv env = new SyntheticHostEnv();
        final StreamingXmlDeserializeBridge.HostBinding binding =
                StreamingXmlDeserializeBridge.resolveHostBinding(env.loader);
        assertTrue(binding.available, () -> "HostBinding failed: " + binding.resolutionFailure);
        final Object reader = env.newArchiveReader();

        final String newerVersionXml =
                "<root fileFormatVersion=\"50305\"><shared/><main><s xs.n=\"x\">ok</s></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        newerVersionXml.getBytes(StandardCharsets.UTF_8),
                        reader,
                        new HashMap<>(),
                        APP_VERSION_OLD,
                        true));

        final String nonUtf8Xml =
                "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?><root><shared/><main><s>x</s></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        nonUtf8Xml.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));

        final String doctypeXml = "<!DOCTYPE root><root><shared/><main><s>x</s></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        doctypeXml.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));

        final String unknownEntityXml = "<root><shared/><main><s>hello &nbsp; world</s></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        unknownEntityXml.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));

        final String unresolvedRefXml = "<root><shared/><main><s xs.ref=\"missing\"/></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        unresolvedRefXml.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));
    }

    @Test
    void singlePassSharedResolvesForwardAndCyclicReferencesAcrossPropertiesAndContainers() throws Throwable {
        final SyntheticHostEnv env = new SyntheticHostEnv();
        final StreamingXmlDeserializeBridge.HostBinding binding =
                StreamingXmlDeserializeBridge.resolveHostBinding(env.loader);
        assertTrue(binding.available, () -> "HostBinding failed: " + binding.resolutionFailure);

        final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<?import " + ModelNode.class.getName() + "?>\n"
                + "<?import " + BaseNode.class.getName() + "?>\n"
                + "<root>\n"
                + "  <shared>\n"
                + "    <ModelNode xs.id=\"n1\">\n"
                + "      <s xs.n=\"label\">first</s>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n2\"/>\n"
                + "      <array_list xs.n=\"list\" count=\"3\">\n"
                + "        <ModelNode xs.ref=\"n2\"/>\n"
                + "        <ModelNode xs.ref=\"n1\"/>\n"
                + "        <s>tail</s>\n"
                + "      </array_list>\n"
                + "      <carray_list xs.n=\"clist\" count=\"2\">\n"
                + "        <ModelNode xs.ref=\"n3\"/>\n"
                + "        <s>c-tail</s>\n"
                + "      </carray_list>\n"
                + "      <array xs.n=\"nodeArray\" count=\"2\" type=\"ModelNode\">\n"
                + "        <ModelNode xs.ref=\"n2\"/>\n"
                + "        <ModelNode xs.ref=\"n3\"/>\n"
                + "      </array>\n"
                + "      <hash_map xs.n=\"strMap\" count=\"2\" keyType=\"string\">\n"
                + "        <ModelNode xs.n=\"later\" xs.ref=\"n2\"/>\n"
                + "        <ModelNode xs.n=\"self\" xs.ref=\"n1\"/>\n"
                + "      </hash_map>\n"
                + "      <linked_map xs.n=\"objMap\" count=\"2\">\n"
                + "        <entry>\n"
                + "          <ModelNode xs.n=\"key\" xs.ref=\"n2\"/>\n"
                + "          <ModelNode xs.n=\"value\" xs.ref=\"n3\"/>\n"
                + "        </entry>\n"
                + "        <entry>\n"
                + "          <ModelNode xs.n=\"key\" xs.ref=\"n1\"/>\n"
                + "          <ModelNode xs.n=\"value\" xs.ref=\"n2\"/>\n"
                + "        </entry>\n"
                + "      </linked_map>\n"
                + "      <linked_set xs.n=\"set\" count=\"3\">\n"
                + "        <s>before</s>\n"
                + "        <ModelNode xs.ref=\"n2\"/>\n"
                + "        <s>after</s>\n"
                + "      </linked_set>\n"
                + "    </ModelNode>\n"
                + "    <ModelNode xs.id=\"n2\">\n"
                + "      <s xs.n=\"label\">second</s>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n1\"/>\n"
                + "    </ModelNode>\n"
                + "    <ModelNode xs.id=\"n3\">\n"
                + "      <s xs.n=\"label\">self-cycle</s>\n"
                + "      <ModelNode xs.n=\"peer\" xs.ref=\"n3\"/>\n"
                + "    </ModelNode>\n"
                + "  </shared>\n"
                + "  <main>\n"
                + "    <ModelNode xs.ref=\"n1\"/>\n"
                + "  </main>\n"
                + "</root>";

        final Object rawResult = StreamingXmlDeserializeBridge.tryDeserialize(
                xml.getBytes(StandardCharsets.UTF_8), env.newArchiveReader(), new HashMap<>(), -1, false);
        assertTrue(rawResult instanceof ModelNode, "Expected ModelNode, got: " + rawResult);
        final ModelNode n1 = (ModelNode) rawResult;
        final ModelNode n2 = n1.peer;
        assertNotNull(n2);
        assertEquals("second", n2.label);
        assertSame(n1, n2.peer);

        // Verify list and CArrayList forward refs preserve exact slot order.
        assertEquals(3, n1.list.size());
        assertSame(n2, n1.list.get(0));
        assertSame(n1, n1.list.get(1));
        assertEquals("tail", n1.list.get(2));
        assertEquals(2, n1.clist.size());
        final ModelNode n3 = (ModelNode) n1.clist.get(0);
        assertNotNull(n3);
        assertEquals("self-cycle", n3.label);
        assertSame(n3, n3.peer);
        assertEquals("c-tail", n1.clist.get(1));

        // Verify Object[] array, string-keyed map, object-keyed map, and LinkedHashSet order.
        assertEquals(2, n1.nodeArray.length);
        assertSame(n2, n1.nodeArray[0]);
        assertSame(n3, n1.nodeArray[1]);
        assertSame(n2, n1.strMap.get("later"));
        assertSame(n1, n1.strMap.get("self"));
        assertSame(n3, n1.objMap.get(n2));
        assertSame(n2, n1.objMap.get(n1));
        assertEquals(List.of("before", n2, "after"), new ArrayList<>(n1.set));
    }

    @Test
    void singlePassSharedFailsClosedOnUnresolvedOrNonDeferrableForwardReferences() throws Throwable {
        final SyntheticHostEnv env = new SyntheticHostEnv();
        final StreamingXmlDeserializeBridge.HostBinding binding =
                StreamingXmlDeserializeBridge.resolveHostBinding(env.loader);
        assertTrue(binding.available, () -> "HostBinding failed: " + binding.resolutionFailure);
        final Object reader = env.newArchiveReader();

        final String unresolvedSharedForwardRef = "<?import " + ModelNode.class.getName() + "?>\n"
                + "<root><shared>"
                + "<ModelNode xs.id=\"n1\"><ModelNode xs.n=\"peer\" xs.ref=\"missing\"/></ModelNode>"
                + "</shared><main><ModelNode xs.ref=\"n1\"/></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        unresolvedSharedForwardRef.getBytes(StandardCharsets.UTF_8),
                        reader,
                        new HashMap<>(),
                        -1,
                        false));

        final String enumForwardRef = "<?import " + ModelNode.class.getName() + "?>\n"
                + "<?import " + SampleMode.class.getName() + "?>\n"
                + "<root><shared>"
                + "<ModelNode xs.id=\"n1\"><SampleMode xs.n=\"mode\" xs.ref=\"e1\"/></ModelNode>"
                + "<SampleMode xs.id=\"e1\" v=\"BETA\"/>"
                + "</shared><main><ModelNode xs.ref=\"n1\"/></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        enumForwardRef.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));

        final String inlineCustomForwardRef = "<?import " + ModelNode.class.getName() + "?>\n"
                + "<?import " + CustomPayload.class.getName() + "?>\n"
                + "<root><shared>"
                + "<ModelNode xs.id=\"n1\">"
                + "  <CustomPayload xs.n=\"custom\" attr=\"a\"><nested k=\"v\" xs.ref=\"n2\"/></CustomPayload>"
                + "</ModelNode>"
                + "<ModelNode xs.id=\"n2\"><s xs.n=\"label\">late</s></ModelNode>"
                + "</shared><main><ModelNode xs.ref=\"n1\"/></main></root>";
        assertSame(
                StreamingXmlDeserializeBridge.UNHANDLED,
                StreamingXmlDeserializeBridge.tryDeserialize(
                        inlineCustomForwardRef.getBytes(StandardCharsets.UTF_8), reader, new HashMap<>(), -1, false));
    }

    @Test
    void patchedSyntheticUUSerializeUsesFastPathAndFallsBackOnUnhandled() throws Exception {
        final SyntheticHostEnv env = new SyntheticHostEnv();
        final StreamingXmlDeserializeBridge.HostBinding binding =
                StreamingXmlDeserializeBridge.resolveHostBinding(env.loader);
        assertTrue(binding.available, () -> "HostBinding failed: " + binding.resolutionFailure);
        final byte[] syntheticUU = buildSyntheticUUSerialize();
        final byte[] patched = StreamingXmlDeserializePatcher.tryPatch(
                StreamingXmlDeserializePatcher.TARGET_INTERNAL_NAME, env.loader, syntheticUU);
        assertNotNull(patched);

        final Class<?> uuClass = env.defineClass("com.live2d.serialize.UUSerialize", patched);
        final Object uuInstance = uuClass.getDeclaredConstructor().newInstance();
        final Method deserializeExe = uuClass.getDeclaredMethod(
                DESERIALIZE_EXE, env.archiveReaderClass, HashMap.class, int.class, boolean.class);
        deserializeExe.setAccessible(true);

        final String handledXml = "<root><shared/><main><s>fast-path-ok</s></main></root>";
        final Object fastResult = deserializeExe.invoke(
                uuInstance,
                env.newArchiveReaderWithBytes(handledXml.getBytes(StandardCharsets.UTF_8)),
                new HashMap<>(),
                -1,
                false);
        assertEquals("fast-path-ok", fastResult);
        assertEquals(0, env.fallbackParserCallCount(), "Native JDOM parser must not run on fast path");

        final String fallbackXml = "<!DOCTYPE root><root><shared/><main><s>x</s></main></root>";
        final Object fallbackResult = deserializeExe.invoke(
                uuInstance,
                env.newArchiveReaderWithBytes(fallbackXml.getBytes(StandardCharsets.UTF_8)),
                new HashMap<>(),
                -1,
                false);
        assertEquals("FALLBACK_DOC_RESULT", fallbackResult);
        assertEquals(1, env.fallbackParserCallCount(), "Native JDOM parser must run when bridge returns UNHANDLED");
    }

    // ---- Test model classes ----

    public enum SampleMode {
        ALPHA,
        BETA
    }

    public static class BaseNode {
        public int baseId;
    }

    public static final class ModelNode extends BaseNode {
        public String label;
        public ModelNode peer;
        public CustomPayload custom;
        public PostProcessNode postNode;
        public SampleMode mode;
        public float[] floats;
        public float[] emptyFloats;
        public int[] ints;
        public ArrayList<Object> list;
        public List<Object> clist;
        public HashMap<Object, Object> strMap;
        public LinkedHashMap<Object, Object> objMap;
        public LinkedHashSet<Object> set;
        public ModelNode[] nodeArray;
    }

    public static final class CustomPayload {
        public String attrValue;
        public String nestedSummary;
    }

    public static final class PostProcessNode {
        public String value;
        public String postProcessedTag;
    }

    // ---- Helpers for host JAR discovery & bytecode inspection ----

    private static List<Path> discoverHostJars() throws IOException {
        final List<Path> jars = new ArrayList<>();
        final Path home = Path.of(System.getProperty("user.home"));
        final Path artifactsDir = home.resolve(ARTIFACTS_DIR_NAME);
        if (Files.isDirectory(artifactsDir)) {
            try (var stream = Files.list(artifactsDir)) {
                stream.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(dir -> dir.resolve(JAR_NAME))
                        .filter(Files::isRegularFile)
                        .forEach(jars::add);
            }
        }
        final Path jar5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(jar5305)) {
            jars.add(jar5305);
        }
        return jars;
    }

    private static String versionLabel(final Path jarPath) {
        if (jarPath.toString().contains(VERSION_5305)) {
            return VERSION_5305;
        }
        return jarPath.getParent().getFileName().toString();
    }

    private static byte[] readClassEntry(final JarFile jar, final String internalName) throws IOException {
        final JarEntry entry = jar.getJarEntry(internalName + CLASS_SUFFIX);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static int countGcCalls(final byte[] bytecode) {
        final int[] count = new int[1];
        new ClassReader(bytecode)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!DESERIALIZE_FROM_FILE.equals(name) && !DESERIALIZE_FROM_BYTES.equals(name)) {
                                    return null;
                                }
                                return new MethodVisitor(ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean isInterface) {
                                        if (opcode == INVOKESTATIC
                                                && SYSTEM_OWNER.equals(owner)
                                                && GC_NAME.equals(mName)
                                                && VOID_DESC.equals(mDesc)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static int countBridgeCalls(final byte[] bytecode) {
        final int[] count = new int[1];
        new ClassReader(bytecode)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!DESERIALIZE_EXE.equals(name) || !DESERIALIZE_EXE_DESC.equals(desc)) {
                                    return null;
                                }
                                return new MethodVisitor(ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean isInterface) {
                                        if (opcode == INVOKESTATIC && BRIDGE_OWNER.equals(owner)) {
                                            count[0]++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return count[0];
    }

    private static byte[] buildMinimalClass(final String internalName) {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, internalName, null, "java/lang/Object", null);
        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", VOID_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", VOID_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Generates a synthetic {@code com.live2d.serialize.UUSerialize} class whose
     * {@code deserialize_exe} invokes {@code com/live2d/util/aL.a([B)Lorg/jdom/Document;} so
     * {@link StreamingXmlDeserializePatcher} can be verified at runtime.
     */
    private static byte[] buildSyntheticUUSerialize() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(
                V17,
                ACC_PUBLIC | ACC_FINAL | ACC_SUPER,
                "com/live2d/serialize/UUSerialize",
                null,
                "java/lang/Object",
                null);
        final MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", VOID_DESC, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", VOID_DESC, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        final MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, DESERIALIZE_EXE, DESERIALIZE_EXE_DESC, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "com/live2d/util/aL", "a", "Lcom/live2d/util/aL;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(
                INVOKEVIRTUAL, "com/live2d/serialize/archiver/ArchiveReader", "getMainXmlBytes", "()[B", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "com/live2d/util/aL", "a", "([B)Lorg/jdom/Document;", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        mv.visitLdcInsn("FALLBACK_DOC_RESULT");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Isolated in-memory stub loader providing minimal Cubism & JDOM types so streaming
     * deserialization parity can be exercised without licensed Cubism JARs on the test classpath.
     */
    private static final class SyntheticHostEnv {

        final StubLoader loader;
        final Class<?> archiveReaderClass;
        final Class<?> xmlReaderClass;
        final Class<?> aLClass;

        SyntheticHostEnv() throws Exception {
            this.loader = new StubLoader(StreamingXmlDeserializeTest.class.getClassLoader());
            defineAllStubs();
            this.archiveReaderClass = loader.loadClass("com.live2d.serialize.archiver.ArchiveReader");
            this.xmlReaderClass = loader.loadClass("com.live2d.serialize.XmlReader");
            this.aLClass = loader.loadClass("com.live2d.util.aL");
        }

        Class<?> defineClass(final String name, final byte[] bytes) {
            return loader.define(name, bytes);
        }

        Object newArchiveReader() throws Exception {
            return archiveReaderClass.getDeclaredConstructor().newInstance();
        }

        Object newArchiveReaderWithBytes(final byte[] bytes) throws Exception {
            return archiveReaderClass.getDeclaredConstructor(byte[].class).newInstance((Object) bytes);
        }

        int fallbackParserCallCount() throws Exception {
            return aLClass.getField("callCount").getInt(null);
        }

        Integer lastVersionFor(final String key) throws Exception {
            @SuppressWarnings("unchecked")
            final Map<String, Integer> versions = (Map<String, Integer>)
                    xmlReaderClass.getField("lastVersions").get(null);
            return versions.get(key);
        }

        private void defineAllStubs() throws Exception {
            // Minimal JDOM classes: Content, Parent, Attribute, Text, CDATA, ProcessingInstruction,
            // Element, Document, UncheckedJDOMFactory.
            final javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            final Path tmpDir = Files.createTempDirectory("streaming-xml-stubs");
            try {
                writeJava(tmpDir, "org/jdom/Content.java", "package org.jdom; public abstract class Content {}");
                writeJava(tmpDir, "org/jdom/Parent.java", "package org.jdom; public interface Parent {}");
                writeJava(
                        tmpDir,
                        "org/jdom/Attribute.java",
                        "package org.jdom; public class Attribute { public final String name; public final String value; "
                                + "public Attribute(String n, String v) { name = n; value = v; } }");
                writeJava(
                        tmpDir,
                        "org/jdom/Text.java",
                        "package org.jdom; public class Text extends Content { public final String text; "
                                + "public Text(String t) { text = t; } }");
                writeJava(
                        tmpDir,
                        "org/jdom/CDATA.java",
                        "package org.jdom; public class CDATA extends Text { public CDATA(String t) { super(t); } }");
                writeJava(
                        tmpDir,
                        "org/jdom/ProcessingInstruction.java",
                        "package org.jdom; public class ProcessingInstruction extends Content { "
                                + "public final String target; public final String data; "
                                + "public ProcessingInstruction(String t, String d) { target = t; data = d; } "
                                + "public String getTarget() { return target; } public String getData() { return data; } }");
                writeJava(
                        tmpDir,
                        "org/jdom/Element.java",
                        "package org.jdom; import java.util.*; public class Element extends Content implements Parent { "
                                + "public final String name; public final Map<String,String> attrs = new LinkedHashMap<>(); "
                                + "public final List<Content> contents = new ArrayList<>(); "
                                + "public Element(String n) { name = n; } "
                                + "public String getName() { return name; } "
                                + "public String getAttributeValue(String k) { return attrs.get(k); } "
                                + "public List<Element> getChildren() { List<Element> r = new ArrayList<>(); "
                                + "for (Content c : contents) if (c instanceof Element) r.add((Element)c); return r; } "
                                + "public String getText() { StringBuilder sb = new StringBuilder(); "
                                + "for (Content c : contents) if (c instanceof Text) sb.append(((Text)c).text); return sb.toString(); } }");
                writeJava(tmpDir, "org/jdom/Document.java", "package org.jdom; public class Document {}");
                writeJava(
                        tmpDir,
                        "org/jdom/UncheckedJDOMFactory.java",
                        "package org.jdom; public class UncheckedJDOMFactory { "
                                + "public Element element(String n) { return new Element(n); } "
                                + "public Attribute attribute(String n, String v) { return new Attribute(n, v); } "
                                + "public Text text(String t) { return new Text(t); } "
                                + "public CDATA cdata(String t) { return new CDATA(t); } "
                                + "public ProcessingInstruction processingInstruction(String t, String d) { return new ProcessingInstruction(t, d); } "
                                + "public void addContent(Parent p, Content c) { ((Element)p).contents.add(c); } "
                                + "public void setAttribute(Element e, Attribute a) { e.attrs.put(a.name, a.value); } }");

                // Minimal Cubism serialization stubs.
                writeJava(
                        tmpDir,
                        "com/live2d/type/CArrayList.java",
                        "package com.live2d.type; import java.util.ArrayList; "
                                + "public class CArrayList<T> extends ArrayList<T> { "
                                + "public CArrayList() { super(); } public CArrayList(int cap) { super(cap); } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/archiver/ArchiveReader.java",
                        "package com.live2d.serialize.archiver; public class ArchiveReader { "
                                + "private final byte[] bytes; public ArchiveReader() { this.bytes = null; } "
                                + "public ArchiveReader(byte[] b) { this.bytes = b; } "
                                + "public byte[] getMainXmlBytes() { return bytes; } }");
                writeJava(
                        tmpDir,
                        "com/live2d/util/aL.java",
                        "package com.live2d.util; import org.jdom.Document; public class aL { "
                                + "public static final aL a = new aL(); public static int callCount = 0; "
                                + "public Document a(byte[] b) { callCount++; return new Document(); } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/XmlParam.java",
                        "package com.live2d.serialize; public class XmlParam {}");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/IXmlSerializable_postProcess.java",
                        "package com.live2d.serialize; import org.jdom.Element; "
                                + "public interface IXmlSerializable_postProcess { void onDeserialized(Element e, XmlReader r); }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/SimpleSerializer.java",
                        "package com.live2d.serialize.impl; import com.live2d.serialize.XmlReader; import org.jdom.Element; "
                                + "public final class SimpleSerializer { public interface c { "
                                + "Object createInstance(Element e, XmlReader r); "
                                + "void setupInstance(Element e, Object o, XmlReader r); } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/AClassSerializer.java",
                        "package com.live2d.serialize.impl; import com.live2d.serialize.XmlReader; import org.jdom.Element; "
                                + "public abstract class AClassSerializer implements SimpleSerializer.c { "
                                + "public abstract void setupInstance(Element e, Object o, XmlReader r); }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/PropertySet.java",
                        "package com.live2d.serialize.impl; import java.lang.reflect.Field; "
                                + "public final class PropertySet { private final Object prop; private final Field field; "
                                + "public PropertySet(Object p, Field f) { this.prop = p; this.field = f; } "
                                + "public Object getProperty() { return prop; } "
                                + "public Field getBackingField() { return field; } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/ClassSerializer.java",
                        "package com.live2d.serialize.impl; import com.live2d.serialize.XmlReader; import java.util.*; import org.jdom.Element; "
                                + "public final class ClassSerializer extends AClassSerializer { "
                                + "private final Class<?> clazz; private final Map<String, PropertySet> map; "
                                + "public ClassSerializer(Class<?> c, Map<String, PropertySet> m) { this.clazz = c; this.map = m; } "
                                + "public Map<String, PropertySet> getNameInParentToPropertySetMap() { return map; } "
                                + "public Object createInstance(Element e, XmlReader r) { try { return clazz.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); } } "
                                + "public void setupInstance(Element e, Object o, XmlReader r) { "
                                + "  for (Element ch : e.getChildren()) { PropertySet ps = map.get(ch.getAttributeValue(\"xs.n\")); "
                                + "    if (ps != null && ps.getBackingField() != null) { try { ps.getBackingField().set(o, ch.getText()); } catch (Exception ex) { throw new RuntimeException(ex); } } } "
                                + "  if (o.getClass().getSimpleName().equals(\"PostProcessNode\")) { try { "
                                + "    o.getClass().getField(\"postProcessedTag\").set(o, e.getAttributeValue(\"tag\")); "
                                + "  } catch (Exception ex) { throw new RuntimeException(ex); } } } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/CustomClassSerializer.java",
                        "package com.live2d.serialize.impl; import com.live2d.serialize.XmlReader; import org.jdom.Element; "
                                + "public final class CustomClassSerializer extends AClassSerializer { "
                                + "private final Class<?> clazz; public CustomClassSerializer(Class<?> c) { this.clazz = c; } "
                                + "public Object createInstance(Element e, XmlReader r) { try { return clazz.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); } } "
                                + "public void setupInstance(Element e, Object o, XmlReader r) { try { "
                                + "  clazz.getField(\"attrValue\").set(o, e.getAttributeValue(\"attr\")); "
                                + "  Element nested = e.getChildren().get(0); "
                                + "  clazz.getField(\"nestedSummary\").set(o, nested.getAttributeValue(\"k\") + \":\" + nested.getText()); "
                                + "} catch (Exception ex) { throw new RuntimeException(ex); } } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/impl/SerializeManager.java",
                        "package com.live2d.serialize.impl; import com.live2d.serialize.XmlParam; import java.lang.reflect.Field; import java.util.*; "
                                + "public final class SerializeManager { public static final SerializeManager INSTANCE = new SerializeManager(); "
                                + "private final Map<Class<?>, AClassSerializer> cache = new HashMap<>(); "
                                + "public AClassSerializer findOrBuildObjectSerializer(Class<?> c, XmlParam p) { "
                                + "  if (c == Object.class) return null; "
                                + "  AClassSerializer existing = cache.get(c); if (existing != null) return existing; "
                                + "  AClassSerializer created; "
                                + "  if (c.getSimpleName().equals(\"CustomPayload\")) { created = new CustomClassSerializer(c); } "
                                + "  else { Map<String, PropertySet> props = new LinkedHashMap<>(); "
                                + "    for (Field f : c.getDeclaredFields()) { props.put(f.getName(), new PropertySet(null, f)); } "
                                + "    created = new ClassSerializer(c, props); } "
                                + "  cache.put(c, created); return created; } }");
                writeJava(
                        tmpDir,
                        "com/live2d/serialize/XmlReader.java",
                        "package com.live2d.serialize; import com.live2d.serialize.archiver.ArchiveReader; "
                                + "import com.live2d.serialize.impl.*; import com.live2d.type.CArrayList; "
                                + "import java.util.*; import org.jdom.ProcessingInstruction; "
                                + "public final class XmlReader extends XmlParam { "
                                + "public static HashMap<String, Integer> lastVersions = new HashMap<>(); "
                                + "private final HashMap<String, Integer> versions = new HashMap<>(); "
                                + "private final HashMap<String, Object> readObjectRefs = new HashMap<>(); "
                                + "private final ArrayList<Object> postProcessTasks = new ArrayList<>(); "
                                + "private final HashMap<String, Class<?>> simpleNameToClassMap = new HashMap<>(); "
                                + "public XmlReader(ArchiveReader ar, HashMap<String, Object> cp) { lastVersions = versions; } "
                                + "public void parse_ProcessingInstruction(ProcessingInstruction pi, CArrayList<String> missing) { "
                                + "  if (\"version\".equals(pi.getTarget().trim())) { String[] parts = pi.getData().trim().split(\":\"); "
                                + "    if (parts.length == 2) versions.put(parts[0], Integer.parseInt(parts[1])); } } "
                                + "public void parseImport(String fqcn, String debug, CArrayList<String> missing) { "
                                + "  try { Class<?> c = Class.forName(fqcn); simpleNameToClassMap.put(c.getSimpleName(), c); } catch (ClassNotFoundException ignored) {} } "
                                + "public Class<?> getClassByTagName(String tag) { return simpleNameToClassMap.get(tag); } "
                                + "public SimpleSerializer.c findSerializer$core(String tag) { "
                                + "  Class<?> c = getClassByTagName(tag); if (c == null) return null; "
                                + "  if (c.getSimpleName().equals(\"PostProcessNode\")) { return new CustomPostProcessSerializer(c); } "
                                + "  return SerializeManager.INSTANCE.findOrBuildObjectSerializer(c, this); } "
                                + "private static final class CustomPostProcessSerializer implements SimpleSerializer.c { "
                                + "  private final Class<?> c; CustomPostProcessSerializer(Class<?> c) { this.c = c; } "
                                + "  public Object createInstance(org.jdom.Element e, XmlReader r) { try { return c.getDeclaredConstructor().newInstance(); } catch (Exception ex) { throw new RuntimeException(ex); } } "
                                + "  public void setupInstance(org.jdom.Element e, Object o, XmlReader r) { try { "
                                + "    c.getField(\"value\").set(o, e.getChildren().get(0).getText()); "
                                + "    c.getField(\"postProcessedTag\").set(o, e.getAttributeValue(\"tag\")); "
                                + "  } catch (Exception ex) { throw new RuntimeException(ex); } } } }");

                // Compile all stubs together.
                final List<String> sources = new ArrayList<>();
                try (var walk = Files.walk(tmpDir)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> sources.add(p.toString()));
                }
                final List<String> args = new ArrayList<>();
                args.add("-d");
                args.add(tmpDir.toString());
                args.addAll(sources);
                final int rc = compiler.run(null, null, null, args.toArray(new String[0]));
                assertEquals(0, rc, "Stub compilation failed");

                try (var walk = Files.walk(tmpDir)) {
                    for (final Path classFile : walk.filter(p -> p.toString().endsWith(CLASS_SUFFIX))
                            .toList()) {
                        final String rel = tmpDir.relativize(classFile).toString();
                        final String className = rel.substring(0, rel.length() - CLASS_SUFFIX.length())
                                .replace('/', '.')
                                .replace('\\', '.');
                        loader.addClassBytes(className, Files.readAllBytes(classFile));
                    }
                }
            } finally {
                deleteRecursively(tmpDir);
            }
        }

        private static void writeJava(final Path root, final String relPath, final String source) throws IOException {
            final Path target = root.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, source, StandardCharsets.UTF_8);
        }

        private static void deleteRecursively(final Path dir) throws IOException {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (final IOException ignored) {
                        // Best-effort temp cleanup.
                    }
                });
            }
        }
    }

    private static final class StubLoader extends ClassLoader {
        private final Map<String, byte[]> bytecodeByName = new HashMap<>();

        StubLoader(final ClassLoader parent) {
            super(parent);
        }

        void addClassBytes(final String name, final byte[] bytes) {
            bytecodeByName.put(name, bytes);
        }

        Class<?> define(final String name, final byte[] bytes) {
            bytecodeByName.put(name, bytes);
            return defineClass(name, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && bytecodeByName.containsKey(name)) {
                    loaded = findClass(name);
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final byte[] bytes = bytecodeByName.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }

        @Override
        public InputStream getResourceAsStream(final String name) {
            if (name != null && name.endsWith(CLASS_SUFFIX)) {
                final String className =
                        name.substring(0, name.length() - CLASS_SUFFIX.length()).replace('/', '.');
                final byte[] bytes = bytecodeByName.get(className);
                if (bytes != null) {
                    return new java.io.ByteArrayInputStream(bytes);
                }
            }
            return super.getResourceAsStream(name);
        }
    }
}
