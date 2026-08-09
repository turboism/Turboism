package dev.turboism.validation.parameterbatchtransfer;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot host diagnostic: cross-checks the drawable-side binding read
 * (drawables().find(id).getParameterBindings()) against the parameter-side
 * read (parameter.getParameterBindings()) and prints the full comparison to
 * the runtime log. Validation tooling only; never part of the product.
 */
public final class BindingCrossCheckDiagnosticPlugin implements TurboismPlugin {

    private PluginLogger logger;
    private PluginContext context;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        final Thread worker = new Thread(this::runWhenModelReady, "binding-cross-check");
        worker.setDaemon(true);
        worker.start();
        logger.info("BINDING_CROSS_CHECK_READY");
    }

    @Override
    public void enable() {
        logger.info("BINDING_CROSS_CHECK_ENABLED");
    }

    @Override
    public void disable() {
    }

    @Override
    public void shutdown() {
    }

    private void runWhenModelReady() {
        final long deadline = System.currentTimeMillis() + 300_000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                if (model != null && onHostThread(() -> !model.drawables().all().isEmpty())) {
                    run(model);
                    return;
                }
            } catch (Exception ignored) {
                // model not ready yet
            }
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("BINDING_CROSS_CHECK_MODEL_TIMEOUT");
    }

    private void run(final CubismModel model) throws Exception {
        final List<Drawable> drawables = onHostThread(model.drawables()::all);
        final List<Parameter> parameters = onHostThread(model.parameters()::all);

        // Parameter-side reverse index: target id -> bound parameter ids.
        final java.util.Map<String, List<String>> byTarget = new java.util.LinkedHashMap<>();
        for (final Parameter parameter : parameters) {
            final List<ParameterBinding> bindings;
            try {
                bindings = onHostThread(() -> parameter.getParameterBindings());
            } catch (Exception failure) {
                logger.warn("BINDING_CROSS_CHECK paramReadFailed id=" + parameter.id().value()
                    + " error=" + failure);
                continue;
            }
            for (final ParameterBinding binding : bindings) {
                byTarget.computeIfAbsent(binding.target().id(), key -> new ArrayList<>())
                    .add(parameter.id().value());
            }
        }

        // Drawable-side reads.
        final StringBuilder report = new StringBuilder();
        for (final Drawable drawable : drawables) {
            final String id = drawable.id().value();
            final List<String> drawableSide = new ArrayList<>();
            try {
                for (final ParameterBinding binding : onHostThread(() -> drawable.getParameterBindings())) {
                    drawableSide.add(binding.parameterId().value());
                }
            } catch (Exception failure) {
                drawableSide.add("READ_FAILED:" + failure);
            }
            final List<String> parameterSide = byTarget.getOrDefault(id, List.of());
            report.append(id)
                .append(" drawableSide=").append(drawableSide)
                .append(" parameterSide=").append(parameterSide)
                .append(" match=").append(new java.util.HashSet<>(drawableSide).equals(new java.util.HashSet<>(parameterSide)))
                .append('\n');
        }
        logger.info("BINDING_CROSS_CHECK_MATRIX\n" + report);
        logger.info("BINDING_CROSS_CHECK_DONE drawables=" + drawables.size() + " parameters=" + parameters.size());
    }

    private <T> T onHostThread(final java.util.concurrent.Callable<T> operation) throws Exception {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        if (failure.get() != null) {
            if (failure.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.get() instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }
}
