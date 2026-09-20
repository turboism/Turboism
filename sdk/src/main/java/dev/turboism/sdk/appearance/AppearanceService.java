package dev.turboism.sdk.appearance;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A plugin's handle on the Editor appearance: read the current state, overlay its own, and put the
 * Editor's back.
 *
 * <p>Every operation is asynchronous and completes on the host's schedule; results are returned as
 * values rather than thrown, so callers branch on the outcome enums instead of catching. A plugin
 * may only restore an appearance it owns. Where the host offers no appearance control at all,
 * {@link #unavailable()} supplies a conformant no-op implementation.
 */
public interface AppearanceService {

    CompletionStage<AppearanceStatus> current();

    CompletionStage<AppearanceApplyResult> apply(AppearanceRequest request);

    CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * @return a service that changes nothing: {@link #current()} reports
     *     {@link AppearanceStatus.Availability#UNAVAILABLE} at revision 0, and both mutating calls
     *     complete with an {@code UNAVAILABLE} outcome and the {@code appearance.unavailable}
     *     diagnostic id. Never returns {@code null} and never fails.
     */
    static AppearanceService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements AppearanceService {
        INSTANCE;

        private static final AppearanceStatus STATUS = new AppearanceStatus(
            AppearanceStatus.Availability.UNAVAILABLE,
            AppearanceStatus.Source.NATIVE,
            java.util.Optional.empty(),
            AppearanceBase.NATIVE,
            0,
            java.util.Optional.of("appearance.unavailable")
        );

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public CompletionStage<AppearanceStatus> current() {
            return CompletableFuture.completedFuture(STATUS);
        }

        @Override public CompletionStage<AppearanceApplyResult> apply(final AppearanceRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return CompletableFuture.completedFuture(new AppearanceApplyResult(
                AppearanceApplyResult.Outcome.UNAVAILABLE,
                STATUS,
                java.util.Optional.of("appearance.unavailable")
            ));
        }

        @Override public CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
            return CompletableFuture.completedFuture(new AppearanceRestoreResult(
                AppearanceRestoreResult.Outcome.UNAVAILABLE,
                STATUS,
                java.util.Optional.of("appearance.unavailable")
            ));
        }
    }
}
