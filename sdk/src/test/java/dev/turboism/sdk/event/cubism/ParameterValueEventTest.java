package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterValueEventTest {

    @Test
    void beforeKeepsOriginalRequestAndValidatesCandidateChanges() {
        final ParameterValueEvent.Before event = new ParameterValueEvent.Before(
            parameter(),
            0.25f,
            0.5f
        );

        event.setValue(0.75f);

        assertEquals(0.25f, event.requestedValue());
        assertEquals(0.75f, event.value());
        event.setValue(Float.NaN);
        assertEquals(Float.NaN, event.value());
    }

    @Test
    void callbackScopedBeforeSealsMutationAfterTheSubscriberReturns() {
        final ParameterValueEvent.Before retained;
        try (ParameterValueEvent.Before.Callback callback =
            ParameterValueEvent.Before.openCallback(parameter(), 0.25F, 0.5F)) {
            retained = callback.event();
            retained.setValue(0.75F);
            assertEquals(0.75F, retained.value());
        }

        assertThrows(IllegalStateException.class, () -> retained.setValue(1.0F));
    }

    @Test
    void callbackScopedBeforeRejectsCrossThreadMutation() throws Exception {
        try (ParameterValueEvent.Before.Callback callback =
            ParameterValueEvent.Before.openCallback(parameter(), 0.25F, 0.5F)) {
            final ParameterValueEvent.Before event = callback.event();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
            final Thread thread = new Thread(() -> {
                try {
                    event.setValue(1.0F);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
            });
            thread.start();
            thread.join();

            org.junit.jupiter.api.Assertions.assertInstanceOf(
                IllegalStateException.class,
                failure.get()
            );
            assertEquals(0.5F, event.value());
        }
    }

    @Test
    void observedStatesPreserveAuthoritativeNonFiniteReadback() {
        final Parameter parameter = parameter();
        final ParameterValueEvent.On on = new ParameterValueEvent.On(
            parameter,
            0.0f,
            Float.POSITIVE_INFINITY
        );
        final ParameterValueEvent.After after = new ParameterValueEvent.After(
            parameter,
            Float.NaN
        );

        assertEquals(Float.POSITIVE_INFINITY, on.newValue());
        assertEquals(Float.NaN, after.finalValue());
    }

    private static Parameter parameter() {
        return new Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamAngleX");
            }

            @Override
            public Optional<String> name() {
                return Optional.of("Angle X");
            }

            @Override
            public float getMinimumValue() {
                return -30.0f;
            }

            @Override
            public float getMaximumValue() {
                return 30.0f;
            }

            @Override
            public float getDefaultValue() {
                return 0.0f;
            }

            @Override
            public float getValue() {
                return 0.0f;
            }

            @Override
            public void setValue(final float value) {
            }
        };
    }
}
