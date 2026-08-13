package dev.turboism.adapter.cubism;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.permission.PluginPermission;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forwarding-completeness guard for the two SDK wrapper layers
 * (w13-20260807-forwarding-audit, lane A).
 *
 * <p>Every {@code dev.turboism.sdk.cubism.model} interface is enumerated from the
 * SDK sources. For each wrapper class (PC {@code PermissionChecked*} named classes,
 * PC anonymous collection classes reached through a live facade, and Session
 * {@code Session*} classes):</p>
 *
 * <ul>
 *   <li>abstract interface methods must be declared somewhere in the wrapper class
 *       hierarchy (compile-time guaranteed; locked here as a contract), and</li>
 *   <li>a default interface method whose SDK default body fails closed
 *       ({@code throw ...}) and that the real implementation layer
 *       ({@code EditorBacked*}/{@code CoreBacked*} inner classes) overrides MUST be
 *       declared in every wrapper class hierarchy implementing that interface.
 *       This is the assertion that goes red on the fourth recurrence of the
 *       W8/W9-style forwarding leak.</li>
 * </ul>
 *
 * <p>Pure JVM: no host, no Cubism runtime. Wrapper classes are inspected
 * reflectively; anonymous PC collection classes are reached through a live
 * {@link CubismFacadeImpl} built over an in-memory stub model.</p>
 */
class SdkForwardingCompletenessGuardTest {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path SDK_MODEL_DIR =
        PROJECT_ROOT.resolve("sdk/src/main/java/dev/turboism/sdk/cubism/model");
    private static final String SDK_MODEL_PACKAGE = "dev.turboism.sdk.cubism.model";
    private static final Path EDITOR_DIR =
        PROJECT_ROOT.resolve("runtime/src/main/java/dev/turboism/adapter/cubism/editor");
    private static final Path CORE_DIR =
        PROJECT_ROOT.resolve("runtime/src/main/java/dev/turboism/adapter/cubism/core");
    private static final Path HOST_DIR =
        PROJECT_ROOT.resolve("runtime/src/main/java/dev/turboism/adapter/host");

