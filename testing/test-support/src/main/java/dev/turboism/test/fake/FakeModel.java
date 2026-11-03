package dev.turboism.test.fake;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.ModelInstance;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Configurable {@link CubismModel} fake for module tests.
 *
 * <p>Collections are mutable and exposed for fixture setup. Missing lookups throw
 * {@link NoSuchElementException}. Optional services that are not configured report empty
 * defaults, except {@link #textures()}, {@link #parameterBindings(ParameterId)} and
 * {@link #parameterBindingBatch()} which stay fail-closed as in the SDK.
 */
public class FakeModel implements CubismModel {

    private ModelId id = new ModelId("model-1");
    private String name = "Fake Model";
    private Runnable updateAction = () -> {};
    private Parts partsOverride;
    private Drawables drawablesOverride;
    private ModelTextures textures;
    private ParameterBindingBatchOperations parameterBindingBatch;
    private Function<ParameterId, ParameterBindingOperations> parameterBindingsFactory;
    private Supplier<Optional<String>> glueProviderVersion = Optional::empty;

    /** Mutable parameter list returned by {@link #parameters()}. */
    public List<Parameter> parameters = new ArrayList<>();

    /** Mutable drawable list returned by {@link #drawables()}. */
    public List<Drawable> drawables = new ArrayList<>();

    /** Mutable part list returned by {@link #parts()}. */
    public List<Part> parts = new ArrayList<>();

    /** Mutable deformer list returned by {@link #deformers()}. */
    public List<Deformer> deformers = new ArrayList<>();

    /** Mutable glue list returned by {@link #glues()}. */
    public List<Glue> glues = new ArrayList<>();

    /** Mutable model-instance list returned by {@link #modelInstances()}. */
    public List<ModelInstance> instances = new ArrayList<>();

    /** Number of {@link #update()} invocations. */
    public final AtomicInteger updateCalls = new AtomicInteger();

    public FakeModel() {}

    public static FakeModel create() {
        return new FakeModel();
    }

    /** Resolves a parameter fixture by display id. */
    public FakeParameter parameter(final String parameterId) {
        return (FakeParameter) parameters().find(new ParameterId(parameterId));
    }

    public FakeModel addParameter(final Parameter parameter) {
        parameters.add(Objects.requireNonNull(parameter, "parameter"));
        return this;
    }

    public FakeModel addGlue(final Glue glue) {
        glues.add(Objects.requireNonNull(glue, "glue"));
        return this;
    }

    public FakeModel withTextures(final ModelTextures newTextures) {
        this.textures = newTextures;
        return this;
    }

    public FakeModel withParameterBindingBatch(final ParameterBindingBatchOperations batch) {
        this.parameterBindingBatch = batch;
        return this;
    }

    public FakeModel withParameterBindings(final Function<ParameterId, ParameterBindingOperations> factory) {
        this.parameterBindingsFactory = factory;
        return this;
    }

    public FakeModel withParts(final Parts newParts) {
        this.partsOverride = newParts;
        return this;
    }

    public FakeModel withDrawables(final Drawables newDrawables) {
        this.drawablesOverride = newDrawables;
        return this;
    }

    public FakeModel withUpdateAction(final Runnable action) {
        this.updateAction = Objects.requireNonNull(action, "action");
        return this;
    }

    public FakeModel withGlueProviderVersion(final Supplier<Optional<String>> supplier) {
        this.glueProviderVersion = Objects.requireNonNull(supplier, "supplier");
        return this;
    }

    @Override
    public ModelId id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void setName(final String newName) {
        this.name = newName;
    }

    @Override
    public Parameters parameters() {
        return new Parameters() {
            @Override
            public List<Parameter> all() {
                return List.copyOf(parameters);
            }

            @Override
            public Parameter find(final ParameterId parameterId) {
                return lookup(parameters, parameter -> parameter.id().equals(parameterId), parameterId.value());
            }
        };
    }

    @Override
    public Parts parts() {
        return partsOverride != null
                ? partsOverride
                : new Parts() {
                    @Override
                    public List<Part> all() {
                        return List.copyOf(parts);
                    }

                    @Override
                    public Part find(final PartId partId) {
                        return lookup(parts, part -> part.id().equals(partId), partId.value());
                    }
                };
    }

    @Override
    public Drawables drawables() {
        return drawablesOverride != null
                ? drawablesOverride
                : new Drawables() {
                    @Override
                    public List<Drawable> all() {
                        return List.copyOf(drawables);
                    }

                    @Override
                    public Drawable find(final ArtMeshId meshId) {
                        return lookup(drawables, drawable -> drawable.id().equals(meshId), meshId.value());
                    }
                };
    }

    @Override
    public Deformers deformers() {
        return new Deformers() {
            @Override
            public List<Deformer> all() {
                return List.copyOf(deformers);
            }

            @Override
            public Deformer find(final DeformerId deformerId) {
                return lookup(deformers, deformer -> deformer.id().equals(deformerId), deformerId.value());
            }
        };
    }

    @Override
    public Glues glues() {
        return new Glues() {
            @Override
            public List<Glue> all() {
                return List.copyOf(glues);
            }

            @Override
            public Glue find(final GlueId glueId) {
                return lookup(glues, glue -> glue.id().equals(glueId), glueId.value());
            }

            @Override
            public Optional<String> providerVersion() {
                return glueProviderVersion.get();
            }
        };
    }

    @Override
    public ParameterGroups parameterGroups() {
        return new ParameterGroups() {
            @Override
            public List<ParameterGroup> all() {
                return List.of();
            }

            @Override
            public ParameterGroup root() {
                return null;
            }

            @Override
            public ParameterGroup find(final dev.turboism.sdk.cubism.id.ParameterGroupId groupId) {
                return null;
            }
        };
    }

    @Override
    public ModelTextures textures() {
        if (textures == null) {
            throw new UnsupportedOperationException("Cubism texture-library access is unavailable.");
        }
        return textures;
    }

    @Override
    public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
        if (parameterBindingsFactory == null) {
            throw new UnsupportedOperationException("Cubism parameter-binding editing is unavailable.");
        }
        return parameterBindingsFactory.apply(parameterId);
    }

    @Override
    public ParameterBindingBatchOperations parameterBindingBatch() {
        if (parameterBindingBatch == null) {
            throw new UnsupportedOperationException("Cubism parameter-binding batch editing is unavailable.");
        }
        return parameterBindingBatch;
    }

    @Override
    public List<ModelInstance> modelInstances() {
        return List.copyOf(instances);
    }

    @Override
    public void update() {
        updateCalls.incrementAndGet();
        updateAction.run();
    }

    private static <T> T lookup(
            final List<T> values, final java.util.function.Predicate<T> predicate, final String key) {
        return values.stream().filter(predicate).findFirst().orElseThrow(() -> new NoSuchElementException(key));
    }
}
