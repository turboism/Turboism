package dev.turboism.validation.modelimport3d;

import dev.turboism.validation.modelimport3d.Model.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only location protocol over the immutable fixture. Pure functions on
 * plain data; every failure is a typed {@link Model.Reason} rejection and the
 * caller decides how to record it. No native mixed-radix grid engine, no
 * interpolation, no write semantics — an alias form never implies a writable
 * independent form.
 */
final class Locate {

    private Locate() {}

    /** Outcome of a locate attempt: either a located form or a rejection. */
    record Result(Model.Reason rejection, String detail, CellKey key,
                  String formGuid, boolean alias, String writable,
                  String basicIdentity) {
        static Result rejected(Model.Reason r, String detail) {
            return new Result(r, detail, null, null, false, null, null);
        }
        static Result found(CellKey key, String formGuid, boolean alias,
                            String writable, String basicIdentity) {
            return new Result(null, null, key, formGuid, alias, writable,
                    basicIdentity);
        }
        boolean ok() { return rejection == null; }
    }

    /** Request axis as (paramId-or-guid, value); resolved to guid identity. */
    record Axis(String paramRef, float value) {}

    /**
     * Normalize a requested tuple against the bound parameter set.
     * Fail-closed: non-finite value, unbound/duplicate dimension, undeclared
     * key, or any missing bound dimension rejects.
     */
    static Result normalize(Grid grid, List<Axis> request) {
        Map<String, Binding> byGuid = new HashMap<>();
        Map<String, Binding> byId = new HashMap<>();
        for (Binding b : grid.bindings()) {
            byGuid.put(b.paramGuid(), b);
            byId.put(b.paramId(), b); // Grid rejects duplicate paramIds
        }
        Set<String> seen = new HashSet<>();
        List<KeyPoint> pts = new ArrayList<>();
        for (Axis a : request) {
            if (!Float.isFinite(a.value())) {
                return Result.rejected(Model.Reason.NON_FINITE,
                        "value " + a.value() + " for " + a.paramRef());
            }
            Binding b = byGuid.get(a.paramRef());
            if (b == null) {
                // Grid rejects duplicate ids and cross-binding id/guid collisions.
                b = byId.get(a.paramRef());
            }
            if (b == null) {
                return Result.rejected(Model.Reason.UNBOUND_DIMENSION,
                        "no bound parameter " + a.paramRef());
            }
            if (!seen.add(b.paramGuid())) {
                return Result.rejected(Model.Reason.DUPLICATE_DIMENSION,
                        "duplicate dimension " + b.paramGuid());
            }
            if (b.indexOfKey(a.value()) < 0) {
                return Result.rejected(Model.Reason.KEY_NOT_DECLARED,
                        "value " + a.value() + " not a declared key of "
                                + b.paramId() + " (bit-exact match incl. signed zero)");
            }
            pts.add(new KeyPoint(b.paramGuid(), a.value()));
        }
        if (pts.size() != grid.bindings().size()) {
            return Result.rejected(Model.Reason.INCOMPLETE_TUPLE,
                    "tuple has " + pts.size() + " of "
                            + grid.bindings().size() + " bound dimensions");
        }
        return Result.found(new CellKey(pts), null, false, null, null);
    }

    /** Scope check shared by all locate paths. */
    static Result checkScope(Fixture f, Scope caller) {
        if (!caller.ownerId().equals(f.scope().ownerId())) {
            return Result.rejected(Model.Reason.WRONG_OWNER,
                    "owner " + caller.ownerId() + " != " + f.scope().ownerId());
        }
        if (caller.generation() != f.scope().generation()
                || !caller.documentId().equals(f.scope().documentId())
                || !caller.modelId().equals(f.scope().modelId())
                || !caller.sessionId().equals(f.scope().sessionId())) {
            return Result.rejected(Model.Reason.STALE_GENERATION,
                    "caller scope does not match fixture generation/identity");
        }
        return null;
    }