    @Test
    void everyWrapperLayerDeclaresAbstractMethodsAndFailClosedDefaultsOfTheSdkModelInterfaces()
        throws Exception {
        final List<String> violations = new ArrayList<>();

        final Set<Class<?>> sdkInterfaces = sdkModelInterfaces();
        final Map<Class<?>, List<DefaultMethod>> defaultsByInterface = defaultsByInterface();

        final Set<Class<?>> realLayerClasses = realImplementationClasses();
        final Set<Class<?>> wrapperClasses = wrapperClasses();

        for (final Class<?> iface : sdkInterfaces) {
            final List<DefaultMethod> failClosedDefaults = defaultsByInterface.getOrDefault(iface, List.of())
                .stream()
                .filter(DefaultMethod::failsClosed)
                .toList();

            // Real implementation layer: which fail-closed defaults are actually overridden?
            final Set<String> overriddenByRealLayer = new LinkedHashSet<>();
            for (final Class<?> realClass : realLayerClasses) {
                if (!iface.isAssignableFrom(realClass)) continue;
                for (final DefaultMethod method : failClosedDefaults) {
                    if (declaredInHierarchy(realClass, method)) {
                        overriddenByRealLayer.add(method.signature());
                    }
                }
            }

            for (final Class<?> wrapper : wrapperClasses) {
                if (!iface.isAssignableFrom(wrapper)) continue;

                // (a) abstract methods must be declared in the wrapper hierarchy.
                for (final Method method : iface.getDeclaredMethods()) {
                    if (Modifier.isAbstract(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && !Modifier.isPrivate(method.getModifiers())
                        && !declaredInHierarchy(wrapper, method)) {
                        violations.add(String.format(
                            "%s wraps %s but does not declare abstract %s.%s(%s)",
                            wrapper.getSimpleName(),
                            iface.getSimpleName(),
                            iface.getSimpleName(),
                            method.getName(),
                            parameterText(method)
                        ));
                    }
                }

                // (b) fail-closed defaults overridden by the real layer must be forwarded.
                for (final DefaultMethod method : failClosedDefaults) {
                    if (!overriddenByRealLayer.contains(method.signature())) continue;
                    if (!declaredInHierarchy(wrapper, method)) {
                        violations.add(String.format(
                            "%s wraps %s but does not declare fail-closed default %s.%s(%s) "
                                + "(SDK default throws and the real implementation layer overrides it)",
                            wrapper.getSimpleName(),
                            iface.getSimpleName(),
                            iface.getSimpleName(),
                            method.name,
                            String.join(", ", method.parameterSimpleNames)
                        ));
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "SDK forwarding contract violations:\n  " + String.join("\n  ", violations)
        );
    }

    // ------------------------------------------------------------ discovery

    /** Every interface class in the SDK model package, from the source tree. */
    private static Set<Class<?>> sdkModelInterfaces() throws Exception {
        final Set<Class<?>> interfaces = new LinkedHashSet<>();
        try (Stream<Path> files = Files.list(SDK_MODEL_DIR)) {
            for (final Path file : files.filter(path -> path.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList()) {
                final String source = Files.readString(file);
                final Matcher matcher = Pattern.compile(
                    "\\bpublic\\s+interface\\s+(\\w+)"
                ).matcher(stripComments(source));
                if (matcher.find()) {
                    interfaces.add(Class.forName(SDK_MODEL_PACKAGE + "." + matcher.group(1)));
                }
            }
        }
        return interfaces;
    }

    /** Parsed default methods (with fail-closed status) per SDK interface. */
    private static Map<Class<?>, List<DefaultMethod>> defaultsByInterface() throws Exception {
        final Map<Class<?>, List<DefaultMethod>> result = new java.util.HashMap<>();
        for (final Class<?> iface : sdkModelInterfaces()) {
            final Path file = SDK_MODEL_DIR.resolve(iface.getSimpleName() + ".java");
            result.put(iface, parseDefaultMethods(Files.readString(file)));
        }
        return result;
    }

    private record DefaultMethod(String name, List<String> parameterSimpleNames, boolean failsClosed) {

        String signature() {
            return name + "(" + String.join(",", parameterSimpleNames) + ")";
        }

        boolean matches(final Method method) {
            if (!method.getName().equals(name)) return false;
            final List<String> actual = new ArrayList<>();
            for (final Class<?> type : method.getParameterTypes()) {
                actual.add(type.getSimpleName());
            }
            return actual.equals(parameterSimpleNames);
        }
    }

    /**
     * Extracts every {@code default} method declaration with its parameter simple
     * names and whether the body contains a {@code throw} (fail-closed).
     */
    private static List<DefaultMethod> parseDefaultMethods(final String rawSource) {
        final String source = stripComments(rawSource);
        final List<DefaultMethod> methods = new ArrayList<>();
        final Pattern pattern = Pattern.compile(
            "\\bdefault\\s+[\\w.<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws[^{]*)?\\{"
        );
        final Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            final String name = matcher.group(1);
            final String body = braceBody(source, matcher.end() - 1);
            if (body == null) continue;
            methods.add(new DefaultMethod(
                name,
                simpleParameterNames(matcher.group(2)),
                body.contains("throw")
            ));
        }
        return methods;
    }

    private static List<String> simpleParameterNames(final String parameterText) {
        final List<String> names = new ArrayList<>();
        int depth = 0;
        final StringBuilder current = new StringBuilder();
        for (final char ch : parameterText.toCharArray()) {
            if (ch == '<') depth++;
            if (ch == '>') depth--;
            if (ch == ',' && depth == 0) {
                names.add(simpleParameterName(current.toString()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            names.add(simpleParameterName(current.toString()));
        }
        return names;
    }

    private static String simpleParameterName(final String raw) {
        String token = raw.trim();
        if (token.isEmpty()) return "";
        final String[] parts = token.split("\\s+");
        int index = 0;
        if (parts[0].equals("final")) index = 1;
        // join remaining tokens minus the trailing parameter name
        final String type = String.join(" ", java.util.Arrays.copyOfRange(parts, index, parts.length));
        String base = type;
        final int generic = base.indexOf('<');
        if (generic >= 0) base = base.substring(0, generic);
        final String trimmed = base.trim();
        // a multi-token type ends with the parameter name; drop the last token
        final String[] typeTokens = trimmed.split("\\s+");
        final String typeName = typeTokens.length > 1
            ? String.join("", java.util.Arrays.copyOf(typeTokens, typeTokens.length - 1))
            : typeTokens[0];
        final int dot = typeName.lastIndexOf('.');
        return dot >= 0 ? typeName.substring(dot + 1) : typeName;
    }

    /** Matched brace-delimited body starting at {@code openBraceIndex}. */
    private static String braceBody(final String source, final int openBraceIndex) {
        int depth = 0;
        for (int index = openBraceIndex; index < source.length(); index++) {
            final char ch = source.charAt(index);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return source.substring(openBraceIndex, index);
            }
        }
        return null;
    }

    private static String stripComments(final String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /**
     * Real implementation layer: inner classes of the Editor-backed and
     * Core-backed accesses that implement SDK model interfaces.
     */
    private static Set<Class<?>> realImplementationClasses() throws Exception {
        final Set<Class<?>> classes = new LinkedHashSet<>();
        for (final Path dir : List.of(EDITOR_DIR, CORE_DIR)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                    final String source = stripComments(Files.readString(file));
                    final Matcher outer = Pattern.compile(
                        "(?:public|final|abstract|\\s)*\\bclass\\s+(\\w+)"
                    ).matcher(source);
                    if (!outer.find()) continue;
                    final String outerName = outer.group(1);
                    final String packageName = packageOf(file);
                    final Class<?> outerClass = Class.forName(packageName + "." + outerName);
                    for (final Class<?> inner : outerClass.getDeclaredClasses()) {
                        if (implementsSdkModelInterface(inner)) classes.add(inner);
                    }
                }
            }
        }
        return classes;
    }

    /**
     * Wrapper layers: Session named classes, PC named {@code PermissionChecked*}
     * classes, and the PC anonymous collection classes reached through a live
     * facade built over an in-memory stub model.
     */
    private static Set<Class<?>> wrapperClasses() throws Exception {
        final Set<Class<?>> classes = new LinkedHashSet<>();

        // Session layer (named inner classes).
        final Class<?> sessionAccess = Class.forName("dev.turboism.adapter.host.DynamicCubismModelAccess");
        for (final Class<?> inner : sessionAccess.getDeclaredClasses()) {
            if (implementsSdkModelInterface(inner)) classes.add(inner);
        }

        // PC layer: named inner classes.
        for (final Class<?> inner : CubismFacadeImpl.class.getDeclaredClasses()) {
            if (implementsSdkModelInterface(inner)) classes.add(inner);
        }

        // PC layer: anonymous collection classes via a live facade.
        final CubismModel model = facade().model().active();
        classes.add(model.parameters().getClass());
        classes.add(model.parameterGroups().getClass());
        classes.add(model.parameterDefinitions().getClass());
        classes.add(model.canvas().getClass());
        classes.add(model.textures().getClass());
        classes.add(model.parts().getClass());
        classes.add(model.drawables().getClass());
        classes.add(model.deformers().getClass());
        classes.add(model.rotationDeformers().getClass());
        classes.add(model.glues().getClass());
        classes.add(model.warpDeformers().getClass());
        classes.add(model.parameterBindings(new dev.turboism.sdk.cubism.id.ParameterId("p")).getClass());
        classes.add(model.parameterBindingBatch().getClass());
        return classes;
    }

    private static boolean implementsSdkModelInterface(final Class<?> type) {
        for (final Class<?> implemented : type.getInterfaces()) {
            if (implemented.getName().startsWith(SDK_MODEL_PACKAGE + ".")) return true;
        }
        return false;
    }

    private static String packageOf(final Path file) throws IOException {
        final String source = Files.readString(file);
        final Matcher matcher = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    /** True when {@code method} is declared by the class or any of its superclasses. */
    private static boolean declaredInHierarchy(final Class<?> type, final DefaultMethod method) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (final Method declared : current.getDeclaredMethods()) {
                if (method.matches(declared)) return true;
            }
        }
        return false;
    }

    private static boolean declaredInHierarchy(final Class<?> type, final Method method) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (final Method declared : current.getDeclaredMethods()) {
                if (!declared.getName().equals(method.getName())) continue;
                if (!java.util.Arrays.equals(
                    declared.getParameterTypes(), method.getParameterTypes())) continue;
                return true;
            }
        }
        return false;
    }

    private static String parameterText(final Method method) {
        return java.util.Arrays.stream(method.getParameterTypes())
            .map(Class::getSimpleName)
            .collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------ fixtures

    private static CubismFacadeImpl facade() {
        return new CubismFacadeImpl(
            new HostSnapshotSource() {
                @Override public java.util.Optional<HostProject> activeProject() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<HostDocument> activeDocument() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<HostModel> activeModel() { return java.util.Optional.empty(); }
                @Override public HostSelection selection() {
                    return new HostSelection(List.of(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
                }
                @Override public boolean isHostPresent() { return false; }
                @Override public long invalidationToken() { return 0L; }
            },
            new CubismPermissionGate(
                "plugin.demo",
                List.of(permission(CubismFacadeImpl.MODEL_READ_PERMISSION), permission(CubismFacadeImpl.MODEL_WRITE_PERMISSION)),
                ignored -> { },
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
            ),
            (CubismModelAccess) StubModel::new
        );
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "read"; }
            @Override public String reason() { return "test"; }
        };
    }

    /** Minimal in-memory model providing every collection entry point. */
    private static final class StubModel implements CubismModel {

        @Override public dev.turboism.sdk.cubism.id.ModelId id() { return new dev.turboism.sdk.cubism.id.ModelId("m"); }
        @Override public dev.turboism.sdk.cubism.model.Parameters parameters() { return emptyParameters(); }
        @Override public dev.turboism.sdk.cubism.model.Parts parts() { return emptyParts(); }
        @Override public dev.turboism.sdk.cubism.model.Drawables drawables() { return emptyDrawables(); }
        @Override public dev.turboism.sdk.cubism.model.Deformers deformers() { return emptyDeformers(); }
        @Override public dev.turboism.sdk.cubism.model.Glues glues() { return emptyGlues(); }
        @Override public void update() { }

        @Override public dev.turboism.sdk.cubism.model.ModelTextures textures() { return emptyTextures(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterDefinitions parameterDefinitions() { return emptyParameterDefinitions(); }
        @Override public dev.turboism.sdk.cubism.model.Canvas canvas() { return emptyCanvas(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterGroups parameterGroups() { return emptyParameterGroups(); }
        @Override public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() { return emptyRotationDeformers(); }
        @Override public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() { return emptyWarpDeformers(); }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
            final dev.turboism.sdk.cubism.id.ParameterId parameterId
        ) {
            return emptyParameterBindings();
        }
        @Override public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
            return emptyParameterBindingBatch();
        }
    }

    private static dev.turboism.sdk.cubism.model.Parameters emptyParameters() {
        return new dev.turboism.sdk.cubism.model.Parameters() {
            @Override public List<dev.turboism.sdk.cubism.model.Parameter> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.Parameter find(
                final dev.turboism.sdk.cubism.id.ParameterId id
            ) { throw new java.util.NoSuchElementException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.Parts emptyParts() {
        return new dev.turboism.sdk.cubism.model.Parts() {
            @Override public List<dev.turboism.sdk.cubism.model.Part> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.Part find(final dev.turboism.sdk.cubism.model.PartId id) {
                throw new java.util.NoSuchElementException();
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.Drawables emptyDrawables() {
        return new dev.turboism.sdk.cubism.model.Drawables() {
            @Override public List<dev.turboism.sdk.cubism.model.Drawable> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.Drawable find(final dev.turboism.sdk.cubism.id.ArtMeshId id) {
                throw new java.util.NoSuchElementException();
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.Deformers emptyDeformers() {
        return new dev.turboism.sdk.cubism.model.Deformers() {
            @Override public List<dev.turboism.sdk.cubism.model.Deformer> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.Deformer find(final dev.turboism.sdk.cubism.id.DeformerId id) {
                throw new java.util.NoSuchElementException();
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.Glues emptyGlues() {
        return new dev.turboism.sdk.cubism.model.Glues() {
            @Override public List<dev.turboism.sdk.cubism.model.Glue> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.Glue find(final dev.turboism.sdk.cubism.model.GlueId id) {
                throw new java.util.NoSuchElementException();
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.ModelTextures emptyTextures() {
        return new dev.turboism.sdk.cubism.model.ModelTextures() {
            @Override public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() { return List.of(); }
            @Override public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() { return List.of(); }
            @Override public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() { return List.of(); }
            @Override public void addModelImageGroup(final String name) { }
            @Override public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) { }
            @Override public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                final String name, final int widthPixels, final int heightPixels
            ) { return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas"); }
            @Override public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) { }
            @Override public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) { }
        };
    }

    private static dev.turboism.sdk.cubism.model.ParameterDefinitions emptyParameterDefinitions() {
        return new dev.turboism.sdk.cubism.model.ParameterDefinitions() {
            @Override public List<dev.turboism.sdk.cubism.model.ParameterDefinition> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.ParameterDefinition find(
                final dev.turboism.sdk.cubism.id.ParameterId id
            ) { throw new java.util.NoSuchElementException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.Canvas emptyCanvas() {
        return new dev.turboism.sdk.cubism.model.Canvas() {
            @Override public float widthPixels() { return 0; }
            @Override public float heightPixels() { return 0; }
            @Override public float originXPixels() { return 0; }
            @Override public float originYPixels() { return 0; }
            @Override public float pixelsPerUnit() { return 0; }
        };
    }

    private static dev.turboism.sdk.cubism.model.ParameterGroups emptyParameterGroups() {
        return new dev.turboism.sdk.cubism.model.ParameterGroups() {
            @Override public List<dev.turboism.sdk.cubism.model.ParameterGroup> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.ParameterGroup root() {
                throw new java.util.NoSuchElementException();
            }
            @Override public dev.turboism.sdk.cubism.model.ParameterGroup find(
                final dev.turboism.sdk.cubism.id.ParameterGroupId id
            ) { throw new java.util.NoSuchElementException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.RotationDeformers emptyRotationDeformers() {
        return new dev.turboism.sdk.cubism.model.RotationDeformers() {
            @Override public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.RotationDeformer find(
                final dev.turboism.sdk.cubism.id.DeformerId id
            ) { throw new java.util.NoSuchElementException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.WarpDeformers emptyWarpDeformers() {
        return new dev.turboism.sdk.cubism.model.WarpDeformers() {
            @Override public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() { return List.of(); }
            @Override public dev.turboism.sdk.cubism.model.WarpDeformer find(
                final dev.turboism.sdk.cubism.id.DeformerId id
            ) { throw new java.util.NoSuchElementException(); }
        };
    }

    private static dev.turboism.sdk.cubism.model.ParameterBindingOperations emptyParameterBindings() {
        return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
            @Override public void bind(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
            ) { }
            @Override public void createPoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
            ) { }
            @Override public void movePoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                final float value
            ) { }
            @Override public void deletePoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
            ) { }
            @Override public void unbind(final dev.turboism.sdk.cubism.model.ParameterBindingTarget target) { }
        };
    }

    private static dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations emptyParameterBindingBatch() {
        return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
            @Override public void invert(final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets) { }
            @Override public void transfer(final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan) { }
        };
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root is unavailable");
        return current;
    }
}
