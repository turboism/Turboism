package dev.turboism.sdk.ui.context;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.permission.RequiresPermission;
import dev.turboism.sdk.plugin.Registration;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

@PreviewApi
@RequiresPermission("turboism.ui.context-menu.contribute")
public interface ContextMenuRegistry {

    Registration contribute(ContextMenuContribution contribution);

    enum Location {
        DEFORMER_TAB(EnumSet.of(ObjectKind.WARP_DEFORMER, ObjectKind.ROTATION_DEFORMER, ObjectKind.ART_MESH)),
        PARAMETER_TAB(EnumSet.of(ObjectKind.PARAMETER, ObjectKind.PARAMETER_FOLDER)),
        PART_TAB(EnumSet.of(
            ObjectKind.PART,
            ObjectKind.PART_FOLDER,
            ObjectKind.GLUE,
            ObjectKind.WARP_DEFORMER,
            ObjectKind.ROTATION_DEFORMER,
            ObjectKind.ART_MESH
        )),
        WORKSPACE_OBJECT(EnumSet.allOf(ObjectKind.class));

        private final Set<ObjectKind> supportedKinds;

        Location(final Set<ObjectKind> supportedKinds) {
            this.supportedKinds = Set.copyOf(supportedKinds);
        }

        public Set<ObjectKind> supportedKinds() {
            return supportedKinds;
        }

        static Location legacy(final String context) {
            return switch (Objects.requireNonNull(context, "context").trim().toLowerCase()) {
                case "deformer" -> DEFORMER_TAB;
                case "parameter" -> PARAMETER_TAB;
                case "part", "parts" -> PART_TAB;
                case "workspace" -> WORKSPACE_OBJECT;
                default -> throw new IllegalArgumentException("unsupported context-menu location: " + context);
            };
        }
    }

    enum ObjectKind {
        WARP_DEFORMER,
        ROTATION_DEFORMER,
        ART_MESH,
        PART,
        PART_FOLDER,
        GLUE,
        PARAMETER,
        PARAMETER_FOLDER
    }

    record ContextMenuContribution(
        String id,
        String actionId,
        String label,
        String icon,
        Location location,
        Set<ObjectKind> objectKinds,
        int priority
    ) {
        public ContextMenuContribution {
            id = requireText(id, "id");
            actionId = requireText(actionId, "actionId");
            label = requireText(label, "label");
            location = Objects.requireNonNull(location, "location");
            objectKinds = Set.copyOf(Objects.requireNonNull(objectKinds, "objectKinds"));
            if (objectKinds.isEmpty()) {
                throw new IllegalArgumentException("objectKinds must not be empty");
            }
            if (!location.supportedKinds().containsAll(objectKinds)) {
                throw new IllegalArgumentException("objectKinds are not valid for " + location);
            }
        }

        /** Compatibility constructor for the earlier context-string Preview shape. */
        public ContextMenuContribution(
            final String id,
            final String label,
            final String icon,
            final String context,
            final int priority
        ) {
            this(
                id,
                id,
                label,
                icon,
                Location.legacy(context),
                Location.legacy(context).supportedKinds(),
                priority
            );
        }

        /** Compatibility view of the earlier Preview context string. */
        public String context() {
            return switch (location) {
                case DEFORMER_TAB -> "deformer";
                case PARAMETER_TAB -> "parameter";
                case PART_TAB -> "part";
                case WORKSPACE_OBJECT -> "workspace";
            };
        }

        private static String requireText(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
