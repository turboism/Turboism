package dev.turboism.adapter.cubism.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCorePublicApiCatalogTest {

    private static final String MODEL =
        "com.live2d.sdk.cubism.core.CubismModel";
    private static final String PARAMETER =
        "com.live2d.sdk.cubism.core.CubismParameterView";

    @Test
    void catalogBindsTheCompleteClassifiedRoster() {
        final List<GeneratedCorePublicApiCatalog.Member> members =
            GeneratedCorePublicApiCatalog.members();

        assertEquals(240, members.size());
        assertEquals(
            177L,
            members.stream().filter(member -> member.supports("5.2")).count()
        );
        assertEquals(
            237L,
            members.stream().filter(member -> member.supports("5.3.02")).count()
        );
        assertEquals(
            "74c87aa70690d33a1179877e9342005d0256196b88d4967c1b591a9891d835e2",
            GeneratedCorePublicApiCatalog.CLASSIFIED_ROSTER_SHA256
        );

        final HashSet<String> identities = new HashSet<>();
        for (GeneratedCorePublicApiCatalog.Member member : members) {
            assertTrue(identities.add(
                member.owner() + "#" + member.kind() + "#"
                    + member.name() + member.descriptor()
            ));
        }
        assertThrows(
            UnsupportedOperationException.class,
            () -> members.add(members.get(0))
        );
    }

    @Test
    void lookupNormalizesVersionClassificationAndLifecycle() {
        final GeneratedCorePublicApiCatalog.Member setValue =
            GeneratedCorePublicApiCatalog.find(
                "5.2",
                PARAMETER,
                "setValue",
                "(F)V"
            ).orElseThrow();

        assertEquals(
            GeneratedCorePublicApiCatalog.Category.MODEL_WRITE,
            setValue.category()
        );
        assertEquals(
            GeneratedCorePublicApiCatalog.Exposure.MODEL,
            setValue.exposure()
        );
        assertEquals(
            GeneratedCorePublicApiCatalog.Lifecycle.BEFORE_ON_AFTER,
            setValue.lifecycle()
        );

        final GeneratedCorePublicApiCatalog.Member close =
            GeneratedCorePublicApiCatalog.find(
                "5.3.02",
                MODEL,
                "close",
                "()V"
            ).orElseThrow();
        assertEquals(
            GeneratedCorePublicApiCatalog.Category.RUNTIME_INTERNAL,
            close.category()
        );
        assertEquals(
            GeneratedCorePublicApiCatalog.Exposure.INTERNAL,
            close.exposure()
        );
    }

    @Test
    void lookupFailsClosedForVersionOnlyMembers() {
        final String descriptor =
            "()Lcom/live2d/sdk/cubism/core/CubismOffscreenRendering;";

        assertFalse(GeneratedCorePublicApiCatalog.find(
            "5.2",
            MODEL,
            "getOffscreenRendering",
            descriptor
        ).isPresent());
        assertTrue(GeneratedCorePublicApiCatalog.find(
            "5.3.02",
            MODEL,
            "getOffscreenRendering",
            descriptor
        ).isPresent());
    }
}
