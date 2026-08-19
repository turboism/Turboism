package dev.turboism.bootstrap;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Targeted semantic checks for the documented host ownership escape routes. */
class HostIngressOwnershipStructureTest {

    @Test
    void productionSourcesKeepTargetedHostOwnershipEscapeRoutesNarrow() throws IOException {
        final Path mainJava = repositoryRoot().resolve("runtime/src/main/java");
        final List<Path> sources;
        try (Stream<Path> paths = Files.walk(mainJava)) {
            sources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }

        assertEquals(List.of(), OwnershipAudit.inspectPaths(sources));
    }

    @Test
    void rejectsLifecycleMirrorsAndGenericOrArraySessionRetentionOutsideIngress() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession { public enum State { ACTIVE } }
                    """),
                source("dev.turboism.bootstrap.HostRuntimeIngress", """
                    package dev.turboism.bootstrap;
                    import dev.turboism.adapter.host.HostSession;
                    final class HostRuntimeIngress {
                        enum State { ACTIVE }
                        private HostSession.State mirrored;
                        private HostSession session;
                    }
                    """),
                source("other.GenericOwner", """
                    package other;
                    import dev.turboism.adapter.host.HostSession;
                    final class GenericOwner {
                        private java.util.List<HostSession> retained;
                        private HostSession[] array;
                    }
                    """)
            ),
            "HostRuntimeIngress must not declare nested enum",
            "HostRuntimeIngress must not retain a HostSession.State field",
            "only HostRuntimeIngress may retain a HostSession field"
        );
    }

    @Test
    void resolvesFqnsSoCrossPackageSameNamesAreNotOwnershipViolations() {
        final List<String> violations = OwnershipAudit.inspectSources(List.of(
            source("unrelated.HostSession", """
                package unrelated;
                public final class HostSession { public enum State { ACTIVE } }
                """),
            source("unrelated.HostAdapterConnector", """
                package unrelated;
                public interface HostAdapterConnector { Object connect(); }
                """),
            source("unrelated.Owner", """
                package unrelated;
                final class Owner implements HostAdapterConnector {
                    private HostSession session = new HostSession();
                    public Object connect() { return session; }
                }
                """)
        ));

        assertEquals(List.of(), violations);
    }

    @Test
    void rejectsConstructorReferencesAndDirectAnonymousLambdaOrIndirectConnectors() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.host.HostInstanceSource", """
                    package dev.turboism.adapter.host;
                    public interface HostInstanceSource {}
                    """),
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession {
                        public enum State { ACTIVE }
                        public HostSession(HostInstanceSource source) {}
                    }
                    """),
                source("dev.turboism.adapter.host.HostAdapterConnector", """
                    package dev.turboism.adapter.host;
                    public interface HostAdapterConnector { Object connect(); }
                    """),
                source("dev.turboism.adapter.host.VerifiedHostAdapterConnector", """
                    package dev.turboism.adapter.host;
                    final class VerifiedHostAdapterConnector implements HostAdapterConnector {
                        public Object connect() { return null; }
                    }
                    """),
                source("dev.turboism.adapter.host.BaseConnector", """
                    package dev.turboism.adapter.host;
                    public abstract class BaseConnector implements HostAdapterConnector {}
                    """),
                source("other.Escapes", """
                    package other;
                    import dev.turboism.adapter.host.*;
                    import java.util.function.Function;
                    final class Escapes {
                        Function<HostInstanceSource, HostSession> factory = HostSession::new;
                        HostAdapterConnector lambda = () -> null;
                        HostAdapterConnector anonymous = new HostAdapterConnector() {
                            public Object connect() { return null; }
                        };
                    }
                    final class IndirectConnector extends dev.turboism.adapter.host.BaseConnector {
                        public Object connect() { return null; }
                    }
                    """)
            ),
            "HostSession composition is owned by HostRuntimeIngress",
            "HostAdapterConnector production implementation must be VerifiedHostAdapterConnector"
        );
    }

    @Test
    void rejectsIngressWhenAnyRequiredInstanceFieldIsMissing() {
        for (String missing : List.of("current", "closeRequested", "session")) {
            final String fields = switch (missing) {
                case "current" -> """
                    private final AtomicBoolean closeRequested = new AtomicBoolean();
                    private final HostSession session = new HostSession();
                    """;
                case "closeRequested" -> """
                    private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
                    private final HostSession session = new HostSession();
                    """;
                case "session" -> """
                    private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
                    private final AtomicBoolean closeRequested = new AtomicBoolean();
                    """;
                default -> throw new IllegalArgumentException(missing);
            };
            assertViolations(
                ingressFieldFixture(fields),
                "HostRuntimeIngress declared instance fields must exactly match"
            );
        }
    }

    @Test
    void rejectsStaticHostOwnershipAndLifecycleResourceFieldsButAllowsConstants() {
        assertViolations(
            ingressFieldFixture("""
                private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
                private final AtomicBoolean closeRequested = new AtomicBoolean();
                private final HostSession session = new HostSession();
                private static final HostSession secondSession = new HostSession();
                private static HostAdapterConnection connection;
                private static Registration registration;
                private static HostAdapterConnector connector;
                private static RuntimeHostAdapters adapters;
                private static HostSession.State lifecycleState;
                private static final String CONSTANT = "allowed";
                """),
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: secondSession",
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: connection",
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: registration",
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: connector",
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: adapters",
            "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: lifecycleState"
        );
    }

    @Test
    void rejectsIngressOwnedConnectionRegistrationAndRenamedLifecycleState() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.host.HostInstanceDescriptor", """
                    package dev.turboism.adapter.host;
                    public final class HostInstanceDescriptor {}
                    """),
                source("dev.turboism.adapter.host.HostAdapterConnection", """
                    package dev.turboism.adapter.host;
                    public interface HostAdapterConnection extends AutoCloseable {
                        @Override void close();
                    }
                    """),
                source("dev.turboism.sdk.plugin.Registration", """
                    package dev.turboism.sdk.plugin;
                    public interface Registration extends AutoCloseable {
                        @Override void close();
                    }
                    """),
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession {
                        public enum State { ACTIVE }
                        public State state() { return State.ACTIVE; }
                    }
                    """),
                source("dev.turboism.bootstrap.HostRuntimeIngress", """
                    package dev.turboism.bootstrap;
                    import dev.turboism.adapter.host.*;
                    import dev.turboism.sdk.plugin.Registration;
                    import java.util.concurrent.atomic.*;
                    final class HostRuntimeIngress {
                        enum LifecyclePhase { ACTIVE }
                        private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
                        private final AtomicBoolean closeRequested = new AtomicBoolean();
                        private final HostSession session = new HostSession();
                        private HostAdapterConnection connection;
                        private Registration registration;
                        private LifecyclePhase lifecyclePhase;
                        void cleanup() {
                            connection.close();
                            registration.close();
                        }
                    }
                    """)
            ),
            "HostRuntimeIngress must not declare nested enum",
            "HostRuntimeIngress declared instance fields must exactly match",
            "HostRuntimeIngress must not invoke dev.turboism.adapter.host.HostAdapterConnection.close",
            "HostRuntimeIngress must not invoke dev.turboism.sdk.plugin.Registration.close"
        );
    }

    @Test
    void rejectsIngressLifecycleCleanupCallsEvenWithoutRetainedFields() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.RuntimeHostAdapters", """
                    package dev.turboism.adapter;
                    public interface RuntimeHostAdapters {}
                    """),
                source("dev.turboism.adapter.host.HostInstanceDescriptor", """
                    package dev.turboism.adapter.host;
                    public final class HostInstanceDescriptor {}
                    """),
                source("dev.turboism.adapter.host.HostAdapterConnection", """
                    package dev.turboism.adapter.host;
                    public interface HostAdapterConnection extends AutoCloseable {
                        @Override void close();
                    }
                    """),
                source("dev.turboism.adapter.host.DynamicRuntimeHostAdapters", """
                    package dev.turboism.adapter.host;
                    public final class DynamicRuntimeHostAdapters {
                        public void deactivate() {}
                    }
                    """),
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession { public enum State { ACTIVE } }
                    """),
                source("dev.turboism.bootstrap.HostRuntimeIngress", """
                    package dev.turboism.bootstrap;
                    import dev.turboism.adapter.host.*;
                    import java.util.concurrent.atomic.*;
                    final class HostRuntimeIngress {
                        private final AtomicReference<HostInstanceDescriptor> current = new AtomicReference<>();
                        private final AtomicBoolean closeRequested = new AtomicBoolean();
                        private final HostSession session = new HostSession();
                        void cleanup(HostAdapterConnection connection, DynamicRuntimeHostAdapters dynamic) {
                            connection.close();
                            dynamic.deactivate();
                        }
                    }
                    """)
            ),
            "HostRuntimeIngress must not invoke dev.turboism.adapter.host.HostAdapterConnection.close",
            "HostRuntimeIngress must not invoke dev.turboism.adapter.host.DynamicRuntimeHostAdapters.deactivate"
        );
    }

    @Test
    void rejectsNonMonotonicCloseRequestedAdmissionFenceMutations() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession { public enum State { ACTIVE } }
                    """),
                source("dev.turboism.bootstrap.HostRuntimeIngress", """
                    package dev.turboism.bootstrap;
                    import java.util.concurrent.atomic.AtomicBoolean;
                    final class HostRuntimeIngress {
                        private final AtomicBoolean closeRequested = new AtomicBoolean(false);
                        void reopen() { closeRequested.set(false); }
                        void weakClose() { closeRequested.set(true); }
                        boolean read() { return closeRequested.get(); }
                    }
                    """)
            ),
            "closeRequested may only use get() or monotonic set(true)"
        );
    }

    @Test
    void rejectsPublicInjectionConstructorsAndMissingSessionState() {
        assertViolations(
            List.of(
                source("dev.turboism.adapter.host.HostInstanceSource", """
                    package dev.turboism.adapter.host;
                    public interface HostInstanceSource {}
                    """),
                source("dev.turboism.adapter.host.HostAdapterConnector", """
                    package dev.turboism.adapter.host;
                    public interface HostAdapterConnector { Object connect(); }
                    """),
                source("dev.turboism.adapter.host.HostSession", """
                    package dev.turboism.adapter.host;
                    public final class HostSession {
                        public HostSession(HostInstanceSource source, HostAdapterConnector connector) {}
                    }
                    """),
                source("dev.turboism.bootstrap.HostRuntimeIngress", """
                    package dev.turboism.bootstrap;
                    import dev.turboism.adapter.host.*;
                    import java.util.function.Function;
                    final class HostRuntimeIngress {
                        public HostRuntimeIngress(Function<HostInstanceSource, HostSession> sessionFactory) {}
                    }
                    """)
            ),
            "HostSession must declare its lifecycle State enum",
            "connector injection constructor must not be public",
            "session factory injection constructor must not be public"
        );
    }

    private static List<SourceText> ingressFieldFixture(final String ingressFields) {
        return List.of(
            source("dev.turboism.adapter.RuntimeHostAdapters", """
                package dev.turboism.adapter;
                public interface RuntimeHostAdapters {}
                """),
            source("dev.turboism.adapter.host.HostInstanceDescriptor", """
                package dev.turboism.adapter.host;
                public final class HostInstanceDescriptor {}
                """),
            source("dev.turboism.adapter.host.HostAdapterConnection", """
                package dev.turboism.adapter.host;
                public interface HostAdapterConnection extends AutoCloseable {}
                """),
            source("dev.turboism.adapter.host.HostAdapterConnector", """
                package dev.turboism.adapter.host;
                public interface HostAdapterConnector { HostAdapterConnection connect(); }
                """),
            source("dev.turboism.sdk.plugin.Registration", """
                package dev.turboism.sdk.plugin;
                public interface Registration extends AutoCloseable {}
                """),
            source("dev.turboism.adapter.host.HostSession", """
                package dev.turboism.adapter.host;
                public final class HostSession {
                    public enum State { ACTIVE }
                }
                """),
            source("dev.turboism.bootstrap.HostRuntimeIngress", """
                package dev.turboism.bootstrap;
                import dev.turboism.adapter.RuntimeHostAdapters;
                import dev.turboism.adapter.host.*;
                import dev.turboism.sdk.plugin.Registration;
                import java.util.concurrent.atomic.*;
                final class HostRuntimeIngress {
                %s
                }
                """.formatted(ingressFields.indent(4)))
        );
    }

    private static SourceText source(final String fqn, final String source) {
        return new SourceText(fqn, source);
    }

    private static void assertViolations(
        final List<? extends JavaFileObject> sources,
        final String... expectedFragments
    ) {
        final List<String> violations = OwnershipAudit.inspectSources(sources);
        for (String fragment : expectedFragments) {
            assertTrue(
                violations.stream().anyMatch(violation -> violation.contains(fragment)),
                () -> "Missing violation " + fragment + " in " + violations
            );
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }

    private static final class SourceText extends SimpleJavaFileObject {
        private final String source;

        private SourceText(final String fqn, final String source) {
            super(URI.create("string:///" + fqn.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source.stripIndent();
        }

        @Override
        public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class OwnershipAudit extends TreePathScanner<Void, Void> {
        private static final String INGRESS = "dev.turboism.bootstrap.HostRuntimeIngress";
        private static final String SESSION = "dev.turboism.adapter.host.HostSession";
        private static final String SESSION_STATE = SESSION + ".State";
        private static final String CONNECTOR = "dev.turboism.adapter.host.HostAdapterConnector";
        private static final String VERIFIED_CONNECTOR =
            "dev.turboism.adapter.host.VerifiedHostAdapterConnector";
        private static final String HOST_INSTANCE_DESCRIPTOR =
            "dev.turboism.adapter.host.HostInstanceDescriptor";
        private static final String HOST_ADAPTER_CONNECTION =
            "dev.turboism.adapter.host.HostAdapterConnection";
        private static final String RUNTIME_HOST_ADAPTERS =
            "dev.turboism.adapter.RuntimeHostAdapters";
        private static final String DYNAMIC_ADAPTERS =
            "dev.turboism.adapter.host.DynamicRuntimeHostAdapters";
        private static final String REGISTRATION = "dev.turboism.sdk.plugin.Registration";
        private static final String ATOMIC_BOOLEAN = "java.util.concurrent.atomic.AtomicBoolean";
        private static final String ATOMIC_REFERENCE = "java.util.concurrent.atomic.AtomicReference";
        private static final Map<String, String> INGRESS_INSTANCE_FIELDS = Map.of(
            "current", ATOMIC_REFERENCE + "<" + HOST_INSTANCE_DESCRIPTOR + ">",
            "closeRequested", ATOMIC_BOOLEAN,
            "session", SESSION
        );
        private static final Set<String> FORBIDDEN_INGRESS_STATIC_FIELD_TYPES = Set.of(
            SESSION,
            SESSION_STATE,
            HOST_ADAPTER_CONNECTION,
            REGISTRATION,
            CONNECTOR,
            RUNTIME_HOST_ADAPTERS,
            DYNAMIC_ADAPTERS
        );
        private static final Set<String> FORBIDDEN_INGRESS_LIFECYCLE_CALLS = Set.of(
            HOST_ADAPTER_CONNECTION + "#close",
            REGISTRATION + "#close",
            DYNAMIC_ADAPTERS + "#connect",
            DYNAMIC_ADAPTERS + "#deactivate",
            CONNECTOR + "#connect"
        );

        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final List<String> violations = new ArrayList<>();
        private TypeElement currentType;
        private boolean hostSessionSeen;
        private boolean hostSessionDeclaresState;
        private boolean ingressSeen;
        private final Map<String, String> ingressInstanceFields = new LinkedHashMap<>();

        private OwnershipAudit(final JavacTask task) {
            trees = Trees.instance(task);
            elements = task.getElements();
            types = task.getTypes();
        }

        static List<String> inspectPaths(final List<Path> paths) throws IOException {
            final JavaCompiler compiler = requiredCompiler();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
                return inspect(compiler, files.getJavaFileObjectsFromPaths(paths));
            }
        }

        static List<String> inspectSources(final List<? extends JavaFileObject> sources) {
            return inspect(requiredCompiler(), sources);
        }

        private static JavaCompiler requiredCompiler() {
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("Ownership audit requires a JDK compiler");
            }
            return compiler;
        }

        private static List<String> inspect(
            final JavaCompiler compiler,
            final Iterable<? extends JavaFileObject> sources
        ) {
            final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            final JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path")),
                null,
                sources
            );
            final List<? extends com.sun.source.tree.CompilationUnitTree> units;
            try {
                units = StreamSupport.stream(task.parse().spliterator(), false).toList();
                task.analyze();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot analyze Java sources", exception);
            }
            final List<String> errors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> diagnostic.getMessage(null))
                .toList();
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Semantic fixture/source analysis failed: " + errors);
            }
            final OwnershipAudit audit = new OwnershipAudit(task);
            units.forEach(unit -> audit.scan(unit, null));
            if (audit.hostSessionSeen && !audit.hostSessionDeclaresState) {
                audit.violations.add("HostSession must declare its lifecycle State enum");
            }
            if (audit.ingressSeen && !INGRESS_INSTANCE_FIELDS.equals(audit.ingressInstanceFields)) {
                audit.violations.add(
                    "HostRuntimeIngress declared instance fields must exactly match "
                        + INGRESS_INSTANCE_FIELDS + ": actual " + audit.ingressInstanceFields
                );
            }
            return List.copyOf(audit.violations);
        }

        @Override
        public Void visitClass(final ClassTree node, final Void unused) {
            final TypeElement previousType = currentType;
            final Element element = trees.getElement(getCurrentPath());
            currentType = element instanceof TypeElement type ? type : previousType;
            final String owner = currentType == null ? "<unknown>" : currentType.getQualifiedName().toString();

            if (INGRESS.equals(owner)) {
                ingressSeen = true;
                if (node.getMembers().stream().anyMatch(member -> member.getKind() == Tree.Kind.ENUM)) {
                    violations.add("HostRuntimeIngress must not declare nested enum");
                }
            }
            if (SESSION.equals(owner)) {
                hostSessionSeen = true;
                hostSessionDeclaresState |= node.getMembers().stream()
                    .filter(member -> member.getKind() == Tree.Kind.ENUM)
                    .map(member -> (ClassTree) member)
                    .map(member -> trees.getElement(new TreePath(getCurrentPath(), member)))
                    .filter(TypeElement.class::isInstance)
                    .map(TypeElement.class::cast)
                    .anyMatch(member -> SESSION_STATE.contentEquals(member.getQualifiedName()));
            }
            if (isConcreteConnector(currentType) && !VERIFIED_CONNECTOR.equals(owner)) {
                violations.add("HostAdapterConnector production implementation must be VerifiedHostAdapterConnector: " + owner);
            }

            super.visitClass(node, unused);
            currentType = previousType;
            return null;
        }

        @Override
        public Void visitVariable(final VariableTree node, final Void unused) {
            final Element declared = trees.getElement(getCurrentPath());
            if (declared instanceof VariableElement variable
                && variable.getKind() == ElementKind.FIELD
                && currentType != null
                && node.getType() != null) {
                final String owner = currentType.getQualifiedName().toString();
                final TypeMirror fieldType = variable.asType();
                if (!INGRESS.equals(owner) && containsType(fieldType, SESSION)) {
                    violations.add("only HostRuntimeIngress may retain a HostSession field: " + owner);
                }
                if (INGRESS.equals(owner) && containsType(fieldType, SESSION_STATE)) {
                    violations.add("HostRuntimeIngress must not retain a HostSession.State field");
                }
                if (INGRESS.equals(owner)) {
                    if (node.getModifiers().getFlags().contains(Modifier.STATIC)) {
                        if (FORBIDDEN_INGRESS_STATIC_FIELD_TYPES.stream()
                            .anyMatch(forbidden -> containsType(fieldType, forbidden))) {
                            violations.add(
                                "HostRuntimeIngress static field must not retain host ownership/lifecycle resource: "
                                    + node.getName() + ":" + fieldType
                            );
                        }
                    } else {
                        ingressInstanceFields.put(node.getName().toString(), fieldType.toString());
                    }
                }
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitMethod(final MethodTree node, final Void unused) {
            if (node.getName().contentEquals("<init>")
                && node.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                final String owner = currentType == null ? "<unknown>" : currentType.getQualifiedName().toString();
                if (node.getParameters().stream().anyMatch(parameter ->
                    containsType(trees.getTypeMirror(new TreePath(getCurrentPath(), parameter.getType())), CONNECTOR))) {
                    violations.add("connector injection constructor must not be public: " + owner);
                }
                if (INGRESS.equals(owner) && node.getParameters().stream().anyMatch(parameter ->
                    containsType(trees.getTypeMirror(new TreePath(getCurrentPath(), parameter.getType())), SESSION))) {
                    violations.add("session factory injection constructor must not be public: " + owner);
                }
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
            if (node.getMethodSelect() instanceof MemberSelectTree select) {
                final Element receiver = trees.getElement(new TreePath(getCurrentPath(), select.getExpression()));
                if (isCloseRequestedField(receiver)) {
                    final boolean allowedGet = select.getIdentifier().contentEquals("get")
                        && node.getArguments().isEmpty();
                    final boolean allowedMonotonicSet = select.getIdentifier().contentEquals("set")
                        && node.getArguments().size() == 1
                        && isBooleanLiteral(node.getArguments().get(0), true);
                    if (!allowedGet && !allowedMonotonicSet) {
                        violations.add("closeRequested may only use get() or monotonic set(true)");
                    }
                }
                if (INGRESS.equals(ownerName())) {
                    final Element invoked = trees.getElement(getCurrentPath());
                    if (invoked instanceof ExecutableElement method
                        && method.getEnclosingElement() instanceof TypeElement declaringType) {
                        final String call = declaringType.getQualifiedName() + "#" + method.getSimpleName();
                        if (FORBIDDEN_INGRESS_LIFECYCLE_CALLS.contains(call)) {
                            violations.add("HostRuntimeIngress must not invoke " + call.replace('#', '.'));
                        }
                    }
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(final NewClassTree node, final Void unused) {
            final Element constructor = trees.getElement(getCurrentPath());
            if (constructor != null
                && constructor.getKind() == ElementKind.CONSTRUCTOR
                && SESSION.equals(((TypeElement) constructor.getEnclosingElement()).getQualifiedName().toString())
                && !INGRESS.equals(ownerName())) {
                violations.add("HostSession composition is owned by HostRuntimeIngress: " + ownerName());
            }
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitLambdaExpression(final LambdaExpressionTree node, final Void unused) {
            final TypeElement connector = elements.getTypeElement(CONNECTOR);
            final TypeMirror lambdaType = trees.getTypeMirror(getCurrentPath());
            if (connector != null
                && lambdaType != null
                && types.isAssignable(types.erasure(lambdaType), types.erasure(connector.asType()))) {
                violations.add("HostAdapterConnector production implementation must be VerifiedHostAdapterConnector: lambda in " + ownerName());
            }
            return super.visitLambdaExpression(node, unused);
        }

        @Override
        public Void visitMemberReference(final MemberReferenceTree node, final Void unused) {
            final Element referenced = trees.getElement(getCurrentPath());
            if (node.getMode() == MemberReferenceTree.ReferenceMode.NEW
                && referenced != null
                && referenced.getKind() == ElementKind.CONSTRUCTOR
                && SESSION.equals(((TypeElement) referenced.getEnclosingElement()).getQualifiedName().toString())
                && !INGRESS.equals(ownerName())) {
                violations.add("HostSession composition is owned by HostRuntimeIngress: " + ownerName());
            }
            return super.visitMemberReference(node, unused);
        }

        private boolean isConcreteConnector(final TypeElement type) {
            final TypeElement connector = elements.getTypeElement(CONNECTOR);
            return type != null
                && connector != null
                && type.getKind().isClass()
                && !type.getModifiers().contains(Modifier.ABSTRACT)
                && types.isAssignable(types.erasure(type.asType()), types.erasure(connector.asType()));
        }

        private boolean containsType(final TypeMirror mirror, final String fqn) {
            if (mirror == null) {
                return false;
            }
            return switch (mirror.getKind()) {
                case ARRAY -> containsType(((ArrayType) mirror).getComponentType(), fqn);
                case DECLARED -> {
                    final DeclaredType declared = (DeclaredType) mirror;
                    final Element element = declared.asElement();
                    final boolean exact = element instanceof TypeElement type
                        && fqn.contentEquals(type.getQualifiedName());
                    yield exact || declared.getTypeArguments().stream()
                        .anyMatch(argument -> containsType(argument, fqn));
                }
                case TYPEVAR -> {
                    final TypeVariable variable = (TypeVariable) mirror;
                    yield containsType(variable.getUpperBound(), fqn)
                        || containsType(variable.getLowerBound(), fqn);
                }
                default -> false;
            };
        }

        private boolean isCloseRequestedField(final Element element) {
            return element instanceof VariableElement variable
                && variable.getKind() == ElementKind.FIELD
                && variable.getSimpleName().contentEquals("closeRequested")
                && variable.getEnclosingElement() instanceof TypeElement owner
                && owner.getQualifiedName().contentEquals(INGRESS);
        }

        private boolean isBooleanLiteral(final Tree tree, final boolean expected) {
            return tree instanceof LiteralTree literal
                && Boolean.valueOf(expected).equals(literal.getValue());
        }

        private String ownerName() {
            return currentType == null ? "<unknown>" : currentType.getQualifiedName().toString();
        }
    }
}
