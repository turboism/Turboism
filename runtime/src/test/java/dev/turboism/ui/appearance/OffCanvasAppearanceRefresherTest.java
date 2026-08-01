package dev.turboism.ui.appearance;

import com.live2d.cubism.CEAppCtrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OffCanvasAppearanceRefresherTest {

    @BeforeEach
    void resetHost() {
        CEAppCtrl.reset();
    }

    @Test
    void updatesTheRenderedCurrentMaterialsBaseColorAndRepaints() {
        assertTrue(new OffCanvasAppearanceRefresher().refresh("#3333FF"));

        assertEquals("baseColor", CEAppCtrl.material().slot());
        assertEquals(51, CEAppCtrl.material().color().red());
        assertEquals(51, CEAppCtrl.material().color().green());
        assertEquals(255, CEAppCtrl.material().color().blue());
        assertTrue(CEAppCtrl.repainted());
    }

    @Test
    void failsClosedOnInvalidColor() {
        assertFalse(new OffCanvasAppearanceRefresher().refresh("not-a-color"));
    }
}
