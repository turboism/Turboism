package dev.turboism.sdk.cubism.model;


import java.util.List;

/** Read-only projection of one model image group (a texture-grouping node). */
public interface ModelImageGroup {

    String groupName();

    String memo();

    List<ModelImageEntry> modelImages();
}
