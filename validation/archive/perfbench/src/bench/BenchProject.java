package bench;

import java.util.List;

/** Synthetic project: document list plus a content tree root list. */
public final class BenchProject {
    private final List<BenchDocument> documents;
    private final List<Object> children;

    public BenchProject(final List<BenchDocument> documents, final List<Object> children) {
        this.documents = documents;
        this.children = children;
    }

    public List<BenchDocument> documents() {
        return documents;
    }

    public List<Object> getChildren() {
        return children;
    }
}
