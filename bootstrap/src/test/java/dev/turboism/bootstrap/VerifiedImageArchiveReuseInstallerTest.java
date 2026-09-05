package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedImageArchiveReuseInstallerTest {
    @Test void admitsOnlyExplicitlyRequestedExact5302OutsideSafeMode() {
        var ordinary = new RuntimeStartupConfig(false,false,false,false);
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,ordinary,false));
        assertTrue(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,ordinary,true));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_03,ordinary,true));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_2_03,ordinary,true));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(true,false,false,false),true));
        assertFalse(VerifiedImageArchiveReuseInstaller.admitted(ReviewedHostArtifacts.CUBISM_5_3_02,
            new RuntimeStartupConfig(false,false,false,false,false,false,false,
                Set.of(VerifiedImageArchiveReuseInstaller.HOOK_ID)),true));
    }
}
