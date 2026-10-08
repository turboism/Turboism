package dev.turboism.plugin.acp;

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

/**
 * Opens an agent-owned interactive command (typically its login flow) in a visible platform
 * terminal. The agent process — not this plugin — owns any credentials entered there.
 */
final class AgentTerminalLauncher {

    private static final String ENV_ARGV = "TURBOISM_ACP_ARGV";
    private static final String ENV_OVERRIDES = "TURBOISM_ACP_ENV";
    private static final String ENV_WORKING_DIRECTORY = "TURBOISM_ACP_WORKING_DIRECTORY";
    private static final String ENV_CHILD_SCRIPT = "TURBOISM_ACP_CHILD_SCRIPT";

    private static final String WINDOWS_CHILD_SCRIPT = """
        $ErrorActionPreference = 'Stop'
        Set-Location -LiteralPath $env:TURBOISM_ACP_WORKING_DIRECTORY
        $argv = @([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:TURBOISM_ACP_ARGV)) -split "`n")
        foreach ($pair in ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($env:TURBOISM_ACP_ENV)) -split "`n")) {
          $separator = $pair.IndexOf('=')
          if ($separator -gt 0) {
            Set-Item -Path "Env:$($pair.Substring(0, $separator))" -Value $pair.Substring($separator + 1)
          }
        }
        & $argv[0] @($argv | Select-Object -Skip 1)
        Write-Host ''
        Write-Host 'The agent command finished. This shell remains open.'
        """;

    private static final String WINDOWS_PARENT_SCRIPT = """
        $arguments = @(
          '-NoLogo',
          '-NoProfile',
          '-NoExit',
          '-EncodedCommand',
          $env:TURBOISM_ACP_CHILD_SCRIPT
        )
        Start-Process -FilePath 'powershell.exe' `
          -ArgumentList $arguments `
          -WorkingDirectory $env:TURBOISM_ACP_WORKING_DIRECTORY
        """;

    private AgentTerminalLauncher() {}

    /**
     * Opens {@code command} in a visible terminal inside {@code workingDirectory}, applying
     * {@code environment} overrides on top of the inherited process environment.
     */
    static void open(
            final List<String> command,
            final Map<String, String> environment,
            final Path workingDirectory)
            throws IOException {
        final List<String> argv = checkedCommand(command);
        final Map<String, String> overrides = checkedEnvironment(environment);
        final Path cwd = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        final LaunchPlan plan = plan(
                System.getProperty("os.name", ""),
                argv,
                overrides,
                cwd,
                System.getenv());
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
            final List<String> command,
            final Map<String, String> environment,
            final Path workingDirectory,
            final Map<String, String> inheritedEnvironment)
            throws IOException {
        final String os = Objects.requireNonNullElse(osName, "").strip().toLowerCase(Locale.ROOT);
        final List<String> argv = checkedCommand(command);
        final Map<String, String> overrides = checkedEnvironment(environment);
        final Path cwd = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        final Map<String, String> launchEnvironment = new LinkedHashMap<>();
        if (os.startsWith("windows")) {
            launchEnvironment.put(ENV_ARGV, base64(joinLines(argv)));
            launchEnvironment.put(ENV_OVERRIDES, base64(joinLines(envPairs(overrides))));
            launchEnvironment.put(ENV_WORKING_DIRECTORY, cwd.toString());
            launchEnvironment.put(ENV_CHILD_SCRIPT, encodedPowerShell(WINDOWS_CHILD_SCRIPT));
            return new LaunchPlan(
                    List.of(
                            "powershell.exe",
                            "-NoLogo",
                            "-NoProfile",
                            "-NonInteractive",
                            "-WindowStyle",
                            "Hidden",
                            "-EncodedCommand",
                            encodedPowerShell(WINDOWS_PARENT_SCRIPT)),
                    launchEnvironment);
        }
        if (os.equals("mac os x") || os.equals("macos") || os.equals("darwin")) {
            return new LaunchPlan(macCommand(argv, overrides, cwd), launchEnvironment);
        }
        if (os.equals("linux") || os.startsWith("linux ")) {
            final String terminal = findLinuxTerminal(inheritedEnvironment);
            return new LaunchPlan(linuxCommand(terminal, argv, overrides, cwd), launchEnvironment);
        }
        throw new IOException("no supported terminal launcher is available for this platform");
    }

