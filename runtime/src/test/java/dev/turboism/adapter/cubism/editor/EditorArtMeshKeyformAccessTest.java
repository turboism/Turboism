package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorArtMeshKeyformAccessTest {
    private static final String M = "cubism.editor-model.";
    private static final ArtMeshId ID = new ArtMeshId("ArtMeshTest");
    private static final List<Point2> POSITIONS =
            List.of(new Point2(20, 30), new Point2(40, 30), new Point2(40, 50), new Point2(20, 50));

    @Test
    void locatesByGuidNotSameParameterIdOrInputOrderAndKeepsDefaultReadOnly() {
        final var fixture = new Fixture();
        final var target = fixture.open(List.of(new Coordinate("y", 1), new Coordinate("x", 1)));
        assertEquals("f8", target.snapshot().formGuid());
        assertEquals(tuple(1, 1), target.coordinates());
        assertFalse(target.defaultKeyform());
        final var base = fixture.open(tuple(0, 0));
        assertTrue(base.defaultKeyform());
        assertEquals("f4", base.snapshot().formGuid());
        assertThrows(UnsupportedOperationException.class, () -> base.setPositions(POSITIONS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> base.setDrawOrder(base.snapshot().drawOrder()));
        assertEquals(0, fixture.writes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void opensAndWritesStoredKeyformsOnEveryReviewedGeneration(final String version) {
        final var fixture = new Fixture();
        fixture.resolver = resolver(version, "", false);
        final var target = fixture.open(List.of(new Coordinate("y", 1), new Coordinate("x", 1)));
        target.setPositions(POSITIONS);
        assertEquals(POSITIONS, target.snapshot().positions());
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void writesOnlySelectedPositionsAndNoopNeverInvokesWriter() {
        final var fixture = new Fixture();
        final var target = fixture.open(tuple(1, 1));
        final var before = fixture.read();
        final var oldValue = target.snapshot();
        final var input = new ArrayList<>(POSITIONS);
        target.setPositions(input);
        input.clear();
        assertEquals(POSITIONS, target.snapshot().positions());
        assertEquals(1, fixture.writes.get());
        assertNotEquals(POSITIONS, oldValue.positions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> target.snapshot().positions().clear());
        assertThrows(
                UnsupportedOperationException.class, () -> target.coordinates().clear());
        final var after = fixture.read();
        assertEquals(before.sourceGeometry(), after.sourceGeometry());
        assertEquals(before.axes(), after.axes());
        assertEquals(before.cells(), after.cells());
        assertEquals(before.morphKeys(), after.morphKeys());
        for (int i = 0; i < before.forms().size(); i++) {
            final var old = before.forms().get(i);
            final var now = after.forms().get(i);
            assertEquals(old.drawOrder(), now.drawOrder());
            if (!old.formGuid().equals("f8")) assertEquals(old, now);
        }
        target.setPositions(POSITIONS);
        assertEquals(1, fixture.writes.get());
    }

    @Test
    void invalidTuplesAndTopologyFailBeforeMutation() {
        final var fixture = new Fixture();
        for (final var input : List.of(
                List.<Coordinate>of(),
                List.of(new Coordinate("x", 1)),
                List.of(new Coordinate("x", 1), new Coordinate("x", 0)),
                List.of(new Coordinate("x", 1), new Coordinate("foreign", 1)),
                tuple(.5F, 1),
                tuple(-0.0F, 1),
                List.of(new Coordinate("x", 1), new Coordinate("y", 1), new Coordinate("z", 1)))) {
            assertThrows(IllegalArgumentException.class, () -> fixture.open(input));
        }
        assertThrows(IllegalArgumentException.class, () -> new Coordinate("x", Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Point2(Float.POSITIVE_INFINITY, 0));
        final var target = fixture.open(tuple(1, 1));
        assertThrows(IllegalArgumentException.class, () -> target.setPositions(List.of(new Point2(0, 0))));
        assertThrows(NullPointerException.class, () -> target.setPositions(java.util.Arrays.asList((Point2) null)));
        assertEquals(0, fixture.writes.get());
    }

    @Test
    void refusesMissingDefaultKeysCellsDuplicateCellsAndAliasIncludingNullMorph() {
        final List<Consumer<Source>> invalid = List.of(
                s -> s.grid.bindings.get(0).parameter.defaultValue = .25F,
                s -> s.grid.bindings.get(0).keys.add(1F),
                s -> s.grid.rows.remove("f4"),
                s -> s.grid.rows.get("f8").add(s.grid.rows.get("f8").get(0)),
                s -> s.grid.rows.get("f8").add(s.grid.rows.remove("f7").get(0)),
                s -> s.targets.targets.add(new Target(new Guid("f8"), null)),
                s -> s.targets.targets.add(new Target(new Guid("f8"), 0F)),
                s -> s.targets.targets.add(new Target(new Guid("f4"), null)));
        for (final var corrupt : invalid) {
            final var fixture = new Fixture();
            corrupt.accept(fixture.source);
            assertThrows(IllegalArgumentException.class, () -> fixture.open(tuple(1, 1)));
            assertEquals(0, fixture.writes.get());
        }
        final var missing = new Fixture();
        missing.source.grid.rows.remove("f8");
        assertThrows(NoSuchElementException.class, () -> missing.open(tuple(1, 1)));
    }

    @Test
    void everyReadAndWriteRejectsReplacedFormEvenWithSameGuid() {
        final var fixture = new Fixture();
        final var target = fixture.open(tuple(1, 1));
        fixture.source.forms.set(8, new StoredForm("f8", 8));
        assertThrows(IllegalStateException.class, target::snapshot);
        assertThrows(IllegalStateException.class, target::coordinates);
        assertThrows(IllegalStateException.class, target::defaultKeyform);
        assertThrows(IllegalStateException.class, () -> target.setPositions(POSITIONS));
        assertThrows(IllegalStateException.class, () -> target.setDrawOrder(600));
        assertEquals(0, fixture.writes.get());
        assertEquals("f8", fixture.open(tuple(1, 1)).snapshot().formGuid());
    }

    @Test
    void refusesChangedSourceOrLayoutAndNeverRebindsToAnotherSource() {
        final List<Consumer<Fixture>> changes = List.of(
                f -> f.source.positions[0] += 1,
                f -> f.source.indices[0] = 1,
                f -> f.source.grid.bindings.get(0).parameter.defaultValue = -1,
                f -> f.source.grid.rows.remove("f7"),
                f -> f.source.targets.targets.add(new Target(new Guid("f8"), 1F)),
                f -> f.currentSource = new Source());
        for (final var change : changes) {
            final var fixture = new Fixture();
            final var target = fixture.open(tuple(1, 1));
            change.accept(fixture);
            assertThrows(IllegalStateException.class, target::snapshot);
            assertThrows(IllegalStateException.class, () -> target.setPositions(POSITIONS));
            assertThrows(IllegalStateException.class, () -> target.setDrawOrder(600));
            assertEquals(0, fixture.writes.get());
        }
    }

    @Test
    void missingWriteCapabilityAliasAndTransactionRefuseBeforeWriter() {
        for (final String alias : EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES) {
            final var fixture = new Fixture();
            fixture.resolver = resolver("5.3.02", alias, false);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fixture.open(tuple(1, 1)).setPositions(POSITIONS));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fixture.open(tuple(1, 1)).setDrawOrder(600));
            assertEquals(0, fixture.writes.get());
        }
        final var capability = new Fixture();
        capability.resolver = resolver("5.3.02", "", true);
        assertThrows(
                UnsupportedOperationException.class,
                () -> capability.open(tuple(1, 1)).setPositions(POSITIONS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> capability.open(tuple(1, 1)).setDrawOrder(600));
        final var transaction = new Fixture();
        transaction.requireTransaction = () -> {
            throw new UnsupportedOperationException("no coordinator");
        };
        assertThrows(
                UnsupportedOperationException.class,
                () -> transaction.open(tuple(1, 1)).setPositions(POSITIONS));
        assertThrows(
                UnsupportedOperationException.class,
                () -> transaction.open(tuple(1, 1)).setDrawOrder(600));
        assertEquals(0, transaction.writes.get());
        for (final String version : List.of("5.4.00")) {
            final var fixture = new Fixture();
            fixture.resolver = resolver(version, "", false);
            assertThrows(UnsupportedOperationException.class, () -> fixture.open(tuple(1, 1)));
        }
    }

    @Test
    void compensationReadsFreshFormAfterAbortReplacementNotDetachedPointer() {
        final var fixture = new Fixture();
        final var before = fixture.read();
        final var target = fixture.open(tuple(1, 1));
        final var detached = fixture.source.forms.get(8);
        fixture.afterMutation = () -> {
            final var replacement = new StoredForm("f8", 8);
            replacement.positions[0] = 900;
            fixture.source.forms.set(8, replacement);
        };
        fixture.failAfterMutation = true;
        assertThrows(IllegalStateException.class, () -> target.setPositions(POSITIONS));
        assertEquals(before, fixture.read());
        assertEquals(POSITIONS.get(0).x(), detached.positions[0]);
        assertThrows(IllegalStateException.class, target::snapshot);
    }

    @Test
    void postconditionDetectsUnrelatedFormCorruptionRatherThanReportingSuccess() {
        final var fixture = new Fixture();
        fixture.afterMutation = () -> fixture.source.forms.get(4).positions[0] = 999;
        final var failure = assertThrows(
                IllegalStateException.class, () -> fixture.open(tuple(1, 1)).setPositions(POSITIONS));
        assertEquals("recovery failed", failure.getMessage());
        assertEquals(999, fixture.source.forms.get(4).positions[0]);
    }

    @Test
    void orderWritesOnlySelectedFieldAndAcceptsBothInclusiveBoundsWithoutClamping() {
        final var fixture = new Fixture();
        final var target = fixture.open(tuple(1, 1));
        final var before = fixture.read();
        for (final int order : List.of(0, 1000)) {
            target.setDrawOrder(order);
            final var forms = before.forms().stream()
                    .map(form -> form.formGuid().equals("f8")
                            ? new ArtMeshAuthoringSnapshot.Form(form.formGuid(), form.positions(), order)
                            : form)
                    .toList();
            assertEquals(
                    new ArtMeshAuthoringSnapshot(
                            before.artMeshId(),
                            before.sourceGuid(),
                            before.sourceGeometry(),
                            before.axes(),
                            before.cells(),
                            forms,
                            before.morphKeys()),
                    fixture.read());
            final int writes = fixture.writes.get();
            target.setDrawOrder(order);
            assertEquals(writes, fixture.writes.get());
        }
        assertEquals(List.of("drawOrder", "drawOrder"), fixture.properties);
        final var unchanged = fixture.read();
        for (final int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> target.setDrawOrder(invalid));
        }
        assertEquals(unchanged, fixture.read());
        assertEquals(2, fixture.writes.get());
    }

    @Test
    void orderCompensationUsesFreshMembersAndRestoresBothSnapshotFields() {
        final var fixture = new Fixture();
        final var before = fixture.read();
        final var target = fixture.open(tuple(1, 1));
        final var detached = fixture.source.forms.get(8);
        fixture.afterMutation = () -> {
            final var replacement = new StoredForm("f8", 8);
            replacement.positions[0] = 900;
            replacement.order = 777;
            fixture.source.forms.set(8, replacement);
        };
        fixture.failAfterMutation = true;
        assertThrows(IllegalStateException.class, () -> target.setDrawOrder(600));
        assertEquals(before, fixture.read());
        assertEquals(600, detached.order);
        assertThrows(IllegalStateException.class, () -> target.setDrawOrder(500));
    }

    @Test
    void compensationNeverRepairsProtectedDefaultSharedMorphOrNonGridForms() {
        final List<Consumer<Source>> protect = List.of(
                s -> {},
                s -> s.grid.rows.get("f3").add(s.grid.rows.remove("f2").get(0)),
                s -> s.targets.targets.add(new Target(new Guid("f3"), null)),
                s -> s.targets.targets.add(new Target(new Guid("f3"), 0F)),
                s -> s.grid.rows.remove("f3"));
        for (int i = 0; i < protect.size(); i++) {
            final var fixture = new Fixture();
            protect.get(i).accept(fixture.source);
            final var protectedForm = fixture.source.forms.get(i == 0 ? 4 : 3);
            fixture.afterMutation = () -> protectedForm.order = 999;
            final var failure = assertThrows(
                    IllegalStateException.class, () -> fixture.open(tuple(1, 1)).setDrawOrder(600));
            assertEquals("recovery failed", failure.getMessage());
            assertEquals(999, protectedForm.order);
        }
    }

    @Test
    void collateralSourceGeometryNeverGetsSilentlyRepairedDuringOrderCompensation() {
        final var fixture = new Fixture();
        fixture.afterMutation = () -> fixture.source.positions[0] = 999;
        assertThrows(
                IllegalStateException.class, () -> fixture.open(tuple(1, 1)).setDrawOrder(600));
        assertEquals(999, fixture.source.positions[0]);
    }

    private static List<Coordinate> tuple(final float x, final float y) {
        return List.of(new Coordinate("x", x), new Coordinate("y", y));
    }

    private static VerifiedMemberResolver resolver(
            final String version, final String missing, final boolean denyWrite) {
        final var capabilities = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);
        if (!denyWrite) capabilities.add(EditorArtMeshKeyformSelectorContract.CAPABILITY_ID);
        final var selectors = new ArrayList<>(EditorArtMeshAuthoringReadAccessTest.selectors());
        selectors.add(method(M + "art-mesh-form.set-positions", StoredForm.class, "setPositions", float[].class));
        selectors.add(method(M + "drawable-form.set-draw-order", StoredForm.class, "setDrawOrder", int.class));
        // These unused aliases grant only this fake resolver, not production transaction-wiring evidence.
        for (final String alias : EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES) {
            if (selectors.stream().noneMatch(s -> s.alias().equals(alias))) {
                selectors.add(method(alias, Source.class, "guid"));
            }
        }
        return TestVerifiedResolvers.create(
                version,
                EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors.stream().filter(s -> !s.alias().equals(missing)).toList(),
                Source.class.getClassLoader());
    }

    private static final class Fixture {
        final Source source = new Source();
        Source currentSource = source;
        VerifiedMemberResolver resolver = resolver("5.3.02", "", false);
        final AtomicInteger writes = new AtomicInteger();
        final List<String> properties = new ArrayList<>();
        Runnable requireTransaction = () -> {};
        Runnable afterMutation = () -> {};
        boolean failAfterMutation;

        Fixture() {
            source.targets.targets.clear();
            source.forms.removeIf(f -> f.guid.text().startsWith("m"));
            final var last = source.grid.rows.get("f0").remove(1);
            source.grid.rows.put("f8", new ArrayList<>(List.of(last)));
            source.forms.add(new StoredForm("f8", 8));
        }

        ArtMeshAuthoringSnapshot read() {
            return EditorHostThread.dispatch(
                    "test stored read", () -> new EditorArtMeshAuthoringReadAccess(resolver).read(ID, source));
        }

        ArtMeshKeyform open(final List<Coordinate> coordinates) {
            return EditorArtMeshKeyformAccess.open(
                    resolver,
                    ID,
                    () -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        return currentSource;
                    },
                    coordinates,
                    requireTransaction,
                    (form, property, label, mutation, applied, compensation, restored) -> {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        writes.incrementAndGet();
                        properties.add(property);
                        try {
                            mutation.run();
                            afterMutation.run();
                            if (failAfterMutation || !applied.getAsBoolean())
                                throw new IllegalStateException("mutation failed");
                        } catch (RuntimeException error) {
                            if (!restored.getAsBoolean()) compensation.run();
                            if (!restored.getAsBoolean()) throw new IllegalStateException("recovery failed", error);
                            throw error;
                        }
                    });
        }
    }
}
