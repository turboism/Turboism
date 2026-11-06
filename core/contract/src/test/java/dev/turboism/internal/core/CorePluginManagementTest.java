package dev.turboism.internal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.internal.core.CorePluginManagement.Author;
import dev.turboism.internal.core.CorePluginManagement.Dependency;
import dev.turboism.internal.core.CorePluginManagement.EventExport;
import dev.turboism.internal.core.CorePluginManagement.EventImport;
import dev.turboism.internal.core.CorePluginManagement.OperationResult;
import dev.turboism.internal.core.CorePluginManagement.Permission;
import dev.turboism.internal.core.CorePluginManagement.PluginDetails;
import dev.turboism.internal.core.CorePluginManagement.PluginInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CorePluginManagementTest {

    private static PluginInfo info(final String id) {
        return new PluginInfo(id, "Name " + id, "1.0.0", null, null, null, false, null, null, null, null);
    }

    @Test
    void pluginInfoRejectsBlankIdentity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginInfo(" ", "n", "1", "", "", "", false, Optional.empty(), "", List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginInfo("id", null, "1", "", "", "", false, Optional.empty(), "", List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginInfo("id", "n", "", "", "", "", false, Optional.empty(), "", List.of(), List.of()));
    }

    @Test
    void pluginInfoNormalizesOptionalFields() {
        final PluginInfo plugin = info("a");
        assertEquals("", plugin.description());
        assertEquals("DISCOVERED", plugin.effectiveState());
        assertEquals("DISCOVERED", plugin.desiredState());
        assertEquals(Optional.empty(), plugin.pendingOperation());
        assertEquals("other", plugin.category());
        assertEquals(List.of(), plugin.tags());
        assertEquals(List.of(), plugin.authors());
    }

    @Test
    void pluginInfoDesiredStateFollowsEffectiveState() {
        final PluginInfo plugin = new PluginInfo("a", "n", "1", "", "ENABLED", null, false, null, " ", null, null);
        assertEquals("ENABLED", plugin.desiredState());
        assertEquals("other", plugin.category());
    }

    @Test
    void pluginInfoDefensivelyCopiesLists() {
        final List<String> tags = new ArrayList<>(List.of("x"));
        final PluginInfo plugin = new PluginInfo("a", "n", "1", "", "", "", false, null, "c", tags, null);
        tags.add("y");
        assertEquals(List.of("x"), plugin.tags());
        assertThrows(UnsupportedOperationException.class, () -> plugin.tags().add("z"));
    }

    @Test
    void shortPluginInfoConstructorHasNoAuthors() {
        final PluginInfo plugin =
                new PluginInfo("a", "n", "1", "d", "E", "E", true, Optional.empty(), "c", List.of("t"));
        assertEquals(List.of(), plugin.authors());
        assertTrue(plugin.core());
    }

    @Test
    void legacyImplementationsCannotDiscardIntentThroughDefaultRecoveryApi() {
        final CorePluginManagement management = new Stub(List.of());
        assertEquals("PLUGIN_PENDING_UNAVAILABLE", management.pendingChanges().code());
        assertTrue(management.pendingChanges().changes().isEmpty());
        assertFalse(management.discardPendingChanges("untrusted").accepted());
    }

    @Test
    void pendingReviewDefensivelyCopiesTheExactDisplayedChanges() {
        final List<CorePluginManagement.PendingChange> mutable = new ArrayList<>();
        mutable.add(new CorePluginManagement.PendingChange("example.plugin", "INSTALL", "2.0.0"));
        final var review = new CorePluginManagement.PendingChanges("opaque", "PLUGIN_PENDING_READY", mutable);
        mutable.clear();
        assertEquals(1, review.changes().size());
        assertThrows(UnsupportedOperationException.class, () -> review.changes().clear());
    }

    @Test
    void detailsDefaultsToSummaryOfMatchingPlugin() {
        final PluginInfo first = info("first");
        final PluginInfo second = info("second");
        final CorePluginManagement management = new Stub(List.of(first, second));

        final PluginDetails details = management.details("second").orElseThrow();
        assertEquals(second, details.plugin());
        assertEquals("none", details.ui());
        assertFalse(details.hotReload());
        assertEquals(Optional.empty(), management.details("missing"));
    }

    @Test
    void detailsNormalizesNullsAndRejectsMissingPlugin() {
        final PluginDetails details = new PluginDetails(
                info("a"), null, null, null, null, null, null, null, true, null, null, null, null, null, null, null,
                null, true);
        assertEquals("", details.turboismApi());
        assertEquals("", details.license());
        assertEquals("none", details.ui());
        assertEquals("", details.i18nBaseName());
        assertEquals(Optional.empty(), details.website());
        assertEquals(Optional.empty(), details.readme());
        assertEquals(List.of(), details.dependencies());
        assertEquals(List.of(), details.eventImports());
        assertTrue(details.requiresCubism());
        assertTrue(details.hotReload());
        assertThrows(IllegalArgumentException.class, () -> PluginDetails.summary(null));
    }

    @Test
    void auxiliaryRecordsValidateIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> new Author(" ", null));
        assertEquals(Optional.empty(), new Author("n", null).email());
        assertThrows(IllegalArgumentException.class, () -> new Dependency("", null, null, null, null));
        assertEquals("", new Dependency("d", null, null, null, null).ordering());
        assertThrows(IllegalArgumentException.class, () -> new Permission(null, null, null));
        assertEquals("", new Permission("p", null, null).scope());
        assertThrows(IllegalArgumentException.class, () -> new EventExport(" ", null, null, null));
        assertEquals("", new EventExport("e", null, null, null).abiSha256());
        assertThrows(IllegalArgumentException.class, () -> new EventImport("", "e", null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> new EventImport("p", null, null, null, null, true));
        assertEquals("", new EventImport("p", "e", null, null, null, false).eventType());
    }

    @Test
    void operationResultFactoriesAndValidation() {
        assertTrue(OperationResult.accepted("OK", "done").accepted());
        final OperationResult rejected = OperationResult.rejected("nope");
        assertFalse(rejected.accepted());
        assertEquals("PLUGIN_OPERATION_REJECTED", rejected.code());
        assertThrows(IllegalArgumentException.class, () -> new OperationResult(true, " ", "m"));
        assertThrows(IllegalArgumentException.class, () -> new OperationResult(true, "c", null));
    }

    @Test
    void requestInstallDefaultRunsInstallSynchronously() {
        final Stub management = new Stub(List.of());
        final List<OperationResult> seen = new ArrayList<>();
        management.requestInstall(seen::add);
        assertEquals(List.of(management.installResult), seen);
        assertEquals(1, management.installs);
        management.close();
    }

    private static final class Stub implements CorePluginManagement {
        private final List<PluginInfo> plugins;
        private final OperationResult installResult = OperationResult.accepted("INSTALLED", "ok");
        private int installs;

        private Stub(final List<PluginInfo> plugins) {
            this.plugins = plugins;
        }

        @Override
        public List<PluginInfo> plugins() {
            return plugins;
        }

        @Override
        public OperationResult install() {
            installs++;
            return installResult;
        }

        @Override
        public OperationResult uninstall(final String pluginId) {
            return OperationResult.rejected("x");
        }

        @Override
        public OperationResult setEnabled(final String pluginId, final boolean enabled) {
            return OperationResult.rejected("x");
        }
    }
}
