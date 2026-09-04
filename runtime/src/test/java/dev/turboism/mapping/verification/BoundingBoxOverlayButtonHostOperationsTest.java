package dev.turboism.mapping.verification;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.overlay.BoundingBoxOverlayButtonDescriptor;
import dev.turboism.ui.overlay.VerifiedBoundingBoxOverlayButtonHostOperations;
import dev.turboism.ui.overlay.NativeBoundingBoxOverlayButtonBridge;
import dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable coverage for {@link VerifiedBoundingBoxOverlayButtonHostOperations} ordering,
 * identity reuse, bound, unregister and close-cleanup behavior.
 *
 * <p>This test lives in {@code dev.turboism.mapping.verification} because the verified
 * resolver construction seam (package-private {@link VerifiedMemberResolver} constructor)
 * is only accessible from this package; the synthetic host below mirrors the exact overlay
 * selector shapes.</p>
 */
class BoundingBoxOverlayButtonHostOperationsTest {

    private EditorUiPluginResourceRegistry registry;
    private VerifiedBoundingBoxOverlayButtonHostOperations host;
    private Registration registration;

    @BeforeEach
    void setUp() throws Exception {
        resetRecorders();
        registry = new EditorUiPluginResourceRegistry();
        registry.register("plugin.overlay", iconLoader());
        host = new VerifiedBoundingBoxOverlayButtonHostOperations(resolver(), registry);
    }

    @AfterEach
    void tearDown() {
        if (registration != null) {
            registration.close();
        }
    }

    private Registration install(final List<BoundingBoxOverlayButtonDescriptor> descriptors) {
        registration = host.install(descriptors);
        return registration;
    }

