package dev.turboism.exportsettings;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.List;
import java.util.Set;

/**
 * Fixture for the protected-export host halves: a real verified plan that carries exactly one
 * unrelated selector, so every {@code cubism.protected-export.*} alias is genuinely unavailable.
 *
 * <p>This is the plan shape the halves must survive: an unavailable alias has to fail closed with
 * the alias named, never degrade into "not this type", an empty list or a skipped rewrite. A plan
 * with no selectors at all cannot be built, so the single unrelated selector keeps the fixture
 * honest without admitting anything the halves resolve.</p>
 */
final class ProtectedExportHostOpsTestSupport {

    private static final String UNRELATED_ALIAS = "cubism.fixture.not-a-protected-export-alias";

    private ProtectedExportHostOpsTestSupport() {}

    /** Returns a resolver whose plan authorizes nothing the protected-export halves resolve. */
    static VerifiedMemberResolver resolverWithoutProtectedExportAliases() {
        return TestVerifiedResolvers.create(
                "5.3.02",
                "fixture.slice",
                Set.of("fixture.capability"),
                List.of(StaticSelector.classSelector(UNRELATED_ALIAS, "java/lang/Object")),
                ProtectedExportHostOpsTestSupport.class.getClassLoader());
    }
}
