package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportHostModelReadWriteOps.enumName;
import static dev.turboism.exportsettings.ProtectedExportHostModelReadWriteOps.listOf;
import static dev.turboism.exportsettings.ProtectedExportHostVocabulary.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Physics and motion-sync settings half of {@link VerifiedProtectedExportHostOperations}:
 * identity and name/ID reads, the behaviour-content signatures the census pins, and the
 * name/ID rewrites the obfuscation pass performs on the disposable copy.
 *
 * <p>Parameter references inside physics settings resolve through
 * {@link ProtectedExportHostModelReadWriteOps}'s model census, so a reference the export cannot
 * preserve stays unpinnable ({@code null}) and rejects rather than riding through unseen.</p>
 */
final class ProtectedExportHostSettingsOps {

    private final VerifiedMemberResolver resolver;
    private final ProtectedExportHostModelReadWriteOps model;

    /** Binds the settings surface to one exact-version resolver and its model reads. */
    ProtectedExportHostSettingsOps(
            final VerifiedMemberResolver resolver, final ProtectedExportHostModelReadWriteOps model) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.model = Objects.requireNonNull(model, "model");
    }

    boolean isPhysicsSettingsSource(final Object object) {
        return resolver.isInstance(PHYSICS_SETTINGS_CLASS, object);
    }

    boolean isMotionSyncSettingSource(final Object object) {
        return resolver.isInstance(MOTION_SYNC_CLASS, object);
    }

    String settingsGuid(final Object settingsSource) {
        if (isPhysicsSettingsSource(settingsSource)) {
            return model.readGuid(resolver.invoke(PHYSICS_SETTINGS_GUID, settingsSource));
        }
        if (isMotionSyncSettingSource(settingsSource)) {
            return model.readGuid(resolver.invoke(MOTION_SYNC_GUID, settingsSource));
        }
        return null;
    }

    String settingsIdString(final Object settingsSource) {
        final Object id;
        if (isPhysicsSettingsSource(settingsSource)) {
            id = resolver.invoke(PHYSICS_SETTINGS_ID, settingsSource);
        } else if (isMotionSyncSettingSource(settingsSource)) {
            id = resolver.invoke(MOTION_SYNC_ID, settingsSource);
        } else {
            return null;
        }
        return id == null ? null : (String) resolver.invoke(ID_STRING, id);
    }

    String settingsName(final Object settingsSource) {
        final Object name;
        if (isPhysicsSettingsSource(settingsSource)) {
            name = resolver.invoke(PHYSICS_SETTINGS_NAME, settingsSource);
        } else if (isMotionSyncSettingSource(settingsSource)) {
            name = resolver.invoke(MOTION_SYNC_NAME, settingsSource);
        } else {
            return null;
        }
        return name instanceof String text ? text : null;
    }

    /**
     * Behavior-content tokens pinning a settings object — everything a physics
     * evaluation or the serialized {@code physics3.json} depends on, except the
     * settings name and ID the obfuscation pass rewrites. Physics entries carry
     * every input/output/vertex member verbatim; parameter references are
     * resolved through the model's parameter census (GUID → pinned parameter
     * ID), and a reference that cannot be resolved marks the whole signature
     * {@code null} — unpinnable, so the census rejects. Motion sync carries the
     * version plus the mapping and post-processing checksums decomposed so the
     * planned name/ID rewrite does not masquerade as drift (the host's own
     * checksum covers the name).
     */
    List<String> settingsSignature(final Object modelSource, final Object settingsSource) {
        if (isPhysicsSettingsSource(settingsSource)) {
            return physicsSettingsSignature(modelSource, settingsSource);
        }
        if (isMotionSyncSettingSource(settingsSource)) {
            final Object mapping = resolver.invoke(MOTION_SYNC_MAPPING, settingsSource);
            final Object postProc = resolver.invoke(MOTION_SYNC_POSTPROC, settingsSource);
            if (mapping == null || postProc == null) {
                return null;
            }
            return List.of(
                    "version=" + enumName(resolver.invoke(MOTION_SYNC_VERSION, settingsSource)),
                    "mapping.checksum=" + resolver.invoke(MOTION_SYNC_MAPPING_CHECKSUM, mapping),
                    "postproc.checksum=" + resolver.invoke(MOTION_SYNC_POSTPROC_CHECKSUM, postProc));
        }
        return null;
    }

    /**
     * Ordered behavior tokens of one physics settings source. The parameter
     * reference in every input/output resolves through {@code modelSource}'s
     * parameter census; a GUID that resolves to no parameter makes the setting
     * unpinnable ({@code null}) — a reference the export cannot preserve must
     * never slip through.
     */
    private List<String> physicsSettingsSignature(final Object modelSource, final Object settingsSource) {
        final Map<String, String> parameterIds = parameterIdsByGuid(modelSource);
        if (parameterIds == null) {
            return null;
        }
        final List<String> tokens = new ArrayList<>();
        tokens.add("enable=" + model.flagValue(PHYSICS_SETTINGS_ENABLE, settingsSource));
        tokens.add("normalization.position=" + floatToken(PHYSICS_SETTINGS_NORM_POS_MIN, settingsSource)
                + "|" + floatToken(PHYSICS_SETTINGS_NORM_POS_DEFAULT, settingsSource)
                + "|" + floatToken(PHYSICS_SETTINGS_NORM_POS_MAX, settingsSource));
        tokens.add("normalization.angle=" + floatToken(PHYSICS_SETTINGS_NORM_ANGLE_MIN, settingsSource)
                + "|" + floatToken(PHYSICS_SETTINGS_NORM_ANGLE_DEFAULT, settingsSource)
                + "|" + floatToken(PHYSICS_SETTINGS_NORM_ANGLE_MAX, settingsSource));
        tokens.add("totalAngle=" + floatToken(PHYSICS_SETTINGS_TOTAL_ANGLE, settingsSource));
        final List<?> inputs = listOf(resolver.invoke(PHYSICS_SETTINGS_INPUTS, settingsSource));
        for (int i = 0; i < inputs.size(); i++) {
            final Object input = inputs.get(i);
            if (input == null) {
                return null;
            }
            final String parameterId = physicsParameterId(parameterIds, resolver.invoke(PHYSICS_INPUT_SOURCE, input));
            if (parameterId == null) {
                return null;
            }
            tokens.add("input." + i + "=" + parameterId
                    + "|" + floatToken(PHYSICS_INPUT_WEIGHT, input)
                    + "|" + enumName(resolver.invoke(PHYSICS_INPUT_TYPE, input))
                    + "|" + model.flagValue(PHYSICS_INPUT_REVERSE, input)
                    + "|" + floatToken(PHYSICS_INPUT_ANGLE_SCALE, input)
                    + "|" + vectorToken(resolver.invoke(PHYSICS_INPUT_TRANSLATION_SCALE, input)));
        }
        final List<?> outputs = listOf(resolver.invoke(PHYSICS_SETTINGS_OUTPUTS, settingsSource));
        for (int i = 0; i < outputs.size(); i++) {
            final Object output = outputs.get(i);
            if (output == null) {
                return null;
            }
            final String parameterId =
                    physicsParameterId(parameterIds, resolver.invoke(PHYSICS_OUTPUT_DESTINATION, output));
            if (parameterId == null) {
                return null;
            }
            tokens.add("output." + i + "=" + parameterId
                    + "|" + resolver.invoke(PHYSICS_OUTPUT_VERTEX_INDEX, output)
                    + "|" + floatToken(PHYSICS_OUTPUT_WEIGHT, output)
                    + "|" + enumName(resolver.invoke(PHYSICS_OUTPUT_TYPE, output))
                    + "|" + model.flagValue(PHYSICS_OUTPUT_REVERSE, output)
                    + "|" + floatToken(PHYSICS_OUTPUT_ANGLE_SCALE, output)
                    + "|" + vectorToken(resolver.invoke(PHYSICS_OUTPUT_TRANSLATION_SCALE, output))
                    + "|" + floatToken(PHYSICS_OUTPUT_BELOW_MINIMUM, output)
                    + "|" + floatToken(PHYSICS_OUTPUT_EXCEEDED_MAXIMUM, output));
        }
        final List<?> vertices = listOf(resolver.invoke(PHYSICS_SETTINGS_VERTICES, settingsSource));
        for (int i = 0; i < vertices.size(); i++) {
            final Object vertex = vertices.get(i);
            if (vertex == null) {
                return null;
            }
            tokens.add("vertex." + i + "=" + vectorToken(resolver.invoke(PHYSICS_VERTEX_POSITION, vertex))
                    + "|" + floatToken(PHYSICS_VERTEX_MOBILITY, vertex)
                    + "|" + floatToken(PHYSICS_VERTEX_DELAY, vertex)
                    + "|" + floatToken(PHYSICS_VERTEX_ACCELERATION, vertex)
                    + "|" + floatToken(PHYSICS_VERTEX_RADIUS, vertex));
        }
        return List.copyOf(tokens);
    }

    /**
     * Parameter GUID → ID map over the model's parameter census; {@code null}
     * when any parameter source is unreadable — the census rejects rather than
     * silently tolerating an unresolvable physics reference.
     */
    private Map<String, String> parameterIdsByGuid(final Object modelSource) {
        final Map<String, String> ids = new LinkedHashMap<>();
        for (Object parameter : model.allParameters(modelSource)) {
            final String guid = parameterSourceGuid(parameter);
            final String id = model.parameterSourceIdString(parameter);
            if (guid == null || guid.isBlank() || id == null || id.isBlank()) {
                return null;
            }
            ids.put(guid, id);
        }
        return ids;
    }

    /**
     * Resolves a physics input/output parameter GUID to its pinned parameter
     * ID; {@code null} when the reference cannot be resolved (fail-closed).
     */
    private String physicsParameterId(final Map<String, String> parameterIds, final Object parameterGuid) {
        final String guid = parameterGuid == null ? null : model.readGuid(parameterGuid);
        return guid == null ? null : parameterIds.get(guid);
    }

    /** Numeric member rendered as a signature token value. */
    private String floatToken(final String alias, final Object source) {
        return String.valueOf(resolver.invoke(alias, source));
    }

    /** {@code GVector2} rendered as {@code x,y}. */
    private String vectorToken(final Object vector) {
        if (vector == null) {
            return "null";
        }
        return resolver.invoke(VECTOR2_X, vector) + "," + resolver.invoke(VECTOR2_Y, vector);
    }

    /**
     * Physics settings set tokens — effective forces, FPS, and the selected
     * settings GUID pinned at the set level. A model with no physics settings
     * contributes no tokens; a model whose physics settings exist but whose
     * set cannot be read is unpinnable ({@code null} → reject).
     */
    List<String> physicsSettingsSetSignature(final Object modelSource) {
        final Object set = resolver.isInstance(MODEL_SOURCE_CLASS, modelSource)
                ? resolver.invoke(MS_PHYSICS_SETTINGS_SET, modelSource)
                : null;
        if (set == null) {
            return model.allPhysicsSettings(modelSource).isEmpty() ? List.of() : null;
        }
        final Object gravity = resolver.invoke(PHYSICS_SET_GRAVITY, set);
        final Object wind = resolver.invoke(PHYSICS_SET_WIND, set);
        return List.of(
                "gravity=" + vectorToken(gravity),
                "wind=" + vectorToken(wind),
                "fps=" + resolver.invoke(PHYSICS_SET_FPS, set),
                "selected=" + model.readGuid(resolver.invoke(PHYSICS_SET_SELECTED, set)));
    }

    /**
     * GUID string of a parameter source — physics input/output entries hold
     * parameter references as {@code CParameterGuid}, so the census needs the
     * GUID to resolve the pinned parameter ID.
     */
    String parameterSourceGuid(final Object parameterSource) {
        if (!resolver.isInstance(PARAMETER_CLASS, parameterSource)) {
            return null;
        }
        return model.readGuid(resolver.invoke(PARAMETER_SOURCE_GUID, parameterSource));
    }

    /**
     * Rewrites a settings source's local name on the disposable copy —
     * obfuscation only; never called on the authoring document.
     */
    void setSettingsName(final Object settingsSource, final String name) {
        if (isPhysicsSettingsSource(settingsSource)) {
            resolver.invoke(PHYSICS_SETTINGS_SET_NAME, settingsSource, Objects.requireNonNull(name, "name"));
            return;
        }
        if (isMotionSyncSettingSource(settingsSource)) {
            resolver.invoke(MOTION_SYNC_SET_NAME, settingsSource, Objects.requireNonNull(name, "name"));
            return;
        }
        throw new IllegalStateException("object is not a settings source");
    }

    /**
     * Rewrites a settings source's ID on the disposable copy. Physics IDs are
     * replaced through the typed setter with a rebuilt {@code CPhysicsSettingId};
     * motion-sync exposes no setter, so its private {@code _id} field is written
     * through the verified field pin (non-static, non-final). The GUID is never
     * touched — the obfuscation token derives from it.
     */
    void setSettingsId(final Object settingsSource, final String idString) {
        Objects.requireNonNull(idString, "idString");
        if (isPhysicsSettingsSource(settingsSource)) {
            resolver.invoke(
                    PHYSICS_SETTINGS_SET_ID, settingsSource, resolver.construct(PHYSICS_SETTING_ID_CREATE, idString));
            return;
        }
        if (isMotionSyncSettingSource(settingsSource)) {
            resolver.writeField(
                    MOTION_SYNC_ID_FIELD, settingsSource, resolver.construct(MOTION_SYNC_ID_CREATE, idString));
            return;
        }
        throw new IllegalStateException("object is not a settings source");
    }
}
