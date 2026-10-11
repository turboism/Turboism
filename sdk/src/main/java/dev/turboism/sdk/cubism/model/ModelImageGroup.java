package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;

/** Read-only projection of one model image group (a texture-grouping node). */
@CubismEditor(from = "5.0.00")
public interface ModelImageGroup {

    /** Returns the group's display name. */
    String groupName();

    /** Returns the group's free-form memo text. */
    String memo();

    /** Returns the image entries in this group, in host order. */
    List<ModelImageEntry> modelImages();
}
