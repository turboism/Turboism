package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.internal.Requirements;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ClipmaskAliases;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Clip-mask HostOperations backed only by the exact verified selector slice. */
public final class VerifiedClipMaskHostOperations implements ClipMaskReadAdapter.HostOperations {

    private static final Set<String> METHOD_ALIASES_USED = Set.of(
            ClipmaskAliases.APP_CONTROLLER_INSTANCE,
            ClipmaskAliases.APP_CONTROLLER_CURRENT_DOCUMENT,
            ClipmaskAliases.MODELING_DOCUMENT_MODEL_SOURCE,
            ClipmaskAliases.MODEL_SOURCE_ALL_ART_MESHES,
            ClipmaskAliases.DRAWABLE_SOURCE_GUID,
            ClipmaskAliases.DRAWABLE_SOURCE_CLIP_GUID_LIST,
            ClipmaskAliases.DRAWABLE_SOURCE_INVERT_CLIPPING_MASK,
            ClipmaskAliases.GUID_VALUE);
    private static final Set<String> CLASS_ALIASES_REQUIRED = Set.of(
            ClipmaskAliases.APP_CONTROLLER_CLASS,
            ClipmaskAliases.DOCUMENT_CLASS,
            ClipmaskAliases.MODELING_DOCUMENT_CLASS,
            ClipmaskAliases.MODEL_SOURCE_CLASS,
            ClipmaskAliases.ART_MESH_SOURCE_CLASS,
            ClipmaskAliases.DRAWABLE_SOURCE_CLASS,
            ClipmaskAliases.DRAWABLE_GUID_CLASS,
            ClipmaskAliases.GUID_CLASS);

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

    public VerifiedClipMaskHostOperations(final VerifiedMemberResolver resolver, final String hostVersion) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.hostVersion = Requirements.text(hostVersion, "hostVersion");
    }

    @Override
    public String contractVersion() {
        return resolver.admittedCubismVersion();
    }

    @Override
    public String hostVersion() {
        return hostVersion;
    }

    @Override
    public boolean supportsClipMaskRead() {
        return ClipMaskVerificationManifest.reviewedCubismVersions().contains(contractVersion());
    }

    @Override
    public List<ClipMaskSnapshot> clipMasks() {
        try {
            final Object appController = resolver.invokeStatic(ClipmaskAliases.APP_CONTROLLER_INSTANCE);
            if (appController == null) {
                return List.of();
            }
            requireInstance(ClipmaskAliases.APP_CONTROLLER_CLASS, appController);
            final Object document = resolver.invoke(ClipmaskAliases.APP_CONTROLLER_CURRENT_DOCUMENT, appController);
            if (document == null) {
                return List.of();
            }
            requireInstance(ClipmaskAliases.DOCUMENT_CLASS, document);
            if (!resolver.isInstance(ClipmaskAliases.MODELING_DOCUMENT_CLASS, document)) {
                return List.of();
            }
            final Object modelSource = resolver.invoke(ClipmaskAliases.MODELING_DOCUMENT_MODEL_SOURCE, document);
            if (modelSource == null) {
                return List.of();
            }
            requireInstance(ClipmaskAliases.MODEL_SOURCE_CLASS, modelSource);
            final Object rawMeshes = resolver.invoke(ClipmaskAliases.MODEL_SOURCE_ALL_ART_MESHES, modelSource);
            if (rawMeshes == null) {
                return List.of();
            }
            if (!(rawMeshes instanceof Iterable<?> meshes)) {
                throw validationFailure();
            }

            final List<ClipMaskSnapshot> snapshots = new ArrayList<>();
            for (Object mesh : meshes) {
                requireInstance(ClipmaskAliases.ART_MESH_SOURCE_CLASS, mesh);
                requireInstance(ClipmaskAliases.DRAWABLE_SOURCE_CLASS, mesh);
                final Object rawClipList = resolver.invoke(ClipmaskAliases.DRAWABLE_SOURCE_CLIP_GUID_LIST, mesh);
                if (!(rawClipList instanceof Iterable<?> clipGuids)) {
                    throw validationFailure();
                }
                final List<String> orderedMaskIds = guidValues(clipGuids);
                if (orderedMaskIds.isEmpty()) {
                    continue;
                }
                final String targetId = drawableGuidValue(resolver.invoke(ClipmaskAliases.DRAWABLE_SOURCE_GUID, mesh));
                final Object rawInverted = resolver.invoke(ClipmaskAliases.DRAWABLE_SOURCE_INVERT_CLIPPING_MASK, mesh);
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
            requireInstance(ClipmaskAliases.DRAWABLE_GUID_CLASS, rawGuid);
            values.add(drawableGuidValue(rawGuid));
        }
        return List.copyOf(values);
    }

    private String drawableGuidValue(final Object drawableGuid) {
        requireInstance(ClipmaskAliases.DRAWABLE_GUID_CLASS, drawableGuid);
        requireInstance(ClipmaskAliases.GUID_CLASS, drawableGuid);
        final Object value = resolver.invoke(ClipmaskAliases.GUID_VALUE, drawableGuid);
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
                "Verified clip-mask selector could not be resolved at runtime.");
    }

    private static AdapterHostException validationFailure() {
        return new AdapterHostException(
                SafeModeDiagnostic.Code.VALIDATION_FAILURE,
                ClipMaskReadAdapter.CAPABILITY_ID,
                "Clip-mask host data could not be converted safely.");
    }
}
