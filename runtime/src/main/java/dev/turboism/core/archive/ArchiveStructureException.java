package dev.turboism.core.archive;

/**
 * Neutral strict-archive violation. Carries the stable machine-readable {@link #code()}
 * (the existing {@code ARCHIVE_*}/{@code PACKAGE_*} code set, unchanged) and the
 * {@link #problemPath()} the violation is attributed to. Callers in a policy-owning
 * package translate it into their own error surface without altering either.
 */
public final class ArchiveStructureException extends Exception {
    private final String code;
    private final String problemPath;

    public ArchiveStructureException(
        final String code,
        final String message,
        final String problemPath
    ) {
        super(message);
        this.code = code;
        this.problemPath = problemPath;
    }

    /** @return the stable violation code, for example {@code ARCHIVE_CENTRAL_INVALID} */
    public String code() {
        return code;
    }

    /** @return the archive or entry path the violation is attributed to */
    public String problemPath() {
        return problemPath;
    }
}
