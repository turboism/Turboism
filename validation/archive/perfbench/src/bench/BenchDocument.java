package bench;

import com.live2d.cubism.doc.IFileContent;

/** Common base so one verified selector covers every synthetic document kind. */
public abstract class BenchDocument {
    public abstract IFileContent fileContent();
}
