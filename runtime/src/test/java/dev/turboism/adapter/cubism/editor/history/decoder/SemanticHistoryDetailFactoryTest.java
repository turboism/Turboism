package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryParameterCoordinate;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticHistoryDetailFactoryTest {

    @Test
    void projectsArtmeshDefaultShapeWithNormalizedColor() {
        final HistoryEntryDetail detail = SemanticHistoryDetailFactory.set(
            "Host label",
            HistoryOrigin.hostUnattributed(),
            artMesh(),
            "multiplyColor",
            Optional.of("#FFFFFF"),
            Optional.of("#66CCFF"),
            new HistoryEditContext(
                HistoryEditContext.Kind.DEFAULT_FORM,
                Optional.of("default-form"),
                List.of()
            )
        );

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("ART_MESH", detail.targets().get(0).type());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, detail.changes().get(0).context().kind());
        assertEquals("#ffffff", detail.changes().get(0).before().orElseThrow());
        assertEquals("#66ccff", detail.changes().get(0).after().orElseThrow());
    }

    @Test
    void preservesCompleteOrderedCoordinatesEvenWhenValuesAreParameterDefaults() {
        final HistoryEntryDetail detail = SemanticHistoryDetailFactory.set(
            "Host label",
            HistoryOrigin.hostUnattributed(),
            artMesh(),
            "multiplyColor",
            Optional.of("#ffffff"),
            Optional.of("#66ccff"),
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.of("form-at-default-values"),
                List.of(
                    coordinate("ParamAngleX", "Angle X", "0"),
                    coordinate("ParamAngleY", "Angle Y", "0")
                )
            )
        );

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(HistoryEditContext.Kind.KEYFORM, detail.changes().get(0).context().kind());
        assertEquals(
            List.of("ParamAngleX", "ParamAngleY"),
            detail.changes().get(0).context().coordinates().stream()
                .map(value -> value.parameter().id().orElseThrow())
                .toList()
        );
        assertEquals(
            List.of("0", "0"),
            detail.changes().get(0).context().coordinates().stream()
                .map(HistoryParameterCoordinate::value)
                .toList()
        );
    }

    @Test
    void incompleteOrWrongScopeCannotBecomeFull() {
        final HistoryEntryDetail incompleteKeyform = SemanticHistoryDetailFactory.set(
            "Host label",
            HistoryOrigin.hostUnattributed(),
            artMesh(),
            "multiplyColor",
            Optional.of("#ffffff"),
            Optional.of("#66ccff"),
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.of("form-1"),
                List.of()
            )
        );
        final HistoryEntryDetail wrongScope = SemanticHistoryDetailFactory.set(
            "Host label",
            HistoryOrigin.hostUnattributed(),
            artMesh(),
            "multiplyColor",
            Optional.of("#ffffff"),
            Optional.of("#66ccff"),
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            )
        );

        assertEquals(HistoryAction.DetailLevel.PARTIAL, incompleteKeyform.detailLevel());
        assertEquals(
            "history.keyform-coordinates-incomplete",
            incompleteKeyform.degradationCode().orElseThrow()
        );
        assertEquals(HistoryAction.DetailLevel.PARTIAL, wrongScope.detailLevel());
        assertEquals("history.form-scope-unresolved", wrongScope.degradationCode().orElseThrow());
    }

    @Test
    void unmappedTechnicalPropertyRemainsLabelOnly() {
        final HistoryEntryDetail detail = SemanticHistoryDetailFactory.set(
            "Opaque native edit",
            HistoryOrigin.hostUnattributed(),
            artMesh(),
            "nativeField42",
            Optional.of("before"),
            Optional.of("after"),
            new HistoryEditContext(
                HistoryEditContext.Kind.OBJECT,
                Optional.empty(),
                List.of()
            )
        );

        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, detail.detailLevel());
        assertEquals("history.semantic-operation-unmapped", detail.degradationCode().orElseThrow());
        assertTrue(detail.targets().isEmpty());
        assertTrue(detail.changes().isEmpty());
    }

    @Test
    void colorCodecRejectsNonColorObjectsAndFormatsRgbDeterministically() {
        assertTrue(SemanticHistoryValueCodec.color("java.awt.Color[r=102,g=204,b=255]").isEmpty());
        assertEquals("#66ccff", SemanticHistoryValueCodec.rgb(102, 204, 255));
    }

    private static HistoryTarget artMesh() {
        return new HistoryTarget(
            "ART_MESH",
            Optional.of("ArtMesh1"),
            Optional.of("Face shadow")
        );
    }

    private static HistoryParameterCoordinate coordinate(
        final String id,
        final String name,
        final String value
    ) {
        return new HistoryParameterCoordinate(
            new HistoryTarget("PARAMETER", Optional.of(id), Optional.of(name)),
            value
        );
    }
}
