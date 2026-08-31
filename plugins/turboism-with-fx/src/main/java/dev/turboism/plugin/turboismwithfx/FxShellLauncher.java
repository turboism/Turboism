package dev.turboism.plugin.turboismwithfx;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Opens an fx-owned interactive command in a visible platform terminal. */
final class FxShellLauncher {

    private static final String ENV_EXECUTABLE = "TURBOISM_FX_EXECUTABLE";
    private static final String ENV_WORKING_DIRECTORY = "TURBOISM_FX_WORKING_DIRECTORY";
    private static final String ENV_ACTION = "TURBOISM_FX_INTERACTIVE_ACTION";
    private static final String ENV_CHILD_SCRIPT = "TURBOISM_FX_CHILD_SCRIPT";

    private static final String WINDOWS_CHILD_SCRIPT = """
        $ErrorActionPreference = 'Stop'
        Set-Location -LiteralPath $env:TURBOISM_FX_WORKING_DIRECTORY
        $fx = $env:TURBOISM_FX_EXECUTABLE
        switch ($env:TURBOISM_FX_INTERACTIVE_ACTION) {
          'SHELL' { & $fx }
          'LOGIN_VERCEL' { & $fx login vercel }
          'LOGIN_CODEX' { & $fx login codex }
          'LOGIN_GROK' { & $fx login grok }
          'SETUP_GATEWAY_KEY' { & $fx setup }
          'LOGOUT_VERCEL' { & $fx logout vercel }
          'LOGOUT_CODEX' { & $fx logout codex }
          'LOGOUT_GROK' { & $fx logout grok }
          default { throw 'Unsupported fx interactive action' }
        }
        Write-Host ''
        Write-Host 'The fx command finished. This shell remains open.'
        """;

    private static final String WINDOWS_PARENT_SCRIPT = """
        $arguments = @(
          '-NoLogo',
          '-NoProfile',
          '-NoExit',
          '-EncodedCommand',
          $env:TURBOISM_FX_CHILD_SCRIPT
        )
        Start-Process -FilePath 'powershell.exe' `
          -ArgumentList $arguments `
          -WorkingDirectory $env:TURBOISM_FX_WORKING_DIRECTORY
        """;

    private FxShellLauncher() {
    }

    static void open(
        final FxRuntimeResolver.Resolution.Available runtime,
        final Path workingDirectory,
        final FxInteractiveAction action
    ) throws IOException {
        final FxRuntimeResolver.Resolution.Available available = Objects.requireNonNull(
            runtime, "runtime"
        );
        final Path cwd = Objects.requireNonNull(workingDirectory, "workingDirectory")
            .toAbsolutePath().normalize();
        final FxLaunchConfiguration verification = new FxLaunchConfiguration(
            available.executable(),
            cwd,
            FxSecurityMode.FX_NATIVE_TOOLS,
            available.managedRuntime()
        );
        FxProcessTransport.verifyManagedRuntimeForLaunch(verification);
        final LaunchPlan plan = plan(
            System.getProperty("os.name", ""),
            available.executable(),
            cwd,
            Objects.requireNonNull(action, "action"),
            System.getenv()
        );
        final ProcessBuilder builder = new ProcessBuilder(plan.command());
        builder.directory(cwd.toFile());
        builder.environment().putAll(plan.environment());
        builder.redirectInput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.start();
    }

