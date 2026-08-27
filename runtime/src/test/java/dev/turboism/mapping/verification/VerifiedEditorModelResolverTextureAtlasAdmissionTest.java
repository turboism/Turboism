package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedEditorModelResolverTextureAtlasAdmissionTest {

    @Test
    void atlasValidationRequiresTheExactCompoundLane() {
        assertTrue(VerifiedEditorModelResolverFactory.admitsTextureAtlasValidation(
            "EXACT_5303_TEXTURE_ATLAS_HOOK_CANDIDATE",
            "5303",
            "texture-atlas-hook-5303",
            "run-1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsTextureAtlasValidation(
            "EXACT_5303_TEXTURE_ATLAS_HOOK_CANDIDATE",
            "5302",
            "texture-atlas-hook-5303",
            "run-1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsTextureAtlasValidation(
            "wrong", "5303", "texture-atlas-hook-5303", "run-1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsTextureAtlasValidation(
            "EXACT_5303_TEXTURE_ATLAS_HOOK_CANDIDATE", "5303", "wrong", "run-1"
        ));
        assertFalse(VerifiedEditorModelResolverFactory.admitsTextureAtlasValidation(
            "EXACT_5303_TEXTURE_ATLAS_HOOK_CANDIDATE",
            "5303",
            "texture-atlas-hook-5303",
            ""
        ));
    }
}
