package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorPartBasicSettingsSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorPartNameAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsLocalNameAndFallsBackToIdText(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertEquals("Clipping Part", part.name());
        fixture.partSource.localName = "";
        assertEquals("PartClip", part.name());
        assertThrows(UnsupportedOperationException.class, part::parentId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void writesAuthoringNameWithUndoDirtyRefreshAndNoChangeElision(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        part.setName("Renamed Part");

        assertEquals("Renamed Part", fixture.partSource.localName);
        assertEquals(1, fixture.editMode.edits.size());
        assertEquals(1, fixture.source.updateCount);
        assertEquals(1, fixture.pack.partRefreshCount);
        assertEquals(1, fixture.pack.repaintCount);
        assertEquals(true, fixture.document.dirty);

        part.setName("Renamed Part");
        assertEquals(1, fixture.editMode.edits.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsAndWritesBasicSettingsAndModelMetadata(final String cubismVersion) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true, cubismVersion), "session-a");
        final var model = access.active();
        final var part = model.parts().find(new PartId("PartClip"));

        assertEquals("Fixture Model", model.name());
        assertEquals(Optional.of("Clipping Part"), part.shortName());
        assertEquals(true, part.visible());
        assertEquals(false, part.locked());
        assertEquals(true, part.visibleInHierarchy());
        assertEquals(false, part.lockedInHierarchy());
        assertEquals(Optional.of(new Color(0.1F, 0.2F, 0.3F, 0.4F)), part.editColor());
        assertEquals(false, part.sketch());
        assertEquals(0, part.defaultOrder());

        part.setShortName(Optional.empty());
        part.setVisible(false);
        part.setLocked(true);
        part.setEditColor(Optional.of(new Color(0.5F, 0.6F, 0.7F, 0.8F)));
        part.setSketch(true);
        part.setDefaultOrder(23);

        assertEquals(Optional.empty(), part.shortName());
        assertEquals("PartClip", part.name());
        assertEquals(false, part.visible());
        assertEquals(true, part.locked());
        assertEquals(false, part.visibleInHierarchy());
        assertEquals(true, part.lockedInHierarchy());
        assertEquals(Optional.of(new Color(0.5F, 0.6F, 0.7F, 0.8F)), part.editColor());
        assertEquals(true, part.sketch());
        assertEquals(23, part.defaultOrder());
        assertEquals(6, fixture.editMode.edits.size());
        assertEquals(6, fixture.source.hierarchyUpdateCount);
        assertEquals(6, fixture.source.updateCount);

        part.setEditColor(Optional.empty());
        assertEquals(Optional.empty(), part.editColor());
        model.update();
        assertEquals(8, fixture.source.updateCount);
    }

    @Test
    void rejectsBlankNameAndStaleSameIdWrite() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertThrows(IllegalArgumentException.class, () -> part.setName("  "));
        fixture.replacePartWithSameId();
        assertThrows(IllegalStateException.class, () -> part.setName("Renamed Part"));
        assertEquals(0, fixture.editMode.edits.size());
    }

    @Test
    void sameIdReplacementMakesNameReferenceStale() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        fixture.replacePartWithSameId();

        assertThrows(IllegalStateException.class, part::name);
    }

    @Test
    void resolverWithoutNameCapabilityFailsClosedBeforeNameSelectorUse() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");

        assertThrows(UnsupportedOperationException.class, () -> access.active().parts());
    }

    private static VerifiedMemberResolver resolver(final boolean includeNameCapability) {
        return resolver(includeNameCapability, "5.3.02");
    }

    private static VerifiedMemberResolver resolver(final boolean includeNameCapability, final String cubismVersion) {
        return TestVerifiedResolvers.create(
                cubismVersion,
                "adapter.editor-model.readwrite",
                includeNameCapability
                        ? java.util.Set.of(
                                EditorPartNameSelectorContract.CAPABILITY_ID,
                                EditorPartNameSelectorContract.WRITE_CAPABILITY_ID,
                                EditorPartBasicSettingsSelectorContract.READ_CAPABILITY_ID,
                                EditorPartBasicSettingsSelectorContract.WRITE_CAPABILITY_ID)
                        : java.util.Set.of("cubism.editor-model.read"),
                hierarchySelectors(),
                Host.class.getClassLoader());
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final PartSource partSource = new PartSource("PartClip", "Clipping Part");
        final Document document;
        final EditMode editMode;
        final CompletePack pack;

        Fixture() {
            source.sources.add(partSource);
            source.model = new Model();
            source.model.parts.add(new HostPart(partSource));
            document = new Document(source);
            editMode = document.editMode;
            pack = document.pack;
        }

        void replacePartWithSameId() {
            final PartSource replacement = new PartSource("PartClip", "Replacement");
            source.sources.clear();
            source.sources.add(replacement);
            source.model.parts.clear();
            source.model.parts.add(new HostPart(replacement));
        }
    }
}
