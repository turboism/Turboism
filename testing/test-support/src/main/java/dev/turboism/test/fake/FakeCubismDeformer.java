package dev.turboism.test.fake;

/**
 * Fake Cubism deformer. No real Cubism classes are used.
 */
public final class FakeCubismDeformer {

    private String id;
    private String name;
    private String deformerType;

    public FakeCubismDeformer(String id, String name) {
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

    public String getDeformerType() {
        return deformerType;
    }

    public void setDeformerType(String deformerType) {
        this.deformerType = deformerType;
    }
}