    static LaunchPlan plan(
        final String osName,
        final String executable,
        final Path workingDirectory,
        final FxInteractiveAction action,
        final Map<String, String> inheritedEnvironment
    ) throws IOException {
        final String os = Objects.requireNonNullElse(osName, "")
            .strip().toLowerCase(Locale.ROOT);
        final String fx = requireText(executable, "executable");
        final Path cwd = Objects.requireNonNull(workingDirectory, "workingDirectory")
            .toAbsolutePath().normalize();
        final FxInteractiveAction selected = Objects.requireNonNull(action, "action");
        final Map<String, String> environment = new LinkedHashMap<>();
        if (os.startsWith("windows")) {
            environment.put(ENV_EXECUTABLE, fx);
            environment.put(ENV_WORKING_DIRECTORY, cwd.toString());
            environment.put(ENV_ACTION, selected.name());
            environment.put(ENV_CHILD_SCRIPT, encodedPowerShell(WINDOWS_CHILD_SCRIPT));
            return new LaunchPlan(List.of(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-EncodedCommand",
                encodedPowerShell(WINDOWS_PARENT_SCRIPT)
            ), environment);
        }
        if (os.equals("mac os x") || os.equals("macos") || os.equals("darwin")) {
            return new LaunchPlan(macCommand(fx, cwd, selected), environment);
        }
        if (os.equals("linux") || os.startsWith("linux ")) {
            final String terminal = findLinuxTerminal(inheritedEnvironment);
            return new LaunchPlan(linuxCommand(terminal, fx, cwd, selected), environment);
        }
        throw new IOException("no supported terminal launcher is available for this platform");
    }

    static List<String> linuxCommand(
        final String terminal,
        final String executable,
        final Path workingDirectory,
        final FxInteractiveAction action
    ) {
        final String command = retainedShellCommand(executable, workingDirectory, action);
        final String name = Path.of(terminal).getFileName().toString();
        if ("gnome-terminal".equals(name)) {
            return List.of(terminal, "--", "/bin/sh", "-lc", command);
        }
        if ("xfce4-terminal".equals(name)) {
            return List.of(terminal, "--disable-server", "-x", "/bin/sh", "-lc", command);
        }
        return List.of(terminal, "-e", "/bin/sh", "-lc", command);
    }

    private static List<String> macCommand(
        final String executable,
        final Path workingDirectory,
        final FxInteractiveAction action
    ) {
        final String script = """
            on run argv
              set workdir to quoted form of item 1 of argv
              set executablePath to quoted form of item 2 of argv
              set commandText to "cd -- " & workdir & " && " & executablePath
              repeat with index from 3 to count of argv
                set commandText to commandText & " " & quoted form of item index of argv
              end repeat
              set commandText to commandText & "; result=$?; printf '\\nfx command exited with status %s.\\n' $result; exec ${SHELL:-/bin/sh} -l"
              tell application "Terminal"
                activate
                do script commandText
              end tell
            end run
            """;
        final ArrayList<String> command = new ArrayList<>(List.of(
            "/usr/bin/osascript", "-e", script, "--",
            workingDirectory.toAbsolutePath().normalize().toString(), executable
        ));
        command.addAll(action.arguments());
        return List.copyOf(command);
    }

    private static String retainedShellCommand(
        final String executable,
        final Path workingDirectory,
        final FxInteractiveAction action
    ) {
        final StringBuilder command = new StringBuilder("cd -- ")
            .append(shellQuote(workingDirectory.toAbsolutePath().normalize().toString()))
            .append(" && ")
            .append(shellQuote(executable));
        for (String argument : action.arguments()) {
            command.append(' ').append(shellQuote(argument));
        }
        return command.append(
            "; result=$?; printf '\\nfx command exited with status %s.\\n' \"$result\"; "
                + "exec \"${SHELL:-/bin/sh}\" -l"
        ).toString();
    }

    private static String findLinuxTerminal(final Map<String, String> inheritedEnvironment)
        throws IOException {
        final String path = Objects.requireNonNullElse(
            inheritedEnvironment == null ? null : inheritedEnvironment.get("PATH"), ""
        );
        for (String candidate : List.of(
            "x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "xterm"
        )) {
            for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) continue;
                final Path executable = Path.of(directory).resolve(candidate);
                if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                    return executable.toString();
                }
            }
        }
        throw new IOException("no supported graphical terminal was found");
    }

    private static String encodedPowerShell(final String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name);
        if (text.isBlank() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }

    record LaunchPlan(List<String> command, Map<String, String> environment) {
        LaunchPlan {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            if (command.isEmpty()) throw new IllegalArgumentException("command is empty");
        }
    }
}
