import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syntax-based Javadoc scan used by check_code_quality.py.
 *
 * Parses every listed source file in one JavacTask.parse() batch and asks
 * DocTrees for the doc comment actually attached to each declaration. A
 * declaration is a target when it is publicly reachable: a public top-level or
 * nested type (nested types in interfaces/annotation types are implicitly
 * public), or a public method of a reachable type (interface and annotation
 * methods are implicitly public unless private). Constructors and fields are
 * not targets; @Override methods keep their existing exemption.
 *
 * Input: argv[0] is a UTF-8 file with one "display-path<TAB>absolute-path" pair
 * per source file. Output: one "FINDING<TAB>kind<TAB>name<TAB>display<TAB>line"
 * line per undocumented target. Exit 0 when the batch parsed; 3 when any source
 * failed to parse; 2 on tool or usage errors. Exit codes other than 0 mean no
 * trustworthy result exists.
 */
public final class JavadocPublicApiScan {

    private static final Set<String> OVERRIDE_ANNOTATIONS = Set.of("Override", "java.lang.Override");

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: JavadocPublicApiScan <source-list-file>");
            System.exit(2);
        }
        int status;
        try {
            status = scan(Path.of(args[0]));
        } catch (Exception failure) {
            System.err.println("javadoc scan aborted: " + failure);
            status = 2;
        }
        System.exit(status);
    }

    private static int scan(Path listFile) throws Exception {
        List<Path> sources = new ArrayList<>();
        Map<String, String> displayByUri = new HashMap<>();
        for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf('\t');
            if (separator < 0) {
                System.err.println("malformed source-list line: " + line);
                return 2;
            }
            Path source = Path.of(line.substring(separator + 1));
            sources.add(source);
            displayByUri.put(source.toUri().toString(), line.substring(0, separator));
        }
        if (sources.isEmpty()) {
            return 0;
        }

        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.err.println("no system Java compiler: a JDK (not a JRE) is required");
            return 2;
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> findings = new ArrayList<>();
        try (StandardJavaFileManager manager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                null, manager, diagnostics, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(sources));
            DocTrees trees = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                String display = displayByUri.get(unit.getSourceFile().toUri().toString());
                PublicApiScanner scanner =
                    new PublicApiScanner(unit, trees, display != null ? display : unit.getSourceFile().getName());
                scanner.scan(unit, true);
                findings.addAll(scanner.findings());
            }
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) {
                continue;
            }
            JavaFileObject file = diagnostic.getSource();
            String display = file == null
                ? "<unknown>"
                : displayByUri.getOrDefault(file.toUri().toString(), file.getName());
            System.err.println(display + ":" + diagnostic.getLineNumber() + ": "
                + diagnostic.getMessage(null));
        }
        findings.forEach(System.out::println);
        return diagnostics.getDiagnostics().stream()
            .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR) ? 3 : 0;
    }

    private static boolean isInterfaceKind(ClassTree node) {
        return node.getKind() == Tree.Kind.INTERFACE
            || node.getKind() == Tree.Kind.ANNOTATION_TYPE;
    }

    private static final class PublicApiScanner extends TreePathScanner<Void, Boolean> {

        private final CompilationUnitTree unit;
        private final DocTrees trees;
        private final String display;
        private final List<String> findings = new ArrayList<>();

        PublicApiScanner(CompilationUnitTree unit, DocTrees trees, String display) {
            this.unit = unit;
            this.trees = trees;
            this.display = display;
        }

        List<String> findings() {
            return findings;
        }

        @Override
        public Void visitClass(ClassTree node, Boolean parentVisible) {
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            boolean implicitPublic = parent instanceof ClassTree owner
                && isInterfaceKind(owner)
                && !node.getModifiers().getFlags().contains(Modifier.PRIVATE);
            boolean visible = Boolean.TRUE.equals(parentVisible)
                && (implicitPublic || node.getModifiers().getFlags().contains(Modifier.PUBLIC));
            if (visible && node.getSimpleName().length() > 0) {
                check(node, "type", node.getSimpleName().toString());
            }
            return super.visitClass(node, visible);
        }

        @Override
        public Void visitMethod(MethodTree node, Boolean ownerVisible) {
            Tree owner = getCurrentPath().getParentPath().getLeaf();
            boolean implicitPublic = owner instanceof ClassTree ownerClass
                && isInterfaceKind(ownerClass)
                && !node.getModifiers().getFlags().contains(Modifier.PRIVATE);
            boolean isPublic = implicitPublic
                || node.getModifiers().getFlags().contains(Modifier.PUBLIC);
            boolean constructor = node.getReturnType() == null;
            boolean override = node.getModifiers().getAnnotations().stream()
                .map(annotation -> annotation.getAnnotationType().toString())
                .anyMatch(OVERRIDE_ANNOTATIONS::contains);
            if (Boolean.TRUE.equals(ownerVisible) && isPublic && !constructor && !override) {
                check(node, "method", node.getName().toString());
            }
            // Method bodies never contain publicly reachable declarations.
            return null;
        }

        private void check(Tree node, String kind, String name) {
            TreePath path = getCurrentPath();
            if (trees.getDocCommentTree(path) == null) {
                long position = trees.getSourcePositions().getStartPosition(unit, node);
                long line = position >= 0 ? unit.getLineMap().getLineNumber(position) : 0;
                findings.add("FINDING\t" + kind + "\t" + name + "\t" + display + "\t" + line);
            }
        }
    }
}
