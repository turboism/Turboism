package bench;

/** Synthetic application controller for the workspace traversal benchmark. */
public final class BenchAppCtrl {
    public static BenchAppCtrl instance;

    private final BenchProject project;
    private final BenchDocument currentDocument;

    public BenchAppCtrl(final BenchProject project, final BenchDocument currentDocument) {
        this.project = project;
        this.currentDocument = currentDocument;
    }

    public static BenchAppCtrl instance() {
        return instance;
    }

    public BenchProject currentProject() {
        return project;
    }

    public BenchDocument currentDocument() {
        return currentDocument;
    }

    public Object mainFrame() {
        return null;
    }
}
