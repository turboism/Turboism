package dev.turboism.plugin.turboismwithfx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxShellLauncherTest {

    @TempDir
    Path directory;

    @Test
    void windowsLaunchUsesEncodedScriptsAndEnvironmentOwnedValues() throws Exception {
        final Path fx = directory.resolve("fx runtime/fx.exe").toAbsolutePath().normalize();
        final Path cwd = directory.resolve("workspace with spaces").toAbsolutePath().normalize();

        final FxShellLauncher.LaunchPlan plan = FxShellLauncher.plan(
            "Windows 11",
            fx.toString(),
            cwd,
            FxInteractiveAction.LOGIN_CODEX,
            Map.of()
        );

        assertEquals("powershell.exe", plan.command().get(0));
        assertTrue(plan.command().contains("-EncodedCommand"));
        assertFalse(plan.command().contains(fx.toString()));
        assertFalse(plan.command().contains(cwd.toString()));
        assertEquals(fx.toString(), plan.environment().get("TURBOISM_FX_EXECUTABLE"));
        assertEquals(cwd.toString(), plan.environment().get("TURBOISM_FX_WORKING_DIRECTORY"));
        assertEquals("LOGIN_CODEX", plan.environment().get("TURBOISM_FX_INTERACTIVE_ACTION"));
        final String child = new String(Base64.getDecoder().decode(
            plan.environment().get("TURBOISM_FX_CHILD_SCRIPT")
        ), StandardCharsets.UTF_16LE);
        assertTrue(child.contains("& $fx login codex"));
        assertFalse(child.contains(fx.toString()));
    }

    @Test
    void linuxLaunchQuotesExecutableAndWorkspaceWithoutJoiningUntrustedInput() {
        final List<String> command = FxShellLauncher.linuxCommand(
            "/usr/bin/gnome-terminal",
            "/opt/fx runtime/fx",
            Path.of("/tmp/work space's copy"),
            FxInteractiveAction.LOGIN_GROK
        );

        assertEquals(List.of(
            "/usr/bin/gnome-terminal", "--", "/bin/sh", "-lc"
        ), command.subList(0, 4));
        assertTrue(command.get(4).contains("'/opt/fx runtime/fx' 'login' 'grok'"));
        assertTrue(command.get(4).contains("'/tmp/work space'\"'\"'s copy'"));
        assertTrue(command.get(4).contains("exec \"${SHELL:-/bin/sh}\" -l"));
    }

    @Test
    void linuxPlanSelectsAnInstalledGraphicalTerminal() throws Exception {
        final Path bin = Files.createDirectories(directory.resolve("bin"));
        final Path terminal = Files.writeString(bin.resolve("x-terminal-emulator"), "fixture");
        terminal.toFile().setExecutable(true);

        final FxShellLauncher.LaunchPlan plan = FxShellLauncher.plan(
            "Linux",
            "/opt/fx",
            directory,
            FxInteractiveAction.SHELL,
            Map.of("PATH", bin.toString())
        );

        assertEquals(terminal.toString(), plan.command().get(0));
        assertEquals("-e", plan.command().get(1));
        assertTrue(plan.command().get(4).contains("'/opt/fx'"));
    }

    @Test
    void macLaunchPassesWorkspaceExecutableAndArgumentsAsSeparateValues() throws Exception {
        final FxShellLauncher.LaunchPlan plan = FxShellLauncher.plan(
            "Mac OS X",
            "/Applications/fx runtime/fx",
            Path.of("/tmp/work space"),
            FxInteractiveAction.SETUP_GATEWAY_KEY,
            Map.of()
        );

        assertEquals("/usr/bin/osascript", plan.command().get(0));
        assertEquals("--", plan.command().get(3));
        assertEquals("/tmp/work space", plan.command().get(4));
        assertEquals("/Applications/fx runtime/fx", plan.command().get(5));
        assertEquals("setup", plan.command().get(6));
    }

    @Test
    void interactiveActionsMatchTheExactFxV005CommandSurface() {
        assertEquals(List.of(), FxInteractiveAction.SHELL.arguments());
        assertEquals(List.of("login", "vercel"), FxInteractiveAction.LOGIN_VERCEL.arguments());
        assertEquals(List.of("login", "codex"), FxInteractiveAction.LOGIN_CODEX.arguments());
        assertEquals(List.of("login", "grok"), FxInteractiveAction.LOGIN_GROK.arguments());
        assertEquals(List.of("setup"), FxInteractiveAction.SETUP_GATEWAY_KEY.arguments());
        assertEquals(List.of("logout", "vercel"), FxInteractiveAction.LOGOUT_VERCEL.arguments());
        assertEquals(List.of("logout", "codex"), FxInteractiveAction.LOGOUT_CODEX.arguments());
        assertEquals(List.of("logout", "grok"), FxInteractiveAction.LOGOUT_GROK.arguments());
    }
}
