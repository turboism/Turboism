package dev.turboism.adapter.cubism.command;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorGridSettingsRequest;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.command.EditorModelingStatisticsRequest;
import dev.turboism.sdk.cubism.command.EditorExternalAppSettingsRequest;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RuntimeEditorCommandServiceTest {
    @Test
    void deniesBeforeInvokingTheHostAdapter() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeEditorCommandService service = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of())
        );

        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, service.execute(EditorCommand.NEXT_FRAME).status());
        assertEquals(0, calls.get());
    }

    @Test
    void separatesNavigationFromAuthoringAndFileWrites() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeEditorCommandService readOnly = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );

        assertEquals(
            Set.of(EditorCommand.NEXT_FRAME, EditorCommand.SHOW_FULL_WORKSPACE, EditorCommand.SHOW_PARAMETER_PALETTE, EditorCommand.OPEN_ABOUT),
            readOnly.available()
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, readOnly.execute(EditorCommand.NEXT_FRAME).status());
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, readOnly.execute(EditorCommand.DELETE).status());
        assertEquals(1, calls.get());

        RuntimeEditorCommandService modelWriter = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_WRITE_PERMISSION)
            ))
        );
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, modelWriter.execute(EditorCommand.SAVE).status());

        RuntimeEditorCommandService fileWriter = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_WRITE_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_WRITE)
            ))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, fileWriter.execute(EditorCommand.SAVE).status());
    }

    @Test
    void gatesClosedExternalAndRuntimeResourcesWithoutExposingUrisOrPaths() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeEditorCommandService readOnly = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );

        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, readOnly.execute(EditorCommand.OPEN_MANUAL_PAGE).status());
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, readOnly.execute(EditorCommand.OPEN_LOG_FILE).status());
        assertEquals(EditorCommandResult.Status.EXECUTED, readOnly.execute(EditorCommand.OPEN_ABOUT).status());

        RuntimeEditorCommandService admitted = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_NETWORK),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_PROCESS)
            ))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, admitted.execute(EditorCommand.OPEN_MANUAL_PAGE).status());
        assertEquals(EditorCommandResult.Status.EXECUTED, admitted.execute(EditorCommand.OPEN_LOG_FILE).status());
    }

    @Test
    void resizeModelRequiresModelWriteAndSanitizesAdapterFailures() {
        AtomicInteger calls = new AtomicInteger();
        EditorResizeModelRequest request = new EditorResizeModelRequest(100);
        RuntimeEditorCommandService denied = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, denied.execute(request).status());
        assertEquals(0, calls.get());

        RuntimeEditorCommandService allowed = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_WRITE_PERMISSION)))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, allowed.execute(request).status());
        assertEquals(1, calls.get());
    }

    @Test
    void separatesUiGridSettingsFromAuthoringCanvasSettings() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeEditorCommandService readOnly = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, readOnly.execute(
            new EditorGridSettingsRequest(50, new Color(0.5f, 0.5f, 0.5f, 1.0f))
        ).status());
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED,
            readOnly.execute(new EditorCanvasSettingsRequest(1000, 1000)).status());
        assertEquals(1, calls.get());
    }

    @Test
    void treatsModelingStatisticsAsUiConfiguration() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeEditorCommandService service = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED,
            service.execute(new EditorModelingStatisticsRequest(true)).status());
        assertEquals(1, calls.get());
    }

    @Test
    void externalAppSettingsRequireNetworkAndProcessPermissions() {
        AtomicInteger calls = new AtomicInteger();
        EditorExternalAppSettingsRequest request = new EditorExternalAppSettingsRequest(22033, false);
        RuntimeEditorCommandService networkOnly = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_NETWORK)))
        );
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, networkOnly.execute(request).status());
        assertEquals(0, calls.get());

        RuntimeEditorCommandService admitted = new RuntimeEditorCommandService(
            adapter(calls), gate(List.of(
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_NETWORK),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_PROCESS)
            ))
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, admitted.execute(request).status());
        assertEquals(1, calls.get());
    }

    @Test
    void fileRequestsRequireModelAndMatchingFilePermission() {
        AtomicInteger calls = new AtomicInteger();
        EditorFileCommandRequest open = new EditorFileCommandRequest(
            EditorFileCommand.OPEN,
            handle(UserFileMode.READ),
            EditorOverwritePolicy.REJECT_EXISTING
        );
        RuntimeEditorCommandService denied = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION)))
        );
        assertEquals(EditorCommandResult.Status.PERMISSION_DENIED, denied.execute(open).status());
        assertEquals(0, calls.get());

        RuntimeEditorCommandService allowed = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_READ)
            )),
            request -> new ResolvedEditorFileCommand(
                request.command(), java.nio.file.Path.of("fixture.cmo3"), request.overwritePolicy()
            )
        );
        assertEquals(EditorCommandResult.Status.EXECUTED, allowed.execute(open).status());
        assertEquals(1, calls.get());

        RuntimeEditorCommandService rejecting = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_READ)
            )),
            request -> { throw new IllegalStateException("private path detail"); }
        );
        assertEquals(EditorCommandResult.Status.REJECTED, rejecting.execute(open).status());
    }

    @Test
    void inactiveServiceFailsClosedWithoutTouchingAdapterOrResolver() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger resolverCalls = new AtomicInteger();
        RuntimeEditorCommandService inactive = new RuntimeEditorCommandService(
            adapter(calls),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_READ)
            )),
            request -> { resolverCalls.incrementAndGet(); return null; },
            () -> false
        );

        assertEquals(Set.of(), inactive.available());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, inactive.execute(EditorCommand.NEXT_FRAME).status());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, inactive.execute(new EditorFileCommandRequest(
            EditorFileCommand.OPEN, handle(UserFileMode.READ), EditorOverwritePolicy.REJECT_EXISTING
        )).status());
        assertEquals(EditorCommandResult.Status.UNAVAILABLE, inactive.execute(new EditorResizeModelRequest(100)).status());
        assertEquals(0, calls.get());
        assertEquals(0, resolverCalls.get());
    }

    @Test
    void sanitizesAdapterAndResolverFailuresToClosedResults() {
        EditorCommandAdapter failing = new EditorCommandAdapter() {
            @Override
            public Set<EditorCommand> available() {
                throw new IllegalStateException("private host menu detail");
            }

            @Override
            public EditorCommandResult execute(final EditorCommand command) {
                throw new IllegalStateException("private host execute detail");
            }

            @Override
            public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
                throw new IllegalStateException("private host file detail");
            }

            @Override
            public EditorCommandResult execute(final EditorParameterizedRequest command) {
                throw new IllegalStateException("private host parameterized detail");
            }
        };
        RuntimeEditorCommandService service = new RuntimeEditorCommandService(
            failing,
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_WRITE_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_READ),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_WRITE)
            )),
            request -> new ResolvedEditorFileCommand(
                request.command(), java.nio.file.Path.of("fixture.cmo3"), request.overwritePolicy()
            )
        );

        assertEquals(Set.of(), service.available());
        EditorCommandResult direct = service.execute(EditorCommand.NEXT_FRAME);
        assertEquals(EditorCommandResult.Status.FAILED, direct.status());
        assertEquals("next.frame", direct.commandId());
        assertFalse(direct.toString().contains("private"));
        EditorCommandResult file = service.execute(new EditorFileCommandRequest(
            EditorFileCommand.SAVE_AS,
            handle(UserFileMode.WRITE),
            EditorOverwritePolicy.REPLACE_EXISTING
        ));
        assertEquals(EditorCommandResult.Status.FAILED, file.status());
        assertFalse(file.toString().contains("private"));
        assertEquals(
            EditorCommandResult.Status.FAILED,
            service.execute(new EditorResizeModelRequest(100)).status()
        );

        RuntimeEditorCommandService rejecting = new RuntimeEditorCommandService(
            adapter(new AtomicInteger()),
            gate(List.of(
                permission(dev.turboism.adapter.cubism.CubismFacadeImpl.MODEL_READ_PERMISSION),
                permission(dev.turboism.sdk.permission.PermissionIds.TURBOISM_FILE_READ)
            )),
            request -> null
        );
        assertEquals(EditorCommandResult.Status.REJECTED, rejecting.execute(new EditorFileCommandRequest(
            EditorFileCommand.OPEN, handle(UserFileMode.READ), EditorOverwritePolicy.REJECT_EXISTING
        )).status());
    }

    private static EditorCommandAdapter adapter(final AtomicInteger calls) {
        return new EditorCommandAdapter() {
            @Override
            public Set<EditorCommand> available() {
                return Set.of(
                    EditorCommand.NEXT_FRAME, EditorCommand.DELETE, EditorCommand.SAVE,
                    EditorCommand.SHOW_FULL_WORKSPACE, EditorCommand.SHOW_PARAMETER_PALETTE,
                    EditorCommand.OPEN_ABOUT, EditorCommand.OPEN_MANUAL_PAGE, EditorCommand.OPEN_LOG_FILE
                );
            }

            @Override
            public EditorCommandResult execute(final EditorCommand command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.id());
            }

            @Override
            public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId());
            }

            @Override
            public EditorCommandResult execute(final EditorParameterizedRequest command) {
                calls.incrementAndGet();
                return new EditorCommandResult(EditorCommandResult.Status.EXECUTED, command.commandId());
            }
        };
    }

    private static CubismPermissionGate gate(final List<PluginPermission> permissions) {
        return new CubismPermissionGate("test.plugin", permissions, ignored -> { }, Clock.systemUTC());
    }

    private static PluginPermission permission(final String id) {
        return new PluginPermission() {
            @Override public String id() { return id; }
            @Override public String scope() { return "plugin"; }
            @Override public String reason() { return "test"; }
        };
    }

    private static UserFileHandle handle(final UserFileMode mode) {
        return new UserFileHandle() {
            @Override public String id() { return "grant"; }
            @Override public String displayName() { return "fixture.cmo3"; }
            @Override public UserFileMode mode() { return mode; }
            @Override public UserFileLifetime lifetime() { return UserFileLifetime.UNTIL_DISABLE; }
            @Override public UserFileHandleState state() { return UserFileHandleState.ACTIVE; }
            @Override public void revoke() { }
            @Override public void close() { }
        };
    }
}
