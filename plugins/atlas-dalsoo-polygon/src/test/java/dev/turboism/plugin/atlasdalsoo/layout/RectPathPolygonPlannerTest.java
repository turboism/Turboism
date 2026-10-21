package dev.turboism.plugin.atlasdalsoo.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.atlasdalsoo.PolygonLayoutSettings;
import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class RectPathPolygonPlannerTest {
    @Test
    void completeAtlasRequestsKeepSourceSizeAndForbidRotationDespitePolygonPreferences() {
        var planner = new RectPathPolygonPlanner(PolygonLayoutSettings::defaults);
        var result = planner.plan(
                List.of(new TextureAtlasLayoutItem("image", 20, 10)),
                new TextureAtlasLayoutConstraints(128, 128, 3, 6, 1, false, false));
        assertEquals(1D, result.scale());
        assertEquals(1, result.placements().size());
        var placement = result.placements().get(0);
        assertFalse(placement.rotated());
        assertEquals(20, placement.width());
        assertEquals(10, placement.height());
    }
}
