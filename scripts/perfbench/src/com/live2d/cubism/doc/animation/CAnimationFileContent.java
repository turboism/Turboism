package com.live2d.cubism.doc.animation;

import bench.BenchFileContent;

import java.io.File;
import java.util.List;

/** Synthetic animation file content carrying the reviewed host class name. */
public final class CAnimationFileContent extends BenchFileContent {
    private final CAnimation animation;

    public CAnimationFileContent(final File file, final List<Object> docs, final CAnimation animation) {
        super(file, docs);
        this.animation = animation;
    }

    public CAnimation getAnimation() {
        return animation;
    }
}
