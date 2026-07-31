package dev.turboism.adapter.cubism.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StartupSuppressionInstallerTest {

    @TempDir
    Path temporaryHome;

    @Test
    void missingConfigDoesNotInstallAnyTransformer() {
        final List<String> calls = new ArrayList<>();

        final StartupSuppressionInstaller.Installation installation =
            StartupSuppressionInstaller.install(
                StartupSuppressionInstaller.AttachmentMode.PREMAIN,
                instrumentation(calls),
                temporaryHome,
                "",
                temporaryHome,
                ignored -> { }
            );

        assertEquals(StartupSuppressionInstaller.Status.NOT_REQUESTED, installation.status());
        assertEquals(List.of(), calls);
    }

    @Test
    void lateAgentmainRefusesRequestedStartupSuppressionBeforeArtifactAdmission() throws Exception {
        writeEnabledConfig();
        final List<String> calls = new ArrayList<>();

        final StartupSuppressionInstaller.Installation installation =
            StartupSuppressionInstaller.install(
                StartupSuppressionInstaller.AttachmentMode.AGENTMAIN,
                instrumentation(calls),
                temporaryHome,
                "not-a-real-classpath",
                temporaryHome,
                ignored -> { }
            );

        assertEquals(StartupSuppressionInstaller.Status.AGENTMAIN_REFUSED, installation.status());
        assertEquals(List.of(), calls);
    }

    private void writeEnabledConfig() throws Exception {
        Files.writeString(temporaryHome.resolve("config.json"), """
            {
              "format": "turboism.runtime.config",
              "schemaVersion": 1,
              "worktreeId": "startup-test",
              "hooks": {
                "startup": {
                  "skipUpdateCheck": true
                }
              }
            }
            """);
    }

    private Instrumentation instrumentation(final List<String> calls) {
        return (Instrumentation) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{Instrumentation.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("addTransformer")) {
                    calls.add("add:" + arguments[1]);
                    return null;
                }
                if (method.getName().equals("removeTransformer")) {
                    calls.add("remove");
                    return true;
                }
                if (method.getName().equals("getAllLoadedClasses")) {
                    return new Class<?>[0];
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
