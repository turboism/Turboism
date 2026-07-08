package dev.turboism.test.fake;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake Cubism host for first-phase testing. No real Cubism classes are used.
 */
public final class FakeCubismHost {

    private boolean running;
    private final List<FakeCubismProject> projects = new ArrayList<>();
    private String activeProjectId;
    private FakeCubismDocument activeDocument;
    private FakeCubismModel activeModel;
    private final FakeCubismSelection selection = new FakeCubismSelection();
    private long invalidationToken;

    public void start() {
        running = true;
        bumpInvalidationToken();
    }

    public void stop() {
        running = false;
        bumpInvalidationToken();
    }

    public boolean isRunning() {
        return running;
    }

    public List<FakeCubismProject> getProjects() {
        return projects;
    }

    public void addProject(FakeCubismProject project) {
        projects.add(project);
        bumpInvalidationToken();
    }

    public void clearProjects() {
        projects.clear();
        bumpInvalidationToken();
    }

    public String getActiveProjectId() {
        return activeProjectId;
    }

    public void setActiveProjectId(String activeProjectId) {
        this.activeProjectId = activeProjectId;
        bumpInvalidationToken();
    }

    public FakeCubismProject getActiveProject() {
        if (activeProjectId == null) {
            return null;
        }
        for (FakeCubismProject project : projects) {
            if (activeProjectId.equals(project.getId())) {
                return project;
            }
        }
        return null;
    }

    public FakeCubismDocument getActiveDocument() {
        return activeDocument;
    }

    public void setActiveDocument(FakeCubismDocument activeDocument) {
        this.activeDocument = activeDocument;
        bumpInvalidationToken();
    }

    public FakeCubismModel getActiveModel() {
        return activeModel;
    }

    public void setActiveModel(FakeCubismModel activeModel) {
        this.activeModel = activeModel;
        bumpInvalidationToken();
    }

    public FakeCubismSelection getSelection() {
        return selection;
    }

    public void select(String id) {
        selection.select(id);
        bumpInvalidationToken();
    }

    public void deselect(String id) {
        selection.deselect(id);
        bumpInvalidationToken();
    }

    public void clearSelection() {
        selection.clear();
        bumpInvalidationToken();
    }

    public long getInvalidationToken() {
        return invalidationToken;
    }

    public void bumpInvalidationToken() {
        invalidationToken++;
    }
}
