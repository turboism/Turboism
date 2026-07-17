package dev.turboism.permissions;

import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.permission.CubismPermissionException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismPermissionGateTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void deniedCapabilityAwareOperationAuditsPermissionOperationAndCapabilitySeparately() {
        final List<CubismFacadeAuditEvent> events = new ArrayList<>();
        final CubismPermissionGate gate = new CubismPermissionGate("plugin.test", List.of(), events::add, CLOCK);

        assertThrows(CubismPermissionException.class, () -> gate.require(
            "turboism.cubism.model.read",
            "cubismRead.clipMasks",
            "cubism.clipmask.read"
        ));

        final CubismFacadeAuditEvent event = events.get(0);
        assertEquals("turboism.cubism.model.read", event.permissionId());
        assertEquals("cubismRead.clipMasks", event.operationId());
        assertEquals("cubism.clipmask.read", event.capabilityId());
        assertEquals("cubismRead.clipMasks", event.methodName());
    }

    @Test
    void legacyTwoArgumentEntryNeverInventsCapability() {
        final List<CubismFacadeAuditEvent> events = new ArrayList<>();
        final CubismPermissionGate gate = new CubismPermissionGate("plugin.test", List.of(), events::add, CLOCK);

        assertThrows(CubismPermissionException.class, () ->
            gate.require("turboism.action.register", "action.register")
        );

        assertEquals("action.register", events.get(0).operationId());
        assertNull(events.get(0).capabilityId());
    }

    @Test
    void legacyAuditConstructorRetainsNullCapabilityAndMethodNameBridge() {
        final CubismFacadeAuditEvent event = new CubismFacadeAuditEvent(
            "plugin.test",
            "turboism.action.register",
            "action.register",
            DiagnosticReport.Severity.WARNING,
            CLOCK.instant()
        );

        assertEquals("action.register", event.operationId());
        assertEquals("action.register", event.methodName());
        assertNull(event.capabilityId());
    }
}
