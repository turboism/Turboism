package dev.turboism.plugin.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.List;

@FunctionalInterface
public interface TextureAtlasLayoutPlanner {
    TextureAtlasLayoutPlan plan(
        List<TextureAtlasLayoutItem> items,
        TextureAtlasLayoutConstraints constraints
    );
}
