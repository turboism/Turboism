package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartEventTest {

    @Test
    void opacityBeforeSealsMutationAfterCallback() {
        final PartOpacityEvent.Before retained;
        try (PartOpacityEvent.Before.Callback callback =
            PartOpacityEvent.Before.openCallback(part(), 0.5F, 0.25F)) {
            retained = callback.event();
            retained.setOpacity(0.75F);
            assertEquals(0.75F, retained.opacity());
        }

        assertThrows(IllegalStateException.class, () -> retained.setOpacity(1.0F));
    }

    @Test
    void nameBeforeSealsMutationAfterCallback() {
        final PartNameEvent.Before retained;
        try (PartNameEvent.Before.Callback callback =
            PartNameEvent.Before.openCallback(part(), "Arm", "Arm L")) {
            retained = callback.event();
            retained.setName("Arm R");
            assertEquals("Arm R", retained.name());
        }

        assertThrows(IllegalStateException.class, () -> retained.setName("Root"));
    }

    private static Part part() {
        return new Part() {
            @Override public PartId id() { return new PartId("PartArmL"); }
            @Override public String name() { return "Arm L"; }
            @Override public void setName(final String name) { }
            @Override public float getOpacity() { return 0.5F; }
            @Override public int parentIndex() { return -1; }
            @Override public void setOpacity(final float opacity) { }
        };
    }
}
