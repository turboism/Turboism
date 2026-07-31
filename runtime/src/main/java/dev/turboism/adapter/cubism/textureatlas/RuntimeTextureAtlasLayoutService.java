package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import dev.turboism.sdk.permission.CubismPermissionException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.Set;

/** Per-plugin permission checked texture-atlas layout service. */
public final class RuntimeTextureAtlasLayoutService implements TextureAtlasLayoutService {

    public static final String READ_PERMISSION = "turboism.cubism.model.read";
    public static final String WRITE_PERMISSION = "turboism.cubism.model.write";
    private static final String CAPABILITY = "cubism.texture-atlas.layout";

    private final TextureAtlasLayoutCoordinator coordinator;
    private final CubismPermissionGate permissionGate;
    private final TextureAtlasNativeInvocationCoordinator nativeInvocations;
    private final Object ownerToken = new Object();

    public RuntimeTextureAtlasLayoutService(
        final TextureAtlasLayoutCoordinator coordinator,
        final CubismPermissionGate permissionGate
    ) {
        this(coordinator, permissionGate, new TextureAtlasNativeInvocationCoordinator());
    }

    public RuntimeTextureAtlasLayoutService(
        final TextureAtlasLayoutCoordinator coordinator,
        final CubismPermissionGate permissionGate,
        final TextureAtlasNativeInvocationCoordinator nativeInvocations
    ) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.nativeInvocations = Objects.requireNonNull(nativeInvocations, "nativeInvocations");
    }

    @Override
    public Optional<TextureAtlasLayoutSnapshot> current() {
        permissionGate.require(READ_PERMISSION, "textureAtlasLayouts.current", CAPABILITY);
        final Optional<TextureAtlasNativeInvocationCoordinator.Invocation> nativeInvocation =
            nativeInvocations.current();
        if (nativeInvocation.isPresent()) {
            final TextureAtlasNativeInvocationCoordinator.Invocation invocation = nativeInvocation.orElseThrow();
            final TextureAtlasAuthoringState state = invocation.session().state();
            return Optional.of(new TextureAtlasLayoutSnapshot(
                new NativeTarget(ownerToken, invocation),
                state.documentId(), state.modelId(), state.atlasId(),
                state.constraints(), state.items(), state.currentPlan()
            ));
        }
        return coordinator.current().map(snapshot -> {
            final TextureAtlasAuthoringState state = snapshot.state();
            return new TextureAtlasLayoutSnapshot(
                new RuntimeTarget(ownerToken, snapshot.generation(), state),
                state.documentId(),
                state.modelId(),
                state.atlasId(),
                state.constraints(),
                state.items(),
                state.currentPlan()
            );
        });
    }

    @Override
    public TextureAtlasLayoutApplyResult apply(
        final TextureAtlasLayoutTarget target,
        final TextureAtlasLayoutPlan plan
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(plan, "plan");
        try {
            permissionGate.require(WRITE_PERMISSION, "textureAtlasLayouts.apply", CAPABILITY);
        } catch (CubismPermissionException exception) {
            return failed(TextureAtlasLayoutFailureCode.PERMISSION_DENIED, "Texture atlas write permission is denied.");
        }
        if (target instanceof NativeTarget nativeTarget) {
            final Optional<TextureAtlasNativeInvocationCoordinator.Invocation> current = nativeInvocations.current();
            if (!nativeTarget.ownedBy(ownerToken)
                || current.isEmpty()
                || current.orElseThrow() != nativeTarget.invocation()) {
                return failed(TextureAtlasLayoutFailureCode.TARGET_STALE, "The texture atlas target is stale.");
            }
            final TextureAtlasNativeInvocationCoordinator.Invocation invocation = current.orElseThrow();
            final TextureAtlasAuthoringState state = invocation.session().state();
            final Optional<String> issue = validate(state, plan);
            if (issue.isPresent()) {
                return failed(TextureAtlasLayoutFailureCode.PLAN_INVALID, issue.orElseThrow());
            }
            final TextureAtlasLayoutProvider.ApplyOutcome outcome = invocation.session().apply(plan);
            if (outcome == TextureAtlasLayoutProvider.ApplyOutcome.REJECTED) {
                return failed(TextureAtlasLayoutFailureCode.PROVIDER_REJECTED, "Native texture atlas invocation rejected the validated plan.");
            }
            invocation.handled(true);
            return outcome == TextureAtlasLayoutProvider.ApplyOutcome.NO_CHANGE
                ? TextureAtlasLayoutApplyResult.noChange()
                : TextureAtlasLayoutApplyResult.applied();
        }
        if (!(target instanceof RuntimeTarget runtimeTarget) || !runtimeTarget.ownedBy(ownerToken)) {
            return failed(TextureAtlasLayoutFailureCode.TARGET_STALE, "The texture atlas target is stale.");
        }
        final Optional<String> issue = validate(runtimeTarget.state(), plan);
        if (issue.isPresent()) {
            return failed(TextureAtlasLayoutFailureCode.PLAN_INVALID, issue.orElseThrow());
        }
        return coordinator.apply(runtimeTarget.generation(), runtimeTarget.state(), plan);
    }

    private Optional<String> validate(
        final TextureAtlasAuthoringState state,
        final TextureAtlasLayoutPlan plan
    ) {
        final TextureAtlasLayoutConstraints constraints = state.constraints();
        if (plan.pageWidth() != constraints.pageWidth()
            || plan.pageHeight() != constraints.pageHeight()
            || plan.pageCount() > constraints.maxPages()) {
            return Optional.of("The plan does not match the issued atlas page constraints.");
        }
        final Set<Integer> representedPages = new HashSet<>();
        for (TextureAtlasPlacement placement : plan.placements()) {
            representedPages.add(placement.pageIndex());
        }
        final Set<Integer> expectedPages = plan.placements().isEmpty()
            ? Set.of(0)
            : IntStream.range(0, plan.pageCount()).boxed().collect(java.util.stream.Collectors.toUnmodifiableSet());
        if ((plan.placements().isEmpty() && plan.pageCount() != 1)
            || (!plan.placements().isEmpty() && !representedPages.equals(expectedPages))) {
            return Optional.of("The plan must represent every declared atlas page exactly.");
        }
        final Map<String, TextureAtlasLayoutItem> items = new HashMap<>();
        for (TextureAtlasLayoutItem item : state.items()) items.put(item.textureId(), item);
        final Set<String> placements = new HashSet<>();
        for (TextureAtlasPlacement placement : plan.placements()) {
            placements.add(placement.textureId());
            final TextureAtlasLayoutItem item = items.get(placement.textureId());
            if (item == null) return Optional.of("The plan contains an unknown texture ID.");
            if (placement.rotated()
                || placement.width() != item.width()
                || placement.height() != item.height()) {
                return Optional.of("The plan rotates or scales a texture without support.");
            }
            if (placement.x() < constraints.edgeMargin()
                || placement.y() < constraints.edgeMargin()
                || (long) placement.x() + placement.width() + constraints.edgeMargin() > plan.pageWidth()
                || (long) placement.y() + placement.height() + constraints.edgeMargin() > plan.pageHeight()) {
                return Optional.of("The plan violates the atlas edge margin.");
            }
        }
        if (!placements.equals(items.keySet())) {
            return Optional.of("The plan must place every issued texture exactly once.");
        }
        for (int leftIndex = 0; leftIndex < plan.placements().size(); leftIndex++) {
            final TextureAtlasPlacement left = plan.placements().get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < plan.placements().size(); rightIndex++) {
                final TextureAtlasPlacement right = plan.placements().get(rightIndex);
                if (tooClose(left, right, constraints.itemPadding())) {
                    return Optional.of("The plan violates the required item padding.");
                }
            }
        }
        return Optional.empty();
    }

    private boolean tooClose(
        final TextureAtlasPlacement left,
        final TextureAtlasPlacement right,
        final int padding
    ) {
        if (left.pageIndex() != right.pageIndex()) return false;
        return (long) left.x() < (long) right.x() + right.width() + padding
            && (long) left.x() + left.width() + padding > right.x()
            && (long) left.y() < (long) right.y() + right.height() + padding
            && (long) left.y() + left.height() + padding > right.y();
    }

    private static TextureAtlasLayoutApplyResult failed(
        final TextureAtlasLayoutFailureCode code,
        final String message
    ) {
        return TextureAtlasLayoutApplyResult.failed(code, message);
    }

    private record NativeTarget(
        Object ownerToken,
        TextureAtlasNativeInvocationCoordinator.Invocation invocation
    ) implements TextureAtlasLayoutTarget {
        private NativeTarget {
            ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
            invocation = Objects.requireNonNull(invocation, "invocation");
        }
        boolean ownedBy(final Object candidate) { return ownerToken == candidate; }
    }

    private record RuntimeTarget(
        Object ownerToken,
        long generation,
        TextureAtlasAuthoringState state
    ) implements TextureAtlasLayoutTarget {
        private RuntimeTarget {
            ownerToken = Objects.requireNonNull(ownerToken, "ownerToken");
            state = Objects.requireNonNull(state, "state");
        }
        boolean ownedBy(final Object candidate) { return ownerToken == candidate; }
    }
}
