package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryRelationLabelFormatterTest {
    private static final String CATALOG_PREFIX = "META-INF/turboism/i18n/";
    private static final PluginLocalization LOCALIZATION = catalog(
        "messages_zh_Hans.properties",
        Locale.SIMPLIFIED_CHINESE
    );

    @Test
    void formatsDeformerParentWithTypedChildAndParentIcons() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel artMeshToWarp = formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetEndpoint(target("WARP_DEFORMER", "old", "Old")),
                targetEndpoint(target("WARP_DEFORMER", "warp-b", "B"))
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel warpToRotation = formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetEndpoint(target("WARP_DEFORMER", "old-2", "Old 2")),
                targetEndpoint(target("ROTATION_DEFORMER", "rotation-b", "B2"))
            ),
            target("WARP_DEFORMER", "warp-a", "A2")
        ).orElseThrow();

        assertEquals("图形网格图标 A 设置为 曲面变形器图标 B 的子级", artMeshToWarp.fallbackText());
        assertEquals("曲面变形器图标 A2 设置为 旋转变形器图标 B2 的子级", warpToRotation.fallbackText());
        assertEquals(
            List.of(CubismIcon.ART_MESH, CubismIcon.WARP_DEFORMER),
            icons(artMeshToWarp)
        );
        assertEquals(
            List.of(CubismIcon.WARP_DEFORMER, CubismIcon.ROTATION_DEFORMER),
            icons(warpToRotation)
        );
    }

    @Test
    void formatsPartMembershipWithAllowedChildIconsAndPartIcon() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel artMeshMembership = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-part", "Old")),
                targetEndpoint(target("PART", "part-c", "C"))
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel rotationMembership = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-part-2", "Old 2")),
                targetEndpoint(target("PART", "part-e", "E"))
            ),
            target("ROTATION_DEFORMER", "rotation-d", "D")
        ).orElseThrow();
        final UiInlineLabel partMembership = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-part-3", "Old 3")),
                targetEndpoint(target("PART", "part-parent", "Parent part"))
            ),
            target("PART", "part-child", "Child part")
        ).orElseThrow();

        assertEquals("图形网格图标 A 加入 部件图标 C", artMeshMembership.fallbackText());
        assertEquals("旋转变形器图标 D 加入 部件图标 E", rotationMembership.fallbackText());
        assertEquals("部件图标 Child part 加入 部件图标 Parent part", partMembership.fallbackText());
        assertEquals(
            List.of(CubismIcon.ART_MESH, CubismIcon.PART),
            icons(artMeshMembership)
        );
        assertEquals(
            List.of(CubismIcon.ROTATION_DEFORMER, CubismIcon.PART),
            icons(rotationMembership)
        );
        assertEquals(
            List.of(CubismIcon.PART, CubismIcon.PART),
            icons(partMembership)
        );
    }

    @Test
    void formatsDetachOnlyFromCapturedTargetToRoot() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel deformerDetach = formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetEndpoint(target("WARP_DEFORMER", "old-parent", "Old parent")),
                rootEndpoint()
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel partDetach = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-part", "Old part")),
                rootEndpoint()
            ),
            target("WARP_DEFORMER", "warp-a", "A2")
        ).orElseThrow();

        assertEquals(
            "图形网格图标 A 移出变形器 曲面变形器图标 Old parent",
            deformerDetach.fallbackText()
        );
        assertEquals(
            "曲面变形器图标 A2 移出部件 部件图标 Old part",
            partDetach.fallbackText()
        );
    }

    @Test
    void formatsVerifiedRootToTargetJoinWithoutInventingRootName() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel deformerJoin = formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                rootEndpoint(),
                targetEndpoint(target("WARP_DEFORMER", "warp-b", "B"))
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel partJoin = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                rootEndpoint(),
                targetEndpoint(target("PART", "part-d", "D"))
            ),
            target("ART_MESH", "mesh-c", "C")
        ).orElseThrow();

        assertEquals("图形网格图标 A 设置为 曲面变形器图标 B 的子级", deformerJoin.fallbackText());
        assertEquals("图形网格图标 C 加入 部件图标 D", partJoin.fallbackText());
        assertFalse(deformerJoin.fallbackText().contains("root"));
        assertFalse(partJoin.fallbackText().contains("root"));
    }

    @Test
    void formatsJoinAndDetachWithJapaneseOperationOrder() {
        final PluginLocalization japanese = catalog("messages_ja.properties", Locale.JAPANESE);
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(japanese);
        final HistoryTarget child = target("ART_MESH", "mesh-a", "A");
        final HistoryTarget parent = target("PART", "part-c", "C");

        final UiInlineLabel join = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                rootEndpoint(),
                targetEndpoint(parent)
            ),
            child
        ).orElseThrow();
        final UiInlineLabel detach = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(parent),
                rootEndpoint()
            ),
            child
        ).orElseThrow();

        assertEquals("アートメッシュアイコン A を パーツアイコン C に追加", join.fallbackText());
        assertEquals("アートメッシュアイコン A を パーツアイコン C から移動", detach.fallbackText());
    }

    @Test
    void formatsJoinAndDetachWithKoreanParticlesAndOperationOrder() {
        final PluginLocalization korean = catalog("messages_ko.properties", Locale.KOREAN);
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(korean);
        final HistoryTarget parent = target("PART", "part-c", "C");

        final UiInlineLabel join = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                rootEndpoint(),
                targetEndpoint(parent)
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel detach = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(parent),
                rootEndpoint()
            ),
            target("ART_MESH", "mesh-a", "A")
        ).orElseThrow();
        final UiInlineLabel batchimJoin = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                rootEndpoint(),
                targetEndpoint(parent)
            ),
            target("ART_MESH", "mesh-batchim", "점")
        ).orElseThrow();

        assertEquals("아트메쉬 아이콘 A를 파트 아이콘 C에 추가", join.fallbackText());
        assertEquals("아트메쉬 아이콘 A를 파트 아이콘 C에서 이동", detach.fallbackText());
        assertEquals("아트메쉬 아이콘 점을 파트 아이콘 C에 추가", batchimJoin.fallbackText());
    }

    @Test
    void rejectsUnknownStatesInvalidOldParentsAndNonChanges() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);
        final HistoryTarget mesh = target("ART_MESH", "mesh-a", "A");
        final HistoryTarget warp = target("WARP_DEFORMER", "warp-b", "B");
        final HistoryTarget part = target("PART", "part-c", "C");
        final HistoryRelationChange.Endpoint targetWarp = targetEndpoint(warp);
        final HistoryRelationChange.Endpoint targetPart = targetEndpoint(part);

        assertTrue(formatter.format(null, mesh).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                unknownEndpoint(),
                targetWarp
            ),
            mesh
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetWarp,
                unknownEndpoint()
            ),
            mesh
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                rootEndpoint(),
                targetWarp
            ),
            mesh
        ).isPresent());

        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.ROOT,
                Optional.of(warp)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoryRelationChange.Endpoint(
                HistoryRelationChange.State.TARGET,
                Optional.empty()
            )
        );

        // The old endpoint must be a legal direct parent for the same relation family.
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetPart,
                targetWarp
            ),
            mesh
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetWarp,
                targetPart
            ),
            mesh
        ).isEmpty());

        // A same-parent rename/reorder is not a reparent: compare the captured identity, not name.
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "same", "Before name")),
                targetEndpoint(target("PART", "same", "After name"))
            ),
            mesh
        ).isEmpty());

        // Same-type child/parent identity is self and must be rejected.
        final HistoryTarget partChild = target("PART", "self", "Self");
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old", "Old")),
                targetEndpoint(target("PART", "self", "Renamed self"))
            ),
            partChild
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "self", "Old self")),
                targetEndpoint(target("PART", "new", "New"))
            ),
            partChild
        ).isEmpty());

        // IDs are namespaced by target type: an Artmesh and Part may share an ID legitimately.
        final UiInlineLabel crossTypeIdentity = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-part", "Old")),
                targetEndpoint(target("PART", "shared", "Shared part"))
            ),
            target("ART_MESH", "shared", "Mesh")
        ).orElseThrow();
        assertEquals("图形网格图标 Mesh 加入 部件图标 Shared part", crossTypeIdentity.fallbackText());

        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.DEFORMER_PARENT,
                targetEndpoint(target("UNKNOWN", "unknown", "Unknown")),
                targetWarp
            ),
            mesh
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetPart,
                targetPart
            ),
            new HistoryTarget("ART_MESH", Optional.empty(), Optional.of("A"))
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetPart,
                targetPart
            ),
            new HistoryTarget("ART_MESH", Optional.of("mesh-a"), Optional.empty())
        ).isEmpty());
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetPart,
                targetPart
            ),
            null
        ).isEmpty());
        assertThrows(
            NullPointerException.class,
            () -> new HistoryTarget(null, Optional.of("mesh-a"), Optional.of("A"))
        );
        assertTrue(formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(new HistoryTarget("PART", Optional.of("old"), Optional.empty())),
                targetPart
            ),
            mesh
        ).isEmpty());
    }

    @Test
    void preservesLiteralNamesAndMaxSupplementaryNamesWithinInlineBounds() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);
        final String maxName = "💠".repeat(128);
        assertEquals(256, maxName.length());

        final UiInlineLabel maxLabel = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old", "Old")),
                targetEndpoint(target("PART", "part-max", maxName))
            ),
            target("ART_MESH", "mesh-max", maxName)
        ).orElseThrow();
        final String expectedMaxFallback = "图形网格图标 " + maxName + " 加入 部件图标 " + maxName;
        assertEquals(expectedMaxFallback, maxLabel.fallbackText());
        assertTrue(maxLabel.fallbackText().length() <= UiInlineLabel.MAX_TOTAL_TEXT_LENGTH);
        assertTrue(maxLabel.runs().size() <= UiInlineLabel.MAX_RUNS);
        assertTrue(maxLabel.runs().stream().allMatch(HistoryRelationLabelFormatterTest::withinInlineBounds));

        final String hostileChild = "<mesh>&\"";
        final String hostileParent = "parent <literal>&";
        final UiInlineLabel literalLabel = formatter.format(
            relation(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                targetEndpoint(target("PART", "old-literal", "Old")),
                targetEndpoint(target("PART", "part-literal", hostileParent))
            ),
            target("ART_MESH", "mesh-literal", hostileChild)
        ).orElseThrow();
        assertEquals(
            "图形网格图标 " + hostileChild + " 加入 部件图标 " + hostileParent,
            literalLabel.fallbackText()
        );
        assertTrue(literalLabel.runs().stream()
            .filter(UiInlineLabel.TextRun.class::isInstance)
            .map(UiInlineLabel.TextRun.class::cast)
            .anyMatch(run -> run.text().contains(hostileChild)));
    }

    private static HistoryRelationChange relation(
        final HistoryRelationChange.Kind kind,
        final HistoryRelationChange.Endpoint before,
        final HistoryRelationChange.Endpoint after
    ) {
        return new HistoryRelationChange(kind, before, after);
    }

    private static HistoryRelationChange.Endpoint targetEndpoint(final HistoryTarget target) {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.TARGET,
            Optional.of(target)
        );
    }

    private static HistoryRelationChange.Endpoint rootEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.ROOT,
            Optional.empty()
        );
    }

    private static HistoryRelationChange.Endpoint unknownEndpoint() {
        return new HistoryRelationChange.Endpoint(
            HistoryRelationChange.State.UNKNOWN,
            Optional.empty()
        );
    }

    private static HistoryTarget target(final String type, final String id, final String name) {
        return new HistoryTarget(type, Optional.of(id), Optional.of(name));
    }

    private static List<CubismIcon> icons(final UiInlineLabel label) {
        return label.runs().stream()
            .filter(UiInlineLabel.IconRun.class::isInstance)
            .map(UiInlineLabel.IconRun.class::cast)
            .map(run -> run.icon().icon())
            .toList();
    }

    private static boolean withinInlineBounds(final UiInlineLabel.Run run) {
        final String text = run instanceof UiInlineLabel.TextRun textRun
            ? textRun.text()
            : ((UiInlineLabel.IconRun) run).fallbackText();
        if (text.length() > UiInlineLabel.MAX_RUN_TEXT_LENGTH) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (Character.isHighSurrogate(character)
                && (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1)))) {
                return false;
            }
            if (Character.isLowSurrogate(character)
                && (index == 0 || !Character.isHighSurrogate(text.charAt(index - 1)))) {
                return false;
            }
        }
        return true;
    }

    private static PluginLocalization catalog(final String fileName, final Locale locale) {
        final String resource = CATALOG_PREFIX + fileName;
        try (InputStream stream = HistoryRelationLabelFormatterTest.class
            .getClassLoader()
            .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing catalog resource " + resource);
            }
            final Properties properties = new Properties();
            properties.load(new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            return new CatalogLocalization(locale, properties);
        } catch (IOException exception) {
            throw new AssertionError("could not read catalog resource " + resource, exception);
        }
    }

    private record CatalogLocalization(Locale locale, Properties properties) implements PluginLocalization {
        @Override
        public String text(final String key) {
            final String value = properties.getProperty(key);
            if (value == null) {
                throw new AssertionError("missing localization key " + key);
            }
            return value;
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return new MessageFormat(text(key), locale).format(arguments);
        }

        @Override
        public boolean contains(final String key) {
            return properties.containsKey(key);
        }
    }
}
