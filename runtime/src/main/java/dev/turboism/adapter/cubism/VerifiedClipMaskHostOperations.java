package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Clip-mask HostOperations backed only by the exact verified selector slice. */
public final class VerifiedClipMaskHostOperations implements ClipMaskReadAdapter.HostOperations {

    private static final String APP_INSTANCE = "cubism.clipmask.app-controller.instance";
    private static final String CURRENT_DOCUMENT = "cubism.clipmask.app-controller.current-document";
    private static final String DOCUMENT_CLASS = "cubism.clipmask.document.class";
    private static final String MODELING_DOCUMENT_CLASS = "cubism.clipmask.modeling-document.class";
    private static final String MODEL_SOURCE = "cubism.clipmask.modeling-document.model-source";
    private static final String MODEL_SOURCE_CLASS = "cubism.clipmask.model-source.class";
    private static final String ALL_ART_MESHES = "cubism.clipmask.model-source.all-art-meshes";
    private static final String ART_MESH_SOURCE_CLASS = "cubism.clipmask.art-mesh-source.class";
    private static final String DRAWABLE_SOURCE_CLASS = "cubism.clipmask.drawable-source.class";
    private static final String DRAWABLE_GUID = "cubism.clipmask.drawable-source.guid";
    private static final String CLIP_GUID_LIST = "cubism.clipmask.drawable-source.clip-guid-list";
    private static final String INVERTED = "cubism.clipmask.drawable-source.invert-clipping-mask";
    private static final String DRAWABLE_GUID_CLASS = "cubism.clipmask.drawable-guid.class";
    private static final String GUID_CLASS = "cubism.clipmask.guid.class";
    private static final String GUID_VALUE = "cubism.clipmask.guid.value";

    private static final Set<String> METHOD_ALIASES_USED = Set.of(
        APP_INSTANCE,
        CURRENT_DOCUMENT,
        MODEL_SOURCE,
        ALL_ART_MESHES,
        DRAWABLE_GUID,
        CLIP_GUID_LIST,
        INVERTED,
        GUID_VALUE
    );
    private static final Set<String> CLASS_ALIASES_REQUIRED = Set.of(
        "cubism.clipmask.app-controller.class",
        DOCUMENT_CLASS,
        MODELING_DOCUMENT_CLASS,
        MODEL_SOURCE_CLASS,
        ART_MESH_SOURCE_CLASS,
        DRAWABLE_SOURCE_CLASS,
        DRAWABLE_GUID_CLASS,
        GUID_CLASS
    );

    /** Aliases independently required by this implementation, not copied from its trust manifest. */
    public static final Set<String> REQUIRED_ALIASES = requiredAliases();

    private static Set<String> requiredAliases() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(METHOD_ALIASES_USED);
        aliases.addAll(CLASS_ALIASES_REQUIRED);
        return Set.copyOf(aliases);
    }

    /** Exact method aliases invoked by this implementation. */
    public static Set<String> methodAliasesUsed() {
        return METHOD_ALIASES_USED;
    }

    /** Exact class aliases used for runtime type validation by this implementation. */
    public static Set<String> classAliasesUsed() {
        return CLASS_ALIASES_REQUIRED;
    }

    private final VerifiedMemberResolver resolver;
    private final String hostVersion;

    public VerifiedClipMaskHostOperations(
        final VerifiedMemberResolver resolver,
        final String hostVersion
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostVersion = requireText(hostVersion, "hostVersion");
    }

    @Override
    public String hostVersion() {
        return hostVersion;
    }

    @Override
    public boolean supportsClipMaskRead() {
        return ClipMaskVerificationManifest.CUBISM_VERSION.equals(hostVersion);
    }

    @Override
    public List<ClipMaskSnapshot> clipMasks() {
        try {
            final Object appController = resolver.invokeStatic(APP_INSTANCE);
            if (appController == null) {
                return List.of();
            }
            final Object document = resolver.invoke(CURRENT_DOCUMENT, appController);
            if (document == null) {
                return List.of();
            }
            requireInstance(DOCUMENT_CLASS, document);
            if (!resolver.isInstance(MODELING_DOCUMENT_CLASS, document)) {
                return List.of();
            }
            final Object modelSource = resolver.invoke(MODEL_SOURCE, document);
            if (modelSource == null) {
                return List.of();
            }
            requireInstance(MODEL_SOURCE_CLASS, modelSource);
            final Object rawMeshes = resolver.invoke(ALL_ART_MESHES, modelSource);
            if (rawMeshes == null) {
                return List.of();
            }
            if (!(rawMeshes instanceof Iterable<?> meshes)) {
                throw validationFailure();
            }

            final List<ClipMaskSnapshot> snapshots = new ArrayList<>();
            for (Object mesh : meshes) {
                requireInstance(ART_MESH_SOURCE_CLASS, mesh);
                requireInstance(DRAWABLE_SOURCE_CLASS, mesh);
                final Object rawClipList = resolver.invoke(CLIP_GUID_LIST, mesh);
                if (!(rawClipList instanceof Iterable<?> clipGuids)) {
                    throw validationFailure();
                }
                final List<String> orderedMaskIds = guidValues(clipGuids);
                if (orderedMaskIds.isEmpty()) {
                    continue;
                }
                final String targetId = drawableGuidValue(resolver.invoke(DRAWABLE_GUID, mesh));
                final Object rawInverted = resolver.invoke(INVERTED, mesh);
                if (!(rawInverted instanceof Boolean inverted)) {
                    throw validationFailure();
                }
                snapshots.add(new ClipMaskSnapshot(targetId, orderedMaskIds, inverted));
            }
            return List.copyOf(snapshots);
        } catch (AdapterHostException exception) {
            throw exception;
        } catch (VerifiedAccessException exception) {
            if (exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION) {
                throw mappingFailure();
            }
            throw validationFailure();
        } catch (RuntimeException exception) {
            throw validationFailure();
        }
    }

    private List<String> guidValues(final Iterable<?> rawGuids) {
        final List<String> values = new ArrayList<>();
        for (Object rawGuid : rawGuids) {
            requireInstance(DRAWABLE_GUID_CLASS, rawGuid);
            values.add(drawableGuidValue(rawGuid));
        }
        return List.copyOf(values);
    }

    private String drawableGuidValue(final Object drawableGuid) {
        requireInstance(DRAWABLE_GUID_CLASS, drawableGuid);
        requireInstance(GUID_CLASS, drawableGuid);
        final Object value = resolver.invoke(GUID_VALUE, drawableGuid);
        if (!(value instanceof String text) || text.isBlank()) {
            throw validationFailure();
        }
        return text;
    }

    private void requireInstance(final String alias, final Object value) {
        if (value == null || !resolver.isInstance(alias, value)) {
            throw validationFailure();
        }
    }

    private static AdapterHostException mappingFailure() {
        return new AdapterHostException(
            SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
            ClipMaskReadAdapter.CAPABILITY_ID,
            "Verified clip-mask selector could not be resolved at runtime."
        );
    }

    private static AdapterHostException validationFailure() {
        return new AdapterHostException(
            SafeModeDiagnostic.Code.VALIDATION_FAILURE,
            ClipMaskReadAdapter.CAPABILITY_ID,
            "Clip-mask host data could not be converted safely."
        );
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
