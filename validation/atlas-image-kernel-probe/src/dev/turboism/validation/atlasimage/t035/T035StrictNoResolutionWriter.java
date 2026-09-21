package dev.turboism.validation.atlasimage.t035;

import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassWriter;

/** Reader-seeded writer that makes every type merge an explicit offline rejection. */
final class T035StrictNoResolutionWriter extends ClassWriter {
    private int commonSuperQueries;
    private String currentMethod = "<class>";

    T035StrictNoResolutionWriter(final ClassReader reader) {
        super(reader, COMPUTE_FRAMES | COMPUTE_MAXS);
    }

    void setCurrentMethod(final String method) {
        currentMethod = method;
    }

    int commonSuperQueries() {
        return commonSuperQueries;
    }

    @Override
    protected String getCommonSuperClass(final String firstType, final String secondType) {
        commonSuperQueries++;
        throw new ResolutionBlockedException(currentMethod, firstType, secondType);
    }

    static final class ResolutionBlockedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ResolutionBlockedException(
            final String method,
            final String firstType,
            final String secondType
        ) {
            super(method + " common-super request " + firstType + " / " + secondType);
        }
    }
}
