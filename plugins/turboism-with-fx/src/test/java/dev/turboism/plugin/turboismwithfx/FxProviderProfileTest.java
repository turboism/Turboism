package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider-profile domain rules, persistence shape, and secret exclusion. */
final class FxProviderProfileTest {

    @Test
    void builtInProfilesAreFxOwnedAndCannotBeStoredAsCustomProfiles() {
        final List<FxProviderProfile> builtIns = FxProviderProfile.builtIns();

        assertEquals(
            List.of(
                FxProviderProfile.UNCONFIGURED_ID,
                FxProviderProfile.VERCEL_ID,
                FxProviderProfile.CODEX_ID,
                FxProviderProfile.GROK_ID
            ),
            builtIns.stream().map(FxProviderProfile::id).toList()
        );
        builtIns.forEach(profile -> {
            assertTrue(profile.builtIn());
            assertEquals(
                FxProviderProfile.UNCONFIGURED_ID.equals(profile.id())
                    ? FxProviderProfile.Kind.NONE
                    : FxProviderProfile.Kind.FX_NATIVE,
                profile.kind()
            );
            assertTrue(profile.endpoint().isEmpty());
            assertTrue(profile.apiKeyEnvironment().isEmpty());
            assertFalse(profile.customEndpoint("sk-ignored").enabled());
        });
        assertThrows(IllegalArgumentException.class, () -> new FxProviderConfiguration(
            FxProviderProfile.VERCEL_ID,
            builtIns,
            Map.of()
        ));
    }

    @Test
    void customProfilesRequireAValidEndpointButAllowModelSelectionLater() {
        final FxProviderProfile withoutDefault = custom("http://host/v1", "");
        assertTrue(withoutDefault.defaultModel().isEmpty());
        assertTrue(withoutDefault.customEndpoint("").model().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> custom("ftp://host/v1", "m"));
        assertThrows(IllegalArgumentException.class, () -> custom("http://u:p@host/v1", "m"));
        assertThrows(IllegalArgumentException.class, () -> custom("http://host/v1?k=1", "m"));

        final FxProviderProfile profile = custom("https://host.example/v1", "vendor/model");
        final FxCustomEndpointSettings endpoint = profile.customEndpoint("sk-session");
        assertTrue(endpoint.enabled());
        assertEquals("vendor/model", endpoint.model());
        assertEquals("sk-session", endpoint.resolveApiKey());
        assertTrue(endpoint.persisted().sessionApiKey().isEmpty());
    }

    @Test
    void discoveredAndManualModelsMergeWithoutDuplicatesAndKeepTheDefault() {
        final FxProviderProfile profile = new FxProviderProfile(
            "custom-1",
            "Self hosted",
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            "http://127.0.0.1:8000/v1",
            "",
            "vendor/default",
            List.of("vendor/manual", "vendor/default")
        );

        assertEquals(
            List.of("vendor/one", "vendor/manual", "vendor/default"),
            profile.models(List.of("vendor/one", "vendor/one"))
        );
        assertEquals(List.of("vendor/manual", "vendor/default"), profile.models(List.of()));
    }

    @Test
    void configurationFallsBackToABuiltInWhenTheActiveProfileDisappears() {
        final FxProviderProfile profile = custom("http://127.0.0.1:8000/v1", "vendor/model");
        final FxProviderConfiguration selected = new FxProviderConfiguration(
            profile.id(),
            List.of(profile),
            Map.of(profile.id(), "sk-session", "unknown", "sk-dropped")
        );

        assertEquals(profile.id(), selected.activeProfile().id());
        assertEquals(Map.of(profile.id(), "sk-session"), selected.sessionApiKeys());
        assertEquals("sk-session", selected.customEndpoint().resolveApiKey());

        final FxProviderConfiguration removed = new FxProviderConfiguration(
            profile.id(),
            List.of(),
            Map.of()
        );
        assertEquals(FxProviderProfile.UNCONFIGURED_ID, removed.activeProfileId());
        assertFalse(removed.customEndpoint().enabled());
    }

    @Test
    void persistedProfilesCarryMetadataOnlyAndNeverAnApiKey() {
        final FxProviderProfile profile = new FxProviderProfile(
            "custom-1",
            "Self hosted",
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            "http://127.0.0.1:8000/v1",
            "SELF_HOSTED_KEY",
            "vendor/default",
            List.of("vendor/manual")
        );

        final String encoded = FxProviderProfileCodec.encode(List.of(profile));
        assertFalse(encoded.contains("sk-"));
        assertFalse(encoded.contains("sessionApiKey"));
        assertTrue(encoded.contains("SELF_HOSTED_KEY"));

        assertEquals(List.of(profile), FxProviderProfileCodec.decode(encoded));
        assertEquals(List.of(), FxProviderProfileCodec.decode(""));
        assertEquals(
            "",
            FxProviderProfileCodec.encode(FxProviderProfile.builtIns()).replace("[]", "")
        );
    }

    @Test
    void environmentVariableSuppliesTheKeyWhenNoValueIsStored() {
        final FxCustomEndpointSettings settings = new FxCustomEndpointSettings(
            true,
            "http://127.0.0.1:8000/v1",
            "vendor/model",
            "TURBOISM_FX_TEST_KEY_ABSENT",
            ""
        );

        assertEquals("", settings.resolveApiKey());
        assertThrows(IllegalArgumentException.class, () -> new FxCustomEndpointSettings(
            true,
            "http://127.0.0.1:8000/v1",
            "vendor/model",
            "1-invalid-name",
            ""
        ));
    }

    private static FxProviderProfile custom(final String endpoint, final String model) {
        return new FxProviderProfile(
            "custom-1",
            "Self hosted",
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            endpoint,
            "",
            model,
            List.of()
        );
    }
}
