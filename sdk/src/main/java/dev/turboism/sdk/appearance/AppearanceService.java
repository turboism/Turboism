package dev.turboism.sdk.appearance;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * A plugin's handle on the Editor appearance: read the current state, overlay its own, and put the
 * Editor's back.
 *
 * <p>Business outcomes — rejection, unavailability, apply and restore failures — are reported as
 * result values on the returned stages, so callers can branch on the outcome enums. That does not
 * make the calls total: an implementation may throw synchronously before returning a stage (for
 * example on permission denial, a {@code null} argument or a provider error), and a returned stage
 * may itself complete exceptionally. A plugin may only restore an appearance it owns. Where the
 * host offers no appearance control at all, {@link #unavailable()} supplies a conformant no-op
 * implementation.
 */
public interface AppearanceService {

    /**
     * Returns the appearance state currently in force on the host.
     *
     * @return a stage for the observed status; see the class contract — synchronous validation
     *     failures may still be thrown
     */
    CompletionStage<AppearanceStatus> current();

    /**
     * Overlays this plugin's appearance on the Editor.
     *
     * @param request the requested appearance, including the {@code expectedRevision}
     *     optimistic-concurrency token from an earlier {@link #current()} result
     * @return a stage for the outcome of the attempt; business rejection and failure arrive as
     *     values, while permission denial and provider errors may throw synchronously
     */
    CompletionStage<AppearanceApplyResult> apply(AppearanceRequest request);

    /**
     * Removes the appearance overlay this plugin owns, putting the Editor's own appearance back.
     *
     * @return a stage for the outcome; {@code NO_OWNED_OVERRIDE} reports a clean no-op when this
     *     plugin had nothing installed; permission denial may throw synchronously
     */
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
     *     diagnostic id. Never returns {@code null}; argument validation still applies.
     */
    static AppearanceService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls that report outcomes complete with the structured unavailability result; and queries report empty results. */
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
