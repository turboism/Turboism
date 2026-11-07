package dev.turboism.exportsettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ProtectedExportHostVocabulary} to the alias roster the operations facade admits.
 *
 * <p>The vocabulary and the roster are two halves of one admission contract: the roster is what
 * {@code VerifiedMemberResolver.authorizes} must cover before the protected-export slice comes
 * up, and the vocabulary is what every resolve call names. A constant that exists but is never
 * rostered narrows the admission silently, a literal declared twice drifts the moment one copy is
 * edited, and a literal outside the reviewed namespace can never match a record. Each of those is
 * rejected here instead of on a user's host.</p>
 */
class ProtectedExportHostVocabularyTest {

    private static final String REVIEWED_NAMESPACE = "cubism.protected-export.";

    @Test
    void everyDeclaredAliasIsRosteredExactlyOnceAcrossMethodAndClassAliases() {
        final Set<String> methodAliases = VerifiedProtectedExportHostOperations.methodAliasesUsed();
        final Set<String> classAliases = VerifiedProtectedExportHostOperations.classAliasesUsed();
        final Set<String> roster = new HashSet<>(methodAliases);
        roster.addAll(classAliases);

        assertEquals(declaredAliases(), roster, "the alias roster must be exactly the declared vocabulary");
        assertTrue(
                Collections.disjoint(methodAliases, classAliases),
                "a declared alias must be rostered as either a method or a class alias, never both");
    }

    @Test
    void theLegacyRosterStaysTheCurrentRosterMinusTheFiveThirdOnlyAliases() {
        final Set<String> legacy = VerifiedProtectedExportHostOperations.CUBISM_5_2_REQUIRED_ALIASES;
        final Set<String> legacyMethods = VerifiedProtectedExportHostOperations.cubism52MethodAliasesUsed();
        final Set<String> legacyClasses = VerifiedProtectedExportHostOperations.cubism52ClassAliasesUsed();

        assertTrue(
                VerifiedProtectedExportHostOperations.REQUIRED_ALIASES.containsAll(legacy),
                "the reviewed 5.2.03 roster must stay a subset of the current roster");
        assertTrue(
                VerifiedProtectedExportHostOperations.methodAliasesUsed().containsAll(legacyMethods),
                "the reviewed 5.2.03 method aliases must stay a subset of the current method aliases");
        assertTrue(
                VerifiedProtectedExportHostOperations.classAliasesUsed().containsAll(legacyClasses),
                "the reviewed 5.2.03 class aliases must stay a subset of the current class aliases");
        assertEquals(
                legacy,
                union(legacyMethods, legacyClasses),
                "the 5.2.03 roster must be exactly the 5.2.03 method plus class aliases");
    }

    @Test
    void everyAliasStaysInsideTheReviewedProtectedExportNamespace() {
        final List<String> outside = new ArrayList<>();
        for (String alias : declaredInDeclarationOrder()) {
            if (!alias.startsWith(REVIEWED_NAMESPACE)) {
                outside.add(alias);
            }
        }

        assertTrue(outside.isEmpty(), "aliases outside " + REVIEWED_NAMESPACE + ": " + outside);
    }

    @Test
    void noTwoConstantsDeclareTheSameAliasLiteral() {
        final Set<String> seen = new HashSet<>();
        final Set<String> duplicated = new LinkedHashSet<>();
        for (String alias : declaredInDeclarationOrder()) {
            if (!seen.add(alias)) {
                duplicated.add(alias);
            }
        }

        assertTrue(duplicated.isEmpty(), "aliases declared by more than one constant: " + duplicated);
    }

    private static Set<String> union(final Set<String> first, final Set<String> second) {
        final Set<String> union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

    private static Set<String> declaredAliases() {
        return new HashSet<>(declaredInDeclarationOrder());
    }

    /** The alias literals the vocabulary declares, in declaration order. */
    private static List<String> declaredInDeclarationOrder() {
        final List<String> aliases = new ArrayList<>();
        for (Field field : ProtectedExportHostVocabulary.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                aliases.add((String) field.get(null));
            } catch (IllegalAccessException failure) {
                throw new AssertionError("the vocabulary constant is not readable: " + field.getName(), failure);
            }
        }
        return aliases;
    }
}
