package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorParameterGroupsReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/** Verified projection of the Editor parameter-group hierarchy. */
final class EditorParameterGroupsAccess {

    private final VerifiedMemberResolver resolver;
    private final BiConsumer<String, Object> modelGuard;
    private final EditorParameterStructureAccess structureAccess;

    EditorParameterGroupsAccess(
        final VerifiedMemberResolver resolver,
        final BiConsumer<String, Object> modelGuard,
        final EditorParameterStructureAccess structureAccess
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
        this.structureAccess = Objects.requireNonNull(structureAccess, "structureAccess");
    }

    ParameterGroups groups(
        final String identity,
        final Object source,
        final Object model
    ) {
        return new EditorParameterGroups(identity, source, model);
    }

    private Object rootGroup(final Object source) {
        if (!resolver.authorizesFeature(
            EditorParameterGroupsReadSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterGroupsReadSelectorContract.CAPABILITY_ID,
            EditorParameterGroupsReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Parameter-group access is unavailable without exact verified host evidence."
            );
        }
        final Object root = resolver.invoke(
            "cubism.editor-model.model-source.root-parameter-group", source
        );
        if (!resolver.isInstance("cubism.editor-model.parameter-group.class", root)) {
            throw unavailable("Editor root parameter group is unavailable.");
        }
        return root;
    }

    private void addGroups(
        final String identity,
        final Object source,
        final Object model,
        final Object group,
        final List<ParameterGroup> groups,
        final Set<Object> identities,
        final Set<ParameterGroupId> ids
    ) {
        if (!identities.add(group)) {
            throw unavailable("Editor parameter group hierarchy contains a cycle.");
        }
        final ParameterGroupId id = groupId(group);
        if (!ids.add(id)) {
            throw unavailable("Editor parameter group identifiers are not unique.");
        }
        groups.add(new EditorParameterGroup(identity, source, model, group));
        for (Object child : children(group)) {
            if (resolver.isInstance("cubism.editor-model.parameter-group.class", child)) {
                addGroups(identity, source, model, child, groups, identities, ids);
            }
        }
    }

    private boolean treeContains(final Object source, final Object expected) {
        final Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ParameterGroupId> ids = new HashSet<>();
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        boolean found = false;
        pending.add(rootGroup(source));
        while (!pending.isEmpty()) {
            final Object candidate = pending.removeFirst();
            if (!identities.add(candidate)) {
                throw unavailable("Editor parameter group hierarchy contains a cycle.");
            }
            if (!ids.add(groupId(candidate))) {
                throw unavailable("Editor parameter group identifiers are not unique.");
            }
            found |= candidate == expected;
            for (Object child : children(candidate)) {
                if (resolver.isInstance("cubism.editor-model.parameter-group.class", child)) {
                    pending.addLast(child);
                }
            }
        }
        return found;
    }

    private List<Object> children(final Object group) {
        final Object raw = resolver.invoke("cubism.editor-model.parameter-group.children", group);
        if (!(raw instanceof List<?> children)) {
            throw unavailable("Editor parameter group children are unavailable.");
        }
        return List.copyOf(children);
    }

    private ParameterGroupId groupId(final Object group) {
        final Object rawId = resolver.invoke("cubism.editor-model.parameter-group.id", group);
        return new ParameterGroupId(text(resolver.invoke("cubism.editor-model.id.value", rawId)));
    }

    private final class EditorParameterGroups implements ParameterGroups {
        private final String identity;
        private final Object source;
        private final Object model;

        private EditorParameterGroups(
            final String identity,
            final Object source,
            final Object model
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
        }

        private void current() {
            modelGuard.accept(identity, model);
        }

        @Override
        public List<ParameterGroup> all() {
            current();
            final List<ParameterGroup> groups = new ArrayList<>();
            addGroups(
                identity,
                source,
                model,
                rootGroup(source),
                groups,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new HashSet<>()
            );
            return List.copyOf(groups);
        }

        @Override
        public ParameterGroup root() {
            current();
            return new EditorParameterGroup(identity, source, model, rootGroup(source));
        }