    static List<String> linuxCommand(
            final String terminal,
            final List<String> command,
            final Map<String, String> environment,
            final Path workingDirectory) {
        final String shellCommand = retainedShellCommand(command, environment, workingDirectory);
        final String name = Path.of(terminal).getFileName().toString();
        if ("gnome-terminal".equals(name)) {
            return List.of(terminal, "--", "/bin/sh", "-lc", shellCommand);
        }
        if ("xfce4-terminal".equals(name)) {
            return List.of(terminal, "--disable-server", "-x", "/bin/sh", "-lc", shellCommand);
        }
        return List.of(terminal, "-e", "/bin/sh", "-lc", shellCommand);
    }

    private static List<String> macCommand(
            final List<String> command, final Map<String, String> environment, final Path workingDirectory) {
        final String script = """
            on run argv
              set workdir to quoted form of item 1 of argv
              set commandText to "cd -- " & workdir & " && env"
              repeat with index from 2 to count of argv
                set commandText to commandText & " " & quoted form of item index of argv
              end repeat
              set commandText to commandText & "; result=$?; printf '\\nagent command exited with status %s.\\n' $result; exec ${SHELL:-/bin/sh} -l"
              tell application "Terminal"
                activate
                do script commandText
              end tell
            end run
            """;
        final ArrayList<String> argv = new ArrayList<>(List.of(
                "/usr/bin/osascript",
                "-e",
                script,
                "--",
                workingDirectory.toAbsolutePath().normalize().toString()));
        argv.addAll(envPairs(environment));
        argv.addAll(command);
        return List.copyOf(argv);
    }

    private static String retainedShellCommand(
            final List<String> command, final Map<String, String> environment, final Path workingDirectory) {
        final StringBuilder shellCommand = new StringBuilder("cd -- ")
                .append(shellQuote(workingDirectory.toAbsolutePath().normalize().toString()))
                .append(" && env");
        for (String pair : envPairs(environment)) {
            shellCommand.append(' ').append(shellQuote(pair));
        }
        for (String element : command) {
            shellCommand.append(' ').append(shellQuote(element));
        }
        return shellCommand
                .append("; result=$?; printf '\\nagent command exited with status %s.\\n' \"$result\"; "
                        + "exec \"${SHELL:-/bin/sh}\" -l")
                .toString();
    }

    private static List<String> envPairs(final Map<String, String> environment) {
        final ArrayList<String> pairs = new ArrayList<>();
        environment.forEach((name, value) -> pairs.add(name + "=" + value));
        return List.copyOf(pairs);
    }

    private static String findLinuxTerminal(final Map<String, String> inheritedEnvironment) throws IOException {
        final String path =
                Objects.requireNonNullElse(inheritedEnvironment == null ? null : inheritedEnvironment.get("PATH"), "");
        for (String candidate :
                List.of("x-terminal-emulator", "gnome-terminal", "konsole", "xfce4-terminal", "xterm")) {
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

    private static List<String> checkedCommand(final List<String> command) {
        final List<String> argv = List.copyOf(Objects.requireNonNull(command, "command"));
        if (argv.isEmpty() || argv.size() > AgentLaunchSpec.MAX_ARGUMENTS) {
            throw new IllegalArgumentException("agent command is invalid");
        }
        for (String element : argv) {
            final String argument = Objects.requireNonNull(element, "command element");
            if (argument.isBlank()
                    || argument.length() > AgentLaunchSpec.MAX_ARGUMENT_CHARS
                    || argument.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("agent command element is invalid");
            }
        }
        return argv;
    }

    private static Map<String, String> checkedEnvironment(final Map<String, String> environment) {
        final Map<String, String> overrides = Map.copyOf(Objects.requireNonNullElse(environment, Map.of()));
        overrides.forEach((name, value) -> {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("process environment is invalid");
            }
        });
        return overrides;
    }

    private static String joinLines(final List<String> values) {
        return String.join("\n", values);
    }

    private static String base64(final String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodedPowerShell(final String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    record LaunchPlan(List<String> command, Map<String, String> environment) {
        LaunchPlan {
            command = List.copyOf(command);
            environment = Map.copyOf(environment);
            if (command.isEmpty()) throw new IllegalArgumentException("command is empty");
        }
    }
}
