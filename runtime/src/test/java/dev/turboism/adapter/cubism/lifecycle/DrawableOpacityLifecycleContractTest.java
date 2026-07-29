package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract for the first ArtMesh-opacity lifecycle vertical slice. */
class DrawableOpacityLifecycleContractTest {

    @Test
    void chainsBeforeHooksAndPublishesChangedCompletion() {
        final List<String> events = new ArrayList<>();
        final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new DrawableHooks() {
            @Override public float beforeSetDrawableOpacity(
                final Drawable drawable,
                final float opacity
            ) {
                events.add("before-a:" + opacity);
                return opacity * 0.5F;
            }
        })));
        coordinator.register(plugin("plugin-b", List.of(new DrawableHooks() {
            @Override public float beforeSetDrawableOpacity(
                final Drawable drawable,
                final float opacity
            ) {
                events.add("before-b:" + opacity);
                return Math.min(opacity, 0.4F);
            }
            @Override public void onDrawableOpacityChanged(
                final Drawable drawable,
                final float oldOpacity,
                final float newOpacity
            ) {
                events.add("on:" + oldOpacity + "->" + newOpacity);
            }
            @Override public void afterSetDrawableOpacity(
                final Drawable drawable,
                final float opacity
            ) {
                events.add("after:" + opacity);
            }
        })));
        final MutableDrawable drawable = new MutableDrawable(0.1F);

        coordinator.setOpacity(drawable, 1.0F, opacity -> {
            events.add("native:" + opacity);
            drawable.write(opacity);
        });
        coordinator.awaitIdle();

        assertEquals(0.4F, drawable.getOpacity());
        assertEquals(List.of(
            "before-a:1.0", "before-b:0.5", "native:0.4", "on:0.1->0.4", "after:0.4"
        ), events);
    }

    @Test
    void publishesAfterForNoChangeAndNothingForFailureOrRecursion() {
        final List<String> events = new ArrayList<>();
        final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
        coordinator.register(plugin("plugin-a", List.of(new DrawableHooks() {
            @Override public void onDrawableOpacityChanged(
                final Drawable drawable,
                final float oldOpacity,
                final float newOpacity
            ) {
                events.add("on");
            }
            @Override public void afterSetDrawableOpacity(
                final Drawable drawable,
                final float opacity
            ) {
                events.add("after:" + opacity);
            }
        })));
        final MutableDrawable drawable = new MutableDrawable(0.5F);

        coordinator.setOpacity(drawable, 0.5F, drawable::write);
        coordinator.awaitIdle();
        assertEquals(List.of("after:0.5"), events);

        events.clear();
        assertThrows(IllegalStateException.class, () -> coordinator.setOpacity(
            drawable, 0.8F, ignored -> { throw new IllegalStateException("native failed"); }
        ));
        coordinator.awaitIdle();
        assertEquals(List.of(), events);

        assertThrows(IllegalStateException.class, () -> coordinator.setOpacity(
            drawable,
            0.8F,
            opacity -> coordinator.setOpacity(drawable, opacity, drawable::write)
        ));
        coordinator.awaitIdle();
        assertEquals(0.5F, drawable.getOpacity());
        assertEquals(List.of(), events);
    }

    private static DrawableLifecycleCoordinator.PluginHooks plugin(
        final String id,
        final List<? extends DrawableHooks> entrypoints
    ) {
        return new DrawableLifecycleCoordinator.PluginHooks(descriptor(id), entrypoints, logger());
    }

    private static PluginDescriptor descriptor(final String id) {
        return new PluginDescriptor() {
            @Override public String id() { return id; }
            @Override public String name() { return id; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "UNLICENSED"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }

    private static final class MutableDrawable implements Drawable {
        private float opacity;

        private MutableDrawable(final float opacity) {
            this.opacity = opacity;
        }

        private void write(final float opacity) {
            this.opacity = opacity;
        }

        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
            return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
        }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return opacity; }
        @Override public dev.turboism.sdk.cubism.model.IntSequence masks() { return ints(); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() { return floats(); }
        @Override public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() { return floats(); }
        @Override public dev.turboism.sdk.cubism.model.IntSequence indices() { return ints(); }
        @Override public dev.turboism.sdk.cubism.model.Color multiplyColor() {
            return new dev.turboism.sdk.cubism.model.Color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        @Override public dev.turboism.sdk.cubism.model.Color screenColor() {
            return new dev.turboism.sdk.cubism.model.Color(0.0F, 0.0F, 0.0F, 1.0F);
        }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public dev.turboism.sdk.cubism.model.IntSequence parameters() { return ints(); }

        private static dev.turboism.sdk.cubism.model.IntSequence ints() {
            return new dev.turboism.sdk.cubism.model.IntSequence() {
                @Override public int size() { return 0; }
                @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
            };
        }

        private static dev.turboism.sdk.cubism.model.FloatSequence floats() {
            return new dev.turboism.sdk.cubism.model.FloatSequence() {
                @Override public int size() { return 0; }
                @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
            };
        }
    }
}
