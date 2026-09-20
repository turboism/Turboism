package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed operations of the parameter-key (keyform) family of the editing surface.
 *
 * <p>Each method is one official operation — {@code AddParameterKey}, {@code DeleteParameterKey},
 * {@code MoveParameterKey}, {@code GetParameterKeys}, {@code GetObjectsByParameterKeys} — carried
 * by a typed request record instead of a command string. All methods require an admitted
 * {@link EditSession} and fail with {@link EditSessionException} subclasses when the session or
 * the operation is unavailable.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface ParameterKeyOps {

    /**
     * Adds one key value to the parameter binding of an object ({@code AddParameterKey}).
     *
     * @return {@code true} when the key was added
     */
    boolean addParameterKey(AddParameterKey request) throws EditSessionException;

    /**
     * Deletes parameter keys matching the request ({@code DeleteParameterKey}). With {@code
     * strict} unset the deletion matches loosely.
     *
     * @return {@code true} when at least one key was deleted
     */
    boolean deleteParameterKey(DeleteParameterKey request) throws EditSessionException;

    /**
     * Moves one parameter key to a new value ({@code MoveParameterKey}).
     *
     * @return {@code true} when the key was moved
     */
    boolean moveParameterKey(MoveParameterKey request) throws EditSessionException;

    /**
     * Returns the parameter keys bound to one object ({@code GetParameterKeys}).
     *
     * @return one entry per bound parameter, never {@code null}
     */
    List<ParameterKeyValues> parameterKeys(GetParameterKeys request) throws EditSessionException;

    /**
     * Returns the ids of objects that have a key at the given parameter value
     * ({@code GetObjectsByParameterKeys}).
     *
     * @return matching object ids, never {@code null}
     */
    List<ModelObjectId> objectsByParameterKeys(GetObjectsByParameterKeys request)
            throws EditSessionException;

    /** Returns a fail-closed implementation in which every operation is unavailable. */
    static ParameterKeyOps unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements ParameterKeyOps {
        INSTANCE;

        @Override
        public boolean addParameterKey(final AddParameterKey request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterKeyOps.addParameterKey");
        }

        @Override
        public boolean deleteParameterKey(final DeleteParameterKey request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterKeyOps.deleteParameterKey");
        }

        @Override
        public boolean moveParameterKey(final MoveParameterKey request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterKeyOps.moveParameterKey");
        }

        @Override
        public List<ParameterKeyValues> parameterKeys(final GetParameterKeys request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterKeyOps.parameterKeys");
        }

        @Override
        public List<ModelObjectId> objectsByParameterKeys(final GetObjectsByParameterKeys request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("ParameterKeyOps.objectsByParameterKeys");
        }
    }

    /**
     * {@code AddParameterKey} request: binds {@code keyValue} as a new key of {@code parameter}
     * on {@code object}.
     */
    record AddParameterKey(ModelObjectReference object, ParameterId parameter, double keyValue) {
        public AddParameterKey {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(parameter, "parameter");
            if (!Double.isFinite(keyValue)) {
                throw new IllegalArgumentException("keyValue must be finite");
            }
        }
    }

    /**
     * {@code DeleteParameterKey} request: deletes keys matching the given object, parameter, and
     * value. All three filters are optional in the official API; {@code strict} selects exact
     * matching (the official default) over loose matching.
     */
    record DeleteParameterKey(
            Optional<ModelObjectReference> object,
            Optional<ParameterId> parameter,
            Optional<Double> keyValue,
            boolean strict) {

        public DeleteParameterKey {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(keyValue, "keyValue");
            keyValue.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("keyValue must be finite");
                }
            });
        }

        /** Returns a strict (exact-match) delete request, the official default. */
        public DeleteParameterKey(
                final Optional<ModelObjectReference> object,
                final Optional<ParameterId> parameter,
                final Optional<Double> keyValue) {
            this(object, parameter, keyValue, true);
        }
    }

    /**
     * {@code MoveParameterKey} request: moves the key at {@code fromValue} to {@code toValue}.
     * {@code strict} selects exact matching (official default); {@code forceOverwrite} allows
     * overwriting an existing key at the destination.
     */
    record MoveParameterKey(
            Optional<ModelObjectReference> object,
            Optional<ParameterId> parameter,
            double fromValue,
            double toValue,
            boolean strict,
            boolean forceOverwrite) {

        public MoveParameterKey {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(parameter, "parameter");
            if (!Double.isFinite(fromValue) || !Double.isFinite(toValue)) {
                throw new IllegalArgumentException("fromValue and toValue must be finite");
            }
        }

        /** Returns a strict, non-overwriting move request, the official defaults. */
        public MoveParameterKey(
                final Optional<ModelObjectReference> object,
                final Optional<ParameterId> parameter,
                final double fromValue,
                final double toValue) {
            this(object, parameter, fromValue, toValue, true, false);
        }
    }

    /** {@code GetParameterKeys} request: reads the parameter keys bound to one object. */
    record GetParameterKeys(ModelObjectReference object) {
        public GetParameterKeys {
            Objects.requireNonNull(object, "object");
        }
    }

    /** {@code GetObjectsByParameterKeys} request: finds objects keyed at a parameter value. */
    record GetObjectsByParameterKeys(ParameterId parameter, double keyValue) {
        public GetObjectsByParameterKeys {
            Objects.requireNonNull(parameter, "parameter");
            if (!Double.isFinite(keyValue)) {
                throw new IllegalArgumentException("keyValue must be finite");
            }
        }
    }

    /** One row of a {@code GetParameterKeys} result: a parameter and its bound key values. */
    record ParameterKeyValues(ParameterId parameter, List<Double> keyValues) {
        public ParameterKeyValues {
            Objects.requireNonNull(parameter, "parameter");
            keyValues = List.copyOf(Objects.requireNonNull(keyValues, "keyValues"));
        }
    }
}
