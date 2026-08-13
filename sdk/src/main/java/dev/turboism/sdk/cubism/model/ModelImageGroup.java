package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Read-only projection of one model image group (a texture-grouping node). */
@PreviewApi
public interface ModelImageGroup {

    String groupName();

    String memo();

    List<ModelImageEntry> modelImages();
}
