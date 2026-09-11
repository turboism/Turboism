package dev.turboism.plugin.historypanel.service;

import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds concise typed labels for one captured direct history relation. */
final class HistoryRelationLabelFormatter {
    private final PluginLocalization localization;

    HistoryRelationLabelFormatter(final PluginLocalization localization) {
        this.localization = Objects.requireNonNull(localization, "localization");
    }

    /**
     * Formats one SDK-captured direct relation transition. Verified ROOT supports ROOT→TARGET
     * joins and TARGET→ROOT detaches; UNKNOWN and incomplete endpoint transitions remain
     * conservative fallbacks.
     */
    Optional<UiInlineLabel> format(
        final HistoryRelationChange relation,
        final HistoryTarget child
    ) {
        if (relation == null
            || relation.kind() == null
            || !validTarget(child)
            || !validEndpoint(relation.before())
            || !validEndpoint(relation.after())) {
            return Optional.empty();
        }

        final HistoryRelationChange.Endpoint before = relation.before();
        final HistoryRelationChange.Endpoint after = relation.after();
        if (after.state() == HistoryRelationChange.State.TARGET) {
            final HistoryTarget newParent = after.target().orElseThrow();
            final ChangeKind changeKind = relation.kind() == HistoryRelationChange.Kind.PART_MEMBERSHIP
                ? ChangeKind.JOIN
                : ChangeKind.SET;
            if (before.state() == HistoryRelationChange.State.ROOT) {
                return render(relation.kind(), changeKind, child, newParent);
            }
            if (before.state() == HistoryRelationChange.State.TARGET) {
                final HistoryTarget oldParent = before.target().orElseThrow();
                if (!validRelation(relation.kind(), child, oldParent)
                    || sameIdentity(child, oldParent)
                    || sameIdentity(oldParent, newParent)) {
                    return Optional.empty();
                }
                return render(relation.kind(), changeKind, child, newParent);
            }
        }
        if (before.state() == HistoryRelationChange.State.TARGET
            && after.state() == HistoryRelationChange.State.ROOT) {
            final HistoryTarget oldParent = before.target().orElseThrow();
            if (!validRelation(relation.kind(), child, oldParent) || sameIdentity(child, oldParent)) {
                return Optional.empty();
            }
            return render(relation.kind(), ChangeKind.DETACH, child, oldParent);
        }
        return Optional.empty();
    }

    private Optional<UiInlineLabel> render(
        final HistoryRelationChange.Kind relationKind,
        final ChangeKind changeKind,
        final HistoryTarget child,
        final HistoryTarget parent
    ) {
        if (!validTarget(parent)
            || sameIdentity(child, parent)
            || !validRelation(relationKind, child, parent)) {
            return Optional.empty();
        }
        if (changeKind == ChangeKind.SET && relationKind != HistoryRelationChange.Kind.DEFORMER_PARENT) {
            return Optional.empty();
        }
        if (changeKind == ChangeKind.JOIN && relationKind != HistoryRelationChange.Kind.PART_MEMBERSHIP) {
            return Optional.empty();
        }

        final IconSpec childIcon = iconFor(child.type()).orElseThrow();
        final IconSpec parentIcon = iconFor(parent.type()).orElseThrow();
        final List<UiInlineLabel.Run> runs = new ArrayList<>();
        runs.add(UiInlineLabel.iconRun(
            new UiIconRef(childIcon.icon()),
            localization.text(childIcon.fallbackKey())
        ));
        final String childName = child.displayName().orElseThrow();
        final String pattern = localization.text(phrase(relationKind, changeKind));
        final int parentSlot = pattern.indexOf(PARENT_SLOT);
        if (parentSlot < 0) {
            return Optional.empty();
        }
        // One localized pattern per phrase carries the whole wording, so a language that needs no
        // trailing words does not have to store a blank catalog value. The parent-name slot splits
        // it into the text after the child and the text after the parent.
        final String infix = pattern.substring(0, parentSlot)
            .replace(PARTICLE_SLOT, koreanObjectParticle(childName));
        final String suffix = pattern.substring(parentSlot + PARENT_SLOT.length());
        HistoryPanelService.appendBoundedTextRuns(runs, " " + childName + infix);
        runs.add(UiInlineLabel.iconRun(
            new UiIconRef(parentIcon.icon()),
            localization.text(parentIcon.fallbackKey())
        ));
        HistoryPanelService.appendBoundedTextRuns(
            runs,
            " " + parent.displayName().orElseThrow() + suffix
        );
        return Optional.of(UiInlineLabel.of(runs));
    }