    @Test
    void rejectsMoreThanEightContributionsWithATypedDiagnostic() {
        final List<BoundingBoxOverlayButtonDescriptor> nine = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            nine.add(descriptor("plugin.overlay", "button-" + index, index));
        }
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> host.install(nine)
        );
        assertTrue(failure.getMessage().contains("8"));
    }

    @Test
    void returnsCachedArrayInStableOrderAndReusesIdentitiesAcrossSnapshots() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final BoundingBoxOverlayButton second = button("second", 20);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second)
        ));

        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] initial = host.customButtonEntities(overlay, scene);
        assertEquals(2, initial.length);
        final Object entity1 = initial[0];
        final Object entity2 = initial[1];
        assertNotSame(entity1, entity2);
        assertEquals(2, OverlayHost.CREATED.size());
        assertSame(entity1, OverlayHost.CREATED.get(0));
        assertSame(entity2, OverlayHost.CREATED.get(1));
        // No proactive setEnabled(true): the native update$setupButton helper is the sole
        // enabler/setup/positioning path; setEnabled(false) is reserved for cleanup.
        assertTrue(Entity.ENABLED.isEmpty(), "native setup is the sole enable path");

        // The cached array is reused without a fresh allocation per update.
        assertSame(initial, host.customButtonEntities(overlay, scene));
        assertTrue(Entity.ENABLED.isEmpty());

        // A changed contribution snapshot retains the live registration: unchanged button
        // identities are reused and only the new identity creates an entity.
        final BoundingBoxOverlayButton third = button("third", 30);
        final Registration reconciled = host.reconcile(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second),
            descriptor("plugin.overlay", third)
        ), registration);
        assertSame(registration, reconciled, "reconcile must retain the native registration");

        final Object[] snapshot = host.customButtonEntities(overlay, scene);
        assertEquals(3, snapshot.length);
        assertSame(entity1, snapshot[0]);
        assertSame(entity2, snapshot[1]);
        assertEquals(3, OverlayHost.CREATED.size());
        assertSame(OverlayHost.CREATED.get(2), snapshot[2]);

        registration.close();
    }

    @Test
    void removedContributionIsDetachedImmediatelyAndStaysOutOfTheArray() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final BoundingBoxOverlayButton second = button("second", 20);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second)
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] initial = host.customButtonEntities(overlay, scene);
        final Object removed = initial[1];
        host.reconcile(List.of(
            descriptor("plugin.overlay", first)
        ), registration);

        final Object[] snapshot = host.customButtonEntities(overlay, scene);
        assertEquals(1, snapshot.length);
        assertSame(initial[0], snapshot[0]);

        // The removed contribution's entity was disabled, removed from the volatile set
        // and removed from the component children.
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removed && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removed));
        assertTrue(Entities.REMOVED.contains(removed));

        registration.close();
    }

    @Test
    void emptyReconcileDetachesCachedButtonsOnTheNextNativeUpdate() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final Registration registration = install(List.of(descriptor("plugin.overlay", first)));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object removed = host.customButtonEntities(overlay, scene)[0];

        host.reconcile(List.of(), registration);

        assertEquals(0, host.customButtonEntities(overlay, scene).length);
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removed && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removed));
        assertTrue(Entities.REMOVED.contains(removed));
    }

    @Test
    void closeDisablesDetachesEveryCustomEntityAndClearsTheSideTable() {
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", button("first", 10)),
            descriptor("plugin.overlay", button("second", 20))
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] buttons = host.customButtonEntities(overlay, scene);
        final Object first = buttons[0];
        final Object second = buttons[1];

        registration.close();

        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == first && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == second && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(first));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(second));
        assertTrue(Entities.REMOVED.contains(first));
        assertTrue(Entities.REMOVED.contains(second));

        // Close is idempotent for the side table and the bridge is reusable.
        registration.close();
        try (Registration ignored = host.install(List.of(
            descriptor("plugin.overlay", button("third", 30))
        ))) {
            assertEquals(1, host.customButtonEntities(overlay, scene).length);
        }
    }

    @Test
    void removedContributionListenerIsClickInertImmediatelyBeforeAnyNativeUpdate() {
        final java.util.concurrent.atomic.AtomicInteger clicks =
            new java.util.concurrent.atomic.AtomicInteger();
        final BoundingBoxOverlayButton first = button("first", 10, clicks::incrementAndGet);
        final BoundingBoxOverlayButton second = button("second", 20);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second)
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] initial = host.customButtonEntities(overlay, scene);
        final Object removed = initial[1];

        host.reconcile(List.of(descriptor("plugin.overlay", first)), registration);

        // The stale native listener is invoked immediately after unregister and before any
        // later native update: the plugin callback must not be invoked at all.
        final Click staleListener = OverlayHost.CLICKS.get(1);
        staleListener.click();
        assertEquals(0, clicks.get(), "stale listener must be click-inert immediately");

        // A current contribution's listener still routes to the plugin callback.
        OverlayHost.CLICKS.get(0).click();
        assertEquals(1, clicks.get());

        // The next native callback detaches the removed entity physically.
        final Object[] snapshot = host.customButtonEntities(overlay, scene);
        assertEquals(1, snapshot.length);
        assertSame(initial[0], snapshot[0]);
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removed && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removed));
        assertTrue(Entities.REMOVED.contains(removed));

        registration.close();
    }

    @Test
    void staleClickProxyCannotReactivateAcrossBridgeGenerations() {
        final java.util.concurrent.atomic.AtomicInteger clicks =
            new java.util.concurrent.atomic.AtomicInteger();
        final BoundingBoxOverlayButton button = button("same", 10, clicks::incrementAndGet);
        final BoundingBoxOverlayButtonDescriptor descriptor = descriptor("plugin.overlay", button);
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();

        final Registration first = install(List.of(descriptor));
        host.customButtonEntities(overlay, scene);
        final Click stale = OverlayHost.CLICKS.get(0);
        first.close();

        final Registration second = install(List.of(descriptor));
        host.customButtonEntities(overlay, scene);
        stale.click();
        assertEquals(0, clicks.get(), "old generation must stay inert after reinstall");
        OverlayHost.CLICKS.get(1).click();
        assertEquals(1, clicks.get(), "current generation remains active");
        second.close();
        registration = null;
    }

    @Test
    void failedBridgePublicationDoesNotPublishTheRequestedSnapshot() {
        final String property = "turboism.bounding-box-overlay.buttons";
        final Object external = new Object();
        System.getProperties().put(property, external);
        try {
            assertThrows(
                IllegalStateException.class,
                () -> host.install(List.of(descriptor("plugin.overlay", "rejected", 10)))
            );
            assertEquals(
                0,
                host.customButtonEntities(new OverlayHost(), new SceneGraph()).length,
                "failed installation must not leave a partial descriptor snapshot"
            );
            assertSame(external, System.getProperties().get(property));
        } finally {
            System.getProperties().remove(property, external);
        }

        registration = host.install(List.of(descriptor("plugin.overlay", "accepted", 10)));
        assertEquals(
            1,
            host.customButtonEntities(new OverlayHost(), new SceneGraph()).length,
            "bridge remains reusable after the rejected installation"
        );
    }

    @Test
    void reconcileWithNineDescriptorsRejectsBeforeMutationAndRetainsPreviousSnapshot() {
        final List<BoundingBoxOverlayButtonDescriptor> eight = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            eight.add(descriptor("plugin.overlay", "button-" + index, index));
        }
        final Registration registration = install(eight);
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] accepted = host.customButtonEntities(overlay, scene);
        assertEquals(8, accepted.length);

        final List<BoundingBoxOverlayButtonDescriptor> nine = new ArrayList<>(eight);
        nine.add(descriptor("plugin.overlay", "button-9", 9));
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> host.reconcile(nine, registration)
        );
        assertTrue(failure.getMessage().contains("8"));

        // Validation happens before mutation: the previous snapshot and live registration
        // are untouched, and close still cleans every accepted entity exactly once.
        final Object[] retained = host.customButtonEntities(overlay, scene);
        assertEquals(8, retained.length);
        for (int index = 0; index < 8; index++) {
            assertSame(accepted[index], retained[index]);
        }
        registration.close();
        assertTrue(Entity.ENABLED.stream().allMatch(call -> {
            final Object[] entry = (Object[]) call;
            return Boolean.FALSE.equals(entry[1]);
        }));
    }

    @Test
    void closeCleansEveryEntityAndSurfacesAggregatedResolverFailures() {
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", button("first", 10)),
            descriptor("plugin.overlay", button("second", 20))
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] buttons = host.customButtonEntities(overlay, scene);
        final Object first = buttons[0];
        final Object second = buttons[1];
        // The first entity's volatile removal fails on close.
        SceneGraph.POISONED.add(first);

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            registration::close
        );
        assertTrue(failure.getMessage().contains("cleanup"));

        // Later operations on the failing entity were still attempted: disable ran and the
        // component-children removal still executed after the volatile removal threw.
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == first && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entities.REMOVED.contains(first), "children removal runs after the failure");
        // The second entity was fully cleaned despite the first entity's failure.
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == second && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(second));
        assertTrue(Entities.REMOVED.contains(second));
        // The side table is cleared and the bridge properties are identity-removed even
        // though cleanup failed: the failure is observable and nothing is left installed.
        assertEquals(0, host.customButtonEntities(overlay, scene).length);
        assertNull(System.getProperties().get(
            "turboism.bounding-box-overlay.buttons"
        ));
        assertNull(System.getProperties().get(
            "turboism.bounding-box-overlay.setup-failure"
        ));
        try (Registration ignored = host.install(List.of(
            descriptor("plugin.overlay", button("third", 30))
        ))) {
            assertEquals(1, host.customButtonEntities(overlay, scene).length);
        }
    }

    @Test
    void removedEntityDetachFailureAttemptsEveryRemovedEntityAndSurfacesTheAggregation() {
        final java.util.concurrent.atomic.AtomicInteger clicks =
            new java.util.concurrent.atomic.AtomicInteger();
        final BoundingBoxOverlayButton first = button("first", 10);
        final BoundingBoxOverlayButton second = button("second", 20, clicks::incrementAndGet);
        final BoundingBoxOverlayButton third = button("third", 30);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second),
            descriptor("plugin.overlay", third)
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] initial = host.customButtonEntities(overlay, scene);
        final Object removedB = initial[1];
        final Object removedC = initial[2];

        host.reconcile(List.of(descriptor("plugin.overlay", first)), registration);
        // The second removed entity's volatile removal fails during the rebuild detach.
        SceneGraph.POISONED.add(removedB);

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> host.customButtonEntities(overlay, scene)
        );
        assertTrue(failure.getMessage().contains("cleanup"));

        // Every removed entity was still attempted even though one detach failed: disable
        // ran for both, the failing entity's children removal still ran, and the later
        // entity was fully detached.
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removedB && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removedC && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entities.REMOVED.contains(removedB), "children removal runs after the failure");
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removedC), "later entity still detached");
        assertTrue(Entities.REMOVED.contains(removedC));

        // The prior cached state stays available: after the injected failure clears, the
        // next native update retries, rebuilds successfully, and no removed plugin callback
        // becomes live again.
        SceneGraph.POISONED.clear();
        final Object[] snapshot = host.customButtonEntities(overlay, scene);
        assertEquals(1, snapshot.length);
        assertSame(initial[0], snapshot[0], "the retained entity identity is reused");
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removedB));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(removedC));
        OverlayHost.CLICKS.get(1).click();
        OverlayHost.CLICKS.get(2).click();
        assertEquals(0, clicks.get(), "removed contribution callbacks stay click-inert");

        registration.close();
    }

    @Test
    void failedRebuildDetachesItsCreatedEntitiesAndKeepsThePriorCacheRetryable() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final BoundingBoxOverlayButton second = button("second", 20);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second)
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object[] initial = host.customButtonEntities(overlay, scene);
        final Object kept = initial[0];

        // The rebuild creates the third button's entity and then fails creating the fourth
        // (its icon resource is missing). The created entity must not leak untracked.
        final BoundingBoxOverlayButton third = button("third", 30);
        host.reconcile(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", third),
            descriptor("plugin.overlay", buttonWithMissingIcon("broken", 40))
        ), registration);

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> host.customButtonEntities(overlay, scene)
        );
        assertTrue(failure.getMessage().contains("icon"));

        final Object created = OverlayHost.CREATED.get(2);
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == created && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(SceneGraph.REMOVED_VOLATILE.contains(created));
        assertTrue(Entities.REMOVED.contains(created));

        // The prior cached state stays available: dropping the broken contribution lets the
        // next native update retry and rebuild successfully.
        host.reconcile(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", third)
        ), registration);
        final Object[] snapshot = host.customButtonEntities(overlay, scene);
        assertEquals(2, snapshot.length);
        assertSame(kept, snapshot[0]);
        assertNotSame(created, snapshot[1], "the failed rebuild's entity is not resurrected");

        registration.close();
    }

    @Test
    void creationFailureAttachesCleanupFailuresOfAlreadyCreatedEntities() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final BoundingBoxOverlayButton second = button("second", 20);
        final Registration registration = install(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", second)
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        host.customButtonEntities(overlay, scene);

        final BoundingBoxOverlayButton third = button("third", 30);
        host.reconcile(List.of(
            descriptor("plugin.overlay", first),
            descriptor("plugin.overlay", third),
            descriptor("plugin.overlay", buttonWithMissingIcon("broken", 40))
        ), registration);
        // The entity this rebuild creates (the third entity, created index 2) fails its own
        // volatile removal during the created-entity cleanup.
        OverlayHost.poisonCreatedIndex = 2;

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> host.customButtonEntities(overlay, scene)
        );
        final Object created = OverlayHost.CREATED.get(2);
        assertTrue(failure.getMessage().contains("icon"));
        assertTrue(
            failure.getSuppressed().length >= 1,
            "cleanup failures attach to the primary creation failure"
        );
        assertTrue(failure.getSuppressed()[0].getMessage().contains("cleanup"));
        // Disable was still attempted on the created entity before the volatile failure.
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == created && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entities.REMOVED.contains(created));

        // The close path still completes every entity; unpoison so close is deterministic.
        OverlayHost.poisonCreatedIndex = -1;
        registration.close();
    }

    @Test
    void emptySnapshotCleanupFailureIsSurfacedAndTheSideTableIsCleared() {
        final BoundingBoxOverlayButton first = button("first", 10);
        final Registration registration = install(List.of(descriptor("plugin.overlay", first)));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        final Object removed = host.customButtonEntities(overlay, scene)[0];
        SceneGraph.POISONED.add(removed);

        host.reconcile(List.of(), registration);

        // The empty-snapshot cleanup attempts every cached entity, clears the side table
        // and surfaces the aggregated failure through the fail-open diagnostic channel.
        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> host.customButtonEntities(overlay, scene)
        );
        assertTrue(failure.getMessage().contains("cleanup"));
        assertTrue(Entity.ENABLED.stream().anyMatch(call -> {
            final Object[] entry = (Object[]) call;
            return entry[0] == removed && Boolean.FALSE.equals(entry[1]);
        }));
        assertTrue(Entities.REMOVED.contains(removed), "children removal runs after the failure");

        // The side table is cleared: a later native update is a diagnostic-free empty result.
        assertEquals(0, host.customButtonEntities(overlay, scene).length);
        registration.close();
    }
    @Test
    void type4ByteAbgrIconsReachTheHostConstructorNormalizedToTypeIntArgb() throws Exception {
        // The production rejection shape: ImageIO decodes common 8-bit RGBA PNGs (the
        // overlay icon files) as TYPE_4BYTE_ABGR, which the host's WritableImage
        // constructor rejects. The fixture is built from a directly constructed
        // TYPE_4BYTE_ABGR source with known pixels, including partial alpha.
        final BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_4BYTE_ABGR);
        source.setRGB(0, 0, 0x80FF8040);
        source.setRGB(1, 0, 0x40201008);
        source.setRGB(0, 1, 0x00000000);
        source.setRGB(1, 1, 0xFF3C2B1A);
        final Path icon = Files.createTempFile("turboism-overlay-icon-abgr-", ".png");
        assertTrue(ImageIO.write(source, "png", icon.toFile()), "PNG writer must accept the ABGR fixture");
        assertEquals(
            BufferedImage.TYPE_4BYTE_ABGR,
            ImageIO.read(icon.toUri().toURL()).getType(),
            "the fixture must decode as TYPE_4BYTE_ABGR, the rejected production shape"
        );

        final URL iconUrl = icon.toUri().toURL();
        registry.register("plugin.overlay.abgr", new ClassLoader(
            BoundingBoxOverlayButtonHostOperationsTest.class.getClassLoader()
        ) {
            @Override
            public URL getResource(final String name) {
                return "icons/fit.png".equals(name) ? iconUrl : super.getResource(name);
            }
        });
        final Registration registration = install(List.of(
            descriptor("plugin.overlay.abgr", button("first", 10))
        ));
        final Object overlay = new OverlayHost();
        final Object scene = new SceneGraph();
        host.customButtonEntities(overlay, scene);

        // Every icon variant reaches the fake host constructor normalized to
        // TYPE_INT_ARGB with width, height and every pixel/alpha value preserved.
        final List<BufferedImage> captured = WritableImage.CAPTURED;
        assertEquals(4, captured.size(), "normal, hover, pressed and disabled variants");
        for (BufferedImage image : captured) {
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
            assertEquals(2, image.getWidth());
            assertEquals(2, image.getHeight());
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals(source.getRGB(x, y), image.getRGB(x, y));
                }
            }
        }
        registration.close();
    }

    private static BoundingBoxOverlayButton buttonWithMissingIcon(final String id, final int order) {
        return new BoundingBoxOverlayButton(
            id,
            "Overlay button " + id,
            new BoundingBoxOverlayButton.IconVariants(
                "icons/missing.png",
                Optional.of("icons/fit.png"),
                Optional.empty(),
                Optional.empty()
            ),
            order,
            () -> { }
        );
    }

    private static BoundingBoxOverlayButtonDescriptor descriptor(
        final String pluginId,
        final BoundingBoxOverlayButton button
    ) {
        return new BoundingBoxOverlayButtonDescriptor(pluginId, button);
    }

    private static BoundingBoxOverlayButtonDescriptor descriptor(
        final String pluginId,
        final String id,
        final int order
    ) {
        return new BoundingBoxOverlayButtonDescriptor(pluginId, button(id, order));
    }

    private static BoundingBoxOverlayButton button(final String id, final int order) {
        return new BoundingBoxOverlayButton(
            id,
            "Overlay button " + id,
            new BoundingBoxOverlayButton.IconVariants(
                "icons/fit.png",
                Optional.of("icons/fit.png"),
                Optional.empty(),
                Optional.empty()
            ),
            order,
            () -> { }
        );
    }

    private static BoundingBoxOverlayButton button(
        final String id,
        final int order,
        final Runnable onClick
    ) {
        return new BoundingBoxOverlayButton(
            id,
            "Overlay button " + id,
            new BoundingBoxOverlayButton.IconVariants(
                "icons/fit.png",
                Optional.of("icons/fit.png"),
                Optional.empty(),
                Optional.empty()
            ),
            order,
            onClick
        );
    }


    private static VerifiedMemberResolver resolver() {
        return new VerifiedMemberResolver(
            plan(
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.button.create",
                    internalName(OverlayHost.class),
                    "createButton",
                    "(L" + internalName(IconSet.class) + ";L" + internalName(Click.class)
                        + ";)L" + internalName(ButtonEntity.class) + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.button.set-enabled",
                    internalName(Entity.class),
                    "setEnabled",
                    "(Z)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.scene.component-objects",
                    internalName(SceneGraph.class),
                    "getObjectsOnComponent",
                    "()L" + internalName(Entity.class) + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.entity.children",
                    internalName(Entity.class),
                    "getChildren",
                    "()L" + internalName(Entities.class) + ";",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.scene.remove-volatile",
                    internalName(SceneGraph.class),
                    "removeVolatileEntity",
                    "(L" + internalName(Entity.class) + ";)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.method(
                    "cubism.ui-bounding-box-overlay.entities.remove",
                    internalName(Entities.class),
                    "remove",
                    "(L" + internalName(Entity.class) + ";)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.constructor(
                    "cubism.ui-bounding-box-overlay.writable-image.create",
                    internalName(WritableImage.class),
                    "(Ljava/awt/image/BufferedImage;)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.constructor(
                    "cubism.ui-bounding-box-overlay.icon-set.create",
                    internalName(IconSet.class),
                    "(" + ("L" + internalName(WritableImage.class) + ";").repeat(7) + ")V",
                    StaticSelector.ACCESS_PUBLIC
                )
            ),
            BoundingBoxOverlayButtonHostOperationsTest.class.getClassLoader()
        );
    }

    private static VerifiedAccessPlan plan(final StaticSelector... selectors) {
        final HostArtifactFingerprint fingerprint = new HostArtifactFingerprint("5.3.02", 1, "a".repeat(64));
        final StaticVerificationRecord record = new StaticVerificationRecord(
            "fixture.overlay-buttons",
            "adapter.editor-ui.bounding-box-overlay-button",
            List.of("cubism.editor-ui.bounding-box-overlay-button"),
            "5.3.02",
            "cubism-5.3.02",
            fingerprint,
            "cubism-ref/verification/fixture.json",
            "runtime-adapter",
            "test",
            Instant.parse("2026-07-10T00:00:00Z"),
            "Fail closed.",
            List.of(selectors)
        );
        final StaticVerificationReport report = new StaticVerificationReport(
            fingerprint,
            fingerprint,
            true,
            List.of(selectors).stream()
                .map(selector -> new StaticSelectorResult(
                    selector,
                    StaticVerificationStatus.VERIFIED_STATIC,
                    "verified"
                ))
                .toList()
        );
        return VerifiedAccessPlan.from(record, report);
    }

    private static String internalName(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static void resetRecorders() {
        OverlayHost.CREATED.clear();
        OverlayHost.CLICKS.clear();
        OverlayHost.poisonCreatedIndex = -1;
        Entity.ENABLED.clear();
        SceneGraph.REMOVED_VOLATILE.clear();
        SceneGraph.POISONED.clear();
        Entities.REMOVED.clear();
        WritableImage.CAPTURED.clear();
    }

    private static ClassLoader iconLoader() throws IOException {
        final Path icon = Files.createTempFile("turboism-overlay-icon-", ".png");
        ImageIO.write(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            "png",
            icon.toFile()
        );
        final URL iconUrl = icon.toUri().toURL();
        return new ClassLoader(BoundingBoxOverlayButtonHostOperationsTest.class.getClassLoader()) {
            @Override
            public URL getResource(final String name) {
                return "icons/fit.png".equals(name) ? iconUrl : super.getResource(name);
            }
        };
    }

    public interface Click {
        Object click();
    }

    public static final class WritableImage {
        public static final List<BufferedImage> CAPTURED = new ArrayList<>();

        public WritableImage(final BufferedImage image) {
            CAPTURED.add(image);
        }
    }

    public static final class IconSet {
        private final List<WritableImage> images = new ArrayList<>();

        public IconSet(
            final WritableImage first,
            final WritableImage second,
            final WritableImage third,
            final WritableImage fourth,
            final WritableImage fifth,
            final WritableImage sixth,
            final WritableImage seventh
        ) {
            images.add(first);
            images.add(second);
            images.add(third);
            images.add(fourth);
            images.add(fifth);
            images.add(sixth);
            images.add(seventh);
        }
    }

    public static class Entity {
        public static final List<Object[]> ENABLED = new ArrayList<>();

        public void setEnabled(final boolean enabled) {
            ENABLED.add(new Object[] {this, enabled});
        }

        public Entities getChildren() {
            return new Entities();
        }
    }

    public static final class ButtonEntity extends Entity {
    }

    public static final class Entities {
        public static final List<Object> REMOVED = new ArrayList<>();

        public void remove(final Entity entity) {
            REMOVED.add(entity);
        }
    }

    public static final class SceneGraph {
        public static final List<Object> REMOVED_VOLATILE = new ArrayList<>();
        public static final List<Object> POISONED = new ArrayList<>();
        private final Entity objects = new Entity();

        public void removeVolatileEntity(final Entity entity) {
            if (POISONED.remove(entity)
                || (OverlayHost.poisonCreatedIndex >= 0
                    && OverlayHost.CREATED.size() > OverlayHost.poisonCreatedIndex
                    && entity == OverlayHost.CREATED.get(OverlayHost.poisonCreatedIndex))) {
                throw new IllegalStateException("injected removeVolatileEntity failure");
            }
            REMOVED_VOLATILE.add(entity);
        }

        public Entity getObjectsOnComponent() {
            return objects;
        }
    }

    public static final class OverlayHost {
        public static final List<Object> CREATED = new ArrayList<>();
        public static final List<Click> CLICKS = new ArrayList<>();
        /** Creation-order keyed poison: -1 disables; the entity created at this index fails volatile removal. */
        public static volatile int poisonCreatedIndex = -1;

        public ButtonEntity createButton(final IconSet icons, final Click click) {
            final ButtonEntity entity = new ButtonEntity();
            CREATED.add(entity);
            CLICKS.add(click);
            return entity;
        }
    }
}
