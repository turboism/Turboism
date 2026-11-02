package dev.turboism.test.fake;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.ui.appearance.model.DrawableAppearance;
import dev.turboism.test.Stubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Configurable {@link Drawable} fake with empty sequences and neutral render values by default. */
public class FakeDrawable implements Drawable {

    private ArtMeshId id;
    private String guid;
    private String name;
    private float opacity = 1f;
    private int drawOrder;
    private DrawableAppearance ui = DrawableAppearance.unavailable();
    private boolean failId;
    private boolean failGuid;
    private boolean failName;

    /** Parameter bindings exposed through {@link #getParameterBindings()}; tests may mutate this list. */
    public final List<ParameterBinding> bindings = new ArrayList<>();

    /** Morph Targets exposed through {@link #morphTargets()}; tests may mutate this list. */
    public final List<MorphTarget> morphTargets = new ArrayList<>();

    public FakeDrawable(final String id) {
        this(id, id, id);
    }

    public FakeDrawable(final String id, final String name) {
        this(id, name, id);
    }

    public FakeDrawable(final String id, final String name, final String guid) {
        this.id = new ArtMeshId(Objects.requireNonNull(id, "id"));
        this.name = Objects.requireNonNull(name, "name");
        this.guid = Objects.requireNonNull(guid, "guid");
    }

    public FakeDrawable withUi(final DrawableAppearance newUi) {
        this.ui = newUi;
        return this;
    }

    public FakeDrawable failId() {
        this.failId = true;
        return this;
    }

    public FakeDrawable failGuid() {
        this.failGuid = true;
        return this;
    }

    public FakeDrawable failName() {
        this.failName = true;
        return this;
    }

    @Override
    public ArtMeshId id() {
        if (failId) {
            throw new UnsupportedOperationException("id unavailable");
        }
        return id;
    }

    @Override
    public String guid() {
        if (failGuid) {
            throw new UnsupportedOperationException("guid unavailable");
        }
        return guid;
    }

    @Override
    public String name() {
        if (failName) {
            throw new UnsupportedOperationException("name unavailable");
        }
        return name;
    }

    @Override
    public void setName(final String newName) {
        this.name = newName;
    }

    @Override
    public DrawableAppearance ui() {
        return ui;
    }

    @Override
    public float getOpacity() {
        return opacity;
    }

    @Override
    public void setOpacity(final float newOpacity) {
        this.opacity = newOpacity;
    }

    @Override
    public int drawOrder() {
        return drawOrder;
    }

    @Override
    public void setDrawOrder(final int newDrawOrder) {
        this.drawOrder = newDrawOrder;
    }

    @Override
    public int renderOrder() {
        return 0;
    }

    @Override
    public int textureIndex() {
        return 0;
    }

    @Override
    public BlendMode blendMode() {
        return BlendMode.NORMAL;
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
    public byte constantFlag() {
        return 0;
    }

    @Override
    public byte dynamicFlag() {
        return 0;
    }

    @Override
    public Color multiplyColor() {
        return new Color(1f, 1f, 1f, 1f);
    }

    @Override
    public Color screenColor() {
        return new Color(0f, 0f, 0f, 0f);
    }

    @Override
    public FloatSequence vertexPositions() {
        return Stubs.emptyFloats();
    }

    @Override
    public FloatSequence vertexUvs() {
        return Stubs.emptyFloats();
    }

    @Override
    public IntSequence indices() {
        return Stubs.emptyInts();
    }

    @Override
    public IntSequence masks() {
        return Stubs.emptyInts();
    }

    @Override
    public IntSequence parameters() {
        return Stubs.emptyInts();
    }

    @Override
    public List<ParameterBinding> getParameterBindings() {
        return bindings;
    }

    @Override
    public MorphTargets morphTargets() {
        return new MorphTargets() {
            @Override
            public List<MorphTarget> all() {
                return List.copyOf(morphTargets);
            }

            @Override
            public MorphTarget find(final ParameterId parameterId) {
                return morphTargets.stream()
                        .filter(target -> target.parameterId().equals(parameterId))
                        .findFirst()
                        .orElseThrow(() -> new java.util.NoSuchElementException(parameterId.value()));
            }

            @Override
            public Optional<MorphTarget> findById(final ParameterId parameterId) {
                return morphTargets.stream()
                        .filter(target -> target.parameterId().equals(parameterId))
                        .findFirst();
            }
        };
    }
}
