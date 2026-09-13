package com.live2d.cubism.doc.modeling;

import bench.BenchDocument;

/** Synthetic modeling document carrying the reviewed host class name. */
public final class CModelingDocument extends BenchDocument {
    private final CModelingFileContent fileContent;

    public CModelingDocument(final CModelingFileContent fileContent) {
        this.fileContent = fileContent;
    }

    @Override
    public CModelingFileContent fileContent() {
        return fileContent;
    }

    public Object getModelSource() {
        return this;
    }

    public String getModelName() {
        return fileContent == null ? "untitled" : fileContent.getModelName();
    }
}
