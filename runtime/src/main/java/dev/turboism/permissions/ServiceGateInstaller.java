package dev.turboism.permissions;

import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The one authorization and lifetime wrapper over the SDK service interfaces a plugin receives.
 *
 * <p>Each service used to carry its own hand-written decorator, so the same
 * permission-check → delegate → scope-adoption sequence was reimplemented once per interface and
 * the inventory of what a plugin may call was spread across ten classes. This installer states
 * that inventory as data ({@link #rows()}) and executes it once. The declaration stays explicit
 * and auditable; the mechanism exists once.</p>
 *
 * <p>Guaranteed order for one call: scope fence (WRITE rows only) → permission check → delegate →
 * registration adoption. A refused or fenced call never reaches the delegate.</p>
 *
 * <p>Invariants:</p>
 * <ul>
 *   <li>A table that does not cover every declared method of the interface — and only rows that
 *       name a declared method — is refused at install time. A call can therefore never arrive
 *       at a method the table forgot; if one somehow does, it is denied rather than forwarded.</li>
 *   <li>Terminal verbs ({@code close}, {@code cancel}, {@code dispose}, {@code release},
 *       {@code unregister} with no arguments, or any row marked {@link ServiceGate.Access#TERMINAL})
 *       bypass both the fence and the permission check, matching {@code PluginGenerationGuard}:
 *       teardown must be able to release what was acquired before the generation was fenced.</li>
 *   <li>A {@link Registration} returned by the delegate is adopted into the owning scope, so the
 *       plugin scope closes it; if adoption fails the just-created registration is closed before
 *       the failure propagates, so a closed scope never leaks a live registration.</li>
 *   <li>The delegate's own exception reaches the caller unwrapped, never as an
 *       {@link InvocationTargetException}.</li>
 * </ul>
 */
public final class ServiceGateInstaller {

    /**
     * Zero-argument release verbs admitted without a row and without checks. Kept identical to
     * {@code PluginGenerationGuard}'s terminal set — name plus arity only, because the SDK's
     * terminal surface spans dozens of interfaces with heterogeneous return types and a missed
     * release leaks a resource.
     */
    private static final Set<String> TERMINAL_VERBS = Set.of("close", "cancel", "dispose", "release", "unregister");

    /** The complete gate table; every row of one interface names a method that interface declares. */
    private static final List<ServiceGate> ROWS = List.of(
            // ArtMesh mirroring: participation, the mirror toggle and the axis reset are host
            // writes; the state probes expose nothing outside the caller's own participation.
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "participate",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.artmesh.mirror",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "setEnabled",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.artmesh.mirror",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "resetAxis",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.artmesh.mirror",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "isEnabled",
                    null,
                    "cubism.artmesh.mirror.state",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "nativeMirrorActive",
                    null,
                    "cubism.artmesh.mirror.native",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    ArtMeshMirrorService.class,
                    "isAvailable",
                    null,
                    "cubism.artmesh.mirror.available",
                    ServiceGate.Access.READ),

            // Warp Alt-mirror policy: participation is a host write, while arming the axis and
            // the weight counters stay admitted as they were (the plugin owns its own axis).
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "participate",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.warp.alt-symmetry.participate",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "setArmedAxis",
                    null,
                    "cubism.warp.alt-symmetry.arm-axis",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "nativeMirrorActive",
                    null,
                    "cubism.warp.alt-symmetry.native",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "weightMirrorAppliedCount",
                    null,
                    "cubism.warp.alt-symmetry.weight-applied",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "weightMirrorLastSourceIndex",
                    null,
                    "cubism.warp.alt-symmetry.weight-source",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "weightMirrorLastCounterpartIndex",
                    null,
                    "cubism.warp.alt-symmetry.weight-counterpart",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    WarpAltMirrorParticipation.class,
                    "isAvailable",
                    null,
                    "cubism.warp.alt-symmetry.available",
                    ServiceGate.Access.READ),

            // Canvas strip buttons: contributing is a toolbar write, updating one button's state
            // is admitted without a permission because the contribution id is the plugin's own.
            new ServiceGate(
                    ViewContextMenuRegistry.class,
                    "contributeStateButtons",
                    PermissionIds.TURBOISM_UI_TOOLBAR_CONTRIBUTE,
                    "viewcontext.statebuttons.contribute",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    ViewContextMenuRegistry.class,
                    "updateButtonState",
                    null,
                    "viewcontext.statebuttons.update",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    ViewContextMenuRegistry.class,
                    "isAvailable",
                    null,
                    "viewcontext.available",
                    ServiceGate.Access.READ),

            // Plugin-initiated mesh authoring: one read for the snapshot, one write per mutation.
            new ServiceGate(
                    MeshEditService.class,
                    "snapshot",
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    "cubism.mesh.edit.snapshot",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshEditService.class,
                    "addPoints",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.add-points",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditService.class,
                    "deletePoints",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.delete-points",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditService.class,
                    "movePoints",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.move-points",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditService.class,
                    "addEdges",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.add-edges",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditService.class,
                    "deleteEdges",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.delete-edges",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditService.class, "isAvailable", null, "cubism.mesh.edit.available", ServiceGate.Access.READ),

            // Participation folds deletions into a host edit, so it is a write, not an observation.
            new ServiceGate(
                    MeshEditParticipation.class,
                    "participate",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.edit.participate",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditParticipation.class,
                    "isAvailable",
                    null,
                    "cubism.mesh.edit-participation.available",
                    ServiceGate.Access.READ),

            // Counterpart resolution feeds a host deletion, so both verbs answer to the write
            // permission; resolving itself observes, overriding replaces the plugin's own rule.
            new ServiceGate(
                    MeshMirrorCounterparts.class,
                    "mirrorOf",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.mirror-counterparts.resolve",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshMirrorCounterparts.class,
                    "overrideResolver",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.mirror-counterparts.override",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshMirrorCounterparts.class,
                    "isAvailable",
                    null,
                    "cubism.mesh.mirror-counterparts.available",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshMirrorMoveParticipation.class,
                    "participate",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.mirror-move.participate",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshMirrorMoveParticipation.class,
                    "isAvailable",
                    null,
                    "cubism.mesh.mirror-move.available",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshMirrorToolEligibility.class,
                    "extendEligibleTools",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.mirror-tool-eligibility.extend",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshMirrorToolEligibility.class,
                    "isAvailable",
                    null,
                    "cubism.mesh.mirror-tool-eligibility.available",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshMirrorAxisService.class,
                    "currentAngleDegrees",
                    PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                    "cubism.mesh.mirror-axis.read",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshMirrorAxisService.class,
                    "setCurrentAngleDegrees",
                    PermissionIds.TURBOISM_CUBISM_MODEL_WRITE,
                    "cubism.mesh.mirror-axis.write",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshMirrorAxisService.class,
                    "isAvailable",
                    null,
                    "cubism.mesh.mirror-axis.available",
                    ServiceGate.Access.READ),
            new ServiceGate(
                    MeshEditUiService.class,
                    "contributeMirrorAxisAngleControl",
                    PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE,
                    "ui.mesh-edit.mirror-axis-angle.contribute",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    MeshEditUiService.class, "isAvailable", null, "ui.mesh-edit.available", ServiceGate.Access.READ),

            // Physics: admission is decided by the capability bundle that hands the service out
            // (it exists only for a plugin holding the model write permission), so both verbs are
            // fenced but carry no second permission check.
            new ServiceGate(
                    PhysicsEditorService.class,
                    "contribute",
                    null,
                    "cubism.physics.editor.contribute",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    PhysicsEditorService.class,
                    "gifCommand",
                    null,
                    "cubism.physics.editor.gif-command",
                    ServiceGate.Access.WRITE),
            new ServiceGate(
                    PhysicsEditorService.class,
                    "isAvailable",
                    null,
                    "cubism.physics.editor.available",
                    ServiceGate.Access.READ));

    private ServiceGateInstaller() {}

    /**
     * Returns the complete gate table, one row per guarded method, for audit and completeness
     * checks.
     *
     * @return the immutable table
     */
    public static List<ServiceGate> rows() {
        return ROWS;
    }

    /**
     * Returns the gate rows declared for one SDK interface.
     *
     * @param api the interface to look up; must not be null
     * @return the rows for that interface, in table order; empty when the interface has none
     * @throws NullPointerException if {@code api} is null
     */
    public static List<ServiceGate> rowsFor(final Class<?> api) {
        Objects.requireNonNull(api, "api");
        return ROWS.stream().filter(row -> row.api() == api).toList();
    }

    /**
     * Wraps one service delegate so every declared method is admitted through the table.
     *
     * @param <T> the service interface
     * @param api the SDK interface to implement; must not be null
     * @param delegate the runtime implementation to protect; must not be null
     * @param checker the permission test applied before the delegate is reached; must not be null
     * @param scope the plugin scope that adopts returned registrations, or {@code null} when the
     *     caller owns every registration itself
     * @param scopeActive the owning scope's liveness, or {@code null} to skip the fence entirely
     *     (services whose admission is already bound to the plugin generation)
     * @param rows the table rows, normally {@link #rowsFor(Class)}; must cover every declared
     *     method of {@code api}
     * @return the gated view; never the delegate itself
     * @throws NullPointerException if {@code api}, {@code delegate}, {@code checker} or
     *     {@code rows} is null
     * @throws IllegalArgumentException if the delegate does not implement {@code api}, if the
     *     table belongs to another interface, declares a method the interface does not declare,
     *     names one method twice, leaves a declared method uncovered, or marks a release verb
     *     with an access class it would not honour
     */
    public static <T> T install(
            final Class<T> api,
            final T delegate,
            final PermissionChecker checker,
            final DisposableScope scope,
            final BooleanSupplier scopeActive,
            final List<ServiceGate> rows) {
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(checker, "checker");
        if (!api.isInstance(delegate)) {
            throw new IllegalArgumentException(
                    "delegate " + delegate.getClass().getName() + " does not implement " + api.getName());
        }
        final Map<String, ServiceGate> table = admit(api, rows);
        final InvocationHandler handler = new Handler(api, delegate, checker, scope, scopeActive, table);
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] {api}, handler));
    }

    /**
     * Checks one table against one interface and keys it by method name.
     *
     * @throws IllegalArgumentException when the table and the interface disagree
     */
    private static Map<String, ServiceGate> admit(final Class<?> api, final List<ServiceGate> rows) {
        Objects.requireNonNull(rows, "rows");
        final Map<String, ServiceGate> table = new LinkedHashMap<>();
        for (final ServiceGate row : rows) {
            if (row.api() != api) {
                throw new IllegalArgumentException("gate row " + row.api().getName() + "." + row.method()
                        + " does not belong to " + api.getName());
            }
            if (table.putIfAbsent(row.method(), row) != null) {
                throw new IllegalArgumentException("duplicate gate row for " + api.getName() + "." + row.method());
            }
        }
        final Map<String, Boolean> declared = new LinkedHashMap<>();
        for (final Method method : api.getMethods()) {
            if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())) continue;
            final boolean terminalVerb = isTerminalVerb(method);
            declared.merge(method.getName(), terminalVerb, (first, second) -> first && second);
        }
        for (final Map.Entry<String, Boolean> entry : declared.entrySet()) {
            final ServiceGate row = table.get(entry.getKey());
            if (row == null) {
                if (entry.getValue()) continue;
                throw new IllegalArgumentException(
                        "service gate table for " + api.getName() + " does not cover " + entry.getKey());
            }
            if (entry.getValue() && row.access() != ServiceGate.Access.TERMINAL) {
                throw new IllegalArgumentException("gate row " + api.getName() + "." + entry.getKey()
                        + " guards a terminal release verb and must be declared TERMINAL");
            }
        }
        for (final String method : table.keySet()) {
            if (!declared.containsKey(method)) {
                throw new IllegalArgumentException(
                        "gate row " + api.getName() + "." + method + " names a method the interface does not declare");
            }
        }
        return table;
    }

    private static boolean isTerminalVerb(final Method method) {
        return method.getParameterCount() == 0 && TERMINAL_VERBS.contains(method.getName());
    }

    /**
     * Per-call gate: fence, permission, delegate, adoption, in that order.
     */
    private static final class Handler implements InvocationHandler {

        private final Class<?> api;
        private final Object delegate;
        private final PermissionChecker checker;
        private final DisposableScope scope;
        private final BooleanSupplier scopeActive;
        private final Map<String, ServiceGate> table;

        Handler(
                final Class<?> api,
                final Object delegate,
                final PermissionChecker checker,
                final DisposableScope scope,
                final BooleanSupplier scopeActive,
                final Map<String, ServiceGate> table) {
            this.api = api;
            this.delegate = delegate;
            this.checker = checker;
            this.scope = scope;
            this.scopeActive = scopeActive;
            this.table = table;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "ServiceGate[" + api.getName() + "]";
                    default -> throw new IllegalStateException("Unexpected Object method: " + method);
                };
            }
            final ServiceGate row = table.get(method.getName());
            final boolean terminal =
                    isTerminalVerb(method) || (row != null && row.access() == ServiceGate.Access.TERMINAL);
            if (!terminal) {
                if (row == null) {
                    // Unreachable through install(), which refuses an incomplete table; kept so a
                    // method the table lost is denied instead of silently forwarded.
                    throw new IllegalStateException(
                            "Service " + api.getName() + "." + method.getName() + " is not gated by this table");
                }
                if (row.access() == ServiceGate.Access.WRITE) {
                    requireActiveScope(row);
                }
                requirePermission(row);
            }
            final Object result;
            try {
                result = method.invoke(delegate, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            return result instanceof Registration registration ? adopt(registration) : result;
        }

        private void requireActiveScope(final ServiceGate row) {
            if (scopeActive == null || scopeActive.getAsBoolean()) return;
            throw new IllegalStateException("Service " + api.getName() + "." + row.method()
                    + " is stale because the owning plugin is disabled.");
        }

        private void requirePermission(final ServiceGate row) {
            if (row.permissionId() == null) return;
            checker.check(row.permissionId(), row.operationId());
        }

        /**
         * Hands a delegate registration to the plugin scope. A scope that refuses the
         * registration (already closed or sealed) must not keep the resource alive, so the
         * freshly created registration is closed before the refusal propagates.
         */
        private Registration adopt(final Registration registration) {
            if (scope == null) return registration;
            try {
                return scope.register(registration);
            } catch (RuntimeException | Error failure) {
                registration.close();
                throw failure;
            }
        }
    }
}