    /** Count of cells + morph records referencing each formGuid. */
    static Map<String, Integer> formRefCounts(Fixture f) {
        Map<String, Integer> counts = new HashMap<>();
        for (CellEntry e : f.grid().cellList()) {
            counts.merge(e.formGuid(), 1, Integer::sum);
        }
        for (MorphRecord m : f.morphs().records()) {
            if (m.keyformGuid() != null) counts.merge(m.keyformGuid(), 1, Integer::sum);
        }
        return counts;
    }

    /** Locate an ordinary cell by full tuple; resolves to a stored form. */
    static Result cell(Fixture f, Scope caller, List<Axis> request) {
        Result scopeFail = checkScope(f, caller);
        if (scopeFail != null) return scopeFail;
        Result norm = normalize(f.grid(), request);
        if (!norm.ok()) return norm;
        String formGuid = f.grid().formGuidAt(norm.key());
        if (formGuid == null) {
            return Result.rejected(Model.Reason.CELL_NOT_FOUND,
                    "no cell at " + norm.key());
        }
        return resolveForm(f, norm.key(), formGuid);
    }

    /** Locate the declared-default tuple cell: per-axis defaultValue, EQUAL. */
    static Result defaultCell(Fixture f, Scope caller) {
        Result scopeFail = checkScope(f, caller);
        if (scopeFail != null) return scopeFail;
        List<Axis> req = new ArrayList<>();
        for (Binding b : f.grid().bindings()) {
            if (b.indexOfKey(b.defaultValue()) < 0) {
                return Result.rejected(Model.Reason.MISSING_DEFAULT_KEY,
                        "defaultValue " + b.defaultValue()
                                + " not a declared key of " + b.paramId());
            }
            req.add(new Axis(b.paramGuid(), b.defaultValue()));
        }
        Result norm = normalize(f.grid(), req);
        if (!norm.ok()) return norm;
        String formGuid = f.grid().formGuidAt(norm.key());
        if (formGuid == null) {
            return Result.rejected(Model.Reason.CELL_NOT_FOUND,
                    "no cell at default tuple " + norm.key());
        }
        return resolveForm(f, norm.key(), formGuid);
    }

    /**
     * Locate a morph target by (paramGuid, keyValue). Null keyValue can never
     * be an exact hit or writable identity; explicit 0.0f is locatable but its
     * basic/nonbasic/editable identity stays UNKNOWN.
     */
    static Result morph(Fixture f, Scope caller, String paramGuid, Float keyValue) {
        Result scopeFail = checkScope(f, caller);
        if (scopeFail != null) return scopeFail;
        if (keyValue == null) {
            return Result.rejected(Model.Reason.NULL_KEY_VALUE,
                    "null keyValue is not an exact locatable/writable identity");
        }
        if (!Float.isFinite(keyValue)) {
            return Result.rejected(Model.Reason.NON_FINITE,
                    "morph keyValue not finite");
        }
        MorphRecord hit = null;
        for (MorphRecord m : f.morphs().records()) {
            if (m.paramGuid().equals(paramGuid) && m.keyValue() != null
                    && Model.keyEquals(m.keyValue(), keyValue)) {
                hit = m;
                break;
            }
        }
        if (hit == null) {
            return Result.rejected(Model.Reason.MORPH_NOT_FOUND,
                    "no morph target " + paramGuid + " key=" + keyValue);
        }
        if (hit.keyformGuid() == null) {
            return Result.rejected(Model.Reason.NULL_FORM_GUID,
                    "morph target has null keyformGuid");
        }
        Result r = resolveForm(f, null, hit.keyformGuid());
        if (!r.ok()) return r;
        return Result.found(null, r.formGuid(), r.alias(), r.writable(),
                hit.basicIdentity());
    }

    private static Result resolveForm(Fixture f, CellKey key, String formGuid) {
        DomainSnapshot form = f.forms().byGuid().get(formGuid);
        if (form == null) {
            return Result.rejected(Model.Reason.FORM_MISSING,
                    "formGuid " + formGuid + " not present in forms");
        }
        boolean alias = formRefCounts(f).getOrDefault(formGuid, 0) > 1;
        // A shared form is never a writable independent target.
        String writable = alias ? "BLOCKED_ALIAS" : "BLOCKED_HOST_UNVERIFIED";
        return Result.found(key, formGuid, alias, writable, null);
    }
}
