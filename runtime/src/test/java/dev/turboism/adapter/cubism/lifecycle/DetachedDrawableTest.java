package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * Event Drawables captured from a host whose evaluated surface is partially
 * unavailable must still detach: fields that fail closed at capture time keep
 * their failure and rethrow it on read, instead of aborting the lifecycle
 * operation that produced the event.
 */
class DetachedDrawableTest {

    @Test
    void captureKeepsUnavailableFieldsAsDeferredFailures() {
        final DetachedDrawable detached = DetachedDrawable.capture(new PartialDrawable(), 0.5F);

        assertEquals(new ArtMeshId("ArtMeshA"), detached.id());
        assertEquals(0.5F, detached.getOpacity());
        assertEquals(7, detached.drawOrder());
        assertEquals(2, detached.parentPartIndex());
        assertEquals(3, detached.parentDeformerIndex());

        final IllegalStateException textureIndex = assertThrows(IllegalStateException.class, detached::textureIndex);
        assertEquals("Event snapshot property is unavailable: textureIndex", textureIndex.getMessage());
        assertThrows(IllegalStateException.class, detached::constantFlag);
        assertThrows(IllegalStateException.class, detached::dynamicFlag);
        assertThrows(IllegalStateException.class, detached::blendMode);
        assertThrows(IllegalStateException.class, detached::renderOrder);
        assertThrows(IllegalStateException.class, detached::multiplyColor);
        assertThrows(IllegalStateException.class, detached::screenColor);
    }

