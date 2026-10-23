package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TextureAtlasCompletePolygonProviderTest {
    @Test
    void meshOverhangCannotMakeAnImageLargerThanItsSourceBitmap() {
        var fixture = new Fixture(false);
        var image = fixture.source.textureManager.images.get(0);
        fixture.source.textureManager.handler.drawableUses.put(
                image.guid, Set.of(new SourceMesh(new float[] {-5000, -5000, 5000, -5000, 5000, 5000, -5000, 5000})));
        var provider = provider(resolver("5.3.02", true), "session", fixture, PROFILE_5_3_02);
        var item = provider.current().orElseThrow().items().get(0);
        var bounds = new java.awt.geom.Area();
        for (var ring : item.outline().rings()) {
            var path = new java.awt.geom.Path2D.Double();
            path.moveTo(ring[0][0], ring[0][1]);
            for (int i = 1; i < ring.length; i++) path.lineTo(ring[i][0], ring[i][1]);
            path.closePath();
            bounds.add(new java.awt.geom.Area(path));
        }
        assertEquals(new java.awt.geom.Rectangle2D.Double(0, 0, item.width(), item.height()), bounds.getBounds2D());
    }

    @Test
    void sourceMeshesAreUnionedInImageLocalCoordinatesAndWrittenAsOneTransaction() {
        var fixture = new Fixture(false);
        var first = fixture.source.textureManager.images.get(0);
        var second = fixture.source.textureManager.images.get(1);
        first.localToCanvas.translate(10, 20);
        fixture.source.textureManager.handler.drawableUses.put(
                first.guid, Set.of(new SourceMesh(new float[] {10, 20, 11, 20, 11, 21, 10, 21})));
        fixture.source.textureManager.handler.drawableUses.put(
                second.guid, Set.of(new SourceMesh(new float[] {0, 0, 1, 0, 1, 1, 0, 1})));
        var provider = provider(resolver("5.3.02", true), "session", fixture, PROFILE_5_3_02);
        var before = provider.current().orElseThrow();
        var outline = before.items().get(0).outline();
        assertNotNull(outline);
        assertEquals(1, outline.rings().size());
        assertEquals(
                Set.of("0.0,0.0", "1.0,0.0", "1.0,1.0", "0.0,1.0"),
                java.util.Arrays.stream(outline.rings().get(0))
                        .map(p -> p[0] + "," + p[1])
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(before, provider.current().orElseThrow(), "fresh outline reads must compare by content");
        var page = new TextureAtlasPolygonPlan(
                16,
                8,
                1D,
                List.of(
                        new TextureAtlasPolygonPlacement("texture-a", 1, 1, 0, 1),
                        new TextureAtlasPolygonPlacement("texture-b", 3, 1, 0, 1)),
                List.of(),
                TextureAtlasLayoutBackend.AUTO,
                Map.of());
        var plan = new TextureAtlasLayoutPlan(16, 8, 1, List.of("dense"), List.of(), 1D, List.of(), List.of(page));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(before, plan));
        assertEquals(1, fixture.document.dirtyCount);
        var after = provider.current().orElseThrow();
        assertTrue(after.currentPlan().placements().isEmpty(), "overlapping raw image bounds must remain readable");
        assertEquals(
                page.placements(), after.currentPlan().polygonPages().get(0).placements());
        assertEquals(before.items(), after.items());
    }

    @Test
    void legacyHostsKeepRectanglePackingEvenWhenSourceGeometryExists() {
        var fixture = new Fixture(false);
        var first = fixture.source.textureManager.images.get(0);
        fixture.source.textureManager.handler.drawableUses.put(
                first.guid, Set.of(new SourceMesh(new float[] {0, 0, 1, 0, 1, 1, 0, 1})));
        var profile = VerifiedTextureAtlasSelectorContract.profileFor("5.1.03").orElseThrow();
        var provider = provider(resolver("5.1.03", true), "session", fixture, profile);
        assertTrue(provider.current().orElseThrow().items().stream().allMatch(item -> item.outline() == null));
    }

    @Test
    void unknownUsersFallBackToFullImageBounds() {
        var fixture = new Fixture(false);
        var provider = provider(resolver("5.3.02", true), "session", fixture, PROFILE_5_3_02);
        assertTrue(provider.current().orElseThrow().items().stream().allMatch(item -> item.outline() == null));
    }
}
