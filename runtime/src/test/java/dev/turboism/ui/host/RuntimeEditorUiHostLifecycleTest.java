package dev.turboism.ui.host;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEditorUiHostLifecycleTest {

    @Test
    void tracksGenerationAndIndependentFamilyReadiness() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();

        EditorUiHostSnapshot connecting = lifecycle.connecting();
        EditorUiHostSnapshot connected = lifecycle.connected(connecting.generation());
        EditorUiHostSnapshot ready = lifecycle.ready(
            connected.generation(),
            Set.of(EditorUiFamily.APPEARANCE, EditorUiFamily.MENU)
        );

        assertEquals(EditorUiHostSnapshot.State.READY, ready.state());
        assertTrue(ready.isReady(EditorUiFamily.APPEARANCE));
        assertTrue(ready.isReady(EditorUiFamily.MENU));
        assertFalse(ready.isReady(EditorUiFamily.MAIN_TOOLBAR));

        EditorUiHostSnapshot degraded = lifecycle.markFamilyUnavailable(
            EditorUiFamily.APPEARANCE,
            "Appearance provider is unavailable."
        );

        assertEquals(ready.generation(), degraded.generation());
        assertFalse(degraded.isReady(EditorUiFamily.APPEARANCE));
        assertTrue(degraded.isReady(EditorUiFamily.MENU));
        assertEquals(
            Optional.of(EditorUiFamily.APPEARANCE),
            degraded.failure().orElseThrow().family()
        );
    }

    @Test
    void replacementInvalidatesReadinessAndNewConnectionAdvancesGeneration() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long firstGeneration = lifecycle.connecting().generation();
        lifecycle.ready(firstGeneration, Set.of(EditorUiFamily.MENU));

        EditorUiHostSnapshot replacing = lifecycle.replacing();
        EditorUiHostSnapshot reconnecting = lifecycle.connecting();

        assertEquals(EditorUiHostSnapshot.State.REPLACING, replacing.state());
        assertTrue(replacing.readyFamilies().isEmpty());
        assertTrue(reconnecting.generation() > firstGeneration);
        assertEquals(EditorUiHostSnapshot.State.CONNECTING, reconnecting.state());
    }

    @Test
    void subscribersReceiveCurrentTransitionsAndStopAfterClose() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        List<EditorUiHostSnapshot.State> states = new ArrayList<>();
        Registration registration = lifecycle.subscribe(snapshot -> states.add(snapshot.state()));

        long generation = lifecycle.connecting().generation();
        lifecycle.connected(generation);
        registration.close();
        lifecycle.ready(generation, Set.of(EditorUiFamily.DIALOG));

        assertEquals(
            List.of(
                EditorUiHostSnapshot.State.ABSENT,
                EditorUiHostSnapshot.State.CONNECTING,
                EditorUiHostSnapshot.State.CONNECTED_NOT_READY
            ),
            states
        );
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherTransitions() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        lifecycle.close();
        lifecycle.close();

        assertEquals(EditorUiHostSnapshot.State.CLOSED, lifecycle.snapshot().state());
        assertThrows(IllegalStateException.class, lifecycle::connecting);
        assertThrows(IllegalStateException.class, () -> lifecycle.subscribe(ignored -> { }));
    }

    @Test
    void snapshotsRejectImpossibleReadyStates() {
        assertThrows(IllegalArgumentException.class, () -> new EditorUiHostSnapshot(
            EditorUiHostSnapshot.State.READY,
            1,
            Set.of(),
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new EditorUiHostSnapshot(
            EditorUiHostSnapshot.State.ABSENT,
            1,
            Set.of(EditorUiFamily.MENU),
            Optional.empty()
        ));
    }
}
