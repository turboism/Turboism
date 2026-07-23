package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@PreviewApi
public interface AppearanceService {

    CompletionStage<AppearanceStatus> current();

    CompletionStage<AppearanceApplyResult> apply(AppearanceRequest request);

    CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance();

    static AppearanceService unavailable() {
        final AppearanceStatus status = new AppearanceStatus(
            AppearanceStatus.Availability.UNAVAILABLE,
            AppearanceStatus.Source.NATIVE,
            java.util.Optional.empty(),
            AppearanceBase.NATIVE,
            0,
            java.util.Optional.of("appearance.unavailable")
        );
        return new AppearanceService() {
            @Override
            public CompletionStage<AppearanceStatus> current() {
                return CompletableFuture.completedFuture(status);
            }

            @Override
            public CompletionStage<AppearanceApplyResult> apply(final AppearanceRequest request) {
                java.util.Objects.requireNonNull(request, "request");
                return CompletableFuture.completedFuture(new AppearanceApplyResult(
                    AppearanceApplyResult.Outcome.UNAVAILABLE,
                    status,
                    java.util.Optional.of("appearance.unavailable")
                ));
            }

            @Override
            public CompletionStage<AppearanceRestoreResult> restoreOwnedAppearance() {
                return CompletableFuture.completedFuture(new AppearanceRestoreResult(
                    AppearanceRestoreResult.Outcome.UNAVAILABLE,
                    status,
                    java.util.Optional.of("appearance.unavailable")
                ));
            }
        };
    }
}
