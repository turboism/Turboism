package dev.turboism.adapter.cubism;

import bench.BenchAppCtrl;
import bench.BenchDocument;
import bench.BenchFileContent;
import bench.BenchProject;
import com.live2d.cubism.doc.animation.CAnimation;
import com.live2d.cubism.doc.animation.CAnimationFileContent;
import com.live2d.cubism.doc.animation.CSceneDocument;
import com.live2d.cubism.doc.modeling.CModelingDocument;
import com.live2d.cubism.doc.modeling.CModelingFileContent;
import dev.turboism.adapter.host.HostSessionSnapshotSource;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Offline A/B harness for the snapshot-read path: builds a synthetic host graph carrying the
 * reviewed Cubism class names, then times (a) one raw {@code activeProject} traversal, (b) the
 * observe()+versionOf logical read, and (c) the legacy four-call scope-capture pattern. Run the
 * same class against a pre-change build and a post-change build to quantify the slice deltas.
 */
public final class PerfBench {

    private PerfBench() { }

    public static void main(final String[] args) {
        final int modelDocs = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        final int animationDocs = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        final int extraContents = args.length > 2 ? Integer.parseInt(args[2]) : 15;
        final int iterations = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        final int warmup = args.length > 4 ? Integer.parseInt(args[4]) : 500;

        final List<BenchDocument> documents = new ArrayList<>();
        final List<Object> children = new ArrayList<>();
        for (int i = 0; i < modelDocs; i++) {
            final CModelingFileContent content = new CModelingFileContent(
                new File("C:/models/demo/model-" + i + ".cmo3"), new ArrayList<>()
            );
            final CModelingDocument document = new CModelingDocument(content);
            content.getFileContentDocs().add(document);
            documents.add(document);
            children.add(content);
        }
        for (int i = 0; i < animationDocs; i++) {
            final CSceneDocument scene = new CSceneDocument(null);
            final CAnimation animation = new CAnimation(
                "anim-" + i, List.of(scene), scene
            );
            final CAnimationFileContent content = new CAnimationFileContent(
                new File("C:/models/demo/anim-" + i + ".can3"), new ArrayList<>(), animation
            );
            final CSceneDocument document = new CSceneDocument(content);
            content.getFileContentDocs().add(document);
            documents.add(document);
            children.add(content);
        }
        for (int i = 0; i < extraContents; i++) {
            children.add(new CModelingFileContent(
                new File("C:/models/demo/extra-" + i + ".cmo3"), new ArrayList<>()
            ));
        }

        BenchAppCtrl.instance = new BenchAppCtrl(
            new BenchProject(documents, children), documents.get(0)
        );
        final VerifiedProjectWorkspaceHostOperations operations =
            new VerifiedProjectWorkspaceHostOperations(resolver(), "5.3.02");
        final ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.connected(operations);
        final HostSessionSnapshotSource source =
            (HostSessionSnapshotSource) HostSessionSnapshotSource.forSession(adapter);

        // Warm every cache and JIT path before measuring.
        for (int i = 0; i < warmup; i++) {
            operations.activeProject();
            final HostSnapshotSource.Observation observation = source.observe();
            source.versionOf(observation);
            source.isHostPresent();
            source.activeDocument();
            source.activeModel();
            source.invalidationToken();
        }

        final long traversalNs = timed(iterations, operations::activeProject);
        final long observeNs = timed(iterations, () -> {
            final HostSnapshotSource.Observation observation = source.observe();
            source.versionOf(observation);
        });
        final long legacyNs = timed(iterations, () -> {
            source.isHostPresent();
            source.activeDocument();
            source.activeModel();
            source.invalidationToken();
        });
        final long traversalBytes = allocated(iterations, operations::activeProject);
        final long observeBytes = allocated(iterations, () -> {
            final HostSnapshotSource.Observation observation = source.observe();
            source.versionOf(observation);
        });

        System.out.printf(
            "docs=%d contents=%d iters=%d%n"
                + "activeProject          %10.1f ns/op  %10.1f B/op%n"
                + "observe+versionOf      %10.1f ns/op  %10.1f B/op%n"
                + "legacy scope capture   %10.1f ns/op%n",
            documents.size(), children.size(), iterations,
            (double) traversalNs / iterations, (double) traversalBytes / iterations,
            (double) observeNs / iterations, (double) observeBytes / iterations,
            (double) legacyNs / iterations
        );
    }

    private static long timed(final int iterations, final Runnable action) {
        final long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
        return System.nanoTime() - start;
    }

    private static long allocated(final int iterations, final Runnable action) {
        final com.sun.management.ThreadMXBean beans =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                .getThreadMXBean();
        final long start = beans.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            action.run();
        }
        return beans.getCurrentThreadAllocatedBytes() - start;
    }

    private static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = List.of(
            StaticSelector.staticMethod(
                "cubism.app-controller.instance", name(BenchAppCtrl.class), "instance",
                "()L" + name(BenchAppCtrl.class) + ";", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.app-controller.current-project", name(BenchAppCtrl.class), "currentProject",
                "()L" + name(BenchProject.class) + ";", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.app-controller.current-document", name(BenchAppCtrl.class), "currentDocument",
                "()L" + name(BenchDocument.class) + ";", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.app-controller.main-frame", name(BenchAppCtrl.class), "mainFrame",
                "()Ljava/lang/Object;", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.project.documents", name(BenchProject.class), "documents",
                "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.document.file-content", name(BenchDocument.class), "fileContent",
                "()Lcom/live2d/cubism/doc/IFileContent;", StaticSelector.ACCESS_PUBLIC
            ),
            StaticSelector.method(
                "cubism.file-content.file", name(BenchFileContent.class), "file",
                "()Ljava/io/File;", StaticSelector.ACCESS_PUBLIC
            )
        );
        return TestVerifiedResolvers.create(
            ProjectWorkspaceAdapter.ADAPTER_SLICE_ID,
            Set.of(
                ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID,
                ProjectWorkspaceAdapter.WORKSPACE_CAPABILITY_ID
            ),
            selectors,
            PerfBench.class.getClassLoader()
        );
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
