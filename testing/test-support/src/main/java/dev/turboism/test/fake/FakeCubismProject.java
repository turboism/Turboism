package dev.turboism.test.fake;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake Cubism project. No real Cubism classes are used.
 */
public final class FakeCubismProject {

    private String id;
    private String name;
    private final List<FakeCubismDocument> documents = new ArrayList<>();

    public FakeCubismProject(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<FakeCubismDocument> getDocuments() {
        return documents;
    }

    public void addDocument(FakeCubismDocument document) {
        documents.add(document);
    }
}
