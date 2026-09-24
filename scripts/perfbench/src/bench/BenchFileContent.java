package bench;

import com.live2d.cubism.doc.IFileContent;

import java.io.File;
import java.util.List;

/** Common base so one verified selector covers every synthetic file content. */
public abstract class BenchFileContent implements IFileContent {
    private final File file;
    private final List<Object> fileContentDocs;

    protected BenchFileContent(final File file, final List<Object> fileContentDocs) {
        this.file = file;
        this.fileContentDocs = fileContentDocs;
    }

    public File file() {
        return file;
    }

    public List<Object> getFileContentDocs() {
        return fileContentDocs;
    }
}
