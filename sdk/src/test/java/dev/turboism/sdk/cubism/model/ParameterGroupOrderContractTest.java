package dev.turboism.sdk.cubism.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParameterGroupOrderContractTest {
    final ParameterGroup group = new ParameterGroup() {
        public ParameterGroupId id() {
            return new ParameterGroupId("root");
        }

        public Optional<String> name() {
            return Optional.empty();
        }

        public Optional<ParameterGroupId> parentId() {
            return Optional.empty();
        }

        public List<ParameterGroupId> childGroupIds() {
            return List.of(new ParameterGroupId("folder"));
        }

        public List<ParameterId> parameterIds() {
            return List.of(new ParameterId("P"));
        }
    };

    @Test
    void legacyBackendDoesNotInventMixedOrderOrDiscardAnExplicitIndex() {
        assertThrows(UnsupportedOperationException.class, group::orderedChildren);
        final boolean[] appended = {false};
        final ParameterGroups groups = new ParameterGroups() {
            public List<ParameterGroup> all() {
                return List.of(group);
            }

            public ParameterGroup root() {
                return group;
            }

            public ParameterGroup find(ParameterGroupId id) {
                return group;
            }

            public void moveParameter(ParameterId id, ParameterGroupId target) {
                appended[0] = true;
            }
        };
        assertThrows(
                UnsupportedOperationException.class, () -> groups.moveParameter(new ParameterId("P"), group.id(), 0));
        assertThrows(IllegalArgumentException.class, () -> groups.moveParameter(new ParameterId("P"), group.id(), -1));
        assertFalse(appended[0]);
    }

    @Test
    void entryKindDisambiguatesSameTextIdAndPreservesIdentityExactly() {
        final var folder = ParameterGroupChild.group(new ParameterGroupId("same"));
        final var parameter = ParameterGroupChild.parameter(new ParameterId("same"));
        assertNotEquals(folder, parameter);
        assertEquals("same", folder.id());
        assertEquals(
                " P ", ParameterGroupChild.parameter(new ParameterId(" P ")).id());
        assertThrows(NullPointerException.class, () -> new ParameterGroupChild(null, "id"));
        assertThrows(NullPointerException.class, () -> ParameterGroupChild.parameter(null));
        assertThrows(
                IllegalArgumentException.class, () -> new ParameterGroupChild(ParameterGroupChild.Kind.GROUP, " "));
    }
}
