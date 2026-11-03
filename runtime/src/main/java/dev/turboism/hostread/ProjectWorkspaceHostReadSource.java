package dev.turboism.hostread;

/** Runtime-only closed source for the first async host-read intent. */
public interface ProjectWorkspaceHostReadSource {

    /**
     * Performs one host read intent.
     *
     * @return the snapshot or the mapped failure code; never null
     */
    ProjectWorkspaceHostReadResult read();
}
