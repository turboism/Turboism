package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hybrid streaming XML deserializer for Cubism {@code UUSerialize.deserialize_exe}.
 *
 * <p>Bypasses full JDOM DOM tree construction for standard primitives, primitive arrays,
 * collections, enums, and {@code ClassSerializer} graphs while materializing only local JDOM
 * subtrees for {@code IXmlSerializable} / {@code CustomClassSerializer} and post-process elements.
 * Any unsupported XML construct or non-fatal failure returns {@link #UNHANDLED} so the host falls
 * back to native JDOM deserialization.</p>
 */
public final class StreamingXmlDeserializeBridge {

    /** Sentinel returned when streaming deserialization declines and native JDOM must run. */
    public static final Object UNHANDLED = new Object();

    private static final String XML_READER_CLASS = "com.live2d.serialize.XmlReader";
    private static final String XML_PARAM_CLASS = "com.live2d.serialize.XmlParam";
    private static final String ARCHIVE_READER_CLASS = "com.live2d.serialize.archiver.ArchiveReader";
    private static final String SERIALIZE_MANAGER_CLASS = "com.live2d.serialize.impl.SerializeManager";
    private static final String CLASS_SERIALIZER_CLASS = "com.live2d.serialize.impl.ClassSerializer";
    private static final String A_CLASS_SERIALIZER_CLASS = "com.live2d.serialize.impl.AClassSerializer";
    private static final String PROPERTY_SET_CLASS = "com.live2d.serialize.impl.PropertySet";
    private static final String SIMPLE_SERIALIZER_IFACE = "com.live2d.serialize.impl.SimpleSerializer$c";
    private static final String POST_PROCESS_IFACE = "com.live2d.serialize.IXmlSerializable_postProcess";
    private static final String CARRAY_LIST_CLASS = "com.live2d.type.CArrayList";
    private static final String CANCEL_EXCEPTION_CLASS = "jp.noids.framework.e.a";
    private static final String IMAGE_RESOURCE_CLASS_NAME = "com.live2d.graphics.CImageResource";

    private static final String JDOM_FACTORY_CLASS = "org.jdom.UncheckedJDOMFactory";
    private static final String JDOM_ELEMENT_CLASS = "org.jdom.Element";
    private static final String JDOM_ATTRIBUTE_CLASS = "org.jdom.Attribute";
    private static final String JDOM_TEXT_CLASS = "org.jdom.Text";
    private static final String JDOM_CDATA_CLASS = "org.jdom.CDATA";
    private static final String JDOM_PI_CLASS = "org.jdom.ProcessingInstruction";
    private static final String JDOM_PARENT_CLASS = "org.jdom.Parent";
    private static final String JDOM_CONTENT_CLASS = "org.jdom.Content";

    private static final String KOTLIN_FUNCTION0_CLASS = "kotlin.jvm.functions.Function0";
    private static final String KOTLIN_MUTABLE_PROP1_CLASS = "kotlin.reflect.KMutableProperty1";
    private static final String KOTLIN_CALLABLE_CLASS = "kotlin.reflect.KCallable";
    private static final String KOTLIN_CALLABLES_JVM_CLASS = "kotlin.reflect.jvm.KCallablesJvm";

    private static final String TAG_ROOT = "root";
    private static final String TAG_SHARED = "shared";
    private static final String TAG_MAIN = "main";
    private static final String TAG_NULL = "null";
    private static final String TAG_STRING = "s";
    private static final String TAG_INT = "i";
    private static final String TAG_FLOAT = "f";
    private static final String TAG_DOUBLE = "d";
    private static final String TAG_LONG = "l";
    private static final String TAG_BOOL = "b";
    private static final String TAG_SHORT = "short";
    private static final String TAG_BYTE = "byte";
    private static final String TAG_CHAR = "char";
    private static final String TAG_FILE = "file";
    private static final String TAG_FLOAT_ARRAY = "float-array";
    private static final String TAG_INT_ARRAY = "int-array";
    private static final String TAG_DOUBLE_ARRAY = "double-array";
    private static final String TAG_SHORT_ARRAY = "short-array";
    private static final String TAG_LONG_ARRAY = "long-array";
    private static final String TAG_BYTE_ARRAY = "byte-array";
    private static final String TAG_CHAR_ARRAY = "char-array";
    private static final String TAG_BOOL_ARRAY = "bool-array";
    private static final String TAG_ARRAY = "array";
    private static final String TAG_ARRAY_LIST = "array_list";
    private static final String TAG_CARRAY_LIST = "carray_list";
    private static final String TAG_HASH_MAP = "hash_map";
    private static final String TAG_LINKED_MAP = "linked_map";
    private static final String TAG_HASH_SET = "hash_set";
    private static final String TAG_LINKED_SET = "linked_set";
    private static final String TAG_ENTRY = "entry";

    private static final String ATTR_NAME = "xs.n";
    private static final String ATTR_ID = "xs.id";
    private static final String ATTR_REF = "xs.ref";
    private static final String ATTR_COUNT = "count";
    private static final String ATTR_TYPE = "type";
    private static final String ATTR_VALUE = "v";
    private static final String ATTR_KEY_TYPE = "keyType";
    private static final String ATTR_FILE_FORMAT_VERSION = "fileFormatVersion";

    private static final String KEY_TYPE_STRING = "string";
    private static final String MAP_KEY_NAME = "key";
    private static final String MAP_VALUE_NAME = "value";
    private static final String SUPER_PROP_NAME = "super";
    private static final String PI_IMPORT = "import";
    private static final String PI_VERSION = "version";
    private static final String DEBUG_FILE_NOT_INIT = "not init";
    private static final String ENUM_CUSTOM_VALUE_OF = "customValueOf";
    private static final String ENUM_VALUE_OF = "valueOf";

    private static final long NO_FILE_FORMAT_VERSION = -1L;
    private static final int NO_SEEK_OFFSET = -1;

    private static final ClassValue<HostBinding> HOST_BINDING_CACHE = new ClassValue<>() {
        @Override
        protected HostBinding computeValue(final Class<?> anchorClass) {
            return HostBinding.resolve(anchorClass.getClassLoader());
        }
    };

    private static final ClassValue<MethodHandle> ENUM_VALUE_OF_CACHE = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> enumType) {
            return resolveEnumValueOf(enumType);
        }
    };

    private StreamingXmlDeserializeBridge() {}

    /**
     * Attempts streaming XML deserialization directly from {@code xmlBytes}.
     *
     * @param xmlBytes raw UTF-8 {@code main.xml} bytes read from the archive
     * @param archiveReader active Cubism {@code ArchiveReader}
     * @param clientProperty caller-supplied client properties map
     * @param appVersion host application file-format version gate, or negative when disabled
     * @param showNewerVersionDialog whether the host would prompt on newer file versions
     * @return deserialized root object, or {@link #UNHANDLED} to fall back to native JDOM
     */
    public static Object tryDeserialize(
            final byte[] xmlBytes,
            final Object archiveReader,
            final HashMap<String, Object> clientProperty,
            final int appVersion,
            final boolean showNewerVersionDialog) {
        if (xmlBytes == null || archiveReader == null) {
            return UNHANDLED;
        }
        final HostBinding host = HOST_BINDING_CACHE.get(archiveReader.getClass());
        if (!host.available) {
            return UNHANDLED;
        }
        try {
            final HashMap<String, Object> props = clientProperty != null ? clientProperty : new HashMap<>();
            final Object xmlReader = host.newXmlReader.invokeExact(archiveReader, props);
            return deserializeWithReader(xmlBytes, xmlReader, host, appVersion);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (isCancelException(failure)) {
                sneakyThrow(failure);
            }
            return UNHANDLED;
        }
    }

    /**
     * Package-private entry point for tests exercising streaming deserialization with an explicit
     * {@code XmlReader} instance.
     */
    static Object deserializeWithReader(
            final byte[] xmlBytes, final Object xmlReader, final HostBinding host, final int appVersion)
            throws Throwable {
        final Session session = new Session(new Utf8XmlPullScanner(xmlBytes), xmlReader, host);
        return session.deserializeDocument(appVersion);
    }

    static HostBinding resolveHostBinding(final ClassLoader loader) {
        return HostBinding.resolve(loader);
    }

    private static boolean isCancelException(final Throwable failure) {
        return CANCEL_EXCEPTION_CLASS.equals(failure.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(final Throwable failure) throws E {
        throw (E) failure;
    }

    private static MethodHandle resolveEnumValueOf(final Class<?> enumType) {
        Method method = null;
        try {
            method = enumType.getMethod(ENUM_CUSTOM_VALUE_OF, String.class);
        } catch (final NoSuchMethodException ignored) {
            try {
                method = enumType.getMethod(ENUM_VALUE_OF, String.class);
            } catch (final NoSuchMethodException ignoredAgain) {
                return null;
            }
        }
        try {
            final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(enumType, MethodHandles.lookup());
            return lookup.unreflect(method).asType(MethodType.methodType(Object.class, String.class));
        } catch (final IllegalAccessException | RuntimeException ignored) {
            try {
                final Method opened = ReflectAccess.open(method);
                return MethodHandles.lookup()
                        .unreflect(opened)
                        .asType(MethodType.methodType(Object.class, String.class));
            } catch (final IllegalAccessException | RuntimeException refused) {
                return null;
            }
        }
    }

    /** Per-document streaming deserialization state. */
    private static final class Session {

        private final Utf8XmlPullScanner scanner;
        private final Object xmlReader;
        private final HostBinding host;
        private final Map<String, Object> readObjectRefs;
        private final List<Object> hostPostProcessTasks;
        private final List<Runnable> localPostProcessTasks = new ArrayList<>();
        private final JdomBuilderImpl jdomBuilder;

        @SuppressWarnings("unchecked")
        Session(final Utf8XmlPullScanner scanner, final Object xmlReader, final HostBinding host) throws Throwable {
            this.scanner = scanner;
            this.xmlReader = xmlReader;
            this.host = host;
            this.readObjectRefs = (Map<String, Object>) host.getReadObjectRefs.invokeExact(xmlReader);
            this.hostPostProcessTasks = (List<Object>) host.getPostProcessTasks.invokeExact(xmlReader);
            this.jdomBuilder = new JdomBuilderImpl(host);
        }

        Object deserializeDocument(final int appVersion) throws Throwable {
            final Object missingImports = host.newEmptyCArrayList.invokeExact();
            int event = scanner.nextTagOrPi();
            while (event == Utf8XmlPullScanner.EVENT_PI) {
                processInstruction(scanner.getPiTarget(), scanner.getPiData(), missingImports);
                event = scanner.nextTagOrPi();
            }
            if (event != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Missing root XML element");
            }
            if (appVersion >= 0) {
                final String versionAttr = scanner.getAttributeValue(ATTR_FILE_FORMAT_VERSION);
                final long fileVersion = versionAttr != null
                        ? parseLongOrDefault(versionAttr, NO_FILE_FORMAT_VERSION)
                        : NO_FILE_FORMAT_VERSION;
                if (fileVersion > (long) appVersion) {
                    return UNHANDLED;
                }
            }
            if (TAG_ROOT.equals(scanner.getTagName())) {
                return readStructuredRoot();
            }
            return deserializeCurrentElement();
        }

        private void processInstruction(final String rawTarget, final String rawData, final Object missingImports)
                throws Throwable {
            final String target = rawTarget.trim();
            if (PI_IMPORT.equals(target)) {
                host.parseImport.invokeExact(xmlReader, rawData.trim(), DEBUG_FILE_NOT_INIT, missingImports);
            } else if (PI_VERSION.equals(target)) {
                final Object pi = host.jdomPi.invokeExact(jdomBuilder.factory, rawTarget, rawData);
                host.parseProcessingInstruction.invokeExact(xmlReader, pi, missingImports);
            }
        }

        private Object readStructuredRoot() throws Throwable {
            if (scanner.isEmptyElement()) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Empty <root> element");
            }
            boolean seenShared = false;
            Object mainResult = null;
            boolean seenMain = false;
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    break;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside <root>");
                }
                final String childTag = scanner.getTagName();
                if (TAG_SHARED.equals(childTag)) {
                    readSharedSection();
                    seenShared = true;
                } else if (TAG_MAIN.equals(childTag)) {
                    mainResult = readMainSection();
                    seenMain = true;
                } else {
                    scanner.skipCurrentElementBody();
                }
            }
            if (!seenShared || !seenMain) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Root missing <shared> or <main>");
            }
            runPostProcessTasks();
            return mainResult;
        }

        private void readSharedSection() throws Throwable {
            if (scanner.isEmptyElement()) {
                return;
            }
            final List<SharedEntry> entries = new ArrayList<>();
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    break;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside <shared>");
                }
                final int startOffset = scanner.getElementStartPos();
                final String id = scanner.getAttributeValue(ATTR_ID);
                if (id == null) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Missing xs.id in <shared> child");
                }
                final String tag = scanner.getTagName();
                final Object leaf = tryReadLeafValue(tag);
                if (leaf != UNHANDLED) {
                    if (leaf != null) {
                        readObjectRefs.put(id, leaf);
                    }
                    continue;
                }
                final Object container = tryCreateContainerInstance(tag);
                if (container != UNHANDLED) {
                    if (container != null) {
                        readObjectRefs.put(id, container);
                        entries.add(new SharedEntry(startOffset, tag, null, container, null));
                    }
                    scanner.skipCurrentElementBody();
                    continue;
                }
                final Object serializer = host.findSerializer.invokeExact(xmlReader, tag);
                if (serializer == null) {
                    scanner.skipCurrentElementBody();
                    continue;
                }
                if (host.classSerializerType.isInstance(serializer)) {
                    final Class<?> clazz = (Class<?>) host.getClassByTagName.invokeExact(xmlReader, tag);
                    if (clazz == null) {
                        throw new Utf8XmlPullScanner.StreamingAbortException("Unresolved class for tag: " + tag);
                    }
                    if (clazz.isEnum()) {
                        final Object enumVal = resolveEnumInstance(clazz, scanner.getAttributeValue(ATTR_VALUE));
                        scanner.skipCurrentElementBody();
                        if (enumVal != null) {
                            readObjectRefs.put(id, enumVal);
                        }
                        continue;
                    }
                    if (host.postProcessType.isAssignableFrom(clazz)) {
                        final Object elem = scanner.materializeCurrentElement(jdomBuilder);
                        final Object instance = host.serializerCreateInstance.invokeExact(serializer, elem, xmlReader);
                        if (instance != null) {
                            readObjectRefs.put(id, instance);
                            entries.add(new SharedEntry(NO_SEEK_OFFSET, tag, serializer, instance, elem));
                        }
                        continue;
                    }
                    final Object instance = DeserializeReflectFastBridge.newInstance(clazz);
                    readObjectRefs.put(id, instance);
                    scanner.skipCurrentElementBody();
                    entries.add(new SharedEntry(startOffset, tag, serializer, instance, null));
                } else {
                    final Object elem = scanner.materializeCurrentElement(jdomBuilder);
                    final Object instance = host.serializerCreateInstance.invokeExact(serializer, elem, xmlReader);
                    if (instance != null) {
                        readObjectRefs.put(id, instance);
                        entries.add(new SharedEntry(NO_SEEK_OFFSET, tag, serializer, instance, elem));
                    }
                }
            }

            final int afterSharedPos = scanner.getPos();
            for (final SharedEntry entry : entries) {
                if (entry.materializedElement != null) {
                    host.serializerSetupInstance.invokeExact(
                            entry.serializer, entry.materializedElement, entry.instance, xmlReader);
                    if (host.postProcessType.isInstance(entry.instance)) {
                        host.onDeserialized.invokeExact(entry.instance, entry.materializedElement, xmlReader);
                    }
                    maybeScheduleImagePredecode(entry.instance);
                } else {
                    scanner.seek(entry.startOffset);
                    scanner.readStartTagAtCurrentPos();
                    if (entry.serializer != null) {
                        populateClassObject(entry.serializer, entry.instance);
                    } else {
                        populateContainerInstance(entry.tag, entry.instance);
                    }
                }
            }
            scanner.seek(afterSharedPos);
        }

        private Object readMainSection() throws Throwable {
            if (scanner.isEmptyElement()) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Empty <main> element");
            }
            final int firstChild = scanner.nextChildOrEnd();
            if (firstChild != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Missing root object inside <main>");
            }
            final Object result = deserializeCurrentElement();
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    return result;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside <main>");
                }
                scanner.skipCurrentElementBody();
            }
        }

        private void runPostProcessTasks() {
            for (final Runnable task : localPostProcessTasks) {
                try {
                    task.run();
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    failure.printStackTrace();
                }
            }
            for (final Object fn : hostPostProcessTasks) {
                try {
                    host.function0Invoke.invokeExact(fn);
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    failure.printStackTrace();
                }
            }
        }

        private Object deserializeCurrentElement() throws Throwable {
            final String tag = scanner.getTagName();
            if (TAG_NULL.equals(tag)) {
                scanner.skipCurrentElementBody();
                return null;
            }
            final String refId = scanner.getAttributeValue(ATTR_REF);
            if (refId != null) {
                scanner.skipCurrentElementBody();
                final Object existing = readObjectRefs.get(refId);
                if (existing == null) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unresolved xs.ref: " + refId);
                }
                return existing;
            }
            final Object leaf = tryReadLeafValue(tag);
            if (leaf != UNHANDLED) {
                return leaf;
            }
            final Object container = tryCreateContainerInstance(tag);
            if (container != UNHANDLED) {
                if (container != null) {
                    populateContainerInstance(tag, container);
                } else {
                    scanner.skipCurrentElementBody();
                }
                return container;
            }
            final Object serializer = host.findSerializer.invokeExact(xmlReader, tag);
            if (serializer == null) {
                scanner.skipCurrentElementBody();
                return null;
            }
            if (host.classSerializerType.isInstance(serializer)) {
                final Class<?> clazz = (Class<?>) host.getClassByTagName.invokeExact(xmlReader, tag);
                if (clazz == null) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unresolved class for tag: " + tag);
                }
                if (clazz.isEnum()) {
                    final Object enumVal = resolveEnumInstance(clazz, scanner.getAttributeValue(ATTR_VALUE));
                    scanner.skipCurrentElementBody();
                    return enumVal;
                }
                if (host.postProcessType.isAssignableFrom(clazz)) {
                    return deserializeViaLocalJdom(serializer);
                }
                final Object instance = DeserializeReflectFastBridge.newInstance(clazz);
                populateClassObject(serializer, instance);
                return instance;
            }
            return deserializeViaLocalJdom(serializer);
        }

        private Object deserializeViaLocalJdom(final Object serializer) throws Throwable {
            final Object elem = scanner.materializeCurrentElement(jdomBuilder);
            final Object instance = host.serializerCreateInstance.invokeExact(serializer, elem, xmlReader);
            if (instance != null) {
                host.serializerSetupInstance.invokeExact(serializer, elem, instance, xmlReader);
                if (host.postProcessType.isInstance(instance)) {
                    host.onDeserialized.invokeExact(instance, elem, xmlReader);
                }
                maybeScheduleImagePredecode(instance);
            }
            return instance;
        }

        private static void maybeScheduleImagePredecode(final Object instance) {
            if (instance != null
                    && IMAGE_RESOURCE_CLASS_NAME.equals(instance.getClass().getName())) {
                ImageDecodeBridge.scheduleImageResourcePredecode(instance);
            }
        }

        private Object tryReadLeafValue(final String tag) {
            switch (tag) {
                case TAG_STRING:
                    return scanner.readElementText();
                case TAG_INT:
                    return Integer.valueOf(Integer.parseInt(scanner.readElementText()));
                case TAG_FLOAT:
                    return Float.valueOf(Float.parseFloat(scanner.readElementText()));
                case TAG_DOUBLE:
                    return Double.valueOf(Double.parseDouble(scanner.readElementText()));
                case TAG_LONG:
                    return Long.valueOf(Long.parseLong(scanner.readElementText()));
                case TAG_BOOL:
                    return Boolean.valueOf(Boolean.parseBoolean(scanner.readElementText()));
                case TAG_SHORT:
                    return Short.valueOf(Short.parseShort(scanner.readElementText()));
                case TAG_BYTE:
                    return Byte.valueOf(Byte.parseByte(scanner.readElementText()));
                case TAG_CHAR:
                    return Character.valueOf(scanner.readElementText().charAt(0));
                case TAG_FILE:
                    return new File(scanner.readElementText());
                case TAG_FLOAT_ARRAY:
                    return scanner.readFloatArray(parseCountAttr());
                case TAG_INT_ARRAY:
                    return scanner.readIntArray(parseCountAttr());
                case TAG_DOUBLE_ARRAY:
                    return scanner.readDoubleArray(parseCountAttr());
                case TAG_SHORT_ARRAY:
                    return scanner.readShortArray(parseCountAttr());
                case TAG_LONG_ARRAY:
                    return scanner.readLongArray(parseCountAttr());
                case TAG_BYTE_ARRAY:
                    return scanner.readByteArray(parseCountAttr());
                case TAG_CHAR_ARRAY:
                    return scanner.readCharArray(parseCountAttr());
                case TAG_BOOL_ARRAY:
                    return scanner.readBoolArray(parseCountAttr());
                default:
                    return UNHANDLED;
            }
        }

        private Object tryCreateContainerInstance(final String tag) throws Throwable {
            switch (tag) {
                case TAG_ARRAY_LIST:
                    return new ArrayList<>(parseCountAttr());
                case TAG_CARRAY_LIST:
                    return host.newSizedCArrayList.invokeExact(parseCountAttr());
                case TAG_HASH_MAP:
                    return new HashMap<>(parseCountAttr());
                case TAG_LINKED_MAP:
                    return new LinkedHashMap<>(parseCountAttr());
                case TAG_HASH_SET:
                    return new HashSet<>(parseCountAttr());
                case TAG_LINKED_SET:
                    return new LinkedHashSet<>(parseCountAttr());
                case TAG_ARRAY: {
                    final int count = parseCountAttr();
                    final String typeName = scanner.getAttributeValue(ATTR_TYPE);
                    if (typeName == null) {
                        throw new Utf8XmlPullScanner.StreamingAbortException("Missing type attribute on <array>");
                    }
                    final Class<?> componentType = (Class<?>) host.getClassByTagName.invokeExact(xmlReader, typeName);
                    if (componentType == null) {
                        throw new Utf8XmlPullScanner.StreamingAbortException("Unresolved array type: " + typeName);
                    }
                    return Array.newInstance(componentType, count);
                }
                default:
                    return UNHANDLED;
            }
        }

        @SuppressWarnings("unchecked")
        private void populateContainerInstance(final String tag, final Object container) throws Throwable {
            if (scanner.isEmptyElement()) {
                return;
            }
            switch (tag) {
                case TAG_ARRAY_LIST:
                case TAG_CARRAY_LIST:
                    populateList((List<Object>) container);
                    return;
                case TAG_HASH_MAP:
                case TAG_LINKED_MAP:
                    populateMap((Map<Object, Object>) container);
                    return;
                case TAG_HASH_SET:
                case TAG_LINKED_SET:
                    populateSet((Set<Object>) container);
                    return;
                case TAG_ARRAY:
                    populateObjectArray(container);
                    return;
                default:
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unknown container tag: " + tag);
            }
        }

        private void populateList(final List<Object> list) throws Throwable {
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    return;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside list");
                }
                final String childId = scanner.getAttributeValue(ATTR_ID);
                final Object item = deserializeCurrentElement();
                if (childId != null && item != null) {
                    readObjectRefs.put(childId, item);
                }
                if (item != null) {
                    list.add(item);
                }
            }
        }

        private void populateMap(final Map<Object, Object> map) throws Throwable {
            final boolean stringKeyed = KEY_TYPE_STRING.equals(scanner.getAttributeValue(ATTR_KEY_TYPE));
            if (stringKeyed) {
                while (true) {
                    final int ev = scanner.nextChildOrEnd();
                    if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                        return;
                    }
                    if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                        throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside string map");
                    }
                    final String key = scanner.getAttributeValue(ATTR_NAME);
                    final Object val = deserializeCurrentElement();
                    map.put(key, val);
                }
            }
            final List<Object> deferredKeys = new ArrayList<>();
            final List<Object> deferredValues = new ArrayList<>();
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    break;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside object map");
                }
                if (!TAG_ENTRY.equals(scanner.getTagName())) {
                    scanner.skipCurrentElementBody();
                    continue;
                }
                Object entryKey = null;
                Object entryVal = null;
                if (!scanner.isEmptyElement()) {
                    while (true) {
                        final int entryEv = scanner.nextChildOrEnd();
                        if (entryEv == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                            break;
                        }
                        if (entryEv != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                            throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside map <entry>");
                        }
                        final String fieldName = scanner.getAttributeValue(ATTR_NAME);
                        if (MAP_KEY_NAME.equals(fieldName)) {
                            entryKey = deserializeCurrentElement();
                        } else if (MAP_VALUE_NAME.equals(fieldName)) {
                            entryVal = deserializeCurrentElement();
                        } else {
                            scanner.skipCurrentElementBody();
                        }
                    }
                }
                if (entryKey == null) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Null map entry key");
                }
                deferredKeys.add(entryKey);
                deferredValues.add(entryVal);
            }
            localPostProcessTasks.add(() -> {
                for (int i = 0; i < deferredKeys.size(); i++) {
                    map.put(deferredKeys.get(i), deferredValues.get(i));
                }
            });
        }

        private void populateSet(final Set<Object> set) throws Throwable {
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    return;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside set");
                }
                final Object item = deserializeCurrentElement();
                if (item != null) {
                    set.add(item);
                }
            }
        }

        private void populateObjectArray(final Object array) throws Throwable {
            int index = 0;
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    return;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside object array");
                }
                final Object item = deserializeCurrentElement();
                Array.set(array, index++, item);
            }
        }

        @SuppressWarnings("unchecked")
        private void populateClassObject(final Object classSerializer, final Object target) throws Throwable {
            if (scanner.isEmptyElement()) {
                return;
            }
            final Map<String, Object> propMap = (Map<String, Object>) host.getPropertyMap.invokeExact(classSerializer);
            while (true) {
                final int ev = scanner.nextChildOrEnd();
                if (ev == Utf8XmlPullScanner.EVENT_END_ELEMENT) {
                    return;
                }
                if (ev != Utf8XmlPullScanner.EVENT_START_ELEMENT) {
                    throw new Utf8XmlPullScanner.StreamingAbortException("Unexpected EOF inside class object");
                }
                final String propName = scanner.getAttributeValue(ATTR_NAME);
                if (SUPER_PROP_NAME.equals(propName)) {
                    final String superTag = scanner.getTagName();
                    final Class<?> superClass = (Class<?>) host.getClassByTagName.invokeExact(xmlReader, superTag);
                    if (superClass == null) {
                        throw new Utf8XmlPullScanner.StreamingAbortException("Unresolved super class: " + superTag);
                    }
                    final Object superSerializer = host.findOrBuildObjectSerializer.invokeExact(
                            host.serializeManagerInstance, superClass, xmlReader);
                    if (superSerializer == null) {
                        scanner.skipCurrentElementBody();
                    } else if (host.classSerializerType.isInstance(superSerializer)) {
                        populateClassObject(superSerializer, target);
                    } else {
                        final Object superElem = scanner.materializeCurrentElement(jdomBuilder);
                        host.serializerSetupInstance.invokeExact(superSerializer, superElem, target, xmlReader);
                    }
                    continue;
                }
                final Object propertySet = propName != null ? propMap.get(propName) : null;
                if (propertySet == null) {
                    scanner.skipCurrentElementBody();
                    continue;
                }
                try {
                    assignProperty(propertySet, target);
                } catch (final Utf8XmlPullScanner.StreamingAbortException abort) {
                    throw abort;
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    if (isCancelException(failure)) {
                        throw failure;
                    }
                    failure.printStackTrace();
                }
            }
        }

        private void assignProperty(final Object propertySet, final Object target) throws Throwable {
            final Object kProperty = host.getProperty.invokeExact(propertySet);
            final Field backingField = (Field) host.getBackingField.invokeExact(propertySet);
            if (kProperty != null && host.kMutableProperty1Type.isInstance(kProperty)) {
                final Object value = deserializeCurrentElement();
                if (!DeserializeReflectFastBridge.trySetMutableProperty(
                        propertySet, kProperty, backingField, target, value)) {
                    invokeKotlinSetterFallback(kProperty, target, value);
                }
                return;
            }
            if (backingField != null) {
                final Object value = deserializeCurrentElement();
                if (!DeserializeReflectFastBridge.trySetBackingField(propertySet, backingField, target, value)) {
                    final Field opened = ReflectAccess.open(backingField);
                    opened.set(target, value);
                }
                return;
            }
            scanner.skipCurrentElementBody();
        }

        private void invokeKotlinSetterFallback(final Object kProperty, final Object target, final Object value)
                throws Throwable {
            if (host.kCallableSetAccessible != null) {
                host.kCallableSetAccessible.invokeExact(kProperty, true);
            }
            final Object setter = host.kMutableGetSetter.invokeExact(kProperty);
            host.kCallableCall.invokeExact(setter, new Object[] {target, value});
        }

        private Object resolveEnumInstance(final Class<?> enumType, final String enumName) throws Throwable {
            final MethodHandle handle = ENUM_VALUE_OF_CACHE.get(enumType);
            if (handle == null) {
                throw new Utf8XmlPullScanner.StreamingAbortException("No valueOf method on enum " + enumType.getName());
            }
            return handle.invokeExact(enumName);
        }

        private int parseCountAttr() {
            final String raw = scanner.getAttributeValue(ATTR_COUNT);
            if (raw == null) {
                throw new Utf8XmlPullScanner.StreamingAbortException("Missing count attribute");
            }
            return Integer.parseInt(raw);
        }

        private static long parseLongOrDefault(final String raw, final long fallback) {
            try {
                return Long.parseLong(raw);
            } catch (final NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private static final class SharedEntry {
        final int startOffset;
        final String tag;
        final Object serializer;
        final Object instance;
        final Object materializedElement;

        SharedEntry(
                final int startOffset,
                final String tag,
                final Object serializer,
                final Object instance,
                final Object materializedElement) {
            this.startOffset = startOffset;
            this.tag = tag;
            this.serializer = serializer;
            this.instance = instance;
            this.materializedElement = materializedElement;
        }
    }

    private static final class JdomBuilderImpl implements Utf8XmlPullScanner.JdomSubtreeBuilder {

        private final HostBinding host;
        final Object factory;

        JdomBuilderImpl(final HostBinding host) throws Throwable {
            this.host = host;
            this.factory = host.newUncheckedJdomFactory.invokeExact();
        }

        @Override
        public Object newElement(final String name) throws Throwable {
            return host.jdomElement.invokeExact(factory, name);
        }

        @Override
        public void addAttribute(final Object element, final String name, final String value) throws Throwable {
            final Object attr = host.jdomAttribute.invokeExact(factory, name, value);
            host.jdomSetAttribute.invokeExact(factory, element, attr);
        }

        @Override
        public void addText(final Object element, final String text) throws Throwable {
            final Object textNode = host.jdomText.invokeExact(factory, text);
            host.jdomAddContent.invokeExact(factory, element, textNode);
        }

        @Override
        public void addCdata(final Object element, final String text) throws Throwable {
            final Object cdataNode = host.jdomCdata.invokeExact(factory, text);
            host.jdomAddContent.invokeExact(factory, element, cdataNode);
        }

        @Override
        public void addProcessingInstruction(final Object element, final String target, final String data)
                throws Throwable {
            final Object pi = host.jdomPi.invokeExact(factory, target, data);
            host.jdomAddContent.invokeExact(factory, element, pi);
        }

        @Override
        public void addChild(final Object parent, final Object child) throws Throwable {
            host.jdomAddContent.invokeExact(factory, parent, child);
        }
    }

    /** Cached host reflective bindings anchored per host {@link ClassLoader}. */
    static final class HostBinding {

        static final HostBinding UNAVAILABLE = new HostBinding(null);

        final boolean available;
        final Throwable resolutionFailure;
        final Class<?> classSerializerType;
        final Class<?> postProcessType;
        final Class<?> kMutableProperty1Type;
        final Object serializeManagerInstance;

        final MethodHandle newXmlReader;
        final MethodHandle parseProcessingInstruction;
        final MethodHandle parseImport;
        final MethodHandle findSerializer;
        final MethodHandle getClassByTagName;
        final MethodHandle getReadObjectRefs;
        final MethodHandle getPostProcessTasks;
        final MethodHandle findOrBuildObjectSerializer;
        final MethodHandle getPropertyMap;
        final MethodHandle getProperty;
        final MethodHandle getBackingField;
        final MethodHandle serializerCreateInstance;
        final MethodHandle serializerSetupInstance;
        final MethodHandle onDeserialized;
        final MethodHandle newEmptyCArrayList;
        final MethodHandle newSizedCArrayList;
        final MethodHandle newUncheckedJdomFactory;
        final MethodHandle jdomElement;
        final MethodHandle jdomAttribute;
        final MethodHandle jdomText;
        final MethodHandle jdomCdata;
        final MethodHandle jdomPi;
        final MethodHandle jdomAddContent;
        final MethodHandle jdomSetAttribute;
        final MethodHandle function0Invoke;
        final MethodHandle kMutableGetSetter;
        final MethodHandle kCallableCall;
        final MethodHandle kCallableSetAccessible;

        private HostBinding(final Throwable resolutionFailure) {
            this.available = false;
            this.resolutionFailure = resolutionFailure;
            this.classSerializerType = null;
            this.postProcessType = null;
            this.kMutableProperty1Type = null;
            this.serializeManagerInstance = null;
            this.newXmlReader = null;
            this.parseProcessingInstruction = null;
            this.parseImport = null;
            this.findSerializer = null;
            this.getClassByTagName = null;
            this.getReadObjectRefs = null;
            this.getPostProcessTasks = null;
            this.findOrBuildObjectSerializer = null;
            this.getPropertyMap = null;
            this.getProperty = null;
            this.getBackingField = null;
            this.serializerCreateInstance = null;
            this.serializerSetupInstance = null;
            this.onDeserialized = null;
            this.newEmptyCArrayList = null;
            this.newSizedCArrayList = null;
            this.newUncheckedJdomFactory = null;
            this.jdomElement = null;
            this.jdomAttribute = null;
            this.jdomText = null;
            this.jdomCdata = null;
            this.jdomPi = null;
            this.jdomAddContent = null;
            this.jdomSetAttribute = null;
            this.function0Invoke = null;
            this.kMutableGetSetter = null;
            this.kCallableCall = null;
            this.kCallableSetAccessible = null;
        }

        private HostBinding(
                final Class<?> classSerializerType,
                final Class<?> postProcessType,
                final Class<?> kMutableProperty1Type,
                final Object serializeManagerInstance,
                final MethodHandle newXmlReader,
                final MethodHandle parseProcessingInstruction,
                final MethodHandle parseImport,
                final MethodHandle findSerializer,
                final MethodHandle getClassByTagName,
                final MethodHandle getReadObjectRefs,
                final MethodHandle getPostProcessTasks,
                final MethodHandle findOrBuildObjectSerializer,
                final MethodHandle getPropertyMap,
                final MethodHandle getProperty,
                final MethodHandle getBackingField,
                final MethodHandle serializerCreateInstance,
                final MethodHandle serializerSetupInstance,
                final MethodHandle onDeserialized,
                final MethodHandle newEmptyCArrayList,
                final MethodHandle newSizedCArrayList,
                final MethodHandle newUncheckedJdomFactory,
                final MethodHandle jdomElement,
                final MethodHandle jdomAttribute,
                final MethodHandle jdomText,
                final MethodHandle jdomCdata,
                final MethodHandle jdomPi,
                final MethodHandle jdomAddContent,
                final MethodHandle jdomSetAttribute,
                final MethodHandle function0Invoke,
                final MethodHandle kMutableGetSetter,
                final MethodHandle kCallableCall,
                final MethodHandle kCallableSetAccessible) {
            this.available = true;
            this.resolutionFailure = null;
            this.classSerializerType = classSerializerType;
            this.postProcessType = postProcessType;
            this.kMutableProperty1Type = kMutableProperty1Type;
            this.serializeManagerInstance = serializeManagerInstance;
            this.newXmlReader = newXmlReader;
            this.parseProcessingInstruction = parseProcessingInstruction;
            this.parseImport = parseImport;
            this.findSerializer = findSerializer;
            this.getClassByTagName = getClassByTagName;
            this.getReadObjectRefs = getReadObjectRefs;
            this.getPostProcessTasks = getPostProcessTasks;
            this.findOrBuildObjectSerializer = findOrBuildObjectSerializer;
            this.getPropertyMap = getPropertyMap;
            this.getProperty = getProperty;
            this.getBackingField = getBackingField;
            this.serializerCreateInstance = serializerCreateInstance;
            this.serializerSetupInstance = serializerSetupInstance;
            this.onDeserialized = onDeserialized;
            this.newEmptyCArrayList = newEmptyCArrayList;
            this.newSizedCArrayList = newSizedCArrayList;
            this.newUncheckedJdomFactory = newUncheckedJdomFactory;
            this.jdomElement = jdomElement;
            this.jdomAttribute = jdomAttribute;
            this.jdomText = jdomText;
            this.jdomCdata = jdomCdata;
            this.jdomPi = jdomPi;
            this.jdomAddContent = jdomAddContent;
            this.jdomSetAttribute = jdomSetAttribute;
            this.function0Invoke = function0Invoke;
            this.kMutableGetSetter = kMutableGetSetter;
            this.kCallableCall = kCallableCall;
            this.kCallableSetAccessible = kCallableSetAccessible;
        }

        static HostBinding resolve(final ClassLoader loader) {
            try {
                final Class<?> xmlReaderClass = Class.forName(XML_READER_CLASS, false, loader);
                final Class<?> xmlParamClass = Class.forName(XML_PARAM_CLASS, false, loader);
                final Class<?> archiveReaderClass = Class.forName(ARCHIVE_READER_CLASS, false, loader);
                final Class<?> serializeManagerClass = Class.forName(SERIALIZE_MANAGER_CLASS, false, loader);
                final Class<?> classSerializerClass = Class.forName(CLASS_SERIALIZER_CLASS, false, loader);
                final Class<?> propertySetClass = Class.forName(PROPERTY_SET_CLASS, false, loader);
                final Class<?> simpleSerializerClass = Class.forName(SIMPLE_SERIALIZER_IFACE, false, loader);
                final Class<?> postProcessClass = Class.forName(POST_PROCESS_IFACE, false, loader);
                final Class<?> cArrayListClass = Class.forName(CARRAY_LIST_CLASS, false, loader);

                final Class<?> jdomFactoryClass = Class.forName(JDOM_FACTORY_CLASS, false, loader);
                final Class<?> jdomElementClass = Class.forName(JDOM_ELEMENT_CLASS, false, loader);
                final Class<?> jdomAttrClass = Class.forName(JDOM_ATTRIBUTE_CLASS, false, loader);
                final Class<?> jdomPiClass = Class.forName(JDOM_PI_CLASS, false, loader);
                final Class<?> jdomParentClass = Class.forName(JDOM_PARENT_CLASS, false, loader);
                final Class<?> jdomContentClass = Class.forName(JDOM_CONTENT_CLASS, false, loader);

                final Class<?> function0Class = Class.forName(KOTLIN_FUNCTION0_CLASS, false, loader);
                final Class<?> kMutableProp1Class = Class.forName(KOTLIN_MUTABLE_PROP1_CLASS, false, loader);
                final Class<?> kCallableClass = Class.forName(KOTLIN_CALLABLE_CLASS, false, loader);

                final MethodHandles.Lookup lookup = MethodHandles.lookup();

                final Constructor<?> readerCtor = xmlReaderClass.getConstructor(archiveReaderClass, HashMap.class);
                final MethodHandle newXmlReader = lookup.unreflectConstructor(readerCtor)
                        .asType(MethodType.methodType(Object.class, Object.class, HashMap.class));

                final MethodHandle parsePi = unreflectMethod(
                        xmlReaderClass.getMethod("parse_ProcessingInstruction", jdomPiClass, cArrayListClass),
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));
                final MethodHandle parseImport = unreflectMethod(
                        xmlReaderClass.getMethod("parseImport", String.class, String.class, cArrayListClass),
                        MethodType.methodType(void.class, Object.class, String.class, String.class, Object.class));
                final MethodHandle findSerializer = unreflectMethod(
                        xmlReaderClass.getMethod("findSerializer$core", String.class),
                        MethodType.methodType(Object.class, Object.class, String.class));
                final MethodHandle getClassByTagName = unreflectMethod(
                        xmlReaderClass.getMethod("getClassByTagName", String.class),
                        MethodType.methodType(Class.class, Object.class, String.class));

                final Field refsField = ReflectAccess.open(xmlReaderClass.getDeclaredField("readObjectRefs"));
                final MethodHandle getReadObjectRefs =
                        lookup.unreflectGetter(refsField).asType(MethodType.methodType(Map.class, Object.class));

                final Field postTasksField = ReflectAccess.open(xmlReaderClass.getDeclaredField("postProcessTasks"));
                final MethodHandle getPostProcessTasks =
                        lookup.unreflectGetter(postTasksField).asType(MethodType.methodType(List.class, Object.class));

                final Object serializeManagerInstance =
                        serializeManagerClass.getField("INSTANCE").get(null);
                final MethodHandle findOrBuildObjSerializer = unreflectMethod(
                        serializeManagerClass.getMethod("findOrBuildObjectSerializer", Class.class, xmlParamClass),
                        MethodType.methodType(Object.class, Object.class, Class.class, Object.class));

                final MethodHandle getPropertyMap = unreflectMethod(
                        classSerializerClass.getMethod("getNameInParentToPropertySetMap"),
                        MethodType.methodType(Map.class, Object.class));
                final MethodHandle getProperty = unreflectMethod(
                        propertySetClass.getMethod("getProperty"), MethodType.methodType(Object.class, Object.class));
                final MethodHandle getBackingField = unreflectMethod(
                        propertySetClass.getMethod("getBackingField"),
                        MethodType.methodType(Field.class, Object.class));

                final MethodHandle serCreate = unreflectMethod(
                        simpleSerializerClass.getMethod("createInstance", jdomElementClass, xmlReaderClass),
                        MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
                final MethodHandle serSetup = unreflectMethod(
                        simpleSerializerClass.getMethod(
                                "setupInstance", jdomElementClass, Object.class, xmlReaderClass),
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class));
                final MethodHandle onDeserialized = unreflectMethod(
                        postProcessClass.getMethod("onDeserialized", jdomElementClass, xmlReaderClass),
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));

                final MethodHandle newEmptyCArrayList = lookup.unreflectConstructor(cArrayListClass.getConstructor())
                        .asType(MethodType.methodType(Object.class));
                final MethodHandle newSizedCArrayList = lookup.unreflectConstructor(
                                cArrayListClass.getConstructor(int.class))
                        .asType(MethodType.methodType(Object.class, int.class));

                final MethodHandle newUncheckedJdomFactory = lookup.unreflectConstructor(
                                jdomFactoryClass.getConstructor())
                        .asType(MethodType.methodType(Object.class));
                final MethodHandle jdomElement = unreflectMethod(
                        jdomFactoryClass.getMethod("element", String.class),
                        MethodType.methodType(Object.class, Object.class, String.class));
                final MethodHandle jdomAttribute = unreflectMethod(
                        jdomFactoryClass.getMethod("attribute", String.class, String.class),
                        MethodType.methodType(Object.class, Object.class, String.class, String.class));
                final MethodHandle jdomText = unreflectMethod(
                        jdomFactoryClass.getMethod("text", String.class),
                        MethodType.methodType(Object.class, Object.class, String.class));
                final MethodHandle jdomCdata = unreflectMethod(
                        jdomFactoryClass.getMethod("cdata", String.class),
                        MethodType.methodType(Object.class, Object.class, String.class));
                final MethodHandle jdomPi = unreflectMethod(
                        jdomFactoryClass.getMethod("processingInstruction", String.class, String.class),
                        MethodType.methodType(Object.class, Object.class, String.class, String.class));
                final MethodHandle jdomAddContent = unreflectMethod(
                        jdomFactoryClass.getMethod("addContent", jdomParentClass, jdomContentClass),
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));
                final MethodHandle jdomSetAttribute = unreflectMethod(
                        jdomFactoryClass.getMethod("setAttribute", jdomElementClass, jdomAttrClass),
                        MethodType.methodType(void.class, Object.class, Object.class, Object.class));

                final MethodHandle function0Invoke = unreflectMethod(
                        function0Class.getMethod("invoke"), MethodType.methodType(void.class, Object.class));
                final MethodHandle kMutableGetSetter = unreflectMethod(
                        kMutableProp1Class.getMethod("getSetter"), MethodType.methodType(Object.class, Object.class));
                final MethodHandle kCallableCall = unreflectMethod(
                        kCallableClass.getMethod("call", Object[].class),
                        MethodType.methodType(void.class, Object.class, Object[].class));
                MethodHandle kCallableSetAccessible = null;
                try {
                    final Class<?> callablesJvmClass = Class.forName(KOTLIN_CALLABLES_JVM_CLASS, false, loader);
                    kCallableSetAccessible = unreflectMethod(
                            callablesJvmClass.getMethod("setAccessible", kCallableClass, boolean.class),
                            MethodType.methodType(void.class, Object.class, boolean.class));
                } catch (final Throwable ignored) {
                    kCallableSetAccessible = null;
                }

                return new HostBinding(
                        classSerializerClass,
                        postProcessClass,
                        kMutableProp1Class,
                        serializeManagerInstance,
                        newXmlReader,
                        parsePi,
                        parseImport,
                        findSerializer,
                        getClassByTagName,
                        getReadObjectRefs,
                        getPostProcessTasks,
                        findOrBuildObjSerializer,
                        getPropertyMap,
                        getProperty,
                        getBackingField,
                        serCreate,
                        serSetup,
                        onDeserialized,
                        newEmptyCArrayList,
                        newSizedCArrayList,
                        newUncheckedJdomFactory,
                        jdomElement,
                        jdomAttribute,
                        jdomText,
                        jdomCdata,
                        jdomPi,
                        jdomAddContent,
                        jdomSetAttribute,
                        function0Invoke,
                        kMutableGetSetter,
                        kCallableCall,
                        kCallableSetAccessible);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return new HostBinding(failure);
            }
        }

        private static MethodHandle unreflectMethod(final Method method, final MethodType targetType)
                throws IllegalAccessException {
            try {
                final MethodHandles.Lookup lookup =
                        MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
                return lookup.unreflect(method).asType(targetType);
            } catch (final IllegalAccessException | RuntimeException ignored) {
                final Method opened = ReflectAccess.open(method);
                return MethodHandles.lookup().unreflect(opened).asType(targetType);
            }
        }
    }
}
