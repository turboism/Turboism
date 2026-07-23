package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterCollectionQueryContractTest {

    @Test
    void queriesParametersByIdNameTextAndDeveloperPredicateInModelOrder() {
        final Parameter leftEye = parameter("ParamEyeLOpen", "Eye Open");
        final Parameter rightEye = parameter("ParamEyeROpen", "Eye Open");
        final Parameter mouth = parameter("ParamMouthForm", "Mouth Form");
        final Parameter unnamed = parameter("ParamHiddenControl", null);
        final Parameters parameters = parameters(leftEye, rightEye, mouth, unnamed);

        assertEquals(
            Optional.of(rightEye),
            parameters.findById(new ParameterId("ParamEyeROpen"))
        );
        assertEquals(
            Optional.of(rightEye),
            parameters.findById("ParamEyeROpen")
        );
        assertEquals(
            Optional.empty(),
            parameters.findById(new ParameterId("Missing"))
        );
        assertEquals(
            List.of(leftEye, rightEye),
            parameters.findByName("Eye Open")
        );
        assertEquals(List.of(), parameters.findByName("eye open"));
        assertEquals(
            List.of(leftEye, rightEye),
            parameters.search("EYE")
        );
        assertEquals(
            List.of(unnamed),
            parameters.search("hidden")
        );
        assertEquals(
            List.of(leftEye, rightEye),
            parameters.filter(parameter -> parameter.id().value().contains("Eye"))
        );

        assertThrows(
            UnsupportedOperationException.class,
            () -> parameters.search("eye").add(mouth)
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> parameters.filter(parameter -> true).clear()
        );
    }

    @Test
    void metadataSupportsBlendShapeRepeatAndCombinedFiltersWithoutInventingUnknownValues() {
        final Parameter blendShape = parameter(
            "ParamCheek",
            "Cheek",
            ParameterType.BLEND_SHAPE,
            Optional.of(false),
            Optional.of(true)
        );
        final Parameter repeating = parameter(
            "ParamAngleX",
            "Angle X",
            ParameterType.NORMAL,
            Optional.of(true),
            Optional.of(false)
        );
        final Parameter legacyUnknown = parameter("ParamLegacy", null);
        final Parameters parameters = parameters(blendShape, repeating, legacyUnknown);

        assertEquals(List.of(blendShape), parameters.filter(Parameter::isBlendShape));
        assertEquals(
            List.of(repeating),
            parameters.filter(parameter -> parameter.repeat().orElse(false))
        );
        assertEquals(
            List.of(blendShape),
            parameters.filter(parameter -> parameter.combined().orElse(false))
        );
        assertEquals(ParameterType.UNKNOWN, legacyUnknown.type());
        assertEquals(Optional.empty(), legacyUnknown.repeat());
        assertEquals(Optional.empty(), legacyUnknown.combined());
    }

    private static Parameters parameters(final Parameter... values) {
        final List<Parameter> all = List.of(values);
        return new Parameters() {
            @Override
            public List<Parameter> all() {
                return all;
            }

            @Override
            public Parameter find(final ParameterId id) {
                return all.stream()
                    .filter(parameter -> parameter.id().equals(id))
                    .findFirst()
                    .orElseThrow(NoSuchElementException::new);
            }
        };
    }

    private static Parameter parameter(final String id, final String name) {
        return new Parameter() {
            @Override public ParameterId id() { return new ParameterId(id); }
            @Override public Optional<String> name() { return Optional.ofNullable(name); }
            @Override public float getValue() { return 0.0f; }
            @Override public float getMinimumValue() { return -1.0f; }
            @Override public float getMaximumValue() { return 1.0f; }
            @Override public float getDefaultValue() { return 0.0f; }
            @Override public void setValue(final float value) { }
        };
    }

    private static Parameter parameter(
        final String id,
        final String name,
        final ParameterType type,
        final Optional<Boolean> repeat,
        final Optional<Boolean> combined
    ) {
        return new Parameter() {
            @Override public ParameterId id() { return new ParameterId(id); }
            @Override public Optional<String> name() { return Optional.of(name); }
            @Override public ParameterType type() { return type; }
            @Override public Optional<Boolean> repeat() { return repeat; }
            @Override public Optional<Boolean> combined() { return combined; }
            @Override public float getValue() { return 0.0f; }
            @Override public float getMinimumValue() { return -1.0f; }
            @Override public float getMaximumValue() { return 1.0f; }
            @Override public float getDefaultValue() { return 0.0f; }
            @Override public void setValue(final float value) { }
        };
    }
}
