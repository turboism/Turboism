package dev.turboism.ui.mesh;

import dev.turboism.adapter.cubism.mesh.MeshToolCoordinator;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reversible independently admitted native mesh-toolbar provider. */
public final class MeshToolbarContributionProvider implements EditorUiContributionProvider {
    private static final Comparator<MeshToolbarContributionDescriptor> TOOL_ORDER = Comparator.comparingInt(
                    MeshToolbarContributionDescriptor::order)
            .thenComparing(MeshToolbarContributionDescriptor::pluginId)
            .thenComparingLong(MeshToolbarContributionDescriptor::pluginGeneration)
            .thenComparing(MeshToolbarContributionDescriptor::toolId);
    private static final Comparator<MeshToolbarSliderContributionDescriptor> SLIDER_ORDER = Comparator.comparingInt(
                    MeshToolbarSliderContributionDescriptor::order)
            .thenComparing(MeshToolbarSliderContributionDescriptor::pluginId)
            .thenComparingLong(MeshToolbarSliderContributionDescriptor::pluginGeneration)
            .thenComparing(MeshToolbarSliderContributionDescriptor::controlId);

    private final EditorUiProviderAdmission admission;
    private final MeshToolbarHostOperations host;
    private final MeshToolCoordinator coordinator;

    public MeshToolbarContributionProvider(
            final EditorUiProviderAdmission admission,
            final MeshToolbarHostOperations host,
            final MeshToolCoordinator coordinator) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.MESH_TOOLBAR) {
            throw new IllegalArgumentException("mesh-toolbar provider requires MESH_TOOLBAR admission");
        }
        this.host = Objects.requireNonNull(host, "host");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.MESH_TOOLBAR;
    }

    @Override
    public EditorUiProviderAdmission admission() {
        return admission;
    }

    @Override
    public Registration apply(final long hostGeneration, final List<EditorUiContribution<?>> contributions) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("mesh-toolbar provider admission is stale");
        }
        coordinator.replaceHostGeneration(hostGeneration);
        final List<MeshToolbarContributionDescriptor> tools = new ArrayList<>();
        final List<MeshToolbarSliderContributionDescriptor> sliders = new ArrayList<>();
        for (EditorUiContribution<?> contribution : List.copyOf(contributions)) {
            if (contribution.descriptor() instanceof MeshToolbarSliderContributionDescriptor) {
                sliders.add(MeshToolbarSliderContributionDescriptor.from(contribution));
            } else {
                tools.add(MeshToolbarContributionDescriptor.from(contribution));
            }
        }
        tools.sort(TOOL_ORDER);
        sliders.sort(SLIDER_ORDER);
        final Reconciler reconciler = new Reconciler(List.copyOf(tools), List.copyOf(sliders));
        reconciler.rebuild();
        final Registration state = coordinator.onStateChanged(reconciler::syncSelectedSafely);
        final Registration rebuild;
        try {
            rebuild = host.onRebuild(reconciler::rebuildSafely);
        } catch (RuntimeException | Error failure) {
            closeSuppressing(state, failure);
            closeSuppressing(reconciler, failure);
            throw failure;
        }
        return once(() -> closeReverse(List.of(reconciler, state, rebuild)));
    }

    private final class Reconciler implements Registration {
        private final List<MeshToolbarContributionDescriptor> tools;
        private final List<MeshToolbarSliderContributionDescriptor> sliders;
        private List<MeshToolbarHostOperations.ButtonHandle> buttons = List.of();
        private List<MeshToolbarHostOperations.SliderHandle> sliderHandles = List.of();
        private boolean closed;

        private Reconciler(
                List<MeshToolbarContributionDescriptor> tools, List<MeshToolbarSliderContributionDescriptor> sliders) {
            this.tools = tools;
            this.sliders = sliders;
        }

        private synchronized void rebuild() {
            if (closed) return;
            closeControls();
            final ArrayList<MeshToolbarHostOperations.ButtonHandle> nextButtons = new ArrayList<>();
            final ArrayList<MeshToolbarHostOperations.SliderHandle> nextSliders = new ArrayList<>();
            try {
                for (MeshToolbarContributionDescriptor tool : tools) {
                    nextButtons.add(Objects.requireNonNull(host.addButton(tool, click(tool)), "host.addButton()"));
                }
                for (MeshToolbarSliderContributionDescriptor slider : sliders) {
                    nextSliders.add(Objects.requireNonNull(
                            host.addSlider(slider, value -> changedSafely(slider, value)), "host.addSlider()"));
                }
                buttons = List.copyOf(nextButtons);
                sliderHandles = List.copyOf(nextSliders);
                syncSelected();
            } catch (RuntimeException | Error failure) {
                closeReverseSuppressing(nextSliders, failure);
                closeReverseSuppressing(nextButtons, failure);
                throw failure;
            }
        }

        private void rebuildSafely() {
            try {
                rebuild();
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            }
        }

        private Runnable click(final MeshToolbarContributionDescriptor descriptor) {
            return () -> {
                final boolean deactivating = isActive(descriptor);
                try {
                    if (deactivating) {
                        coordinator.deactivate(
                                descriptor.pluginId(), descriptor.pluginGeneration(), descriptor.toolId());
                    } else {
                        coordinator.activate(descriptor.pluginId(), descriptor.pluginGeneration(), descriptor.toolId());
                    }
                } catch (Throwable failure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                    // A refused activation must stay contained, but it must not be invisible: the
                    // engine silently clears the button selection afterwards, which looks to the user
                    // like a click that did nothing.
                    reportActivationFailure(descriptor, deactivating, failure);
                } finally {
                    syncSelectedSafely();
                }
                // Activation is user-driven and rare, so the committed transition is recorded at
                // INFO: a human-operated session otherwise leaves no positive evidence at all, and
                // "no warning" cannot be told apart from "never clicked".
                reportActivationCommitted(descriptor, deactivating);
            };
        }

        /** Records one committed toolbar transition with the resulting active tool. */
        private void reportActivationCommitted(
                final MeshToolbarContributionDescriptor descriptor, final boolean deactivating) {
            try {
                dev.turboism.runtime.log.RuntimeDiagnostics.info(
                        "mesh-toolbar",
                        "action=" + (deactivating ? "deactivate" : "activate")
                                + " tool=" + descriptor.toolId()
                                + " result=committed sessionOpen=" + coordinator.hasActiveSession()
                                + " activeTool="
                                + coordinator
                                        .activeTool()
                                        .map(active -> active.tool().id())
                                        .orElse("none"));
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
                // Diagnostics must never escape the host action ingress.
            }
        }

        /**
         * Reports one bounded toolbar activation failure together with the state that decided it.
         *
         * <p>Activation requires an open exact mesh-editor session, so the session presence is the
         * single most useful fact and is included. No host object or host stack frame is logged.</p>
         */
        private void reportActivationFailure(
                final MeshToolbarContributionDescriptor descriptor,
                final boolean deactivating,
                final Throwable failure) {
            try {
                final String detail = failure.getMessage();
                dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                        "mesh-toolbar",
                        "action=" + (deactivating ? "deactivate" : "activate")
                                + " tool=" + descriptor.toolId()
                                + " sessionOpen=" + coordinator.hasActiveSession()
                                + " activeTool="
                                + coordinator
                                        .activeTool()
                                        .map(active -> active.tool().id())
                                        .orElse("none")
                                + " failure=" + failure.getClass().getSimpleName()
                                + " detail="
                                + (detail == null || detail.isBlank()
                                        ? "none"
                                        : detail.length() <= 120 ? detail : detail.substring(0, 120)));
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
                // Diagnostics must never escape the host action ingress either.
            }
        }

        private void changedSafely(final MeshToolbarSliderContributionDescriptor descriptor, final int value) {
            try {
                descriptor.changed(value);
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            }
        }

        private synchronized void syncSelected() {
            for (int index = 0; index < buttons.size(); index++) {
                buttons.get(index).setSelected(isActive(tools.get(index)));
            }
        }

        private void syncSelectedSafely() {
            try {
                syncSelected();
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            }
        }

        private boolean isActive(final MeshToolbarContributionDescriptor descriptor) {
            return coordinator
                    .activeTool()
                    .filter(active -> active.pluginId().equals(descriptor.pluginId())
                            && active.generation() == descriptor.pluginGeneration()
                            && active.tool().id().equals(descriptor.toolId()))
                    .isPresent();
        }

        private void closeControls() {
            RuntimeException first = closeReverse(sliderHandles);
            sliderHandles = List.of();
            first = append(first, closeReverse(buttons));
            buttons = List.of();
            if (first != null) throw first;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            closeControls();
        }
    }

    private static Registration once(final Runnable close) {
        final AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) close.run();
        };
    }

    private static RuntimeException closeReverse(final List<? extends Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                first = append(first, failure);
            }
        }
        return first;
    }

    private static void closeReverseSuppressing(
            final List<? extends Registration> registrations, final Throwable failure) {
        final RuntimeException cleanup = closeReverse(registrations);
        if (cleanup != null) failure.addSuppressed(cleanup);
    }

    private static void closeSuppressing(final Registration registration, final Throwable failure) {
        try {
            registration.close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static RuntimeException append(final RuntimeException first, final RuntimeException next) {
        if (next == null) return first;
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }
}
