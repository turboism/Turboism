package dev.turboism.core.runtime.psd;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.plugin.Registration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registry provenance fixtures only; no plugin permissions, live host or native writes exercised. */
class PsdEditRegistryTest {
    @TempDir
    Path root;

    private final PsdEditRegistry.Binding binding = new PsdEditRegistry.Binding("session-a", 1);

    @Test
    void resolvesOnlyRegisteredObjectIdentityAndNeverCallsHandleCode() throws Exception {
        final var registry = new PsdEditRegistry();
        final var file = new ExplosiveHandle();
        final var allocation = allocation();
        registry.register(binding, file, allocation);
        assertSame(allocation, registry.requireFile(binding, file));
        assertThrows(SecurityException.class, () -> registry.requireFile(binding, new ExplosiveHandle()));
        assertThrows(IllegalStateException.class, () -> registry.register(binding, file, allocation));
    }

    @Test
    void anotherPluginRegistryCannotBorrowAHandleOrRevision() throws Exception {
        final var first = new PsdEditRegistry();
        final var second = new PsdEditRegistry();
        final var file = new ExplosiveHandle();
        final var allocation = allocation();
        first.register(binding, file, allocation);
        final var snapshot = PsdStableSnapshot.capture(allocation);
        final var revision = first.issueRevision(binding, file, snapshot);
        assertSame(snapshot, first.requireRevision(binding, file, revision));
        assertThrows(SecurityException.class, () -> second.requireFile(binding, file));
        assertThrows(SecurityException.class, () -> second.requireRevision(binding, file, revision));
    }

    @Test
    void wrongSessionOrGenerationCannotResolveSameHandle() throws Exception {
        final var registry = new PsdEditRegistry();
        final var file = new ExplosiveHandle();
        registry.register(binding, file, allocation());
        assertThrows(
                SecurityException.class, () -> registry.requireFile(new PsdEditRegistry.Binding("session-b", 1), file));
        assertThrows(
                SecurityException.class, () -> registry.requireFile(new PsdEditRegistry.Binding("session-a", 2), file));
    }

    @Test
    void revisionsAreOpaqueAndBoundToExactlyOneHandle() throws Exception {
        final var registry = new PsdEditRegistry();
        final var first = new ExplosiveHandle();
        final var second = new ExplosiveHandle();
        final var allocation = allocation();
        registry.register(binding, first, allocation);
        registry.register(binding, second, allocation());
        final var token = registry.issueRevision(binding, first, PsdStableSnapshot.capture(allocation));
        assertThrows(SecurityException.class, () -> registry.requireRevision(binding, second, token));
        final PsdFileRevision forged = new PsdFileRevision() {
            @Override
            public boolean equals(Object other) {
                throw new AssertionError("equals invoked");
            }

            @Override
            public int hashCode() {
                throw new AssertionError("hashCode invoked");
            }
        };
        assertThrows(SecurityException.class, () -> registry.requireRevision(binding, first, forged));
    }

    @Test
    void stageFromAnotherAllocationOrLiveFileCannotBeIssued() throws Exception {
        final var registry = new PsdEditRegistry();
        final var file = new ExplosiveHandle();
        final var own = allocation();
        registry.register(binding, file, own);
        final var other = PsdStableSnapshot.capture(allocation());
        assertThrows(SecurityException.class, () -> registry.issueRevision(binding, file, other));
        final var live = new PsdStableSnapshot.Snapshot(own.validatedPath(), "a".repeat(64), 1);
        assertThrows(SecurityException.class, () -> registry.issueRevision(binding, file, live));
    }

    @Test
    void retirementReleasesTokenSlotWithoutDeletingStage() throws Exception {
        final var registry = new PsdEditRegistry();
        final var file = new ExplosiveHandle();
        final var allocation = allocation();
        registry.register(binding, file, allocation);
        final var snapshot = PsdStableSnapshot.capture(allocation);
        final var first = registry.issueRevision(binding, file, snapshot);
        final var second = registry.issueRevision(binding, file, snapshot);
        assertThrows(IllegalStateException.class, () -> registry.issueRevision(binding, file, snapshot));
        registry.retireRevision(binding, file, first);
        assertThrows(SecurityException.class, () -> registry.requireRevision(binding, file, first));
        assertSame(snapshot, registry.requireRevision(binding, file, second));
        assertNotNull(registry.issueRevision(binding, file, snapshot));
        assertTrue(Files.exists(snapshot.path()));
        assertTrue(Files.exists(allocation.validatedPath()));
    }

    @Test
    void handleQuotaIsBoundedAndRevocationFreesSlot() throws Exception {
        final var registry = new PsdEditRegistry();
        final var files = new ArrayList<PsdEditFile>();
        final var allocation = allocation();
        for (int i = 0; i < PsdEditRegistry.MAX_HANDLES; i++) {
            final var file = new ExplosiveHandle();
            files.add(file);
            registry.register(binding, file, allocation);
        }
        final var ninth = new ExplosiveHandle();
        assertThrows(IllegalStateException.class, () -> registry.register(binding, ninth, allocation));
        registry.revoke(files.get(0));
        registry.revoke(files.get(0));
        registry.register(binding, ninth, allocation);
        assertThrows(SecurityException.class, () -> registry.requireFile(binding, files.get(0)));
        assertSame(allocation, registry.requireFile(binding, ninth));
    }

    @Test
    void bindingRevocationAndCloseStopAdmissionButRetainAllFiles() throws Exception {
        final var registry = new PsdEditRegistry();
        final var first = new ExplosiveHandle();
        final var second = new ExplosiveHandle();
        final var allocation = allocation();
        final var other = new PsdEditRegistry.Binding("session-b", 1);
        registry.register(binding, first, allocation);
        registry.register(other, second, allocation);
        final var snapshot = PsdStableSnapshot.capture(allocation);
        final var token = registry.issueRevision(binding, first, snapshot);
        registry.revokeBinding(binding);
        assertThrows(SecurityException.class, () -> registry.requireRevision(binding, first, token));
        assertSame(allocation, registry.requireFile(other, second));
        registry.close();
        registry.close();
        assertThrows(IllegalStateException.class, () -> registry.requireFile(other, second));
        assertThrows(IllegalStateException.class, () -> registry.register(binding, new ExplosiveHandle(), allocation));
        assertTrue(Files.exists(snapshot.path()));
        assertTrue(Files.exists(allocation.validatedPath()));
    }

    private PsdTemporaryFile allocation() throws Exception {
        final var allocation = PsdTemporaryFile.createIn(root);
        Files.writeString(allocation.validatedPath(), "non-PSD registry fixture");
        return allocation;
    }

    private static final class ExplosiveHandle implements PsdEditFile {
        @Override
        public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
            throw new AssertionError("open invoked");
        }

        @Override
        public Registration observeSaves(Consumer<PsdFileRevision> listener) {
            throw new AssertionError("observe invoked");
        }

        @Override
        public CompletionStage<PsdFileOperationResult> stop() {
            throw new AssertionError("stop invoked");
        }

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("equals invoked");
        }

        @Override
        public int hashCode() {
            throw new AssertionError("hashCode invoked");
        }

        @Override
        public String toString() {
            throw new AssertionError("toString invoked");
        }
    }
}
