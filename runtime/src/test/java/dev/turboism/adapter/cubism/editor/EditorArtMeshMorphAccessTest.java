package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshMorphTransactionTest.onEdt;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.selector.EditorArtMeshMorphSelectorContract;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fail-closed lookup and native admission boundaries, using detached values and real selectors. */
class EditorArtMeshMorphAccessTest {
    @Test
    void lookupDoesNotCreateAndBaseIsTheMiddleDefaultCell() throws Exception {
        onEdt(f -> {
            assertThrows(NoSuchElementException.class, () -> f.key(.5f));
            assertEquals(0, f.begins);
            assertEquals(0, f.mutations);
            final var target = f.create(.5f);
            assertEquals("m", target.parameterGuid());
            assertEquals(.5f, target.keyValue());
            assertEquals("f4", target.baseSnapshot().formGuid());
            assertNotEquals("f0", target.baseSnapshot().formGuid());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> target.snapshot().positions().clear());
            assertThrows(IllegalArgumentException.class, () -> target.setPositions(List.of()));
            final var history = f.history();
            assertThrows(IllegalArgumentException.class, () -> f.create(.5f));
            assertThrows(IllegalArgumentException.class, () -> f.create(.5005f));
            assertEquals(history, f.history());
        });
    }

    @ParameterizedTest
    @ValueSource(floats = {0f, -0f, -.5f, .001f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY})
    void invalidBasicAndOutOfRangeKeysNeverMutate(final float key) throws Exception {
        onEdt(f -> {
            assertThrows(IllegalArgumentException.class, () -> f.create(key));
            assertEquals(0, f.begins);
            assertEquals(0, f.mutations);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "forms-immutable",
                "targets-immutable",
                "extended-active",
                "extended-disabled",
                "locked",
                "deformer",
                "constraints",
                "repeat",
                "range",
                "default-not-key",
                "shared",
                "null",
                "zero"
            })
    void unsupportedDomainIsRefusedBeforeAdmission(final String fault) throws Exception {
        onEdt(f -> {
            switch (fault) {
                case "forms-immutable" -> f.source.forms.immutable = true;
                case "targets-immutable" -> f.source.targets.targets.immutable = true;
                case "extended-active" -> f.source.extended = new Object(); // Source-level: not parameter m's settings.
                case "extended-disabled" -> f.source.extensions.add(new EditorArtMeshMorphFixture.Extended());
                case "locked" -> f.source.locked = true;
                case "deformer" -> f.source.deformer = new Object();
                case "constraints" -> f.source.targets.constraints.entries.add(new Object());
                case "repeat" -> f.model.parameters.get(2).repeat = true;
                case "range" -> f.model.parameters.get(2).maximum = 2;
                case "default-not-key" -> f.source.grid.bindings.get(0).defaultValue = .25f;
                case "shared" -> f.source.grid.rows.put("f0", f.source.grid.rows.get("f1"));
                case "null", "zero" ->
                    f.source.targets.targets.add(new EditorArtMeshMorphFixture.Target(
                            f.source,
                            new EditorArtMeshMorphFixture.Guid("m"),
                            new EditorArtMeshMorphFixture.Guid("f4"),
                            fault.equals("null") ? null : 0f));
                default -> throw new AssertionError(fault);
            }
            assertThrows(RuntimeException.class, () -> f.create(.5f));
            assertEquals(0, f.begins);
            assertEquals(0, f.mutations);
        });
    }

    @Test
    void everyRequiredAliasCapabilityVersionAndCoordinatorAreFailClosed() throws Exception {
        onEdt(f -> {
            for (String alias : EditorArtMeshMorphSelectorContract.REQUIRED_ALIASES) {
                f.resolver = f.resolver("5.3.02", alias, null);
                assertThrows(UnsupportedOperationException.class, () -> f.create(.5f), alias);
            }
            f.resolver = f.resolver("5.3.02", null, EditorArtMeshMorphSelectorContract.CAPABILITY_ID);
            assertThrows(UnsupportedOperationException.class, () -> f.create(.5f));
            for (String version : List.of("5.4.00")) {
                f.resolver = f.resolver(version, null, null);
                assertThrows(UnsupportedOperationException.class, () -> f.create(.5f));
            }
            f.resolver = f.resolver("5.3.02", null, null);
            f.denyTransaction = true;
            assertThrows(UnsupportedOperationException.class, () -> f.create(.5f));
            assertEquals(0, f.begins);
            assertEquals(0, f.mutations);
        });
    }

    @Test
    void siblingCreationSameGuidReplacementAndSourceReplacementInvalidateReferences() throws Exception {
        onEdt(f -> {
            final var first = f.create(.5f);
            final var detached = first.snapshot();
            f.create(1f);
            assertThrows(IllegalStateException.class, first::snapshot);
            final var fresh = f.key(.5f);
            final int index = f.source.forms.indexOf(f.state().formObjects.get(detached.formGuid()));
            f.source.forms.set(index, f.source.forms.get(index).copy());
            assertThrows(IllegalStateException.class, fresh::snapshot);
            final var freshAgain = f.key(.5f);
            f.currentSource = new Object();
            assertThrows(IllegalStateException.class, freshAgain::baseSnapshot);
            assertEquals(4, detached.positions().size());
        });
    }

    @Test
    void malformedStoredVertexCountsAreRejectedBySnapshotBeforeAnyAdmission() throws Exception {
        onEdt(f -> {
            f.source.forms.get(4).positions = new float[] {1, 2};
            final var failure = assertThrows(IllegalArgumentException.class, () -> f.create(.5f));
            assertEquals("stored form vertex count differs from source", failure.getMessage());
            assertEquals(0, f.begins);
            assertEquals(0, f.snapshots);
            assertEquals(0, f.mutations);
        });
        onEdt(f -> {
            final var target = f.create(.5f);
            f.source.forms.get(9).positions = new float[] {1, 2};
            final int begins = f.begins;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> target.setPositions(List.of(new dev.turboism.sdk.cubism.model.Point2(3, 4))));
            assertEquals(begins, f.begins);
            assertEquals(1, f.mutations);
        });
    }

    @Test
    void realDrawableWiringUsesCoordinatorRefreshAndStrictInstanceIdentity() throws Exception {
        onEdt(f -> {
            final var mesh = f.drawable(f.resolver, true);
            assertThrows(NoSuchElementException.class, () -> mesh.morphKeyform("m", .5f));
            final var target = mesh.createMorphKeyform("m", .5f);
            target.setPositions(EditorArtMeshMorphTransactionTest.U);
            assertEquals(
                    EditorArtMeshMorphTransactionTest.U,
                    mesh.morphKeyform("m", .5f).snapshot().positions());
            assertEquals(2, f.position);
            assertEquals(2, f.begins);
            assertEquals(2, f.instanceUpdates);
            assertEquals(2, f.repaints);
            assertEquals(2, f.dirty);
            f.undo();
            assertThrows(IllegalStateException.class, target::snapshot);
            final var fresh = mesh.morphKeyform("m", .5f);
            assertEquals(fresh.baseSnapshot().positions(), fresh.snapshot().positions());
            f.model.mesh = new EditorArtMeshMorphFixture.Mesh(f.source);
            assertThrows(IllegalStateException.class, fresh::snapshot);
            assertThrows(IllegalStateException.class, () -> mesh.createMorphKeyform("m", 1f));
            assertEquals(2, f.begins);
            assertEquals(2, f.mutations);
        });
    }

    @Test
    void realDrawableRejectsAbsentCoordinatorAndHistoryBeforeNativeMutation() throws Exception {
        onEdt(f -> {
            final var absent = f.drawable(f.resolver, false);
            assertThrows(UnsupportedOperationException.class, () -> absent.createMorphKeyform("m", .5f));
            final var noHistory = f.drawable(f.resolver("5.3.02", null, "cubism.editor-history.read"), true);
            assertThrows(UnsupportedOperationException.class, () -> noHistory.createMorphKeyform("m", .5f));
            assertEquals(0, f.begins);
            assertEquals(0, f.mutations);
            f.create(.5f);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> absent.morphKeyform("m", .5f).setPositions(EditorArtMeshMorphTransactionTest.U));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> noHistory.morphKeyform("m", .5f).setPositions(EditorArtMeshMorphTransactionTest.U));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> absent.morphKeyform("m", .5f).setDrawOrder(700));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> noHistory.morphKeyform("m", .5f).setDrawOrder(700));
            assertEquals(1, f.begins);
            assertEquals(1, f.mutations);
        });
    }

    @Test
    void drawOrderAcceptsOnlyExactAbsoluteRangeAndNeverClamps() throws Exception {
        onEdt(f -> {
            final var target = f.create(.5f);
            target.setDrawOrder(0);
            assertEquals(0, f.key(.5f).snapshot().drawOrder());
            target.setDrawOrder(1000);
            assertEquals(1000, f.key(.5f).snapshot().drawOrder());
            final int mutations = f.mutations, begins = f.begins, snapshots = f.snapshots, dirty = f.dirty;
            for (final int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> target.setDrawOrder(invalid));
            }
            assertEquals(mutations, f.mutations);
            assertEquals(begins, f.begins);
            assertEquals(snapshots, f.snapshots);
            assertEquals(dirty, f.dirty);
            assertEquals(1000, f.key(.5f).snapshot().drawOrder());
        });
    }

    @Test
    void drawOrderRangeIsRejectedBeforeEdtAndNativeAdmission() throws Exception {
        final var rejected = new java.util.concurrent.atomic.AtomicInteger();
        onEdt(f -> {
            final var target = f.create(.5f);
            final int mutations = f.mutations;
            final var worker = new Thread(() -> {
                for (final int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                    try {
                        target.setDrawOrder(invalid);
                    } catch (IllegalArgumentException ok) {
                        rejected.incrementAndGet();
                    }
                }
            });
            // A regression that dispatches to this blocked EDT must fail, not deadlock the suite.
            worker.setDaemon(true);
            worker.start();
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            assertFalse(worker.isAlive(), "invalid order must be rejected without waiting for the EDT");
            assertEquals(4, rejected.get());
            assertEquals(mutations, f.mutations);
            assertEquals(500, f.source.forms.get(9).order);
        });
    }

    @Test
    void storedOrderIsAbsoluteNotRelativeToOrdinaryBase() throws Exception {
        onEdt(f -> {
            f.source.forms.get(4).order = 151;
            final var target = f.create(.5f);
            assertEquals(151, target.snapshot().drawOrder());
            target.setDrawOrder(700);
            assertEquals(700, f.key(.5f).snapshot().drawOrder());
            assertEquals(151, target.baseSnapshot().drawOrder());
        });
    }

    @Test
    void drawOrderWriteLeavesEveryOtherStoredValueIntact() throws Exception {
        onEdt(f -> {
            f.create(.25f);
            f.create(.75f);
            f.key("n", 1f, true);
            final var before = f.state();
            final var target = f.key(.25f);
            final var detached = target.snapshot();
            final var detachedOther = f.key(.75f).snapshot();
            final var detachedCross = f.key("n", 1f, false).snapshot();
            final var base = target.baseSnapshot();
            target.setDrawOrder(0);
            assertEquals(0, f.key(.25f).snapshot().drawOrder());
            assertEquals(detached.positions(), f.key(.25f).snapshot().positions());
            assertEquals(detachedOther, f.key(.75f).snapshot());
            assertEquals(detachedCross, f.key("n", 1f, false).snapshot());
            assertEquals(base, f.key(.25f).baseSnapshot());
            assertTrue(before.onlyDrawOrderChanged(f.state(), detached.formGuid(), 0));
            assertTrue(before.protectedUnchanged(f.state()));
        });
    }

    @Test
    void sameDrawOrderIsNoChangeWithoutSnapshotUndoOrDirty() throws Exception {
        onEdt(f -> {
            final var target = f.create(.5f);
            target.setDrawOrder(700);
            final var history = f.history();
            final int snapshots = f.snapshots, mutations = f.mutations, begins = f.begins, dirty = f.dirty;
            target.setDrawOrder(700);
            assertEquals(history, f.history());
            assertEquals(snapshots, f.snapshots);
            assertEquals(mutations, f.mutations);
            assertEquals(begins, f.begins);
            assertEquals(dirty, f.dirty);
            assertDoesNotThrow(target::snapshot);
            assertEquals(700, f.key(.5f).snapshot().drawOrder());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void readsAndEditsMorphKeyformsOnEveryReviewedGeneration(final String version) throws Exception {
        onEdt(f -> {
            f.resolver = f.resolver(version, null, null);
            final var target = f.create(.5f);
            assertEquals(.5f, target.keyValue());
            target.setPositions(EditorArtMeshMorphTransactionTest.U);
            assertEquals(EditorArtMeshMorphTransactionTest.U, target.snapshot().positions());
            assertEquals(2, f.mutations); // the create and the position edit each mutate once
        });
    }

    @Test
    void missingDrawOrderAliasRejectsOnlyTheSetterWithZeroMutation() throws Exception {
        onEdt(f -> {
            f.create(.5f);
            f.resolver = f.resolver("5.3.02", "cubism.editor-model.drawable-form.set-draw-order", null);
            final var target = f.key(.5f);
            assertEquals(500, target.snapshot().drawOrder());
            target.setPositions(EditorArtMeshMorphTransactionTest.U);
            final int mutations = f.mutations, snapshots = f.snapshots, begins = f.begins;
            final var failure = assertThrows(UnsupportedOperationException.class, () -> target.setDrawOrder(700));
            assertEquals("unsupported root ArtMesh morph authoring domain", failure.getMessage());
            assertEquals(mutations, f.mutations);
            assertEquals(snapshots, f.snapshots);
            assertEquals(begins, f.begins);
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
        });
    }

    @Test
    void drawOrderAdmissionIsFailClosedIndependentlyOfReads() throws Exception {
        onEdt(f -> {
            f.create(.5f);
            for (String alias : EditorArtMeshMorphSelectorContract.DRAW_ORDER_REQUIRED_ALIASES) {
                f.resolver = f.resolver("5.3.02", alias, null);
                final int mutations = f.mutations;
                assertThrows(
                        UnsupportedOperationException.class, () -> f.key(.5f).setDrawOrder(700), alias);
                assertEquals(mutations, f.mutations);
            }
            f.resolver = f.resolver("5.3.02", null, EditorArtMeshMorphSelectorContract.CAPABILITY_ID);
            assertThrows(UnsupportedOperationException.class, () -> f.key(.5f).setDrawOrder(700));
            for (String version : List.of("5.4.00")) {
                f.resolver = f.resolver(version, null, null);
                assertThrows(
                        UnsupportedOperationException.class, () -> f.key(.5f).setDrawOrder(700));
            }
            f.resolver = f.resolver("5.3.02", null, null);
            f.denyTransaction = true;
            assertThrows(UnsupportedOperationException.class, () -> f.key(.5f).setDrawOrder(700));
            assertEquals(1, f.mutations);
            assertEquals(500, f.source.forms.get(9).order);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"locked", "deformer", "constraints", "extended-active", "repeat", "shared"})
    void unsupportedDomainRefusesDrawOrderBeforeMutation(final String fault) throws Exception {
        onEdt(f -> {
            final var target = f.create(.5f);
            switch (fault) {
                case "locked" -> f.source.locked = true;
                case "deformer" -> f.source.deformer = new Object();
                case "constraints" -> f.source.targets.constraints.entries.add(new Object());
                case "extended-active" -> f.source.extended = new Object();
                case "repeat" -> f.model.parameters.get(2).repeat = true;
                case "shared" -> f.source.grid.rows.put("f0", f.source.grid.rows.get("f1"));
                default -> throw new AssertionError(fault);
            }
            final int mutations = f.mutations, begins = f.begins;
            assertThrows(RuntimeException.class, () -> target.setDrawOrder(700));
            assertEquals(mutations, f.mutations);
            assertEquals(begins, f.begins);
        });
    }
}
