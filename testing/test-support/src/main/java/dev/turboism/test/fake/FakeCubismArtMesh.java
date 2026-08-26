package dev.turboism.test.fake;

/**
 * Fake Cubism art mesh. No real Cubism classes are used.
 */
public final class FakeCubismArtMesh {

    private String id;
    private String name;
    private int drawOrder;
    private float opacity;

    public FakeCubismArtMesh(String id, String name) {
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

    public int getDrawOrder() {
        return drawOrder;
    }

    public void setDrawOrder(int drawOrder) {
        this.drawOrder = drawOrder;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = opacity;
    }
}
