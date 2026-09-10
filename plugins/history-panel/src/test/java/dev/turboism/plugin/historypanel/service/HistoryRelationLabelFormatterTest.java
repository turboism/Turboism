package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryRelationLabelFormatterTest {
    private static final PluginLocalization LOCALIZATION = new ChineseLocalization();

    @Test
    void formatsDeformerParentWithTypedChildAndParentIcons() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel artMeshToWarp = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            target("ART_MESH", "mesh-a", "A"),
            HistoryRelationLabelFormatter.Endpoint.target(target("WARP_DEFORMER", "old", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("WARP_DEFORMER", "warp-b", "B"))
        ).orElseThrow();
        final UiInlineLabel warpToRotation = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            target("WARP_DEFORMER", "warp-a", "A2"),
            HistoryRelationLabelFormatter.Endpoint.target(target("WARP_DEFORMER", "old-2", "Old 2")),
            HistoryRelationLabelFormatter.Endpoint.target(target("ROTATION_DEFORMER", "rotation-b", "B2"))
        ).orElseThrow();

        assertEquals("图形网格图标 A 设置为 弯曲变形器图标 B 的子级", artMeshToWarp.fallbackText());
        assertEquals("弯曲变形器图标 A2 设置为 旋转变形器图标 B2 的子级", warpToRotation.fallbackText());
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
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ART_MESH", "mesh-a", "A"),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-part", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-c", "C"))
        ).orElseThrow();
        final UiInlineLabel rotationMembership = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ROTATION_DEFORMER", "rotation-d", "D"),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-part-2", "Old 2")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-e", "E"))
        ).orElseThrow();
        final UiInlineLabel partMembership = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("PART", "part-child", "Child part"),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-part-3", "Old 3")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-parent", "Parent part"))
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
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            target("ART_MESH", "mesh-a", "A"),
            HistoryRelationLabelFormatter.Endpoint.target(
                target("WARP_DEFORMER", "old-parent", "Old parent")
            ),
            HistoryRelationLabelFormatter.Endpoint.root()
        ).orElseThrow();
        final UiInlineLabel partDetach = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("WARP_DEFORMER", "warp-a", "A2"),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-part", "Old part")),
            HistoryRelationLabelFormatter.Endpoint.root()
        ).orElseThrow();

        assertEquals(
            "图形网格图标 A 移出变形器 弯曲变形器图标 Old parent",
            deformerDetach.fallbackText()
        );
        assertEquals(
            "弯曲变形器图标 A2 移出部件 部件图标 Old part",
            partDetach.fallbackText()
        );
    }

    @Test
    void formatsVerifiedRootToTargetJoinWithoutInventingRootName() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);

        final UiInlineLabel deformerJoin = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            target("ART_MESH", "mesh-a", "A"),
            HistoryRelationLabelFormatter.Endpoint.root(),
            HistoryRelationLabelFormatter.Endpoint.target(target("WARP_DEFORMER", "warp-b", "B"))
        ).orElseThrow();
        final UiInlineLabel partJoin = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ART_MESH", "mesh-c", "C"),
            HistoryRelationLabelFormatter.Endpoint.root(),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-d", "D"))
        ).orElseThrow();

        assertEquals("图形网格图标 A 设置为 弯曲变形器图标 B 的子级", deformerJoin.fallbackText());
        assertEquals("图形网格图标 C 加入 部件图标 D", partJoin.fallbackText());
        assertFalse(deformerJoin.fallbackText().contains("root"));
        assertFalse(partJoin.fallbackText().contains("root"));
    }


    @Test
    void rejectsUnknownStatesInvalidOldParentsAndNonChanges() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);
        final HistoryTarget mesh = target("ART_MESH", "mesh-a", "A");
        final HistoryTarget warp = target("WARP_DEFORMER", "warp-b", "B");
        final HistoryTarget part = target("PART", "part-c", "C");
        final HistoryRelationLabelFormatter.Endpoint targetWarp =
            HistoryRelationLabelFormatter.Endpoint.target(warp);
        final HistoryRelationLabelFormatter.Endpoint targetPart =
            HistoryRelationLabelFormatter.Endpoint.target(part);

        assertTrue(formatter.format(null, mesh, targetWarp, targetWarp).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            HistoryRelationLabelFormatter.Endpoint.unknown(),
            targetWarp
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            targetWarp,
            HistoryRelationLabelFormatter.Endpoint.unknown()
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            HistoryRelationLabelFormatter.Endpoint.root(),
            targetWarp
        ).isPresent());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            new HistoryRelationLabelFormatter.Endpoint(
                HistoryRelationLabelFormatter.State.ROOT,
                Optional.of(warp)
            ),
            HistoryRelationLabelFormatter.Endpoint.root()
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            new HistoryRelationLabelFormatter.Endpoint(
                HistoryRelationLabelFormatter.State.TARGET,
                Optional.empty()
            ),
            targetWarp
        ).isEmpty());

        // The old endpoint must be a legal direct parent for the same relation family.
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            mesh,
            targetPart,
            targetWarp
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            mesh,
            targetWarp,
            targetPart
        ).isEmpty());

        // A same-parent rename/reorder is not a reparent: compare the captured identity, not name.
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            mesh,
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "same", "Before name")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "same", "After name"))
        ).isEmpty());

        // Same-type child/parent identity is self and must be rejected.
        final HistoryTarget partChild = target("PART", "self", "Self");
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            partChild,
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "self", "Renamed self"))
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            partChild,
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "self", "Old self")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "new", "New"))
        ).isEmpty());

        // IDs are namespaced by target type: an Artmesh and Part may share an ID legitimately.
        final UiInlineLabel crossTypeIdentity = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ART_MESH", "shared", "Mesh"),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-part", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "shared", "Shared part"))
        ).orElseThrow();
        assertEquals("图形网格图标 Mesh 加入 部件图标 Shared part", crossTypeIdentity.fallbackText());

        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.DEFORMER_PARENT,
            target("UNKNOWN", "unknown", "Unknown"),
            targetWarp,
            targetWarp
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            new HistoryTarget("ART_MESH", Optional.empty(), Optional.of("A")),
            targetPart,
            targetPart
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            new HistoryTarget("ART_MESH", Optional.of("mesh-a"), Optional.empty()),
            targetPart,
            targetPart
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            null,
            targetPart,
            targetPart
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            mesh,
            null,
            targetPart
        ).isEmpty());
        assertTrue(formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            mesh,
            targetPart,
            null
        ).isEmpty());
    }

    @Test
    void preservesLiteralNamesAndMaxSupplementaryNamesWithinInlineBounds() {
        final HistoryRelationLabelFormatter formatter = new HistoryRelationLabelFormatter(LOCALIZATION);
        final String maxName = "💠".repeat(128);
        assertEquals(256, maxName.length());

        final UiInlineLabel maxLabel = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ART_MESH", "mesh-max", maxName),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-max", maxName))
        ).orElseThrow();
        final String expectedMaxFallback = "图形网格图标 " + maxName + " 加入 部件图标 " + maxName;
        assertEquals(expectedMaxFallback, maxLabel.fallbackText());
        assertTrue(maxLabel.fallbackText().length() <= UiInlineLabel.MAX_TOTAL_TEXT_LENGTH);
        assertTrue(maxLabel.runs().size() <= UiInlineLabel.MAX_RUNS);
        assertTrue(maxLabel.runs().stream().allMatch(HistoryRelationLabelFormatterTest::withinInlineBounds));

        final String hostileChild = "<mesh>&\"";
        final String hostileParent = "parent <literal>&";
        final UiInlineLabel literalLabel = formatter.format(
            HistoryRelationLabelFormatter.RelationKind.PART_MEMBERSHIP,
            target("ART_MESH", "mesh-literal", hostileChild),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "old-literal", "Old")),
            HistoryRelationLabelFormatter.Endpoint.target(target("PART", "part-literal", hostileParent))
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

    private static final class ChineseLocalization implements PluginLocalization {
        @Override
        public Locale locale() {
            return Locale.SIMPLIFIED_CHINESE;
        }

        @Override
        public String text(final String key) {
            return switch (key) {
                case "history.icon.art-mesh" -> "图形网格图标";
                case "history.icon.warp-deformer" -> "弯曲变形器图标";
                case "history.icon.rotation-deformer" -> "旋转变形器图标";
                case "history.icon.part" -> "部件图标";
                case "history.relation.deformer-parent.set.infix" -> "设置为";
                case "history.relation.deformer-parent.set.suffix" -> "的子级";
                case "history.relation.part-membership.join.infix" -> "加入";
                case "history.relation.deformer-parent.detach.infix" -> "移出变形器";
                case "history.relation.part-membership.detach.infix" -> "移出部件";
                default -> throw new AssertionError("unexpected localization key: " + key);
            };
        }

        @Override
        public String format(final String key, final Object... arguments) {
            return text(key);
        }

        @Override
        public boolean contains(final String key) {
            return true;
        }
    }
}
