package dev.turboism.distribution;

final class DistributionValidationException extends Exception {
    private final String code;
    private final String problemPath;

    DistributionValidationException(String code, String message, String problemPath) {
        super(message);
        this.code = code;
        this.problemPath = problemPath;
    }

    String code() {
        return code;
    }

    String problemPath() {
        return problemPath;
    }
}
