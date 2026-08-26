package dev.turboism.test.fake;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake Cubism model. No real Cubism classes are used.
 */
public final class FakeCubismModel {

    private String id;
    private String name;
    private final List<FakeCubismParameter> parameters = new ArrayList<>();
    private final List<FakeCubismArtMesh> artMeshes = new ArrayList<>();
    private final List<FakeCubismDeformer> deformers = new ArrayList<>();

    public FakeCubismModel(String id, String name) {
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

    public List<FakeCubismParameter> getParameters() {
        return parameters;
    }

    public void addParameter(FakeCubismParameter parameter) {
        parameters.add(parameter);
    }

    public List<FakeCubismArtMesh> getArtMeshes() {
        return artMeshes;
    }

    public void addArtMesh(FakeCubismArtMesh artMesh) {
        artMeshes.add(artMesh);
    }

    public List<FakeCubismDeformer> getDeformers() {
        return deformers;
    }

    public void addDeformer(FakeCubismDeformer deformer) {
        deformers.add(deformer);
    }
}
