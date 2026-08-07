package dev.turboism.adapter.cubism;

final class CubismTransactionStub {

    private static final String MESSAGE = "M6 read-only boundary: Cubism transaction writes are disabled";

    void setParameterValue(final String targetObjectId, final String parameterId, final double value) {
        throw unsupportedOperation();
    }

    void mutateArtMesh(final String targetObjectId) {
        throw unsupportedOperation();
    }

    void mutateDeformer(final String targetObjectId) {
        throw unsupportedOperation();
    }

    void setProjectPath(final String projectPath) {
        throw unsupportedOperation();
    }

    void commit() {
        throw unsupportedOperation();
    }

    private static UnsupportedOperationException unsupportedOperation() {
        return new UnsupportedOperationException(MESSAGE);
    }
}
