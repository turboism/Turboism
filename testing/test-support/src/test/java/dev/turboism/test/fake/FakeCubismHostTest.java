package dev.turboism.test.fake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FakeCubismHostTest {

    @Test
    void hostStartsAndStops() {
        FakeCubismHost host = new FakeCubismHost();
        assertFalse(host.isRunning());
        host.start();
        assertTrue(host.isRunning());
        host.stop();
        assertFalse(host.isRunning());
    }

    @Test
    void canSetActiveProjectAndRetrieveIt() {
        FakeCubismHost host = new FakeCubismHost();
        FakeCubismProject project = new FakeCubismProject("project-1", "Demo Project");
        host.addProject(project);

        host.setActiveProjectId("project-1");
        assertNotNull(host.getActiveProject());
        assertEquals("project-1", host.getActiveProject().getId());
        assertEquals("project-1", host.getActiveProjectId());
    }

    @Test
    void canSetActiveDocumentAndModel() {
        FakeCubismHost host = new FakeCubismHost();
        FakeCubismDocument document = new FakeCubismDocument("doc-1", "Demo Document");
        FakeCubismModel model = new FakeCubismModel("model-1", "Demo Model");

        host.setActiveDocument(document);
        host.setActiveModel(model);

        assertEquals(document, host.getActiveDocument());
        assertEquals(model, host.getActiveModel());
    }

    @Test
    void selectionMutationsWork() {
        FakeCubismHost host = new FakeCubismHost();
        host.select("param-1");
        host.select("param-2");
        host.select("param-1");

        assertTrue(host.getSelection().isSelected("param-1"));
        assertTrue(host.getSelection().isSelected("param-2"));
        assertEquals(2, host.getSelection().getSelectedIds().size());

        host.deselect("param-1");
        assertFalse(host.getSelection().isSelected("param-1"));

        host.clearSelection();
        assertTrue(host.getSelection().getSelectedIds().isEmpty());
    }

    @Test
    void invalidationTokenIncrementsOnMutations() {
        FakeCubismHost host = new FakeCubismHost();
        long initialToken = host.getInvalidationToken();

        host.start();
        long afterStart = host.getInvalidationToken();
        assertEquals(initialToken + 1, afterStart);

        host.addProject(new FakeCubismProject("p1", "P1"));
        long afterProject = host.getInvalidationToken();
        assertEquals(afterStart + 1, afterProject);

        host.setActiveProjectId("p1");
        long afterActiveProject = host.getInvalidationToken();
        assertEquals(afterProject + 1, afterActiveProject);

        host.setActiveDocument(new FakeCubismDocument("d1", "D1"));
        long afterDocument = host.getInvalidationToken();
        assertEquals(afterActiveProject + 1, afterDocument);

        host.setActiveModel(new FakeCubismModel("m1", "M1"));
        long afterModel = host.getInvalidationToken();
        assertEquals(afterDocument + 1, afterModel);

        host.select("id");
        long afterSelect = host.getInvalidationToken();
        assertEquals(afterModel + 1, afterSelect);

        host.stop();
        long afterStop = host.getInvalidationToken();
        assertEquals(afterSelect + 1, afterStop);
    }
}