        @Override
        public ParameterGroup find(final ParameterGroupId id) {
            Objects.requireNonNull(id, "id");
            return all().stream()
                .filter(group -> group.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                    "Cubism parameter group is absent: " + id.value()
                ));
        }
        /**
         * Creates a new parameter folder as the last child of the root group, through the host's own
         * undoable command ("Turboism: Create Parameter Folder").
         *
         * <p>The model is checked for currency before and after the write, so the call fails rather
         * than mutating a model the Editor has since replaced. Runs on the Cubism host thread.
         *
         * @param name the folder name, non-null and non-blank
         * @return a live handle to the newly created group
         * @throws NullPointerException          if {@code name} is null
         * @throws IllegalArgumentException      if {@code name} is blank
         * @throws UnsupportedOperationException if the host lacks the exact verified evidence this
         *                                       slice requires
         * @throws NoSuchElementException        if the created group cannot be found afterwards
         */
        public ParameterGroup addGroup(final String name) {
            current();
            final ParameterGroupId created = structureAccess.addGroup(identity, source, model, name);
            return new EditorParameterGroup(identity, source, model, requireGroupById(created));
        }

        @Override
        public void removeGroup(final ParameterGroupId id) {
            current();
            structureAccess.removeGroup(identity, source, model, id);
        }

        @Override
        public void moveParameter(final ParameterId parameterId, final ParameterGroupId targetGroupId) {
            current();
            structureAccess.moveParameter(identity, source, model, parameterId, targetGroupId);
        }

        private Object requireGroupById(final ParameterGroupId id) {
            final java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
            pending.add(rootGroup(source));
            while (!pending.isEmpty()) {
                final Object candidate = pending.removeFirst();
                if (groupId(candidate).equals(id)) return candidate;
                for (Object child : children(candidate)) {
                    if (resolver.isInstance("cubism.editor-model.parameter-group.class", child)) {
                        pending.addLast(child);
                    }
                }
            }
            throw new NoSuchElementException("Cubism parameter group is absent: " + id.value());
        }
    }

    private final class EditorParameterGroup implements ParameterGroup {
        private final String identity;
        private final Object source;
        private final Object model;
        private final Object group;

        private EditorParameterGroup(
            final String identity,
            final Object source,
            final Object model,
            final Object group
        ) {
            this.identity = identity;
            this.source = source;
            this.model = model;
            this.group = group;
        }

        private void current() {
            modelGuard.accept(identity, model);
            if (!resolver.isInstance("cubism.editor-model.parameter-group.class", group)
                || !treeContains(source, group)) {
                throw unavailable("Editor parameter group is unavailable.");
            }
        }

        @Override
        public ParameterGroupId id() {
            current();
            return groupId(group);
        }

        @Override
        public Optional<String> name() {
            current();
            final Object value = resolver.invoke("cubism.editor-model.parameter-group.name", group);
            if (value == null) {
                return Optional.empty();
            }
            if (!(value instanceof String text)) {
                throw unavailable("Editor parameter group name is invalid.");
            }
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        }

        @Override
        public Optional<ParameterGroupId> parentId() {
            current();
            final Object parent = resolver.invoke(
                "cubism.editor-model.parameter-group.parent", group
            );
            if (parent == null) {
                return Optional.empty();
            }
            if (!resolver.isInstance("cubism.editor-model.parameter-group.class", parent)) {
                throw unavailable("Editor parent parameter group is invalid.");
            }
            return Optional.of(groupId(parent));
        }

        @Override
        public List<ParameterGroupId> childGroupIds() {
            current();
            return children(group).stream()
                .filter(child -> resolver.isInstance(
                    "cubism.editor-model.parameter-group.class", child
                ))
                .map(EditorParameterGroupsAccess.this::groupId)
                .toList();
        }

        @Override
        public List<ParameterId> parameterIds() {
            current();
            return children(group).stream()
                .filter(child -> resolver.isInstance(
                    "cubism.editor-model.parameter-source.class", child
                ))
                .map(child -> new ParameterId(text(resolver.invoke(
                    "cubism.editor-model.id.value",
                    resolver.invoke("cubism.editor-model.parameter-source.id", child)
                ))))
                .toList();
        }

        @Override
        public void rename(final String name) {
            current();
            structureAccess.renameGroup(identity, source, model, id(), name);
        }
    }

    private static String text(final Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw unavailable("Verified Editor identity is unavailable.");
        }
        return text;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