    @Test
    void lifecycleWriteProceedsWhenEvaluatedFieldsAreUnavailable() throws Exception {
        // DrawableLifecycleCoordinator captures a detached snapshot for event
        // publication; deferred evaluated fields must not block the native write
        // itself. A stale drawable (identity unreadable) still aborts the write.
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 8, ignored -> {}, Clock.systemUTC()),
                new NoOpSidecarDispatcher(),
                ignored -> {});
        try {
            final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
            final DrawableLifecycleCoordinator coordinator = new DrawableLifecycleCoordinator();
            coordinator.attachEventBroker(broker);
            final LockablePartialDrawable drawable = new LockablePartialDrawable();
            final java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean();
            coordinator.setLocked(drawable, true, value -> {
                applied.set(true);
                drawable.locked = value;
            });
            assertTrue(applied.get(), "the native write ran despite unavailable evaluated fields");
            assertTrue(drawable.locked());
            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.setLocked(
                            new Drawable() {
                                @Override
                                public ArtMeshId id() {
                                    throw new IllegalStateException("stale drawable view");
                                }

                                @Override
                                public byte constantFlag() {
                                    return 0;
                                }

                                @Override
                                public byte dynamicFlag() {
                                    return 0;
                                }

                                @Override
                                public BlendMode blendMode() {
                                    return BlendMode.NORMAL;
                                }

                                @Override
                                public int textureIndex() {
                                    return 0;
                                }

                                @Override
                                public int drawOrder() {
                                    return 0;
                                }

                                @Override
                                public int renderOrder() {
                                    return 0;
                                }

                                @Override
                                public float getOpacity() {
                                    return 1;
                                }

                                @Override
                                public IntSequence masks() {
                                    return emptyInts();
                                }

                                @Override
                                public FloatSequence vertexPositions() {
                                    return emptyFloats();
                                }

                                @Override
                                public FloatSequence vertexUvs() {
                                    return emptyFloats();
                                }

                                @Override
                                public IntSequence indices() {
                                    return emptyInts();
                                }

                                @Override
                                public Color multiplyColor() {
                                    return new Color(1, 1, 1, 1);
                                }

                                @Override
                                public Color screenColor() {
                                    return new Color(0, 0, 0, 1);
                                }

                                @Override
                                public int parentPartIndex() {
                                    return -1;
                                }

                                @Override
                                public int parentDeformerIndex() {
                                    return -1;
                                }

                                @Override
                                public IntSequence parameters() {
                                    return emptyInts();
                                }
                            },
                            true,
                            value -> {}));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void captureStillFailsWhenIdentityItselfIsUnreadable() {
        assertThrows(
                IllegalStateException.class,
                () -> DetachedDrawable.capture(
                        new Drawable() {
                            @Override
                            public ArtMeshId id() {
                                throw new IllegalStateException("stale drawable view");
                            }

                            @Override
                            public byte constantFlag() {
                                return 0;
                            }

                            @Override
                            public byte dynamicFlag() {
                                return 0;
                            }

                            @Override
                            public BlendMode blendMode() {
                                return BlendMode.NORMAL;
                            }

                            @Override
                            public int textureIndex() {
                                return 0;
                            }

                            @Override
                            public int drawOrder() {
                                return 0;
                            }

                            @Override
                            public int renderOrder() {
                                return 0;
                            }

                            @Override
                            public float getOpacity() {
                                return 1;
                            }

                            @Override
                            public IntSequence masks() {
                                return emptyInts();
                            }

                            @Override
                            public FloatSequence vertexPositions() {
                                return emptyFloats();
                            }

                            @Override
                            public FloatSequence vertexUvs() {
                                return emptyFloats();
                            }

                            @Override
                            public IntSequence indices() {
                                return emptyInts();
                            }

                            @Override
                            public Color multiplyColor() {
                                return new Color(1, 1, 1, 1);
                            }

                            @Override
                            public Color screenColor() {
                                return new Color(0, 0, 0, 1);
                            }

                            @Override
                            public int parentPartIndex() {
                                return -1;
                            }

                            @Override
                            public int parentDeformerIndex() {
                                return -1;
                            }

                            @Override
                            public IntSequence parameters() {
                                return emptyInts();
                            }
                        },
                        1F));
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public float get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    private static final class LockablePartialDrawable implements Drawable {
        private boolean locked;

        @Override
        public boolean locked() {
            return locked;
        }

        @Override
        public ArtMeshId id() {
            return new ArtMeshId("ArtMeshA");
        }

        @Override
        public byte constantFlag() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public byte dynamicFlag() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public BlendMode blendMode() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public int textureIndex() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public int drawOrder() {
            return 7;
        }

        @Override
        public int renderOrder() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public float getOpacity() {
            return 0.5F;
        }

        @Override
        public IntSequence masks() {
            return emptyInts();
        }

        @Override
        public FloatSequence vertexPositions() {
            return emptyFloats();
        }

        @Override
        public FloatSequence vertexUvs() {
            return emptyFloats();
        }

        @Override
        public IntSequence indices() {
            return emptyInts();
        }

        @Override
        public Color multiplyColor() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public Color screenColor() {
            throw new IllegalStateException(PartialDrawable.UNAVAILABLE);
        }

        @Override
        public int parentPartIndex() {
            return 2;
        }

        @Override
        public int parentDeformerIndex() {
            return 3;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }

    /** Evaluated-surface getters fail closed exactly like the editor overlay. */
    private static final class PartialDrawable implements Drawable {
        static final String UNAVAILABLE =
                "Core evaluated data is unavailable: Verified Core structural selector failed safely: cubism.core.model.get-canvas-info (RESOLUTION).";

        @Override
        public ArtMeshId id() {
            return new ArtMeshId("ArtMeshA");
        }

        @Override
        public byte constantFlag() {
            throw unavailable();
        }

        @Override
        public byte dynamicFlag() {
            throw unavailable();
        }

        @Override
        public BlendMode blendMode() {
            throw unavailable();
        }

        @Override
        public int textureIndex() {
            throw unavailable();
        }

        @Override
        public int drawOrder() {
            return 7;
        }

        @Override
        public int renderOrder() {
            throw unavailable();
        }

        @Override
        public float getOpacity() {
            return 0.5F;
        }

        @Override
        public IntSequence masks() {
            return emptyInts();
        }

        @Override
        public FloatSequence vertexPositions() {
            return emptyFloats();
        }

        @Override
        public FloatSequence vertexUvs() {
            return emptyFloats();
        }

        @Override
        public IntSequence indices() {
            return emptyInts();
        }

        @Override
        public Color multiplyColor() {
            throw unavailable();
        }

        @Override
        public Color screenColor() {
            throw unavailable();
        }

        @Override
        public int parentPartIndex() {
            return 2;
        }

        @Override
        public int parentDeformerIndex() {
            return 3;
        }

        @Override
        public IntSequence parameters() {
            return emptyInts();
        }

        private static IllegalStateException unavailable() {
            return new IllegalStateException(UNAVAILABLE);
        }
    }
}