    private static String phrase(
        final HistoryRelationChange.Kind relationKind,
        final ChangeKind changeKind
    ) {
        return switch (changeKind) {
            case SET -> "history.relation.deformer-parent.set";
            case JOIN -> "history.relation.part-membership.join";
            case DETACH -> relationKind == HistoryRelationChange.Kind.DEFORMER_PARENT
                ? "history.relation.deformer-parent.detach"
                : "history.relation.part-membership.detach";
        };
    }

    private String koreanObjectParticle(final String childName) {
        if (localization.locale() == null || !"ko".equalsIgnoreCase(localization.locale().getLanguage())) {
            return "";
        }
        final int lastCodePoint = childName.codePointBefore(childName.length());
        if (lastCodePoint >= 0xAC00 && lastCodePoint <= 0xD7A3) {
            return (lastCodePoint - 0xAC00) % 28 == 0 ? "를" : "을";
        }
        if (lastCodePoint >= 0x11A8 && lastCodePoint <= 0x11FF) {
            return "을";
        }
        return "를";
    }

    private static boolean validEndpoint(final HistoryRelationChange.Endpoint endpoint) {
        if (endpoint == null || endpoint.state() == null || endpoint.target() == null) {
            return false;
        }
        return switch (endpoint.state()) {
            case TARGET -> endpoint.target().filter(HistoryRelationLabelFormatter::validTarget).isPresent();
            case ROOT, UNKNOWN -> endpoint.target().isEmpty();
        };
    }

    private static boolean validTarget(final HistoryTarget target) {
        return target != null
            && target.type() != null
            && target.id() != null
            && target.id().filter(value -> !value.isBlank()).isPresent()
            && target.displayName() != null
            && target.displayName().filter(value -> !value.isBlank()).isPresent()
            && iconFor(target.type()).isPresent();
    }

    private static boolean sameIdentity(final HistoryTarget left, final HistoryTarget right) {
        return left.type().equals(right.type())
            && left.id().orElseThrow().equals(right.id().orElseThrow());
    }

    private static boolean validRelation(
        final HistoryRelationChange.Kind relationKind,
        final HistoryTarget child,
        final HistoryTarget parent
    ) {
        return switch (relationKind) {
            case DEFORMER_PARENT -> isDeformerChild(child.type()) && isDeformerParent(parent.type());
            case PART_MEMBERSHIP -> isPartChild(child.type()) && parent.type().equals("PART");
        };
    }

    private static boolean isDeformerChild(final String type) {
        return type.equals("ART_MESH")
            || type.equals("WARP_DEFORMER")
            || type.equals("ROTATION_DEFORMER");
    }

    private static boolean isDeformerParent(final String type) {
        return type.equals("WARP_DEFORMER") || type.equals("ROTATION_DEFORMER");
    }

    private static boolean isPartChild(final String type) {
        return isDeformerChild(type) || type.equals("PART");
    }

    private static Optional<IconSpec> iconFor(final String type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case "ART_MESH" -> Optional.of(new IconSpec(CubismIcon.ART_MESH, "history.icon.art-mesh"));
            case "WARP_DEFORMER" -> Optional.of(
                new IconSpec(CubismIcon.WARP_DEFORMER, "history.icon.warp-deformer")
            );
            case "ROTATION_DEFORMER" -> Optional.of(
                new IconSpec(CubismIcon.ROTATION_DEFORMER, "history.icon.rotation-deformer")
            );
            case "PART" -> Optional.of(new IconSpec(CubismIcon.PART, "history.icon.part"));
            default -> Optional.empty();
        };
    }

    private record IconSpec(CubismIcon icon, String fallbackKey) { }

    /** Argument marker for the language-specific object particle; empty outside Korean. */
    private static final String PARTICLE_SLOT = "{0}";
    /** Argument marker for the parent name, which is rendered as its own icon run. */
    private static final String PARENT_SLOT = "{1}";

    private enum ChangeKind {
        SET,
        JOIN,
        DETACH
    }
}
