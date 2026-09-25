package dev.turboism.ui.contribution;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorUiContributionAuthorityTest {

    @Test
    void recordsBeforeReadyAndReconcilesInDeterministicOrder() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        authority.contribute(contribution("plugin-b", "second", 10));
        authority.contribute(contribution("plugin-a", "first", 10));
        authority.contribute(contribution("plugin-a", "leading", 0));

        assertTrue(provider.snapshots.isEmpty());
        long generation = lifecycle.connecting().generation();
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));

        assertEquals(1, provider.snapshots.size());
        assertEquals(
            List.of("plugin-a:leading", "plugin-a:first", "plugin-b:second"),
            provider.snapshots.get(0)
        );
    }

    @Test
    void contributionChangesReplaceNativeRegistrationExactlyOnce() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        Registration first = authority.contribute(contribution("plugin-a", "first", 0));
        Registration second = authority.contribute(contribution("plugin-a", "second", 1));
        second.close();
        second.close();
        first.close();

        assertEquals(3, provider.snapshots.size());
        assertEquals(3, provider.closedRegistrations);
        assertTrue(authority.contributions(EditorUiFamily.MENU).isEmpty());
    }

    @Test
    void panelProviderCanReconcileInPlaceWithoutClosingFamilyRegistration() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        IncrementalRecordingProvider provider = new IncrementalRecordingProvider();
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.PANEL));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        Registration first = authority.contribute(panelContribution("plugin-a", "first", 0));
        Registration second = authority.contribute(panelContribution("plugin-b", "second", 1));

        assertEquals(1, provider.applied);
        assertEquals(1, provider.reconciled);
        assertEquals(0, provider.closedRegistrations);

        second.close();
        assertEquals(2, provider.reconciled);
        assertEquals(0, provider.closedRegistrations);

        first.close();
        assertEquals(3, provider.reconciled);
        assertEquals(1, provider.closedRegistrations);
    }

    @Test
    void hostReplacementRequiresFreshGenerationAdmission() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));
        long first = lifecycle.connecting().generation();
        provider.admit(first);
        lifecycle.ready(first, Set.of(EditorUiFamily.MENU));

        lifecycle.replacing();
        long second = lifecycle.connecting().generation();
        lifecycle.ready(second, Set.of(EditorUiFamily.MENU));

        assertEquals(List.of(first), provider.generations);
        assertEquals(1, provider.closedRegistrations);
        assertEquals(
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            authority.lastFailure(EditorUiFamily.MENU).orElseThrow().code()
        );

        authority.removeProvider(provider);
        provider.admit(second);
        authority.installProvider(provider);

        assertEquals(List.of(first, second), provider.generations);
    }

    @Test
    void duplicateIdentityReplacesOriginalAndReconciles() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "same", 0));
        int reconciledBefore = provider.generations.size();

        // Same identity refreshes the contribution (content update) instead of
        // being rejected, so the host content can be updated in place.
        authority.contribute(contribution("plugin-a", "same", 1));
        assertEquals(1, authority.contributions(EditorUiFamily.MENU).size());
        assertEquals("same", authority.contributions(EditorUiFamily.MENU).get(0).identity().contributionId());
        assertTrue(provider.generations.size() > reconciledBefore);
    }

    @Test
    void unavailableProviderDoesNotApplyAndRecordsFailure() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));

        assertTrue(provider.snapshots.isEmpty());
        assertEquals(
            EditorUiContributionFailure.Code.MAPPING_NOT_VERIFIED,
            authority.lastFailure(EditorUiFamily.MENU).orElseThrow().code()
        );
    }

    @Test
    void mismatchedAdmissionFamilyIsRejected() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admission = EditorUiProviderAdmission.safeMode(
            EditorUiFamily.MAIN_TOOLBAR,
            "ui.toolbar.mapping-not-verified"
        );

        assertThrows(IllegalArgumentException.class, () -> authority.installProvider(provider));
    }

    @Test
    void concurrentReconcilesLeaveExactlyOneOrderedInstall() throws Exception {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        GatedProvider provider = new GatedProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);

        Thread first = new Thread(
            () -> authority.contribute(contribution("plugin-a", "first", 0)),
            "contribute-first"
        );
        first.start();
        assertTrue(
            provider.applyEntered.await(5, TimeUnit.SECONDS),
            "the first reconcile must reach the provider before the second contributes"
        );

        Thread second = new Thread(
            () -> authority.contribute(contribution("plugin-b", "second", 1)),
            "contribute-second"
        );
        second.start();
        awaitTrue(
            () -> authority.contributions(EditorUiFamily.MENU).size() == 2,
            "the second contribution must be recorded before the gate opens"
        );
        provider.releaseApply.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(first.isAlive(), "first contribute did not finish");
        assertFalse(second.isAlive(), "second contribute did not finish");

        assertEquals(
            1,
            provider.liveInstalls().size(),
            "concurrent reconciles must leave exactly one live native install, not one per writer"
        );
        assertEquals(
            List.of("plugin-a:first", "plugin-b:second"),
            provider.liveInstalls().get(0).descriptors
        );

        authority.close();
        assertTrue(
            provider.liveInstalls().isEmpty(),
            "every registration the provider issued must be closed by authority close"
        );
    }

    @Test
    void edtContributeDoesNotDeadlockBehindProviderInvokeAndWait() throws Exception {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        EdtDispatchProvider provider = new EdtDispatchProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        provider.authority = authority;
        authority.installProvider(provider);

        // The writer's provider call parks inside invokeAndWait while its EDT runnable adds a
        // second contribution to the same family. With a blocking per-family lock this
        // deadlocks: the writer holds the lock while waiting for the EDT, and the EDT-side
        // contribute() waits on the same lock.
        Thread writer = new Thread(
            () -> authority.contribute(contribution("plugin-a", "first", 0)),
            "contribute-off-edt"
        );
        writer.start();
        assertTrue(
            provider.applyEntered.await(5, TimeUnit.SECONDS),
            "the first reconcile must reach the provider before the EDT contribution runs"
        );
        assertTrue(
            provider.edtContributeDone.await(5, TimeUnit.SECONDS),
            "an EDT contribute must return while another reconcile is parked in invokeAndWait"
        );
        writer.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(writer.isAlive(), "the writer's contribute did not finish");
        assertNull(provider.edtFailure, "the EDT contribution must not fail");

        assertEquals(
            1,
            provider.liveInstalls().size(),
            "the coalesced reconcile must fold both contributions into one live install"
        );
        assertEquals(
            List.of("plugin-a:first", "plugin-b:second"),
            provider.liveInstalls().get(0).descriptors
        );

        authority.close();
        assertTrue(provider.liveInstalls().isEmpty());
    }

    @Test
    void closeDisposesNativeAndRejectsNewContributions() {
        RuntimeEditorUiHostLifecycle lifecycle = new RuntimeEditorUiHostLifecycle();
        long generation = lifecycle.connecting().generation();
        RecordingProvider provider = new RecordingProvider(EditorUiFamily.MENU);
        provider.admit(generation);
        lifecycle.ready(generation, Set.of(EditorUiFamily.MENU));
        EditorUiContributionAuthority authority = new EditorUiContributionAuthority(lifecycle);
        authority.installProvider(provider);
        authority.contribute(contribution("plugin-a", "first", 0));

        authority.close();
        authority.close();

        assertEquals(1, provider.closedRegistrations);
        assertThrows(
            IllegalStateException.class,
            () -> authority.contribute(contribution("plugin-a", "late", 0))
        );
    }

    private static EditorUiContribution<String> contribution(
        final String pluginId,
        final String id,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.MENU, id),
            order,
            pluginId + ":" + id
        );
    }

    private static EditorUiContribution<String> panelContribution(
        final String pluginId,
        final String id,
        final int order
    ) {
        return new EditorUiContribution<>(
            new EditorUiContributionIdentity(pluginId, EditorUiFamily.PANEL, id),
            order,
            pluginId + ":" + id
        );
    }

    private static void awaitTrue(
        final java.util.function.BooleanSupplier condition,
        final String description
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + description);
            }
            Thread.sleep(1);
        }
    }

    /**
     * Provider whose first {@code apply} call blocks on a latch so two concurrent reconciles
     * can be interleaved deterministically. Every issued registration records its installed
     * snapshot and stays observable until closed.
     */
    private static final class GatedProvider implements EditorUiContributionProvider {
        private final EditorUiFamily family;
        private final CountDownLatch applyEntered = new CountDownLatch(1);
        private final CountDownLatch releaseApply = new CountDownLatch(1);
        private final AtomicBoolean gateArmed = new AtomicBoolean(true);
        private final List<Install> installs = new CopyOnWriteArrayList<>();
        private EditorUiProviderAdmission admission;

        private GatedProvider(final EditorUiFamily family) {
            this.family = family;
            this.admission = EditorUiProviderAdmission.safeMode(
                family,
                "ui.provider.mapping-not-verified"
            );
        }

        private void admit(final long generation) {
            admission = EditorUiProviderAdmission.admitted(
                family,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                    "5.3.02",
                    42,
                    "a".repeat(64),
                    "adapter.editor-ui." + family.name().toLowerCase(java.util.Locale.ROOT),
                    "b".repeat(64)
                )
            );
        }

        private List<Install> liveInstalls() {
            return installs.stream().filter(install -> !install.closed).toList();
        }

        @Override
        public EditorUiFamily family() {
            return family;
        }

        @Override
        public EditorUiProviderAdmission admission() {
            return admission;
        }

        @Override
        public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            final Install install = new Install(
                contributions.stream().map(value -> (String) value.descriptor()).toList()
            );
            installs.add(install);
            if (gateArmed.compareAndSet(true, false)) {
                applyEntered.countDown();
                try {
                    releaseApply.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("gated apply interrupted", exception);
                }
            }
            return install;
        }
    }

    /** Native-install stand-in recording the snapshot it was created from until closed. */
    private static final class Install implements Registration {
        private final List<String> descriptors;
        private volatile boolean closed;

        private Install(final List<String> descriptors) {
            this.descriptors = descriptors;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Provider whose first {@code apply} call parks on {@code SwingUtilities.invokeAndWait}
     * while the dispatched runnable contributes to the same family from the EDT — the exact
     * interleave that deadlocks a blocking per-family reconcile lock.
     */
    private static final class EdtDispatchProvider implements EditorUiContributionProvider {
        private final EditorUiFamily family;
        private final CountDownLatch applyEntered = new CountDownLatch(1);
        private final CountDownLatch edtContributeDone = new CountDownLatch(1);
        private final AtomicBoolean dispatchArmed = new AtomicBoolean(true);
        private final List<Install> installs = new CopyOnWriteArrayList<>();
        private volatile EditorUiContributionAuthority authority;
        private volatile Throwable edtFailure;
        private EditorUiProviderAdmission admission;

        private EdtDispatchProvider(final EditorUiFamily family) {
            this.family = family;
            this.admission = EditorUiProviderAdmission.safeMode(
                family,
                "ui.provider.mapping-not-verified"
            );
        }

        private void admit(final long generation) {
            admission = EditorUiProviderAdmission.admitted(
                family,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                    "5.3.02",
                    42,
                    "a".repeat(64),
                    "adapter.editor-ui." + family.name().toLowerCase(java.util.Locale.ROOT),
                    "b".repeat(64)
                )
            );
        }

        private List<Install> liveInstalls() {
            return installs.stream().filter(install -> !install.closed).toList();
        }

        @Override
        public EditorUiFamily family() {
            return family;
        }

        @Override
        public EditorUiProviderAdmission admission() {
            return admission;
        }

        @Override
        public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            final Install install = new Install(
                contributions.stream().map(value -> (String) value.descriptor()).toList()
            );
            installs.add(install);
            if (dispatchArmed.compareAndSet(true, false)) {
                applyEntered.countDown();
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            authority.contribute(contribution("plugin-b", "second", 1));
                        } catch (RuntimeException | Error failure) {
                            edtFailure = failure;
                        } finally {
                            edtContributeDone.countDown();
                        }
                    });
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("edt dispatch interrupted", exception);
                } catch (InvocationTargetException exception) {
                    throw new IllegalStateException("edt dispatch failed", exception);
                }
            }
            return install;
        }
    }

    private static final class RecordingProvider implements EditorUiContributionProvider {
        private static final String ARTIFACT_SHA256 = "a".repeat(64);
        private static final String RECORD_SHA256 = "b".repeat(64);

        private final EditorUiFamily family;
        private final List<Long> generations = new ArrayList<>();
        private final List<List<String>> snapshots = new ArrayList<>();
        private EditorUiProviderAdmission admission;
        private int closedRegistrations;

        private RecordingProvider(final EditorUiFamily family) {
            this.family = family;
            this.admission = EditorUiProviderAdmission.safeMode(
                family,
                "ui.provider.mapping-not-verified"
            );
        }

        private void admit(final long generation) {
            admission = EditorUiProviderAdmission.admitted(
                family,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                    "5.3.02",
                    42,
                    ARTIFACT_SHA256,
                    "adapter.editor-ui." + family.name().toLowerCase(java.util.Locale.ROOT),
                    RECORD_SHA256
                )
            );
        }

        @Override
        public EditorUiFamily family() {
            return family;
        }

        @Override
        public EditorUiProviderAdmission admission() {
            return admission;
        }

        @Override
        public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            generations.add(hostGeneration);
            snapshots.add(contributions.stream().map(value -> (String) value.descriptor()).toList());
            return () -> closedRegistrations++;
        }
    }

    private static final class IncrementalRecordingProvider implements EditorUiContributionProvider {
        private EditorUiProviderAdmission admission = EditorUiProviderAdmission.safeMode(
            EditorUiFamily.PANEL,
            "ui.provider.mapping-not-verified"
        );
        private int applied;
        private int reconciled;
        private int closedRegistrations;

        private void admit(final long generation) {
            admission = EditorUiProviderAdmission.admitted(
                EditorUiFamily.PANEL,
                generation,
                new EditorUiProviderAdmission.VerificationEvidence(
                    "5.3.02",
                    42,
                    "a".repeat(64),
                    "adapter.editor-ui.panel",
                    "b".repeat(64)
                )
            );
        }

        @Override
        public EditorUiFamily family() {
            return EditorUiFamily.PANEL;
        }

        @Override
        public EditorUiProviderAdmission admission() {
            return admission;
        }

        @Override
        public boolean supportsIncrementalReconcile() {
            return true;
        }

        @Override
        public Registration apply(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions
        ) {
            applied++;
            return () -> closedRegistrations++;
        }

        @Override
        public Registration reconcile(
            final long hostGeneration,
            final List<EditorUiContribution<?>> contributions,
            final Registration existing
        ) {
            if (existing == null) {
                return contributions.isEmpty() ? null : apply(hostGeneration, contributions);
            }
            reconciled++;
            if (contributions.isEmpty()) {
                existing.close();
                return null;
            }
            return existing;
        }
    }
}
