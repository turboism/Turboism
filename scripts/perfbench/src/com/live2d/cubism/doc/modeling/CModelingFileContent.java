package com.live2d.cubism.doc.modeling;

import bench.BenchFileContent;

import java.io.File;
import java.util.List;

/** Synthetic model file content carrying the reviewed host class name. */
public final class CModelingFileContent extends BenchFileContent {
    public CModelingFileContent(final File file, final List<Object> docs) {
        super(file, docs);
    }

    public String getModelName() {
        return file() == null ? "untitled" : file().getName();
    }
}
