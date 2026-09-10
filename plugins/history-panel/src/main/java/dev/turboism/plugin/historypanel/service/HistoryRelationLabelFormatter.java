package dev.turboism.plugin.historypanel.service;

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
     * Formats a frozen direct relation transition without depending on the SDK relation DTO.
     * Verified ROOT supports ROOT→TARGET joins and TARGET→ROOT detaches; UNKNOWN and other
     * and all other incomplete endpoint transitions remain conservative fallbacks.
     */
    Optional<UiInlineLabel> format(
        final RelationKind relationKind,
        final HistoryTarget child,
        final Endpoint before,
        final Endpoint after
    ) {
        if (relationKind == null
            || !validTarget(child)
            || !validEndpoint(before)
            || !validEndpoint(after)) {
            return Optional.empty();
        }

        if (after.state() == State.TARGET) {
            final HistoryTarget newParent = after.target().orElseThrow();
            final ChangeKind changeKind = relationKind == RelationKind.PART_MEMBERSHIP
                ? ChangeKind.JOIN
                : ChangeKind.SET;
            if (before.state() == State.ROOT) {
                return render(relationKind, changeKind, child, newParent);
            }
            if (before.state() == State.TARGET) {
                final HistoryTarget oldParent = before.target().orElseThrow();
                if (!validRelation(relationKind, child, oldParent)
                    || sameIdentity(child, oldParent)
                    || sameIdentity(oldParent, newParent)) {
                    return Optional.empty();
                }
                return render(relationKind, changeKind, child, newParent);
            }
        }
        if (before.state() == State.TARGET && after.state() == State.ROOT) {
            final HistoryTarget oldParent = before.target().orElseThrow();
            if (!validRelation(relationKind, child, oldParent) || sameIdentity(child, oldParent)) {
                return Optional.empty();
            }
            return render(relationKind, ChangeKind.DETACH, child, oldParent);
        }
        return Optional.empty();
    }

    private Optional<UiInlineLabel> render(
        final RelationKind relationKind,
        final ChangeKind changeKind,
        final HistoryTarget child,
        final HistoryTarget parent
    ) {
        if (!validTarget(parent)
            || sameIdentity(child, parent)
            || !validRelation(relationKind, child, parent)) {
            return Optional.empty();
        }
        if (changeKind == ChangeKind.SET && relationKind != RelationKind.DEFORMER_PARENT) {
            return Optional.empty();
        }
        if (changeKind == ChangeKind.JOIN && relationKind != RelationKind.PART_MEMBERSHIP) {
            return Optional.empty();
        }

        final IconSpec childIcon = iconFor(child.type()).orElseThrow();
        final IconSpec parentIcon = iconFor(parent.type()).orElseThrow();
        final List<UiInlineLabel.Run> runs = new ArrayList<>();
        runs.add(UiInlineLabel.iconRun(
            new UiIconRef(childIcon.icon()),
            localization.text(childIcon.fallbackKey())
        ));
        HistoryPanelService.appendBoundedTextRuns(
            runs,
            " " + child.displayName().orElseThrow() + " " + localization.text(middleKey(relationKind, changeKind)) + " "
        );
        runs.add(UiInlineLabel.iconRun(
            new UiIconRef(parentIcon.icon()),
            localization.text(parentIcon.fallbackKey())
        ));
        final String suffix = changeKind == ChangeKind.SET
            ? " " + localization.text("history.relation.deformer-parent.set.suffix")
            : "";
        HistoryPanelService.appendBoundedTextRuns(
            runs,
            " " + parent.displayName().orElseThrow() + suffix
        );
        return Optional.of(UiInlineLabel.of(runs));
    }

    private static String middleKey(final RelationKind relationKind, final ChangeKind changeKind) {
        return switch (changeKind) {
            case SET -> "history.relation.deformer-parent.set.infix";
            case JOIN -> "history.relation.part-membership.join.infix";
            case DETACH -> relationKind == RelationKind.DEFORMER_PARENT
                ? "history.relation.deformer-parent.detach.infix"
                : "history.relation.part-membership.detach.infix";
        };
    }

    private static boolean validEndpoint(final Endpoint endpoint) {
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
        final RelationKind relationKind,
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

    record Endpoint(State state, Optional<HistoryTarget> target) {
        Endpoint {
            target = target == null ? Optional.empty() : target;
        }

        static Endpoint target(final HistoryTarget target) {
            return new Endpoint(State.TARGET, Optional.ofNullable(target));
        }

        static Endpoint root() {
            return new Endpoint(State.ROOT, Optional.empty());
        }

        static Endpoint unknown() {
            return new Endpoint(State.UNKNOWN, Optional.empty());
        }
    }

    enum State {
        TARGET,
        ROOT,
        UNKNOWN
    }

    enum RelationKind {
        DEFORMER_PARENT,
        PART_MEMBERSHIP
    }

    private enum ChangeKind {
        SET,
        JOIN,
        DETACH
    }
}
