package com.live2d.cubism.doc.animation;

import bench.BenchDocument;

/** Synthetic animation scene document carrying the reviewed host class name. */
public final class CSceneDocument extends BenchDocument {
    private final CAnimationFileContent fileContent;

    public CSceneDocument(final CAnimationFileContent fileContent) {
        this.fileContent = fileContent;
    }

    @Override
    public CAnimationFileContent fileContent() {
        return fileContent;
    }
}
