package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.lifecycle.CapabilityAdmission;
import dev.turboism.core.lifecycle.CapabilityRule;
import dev.turboism.sdk.permission.PermissionIds;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Keeps the capability admission table inside the one capability vocabulary the preview reports
 * know about: a row that names an unregistered capability would make an admitted surface invisible
 * in the capability report, and a row that names an unknown permission id would never be granted.
 */
class CapabilityVocabularyTest {

    @Test
    void everyAdmissionCapabilityIsACanonicalPreviewCapability() {
        final Set<String> canonical = KernelReportSnapshotFactory.canonicalCapabilityIds();
        final Set<String> unregistered = new HashSet<>();
        for (CapabilityRule rule : CapabilityAdmission.rules()) {
            for (String capability : rule.anyOfCapabilities()) {
                if (!canonical.contains(capability)) {
                    unregistered.add(rule.hookId() + " -> " + capability);
                }
            }
        }

        assertTrue(
                unregistered.isEmpty(),
                "capability admission ids must be registered in KernelReportSnapshotFactory: " + unregistered);
    }

    @Test
    void everyAdmissionPermissionIsADeclaredPermissionId() {
        final Set<String> declared = declaredPermissionIds();
        final Set<String> unknown = new HashSet<>();
        for (CapabilityRule rule : CapabilityAdmission.rules()) {
            for (String permission : rule.allOfPermissions()) {
                if (!declared.contains(permission)) {
                    unknown.add(rule.hookId() + " -> " + permission);
                }
            }
        }

        assertTrue(unknown.isEmpty(), "capability admission permissions must be PermissionIds constants: " + unknown);
    }

    @Test
    void everyAdmissionRowRequiresAtLeastOneCapabilityAndPermission() {
        for (CapabilityRule rule : CapabilityAdmission.rules()) {
            assertFalse(rule.hookId().isBlank(), "a row must name its hook");
            assertFalse(rule.anyOfCapabilities().isEmpty(), rule.hookId() + " must declare at least one capability");
            assertFalse(rule.allOfPermissions().isEmpty(), rule.hookId() + " must require at least one permission");
        }
    }

    private static Set<String> declaredPermissionIds() {
        final Set<String> ids = new HashSet<>();
        for (Field field : PermissionIds.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                ids.add((String) field.get(null));
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("PermissionIds constant is unreadable: " + field.getName(), failure);
            }
        }
        return ids;
    }
}
