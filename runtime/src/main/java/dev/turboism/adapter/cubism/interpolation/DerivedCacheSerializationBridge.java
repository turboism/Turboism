package dev.turboism.adapter.cubism.interpolation;

/** Serializer-thread projection; never clears or replaces any live cache. */
public final class DerivedCacheSerializationBridge {
    private static final ThreadLocal<java.util.ArrayDeque<Boolean>> SCOPES = new ThreadLocal<>();
    private static volatile boolean modelRootOnly;
    private static volatile boolean ready;
    private static final ThreadLocal<java.lang.ref.WeakReference<Object>> MODEL_DOCUMENT = new ThreadLocal<>();
    private static volatile boolean enabled;
    private static volatile boolean gridEnabled;
    private static final boolean DIAGNOSTIC = Boolean.getBoolean("turboism.meshInterpolation.projectionDiagnostic");
    private static final java.util.concurrent.atomic.LongAdder CANDIDATES = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder PROJECTED = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder GRIDS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder OMITTED = new java.util.concurrent.atomic.LongAdder();
    /**
     * @return the projection counters snapshot for diagnostics
     */
    public static String diagnostic() { return "candidateReads="+CANDIDATES.sum()+" projectedReads="+PROJECTED.sum()+" omittedForms="+OMITTED.sum()+" omittedGrids="+GRIDS.sum(); }
    private static final ClassValue<java.util.Optional<java.lang.invoke.MethodHandle>> EMPTY_CACHE = new ClassValue<>() {
        @Override protected java.util.Optional<java.lang.invoke.MethodHandle> computeValue(Class<?> owner) {
            try {
                if(!owner.getName().equals(NativeMeshInterpolationBridge.EXTENSION))return java.util.Optional.empty();
                var path=java.nio.file.Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                try(var input=java.nio.file.Files.newInputStream(path)) {
                    if(!NativeMeshInterpolationBridge.HOST_SHA256S.contains(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))))return java.util.Optional.empty();
                }
                Class<?> list=Class.forName("com.live2d.type.CArrayList",false,owner.getClassLoader());
                if(list.getClassLoader()!=owner.getClassLoader() || !java.nio.file.Path.of(list.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(path))return java.util.Optional.empty();
                return java.util.Optional.of(java.lang.invoke.MethodHandles.publicLookup().findConstructor(list,java.lang.invoke.MethodType.methodType(void.class)).asType(java.lang.invoke.MethodType.methodType(Object.class)));
            } catch(Exception|LinkageError refused) { return java.util.Optional.empty(); }
        }
    };
    private static final ClassValue<Boolean> MODEL_ROOT = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if(!type.getName().equals("com.live2d.cubism.doc.model.CModelSource"))return false;
            try {
                var path=java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                try(var input=java.nio.file.Files.newInputStream(path)) {
                    return NativeMeshInterpolationBridge.HOST_SHA256S.contains(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
                }
            } catch(Exception|LinkageError refused) { return false; }
        }
    };
    private DerivedCacheSerializationBridge() { }
    /**
     * Restricts projection to documents under the model root.
     *
     * @param value whether only model-root documents may project their cache
     */
    public static void modelRootOnly(boolean value) { modelRootOnly=value; }
    static void ready(boolean value) { ready=value; }
    /**
     * Returns whether streaming projection is armed for a model source.
     *
     * @param source host model source instance
     * @return {@code true} when every projection precondition holds for {@code source}
     */
    public static boolean availableForModel(Object source) {
        return enabled && gridEnabled && ready && NativeXmlStreamingBridge.isEnabled()
            && source!=null && MODEL_ROOT.get(source.getClass());
    }
    /**
     * @param value installer lifecycle switch for the projection bridge
     */
    public static void enabled(boolean value) { enabled=value; }
    /**
     * @param value whether the mutable grid-source projection may return empty caches
     */
    public static void gridEnabled(boolean value) { gridEnabled=value; }
    /** Enters a serializer scope with no explicit root document. */
    public static void enter() { enter(null); }
    /**
     * Enters a serializer scope bound to a root document.
     *
     * @param root root document that decides projection when model-root-only mode is on
     */
    public static void enter(Object root) {
        var scopes=SCOPES.get();
        if(scopes==null) { MODEL_DOCUMENT.remove();scopes=new java.util.ArrayDeque<>();SCOPES.set(scopes); }
        boolean inherited=!scopes.isEmpty() && scopes.peek();
        scopes.push(inherited || !modelRootOnly || root!=null && MODEL_ROOT.get(root.getClass()));
    }
    /**
     * Records the document produced by the current serializer scope.
     *
     * @param document document the scope just produced, or null to clear
     */
    public static void documentResult(Object document) {
        if(projectCache() && document!=null)MODEL_DOCUMENT.set(new java.lang.ref.WeakReference<>(document));
        else MODEL_DOCUMENT.remove();
    }
    static boolean mayStream(Object document) {
        if(!modelRootOnly)return true;
        var eligible=MODEL_DOCUMENT.get();return eligible!=null && eligible.get()==document;
    }
    static void releaseDocument() { MODEL_DOCUMENT.remove(); }
    /** Leaves the current serializer scope. */
    public static void exit() {
        var scopes=SCOPES.get();
        if(scopes==null)return;
        scopes.pop();if(scopes.isEmpty())SCOPES.remove();
    }
    /**
     * @return {@code true} while the current scope may project the serialized cache
     */
    public static boolean projectCache() {
        var scopes=SCOPES.get();return enabled && scopes!=null && !scopes.isEmpty() && scopes.peek();
    }
    /** Mutable Kotlin property result projection; the original getter always executes unchanged. */
    public static Object getterValue(Object value,java.lang.reflect.Field field,Object receiver) {
        if(projectCache() && gridEnabled && value!=null && field!=null && receiver!=null
                && field.getDeclaringClass()==receiver.getClass()
                && field.getName().equals("extendedKeyformGridSource")
                && field.getType().getName().equals("com.live2d.cubism.doc.model.interpolator.KeyformGridSource")
                && value.getClass()==field.getType() && EMPTY_CACHE.get(receiver.getClass()).isPresent()) {
            if(DIAGNOSTIC)GRIDS.increment();
            return null;
        }
        return value;
    }
    /** Called at the pinned PropertySet field-read site, after official accessibility checks. */
    public static Object fieldValue(java.lang.reflect.Field field,Object receiver) throws IllegalAccessException {
        if(projectCache() && receiver!=null && field.getDeclaringClass()==receiver.getClass()
                && field.getName().equals("normalExtendedKeyforms") && field.getType().getName().equals("com.live2d.type.CArrayList")) {
            if(DIAGNOSTIC)CANDIDATES.increment();
            var constructor=EMPTY_CACHE.get(receiver.getClass());
            if(constructor.isPresent()) {
                try {
                    if(DIAGNOSTIC) {
                        PROJECTED.increment();
                        OMITTED.add(((java.util.Collection<?>)field.get(receiver)).size());
                    }
                    return (Object)constructor.get().invokeExact();
                }
                catch(Throwable refused) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(refused);
                    throw new IllegalStateException("Verified empty-cache constructor failed",refused);
                }
            }
        }
        return field.get(receiver);
    }
}
