package com.live2d.cubism.doc.animation;

import java.util.List;

/** Synthetic animation object exposing the members the traversal reads publicly. */
public final class CAnimation {
    private final String name;
    private final List<Object> sceneDocs;
    private final Object currentSceneDoc;

    public CAnimation(final String name, final List<Object> sceneDocs, final Object currentSceneDoc) {
        this.name = name;
        this.sceneDocs = sceneDocs;
        this.currentSceneDoc = currentSceneDoc;
    }

    public String getName() {
        return name;
    }

    public List<Object> getSceneDocs() {
        return sceneDocs;
    }

    public Object getCurrentSceneDoc() {
        return currentSceneDoc;
    }
}
