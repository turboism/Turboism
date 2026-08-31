# -*- coding: utf-8 -*-
[CmdletBinding()]
param(
    [Alias("Home")]
    [Parameter(Mandatory = $true)]
    [string]$HomePath,
    [string]$Java = "",
    [switch]$Gui
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir "cubism-launch-common.ps1")

function Get-InstallerLanguage {
    $language = [System.Threading.Thread]::CurrentThread.CurrentUICulture.TwoLetterISOLanguageName
    if (@("en", "zh", "ja") -notcontains $language) { return "en" }
    return $language
}

$strings = @{
    en = @{
        Title = "Turboism - GraalVM installation"; Preparing = "Preparing the GraalVM download..."
        Downloading = "Downloading GraalVM"; Extracting = "Extracting the managed runtime..."
        Verifying = "Verifying GraalVM and the isolated host..."; Ready = "GraalVM is ready."
        Cancel = "Cancel download"; Cancelling = "Cancelling download..."; Cancelled = "GraalVM installation was cancelled."
        Progress = "{0} / {1}   {2}/s"; Error = "Managed GraalVM installation failed. See the installer log: {0}"
    }
    zh = @{
        Title = "Turboism - GraalVM 安装"; Preparing = "正在准备 GraalVM 下载..."
        Downloading = "正在下载 GraalVM"; Extracting = "正在解压托管运行时..."
        Verifying = "正在验证 GraalVM 与隔离宿主..."; Ready = "GraalVM 已就绪。"
        Cancel = "取消下载"; Cancelling = "正在取消下载..."; Cancelled = "GraalVM 安装已取消。"
        Progress = "{0} / {1}   {2}/秒"; Error = "托管 GraalVM 安装失败。请查看安装日志：{0}"
    }
    ja = @{
        Title = "Turboism - GraalVM インストール"; Preparing = "GraalVM のダウンロードを準備しています..."
        Downloading = "GraalVM をダウンロード中"; Extracting = "管理対象ランタイムを展開しています..."
        Verifying = "GraalVM と分離ホストを検証しています..."; Ready = "GraalVM の準備ができました。"
        Cancel = "ダウンロードをキャンセル"; Cancelling = "ダウンロードをキャンセルしています..."; Cancelled = "GraalVM のインストールをキャンセルしました。"
        Progress = "{0} / {1}   {2}/秒"; Error = "管理対象 GraalVM のインストールに失敗しました。インストーラーログを確認してください：{0}"
    }
}
$S = $strings[(Get-InstallerLanguage)]

function Format-ByteCount {
    param([long]$Bytes)
    if ($Bytes -ge 1GB) { return ("{0:N1} GB" -f ($Bytes / 1GB)) }
    if ($Bytes -ge 1MB) { return ("{0:N1} MB" -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ("{0:N1} KB" -f ($Bytes / 1KB)) }
    return "$Bytes B"
}

function Test-TurboismJava17 {
    param([string]$Candidate)
    if ([string]::IsNullOrWhiteSpace($Candidate) -or -not (Test-CubismNormalFile $Candidate)) { return $false }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = [System.IO.Path]::GetFullPath($Candidate); $psi.Arguments = "-version"
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($name in @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
        if ($psi.EnvironmentVariables.ContainsKey($name)) { $psi.EnvironmentVariables.Remove($name) }
    }
    try {
        $process = [System.Diagnostics.Process]::Start($psi)
        if (-not $process.WaitForExit(15000)) { try { $process.Kill() } catch { }; return $false }
        $versionText = $process.StandardError.ReadToEnd() + "`n" + $process.StandardOutput.ReadToEnd()
        $match = [regex]::Match($versionText, '(?m)version\s+"(?<major>[0-9]+)(?:\.|\")')
        return $process.ExitCode -eq 0 -and $match.Success -and [int]$match.Groups["major"].Value -ge 17
    }
    catch { return $false }
    finally { if ($null -ne $process) { $process.Dispose() } }
}

function Find-TurboismInstallerJava {
    param([string]$ExplicitJava, [string]$TurboismHome)
    $candidates = [System.Collections.Generic.List[string]]::new()
    $seen = New-Object System.Collections.Generic.HashSet[string]([System.StringComparer]::OrdinalIgnoreCase)
    $add = { param([string]$Candidate); if ([string]::IsNullOrWhiteSpace($Candidate)) { return }; try { $full = [System.IO.Path]::GetFullPath($Candidate) } catch { return }; if ($seen.Add($full)) { [void]$candidates.Add($full) } }
    & $add $ExplicitJava
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { & $add (Join-Path $env:JAVA_HOME "bin\java.exe") }
    try { $command = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue; if ($null -ne $command) { & $add $command.Source } } catch { }
    $statePath = Join-Path $TurboismHome "cubism-installations.json"
    if (Test-CubismNormalFile $statePath) {
        try { $state = Read-CubismInstallationState -StatePath $statePath; if ($state.Valid) { foreach ($entry in @($state.Installations | Where-Object { $_.Selected })) { & $add (Join-Path $entry.Root "app\jre\bin\java.exe") } } } catch { }
    }
    try { foreach ($candidate in Get-CubismInstallations -Roots (Get-CubismDiscoveryRoots) -TurboismHome $TurboismHome) { if ($candidate.Selectable) { & $add $candidate.Java } } } catch { }
    foreach ($candidate in $candidates) { if (Test-TurboismJava17 $candidate) { return $candidate } }
    return ""
}

function Start-ManagedGraalProcess {
    param([string]$JavaExe, [string]$Agent, [string]$TurboismHome)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $JavaExe
    $psi.Arguments = ('-cp "{0}" dev.turboism.graal.ManagedGraalRuntimeCli install "{1}"' -f $Agent.Replace('"','\"'), $TurboismHome.Replace('"','\"'))
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    foreach ($name in @("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) { if ($psi.EnvironmentVariables.ContainsKey($name)) { $psi.EnvironmentVariables.Remove($name) } }
    return [System.Diagnostics.Process]::Start($psi)
}

function Invoke-ManagedGraalGui {
    param([System.Diagnostics.Process]$Process, [string]$LogPath)
    Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing
    [System.Windows.Forms.Application]::EnableVisualStyles()
    $script:managedGraalExit = 1
    $script:managedGraalErrors = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
    $Process.add_ErrorDataReceived({ param($sender, $event) if ($null -ne $event.Data) { $script:managedGraalErrors.Enqueue($event.Data) } })
    $Process.BeginErrorReadLine()
    $form = New-Object System.Windows.Forms.Form; $form.Text = $S.Title; $form.Size = New-Object System.Drawing.Size(560, 220); $form.StartPosition = "CenterScreen"; $form.ControlBox = $false
    $status = New-Object System.Windows.Forms.Label; $status.Text = $S.Preparing; $status.Location = New-Object System.Drawing.Point(20, 20); $status.Size = New-Object System.Drawing.Size(510, 26); $form.Controls.Add($status)
    $progress = New-Object System.Windows.Forms.ProgressBar; $progress.Location = New-Object System.Drawing.Point(20, 58); $progress.Size = New-Object System.Drawing.Size(510, 24); $form.Controls.Add($progress)
    $detail = New-Object System.Windows.Forms.Label; $detail.Location = New-Object System.Drawing.Point(20, 92); $detail.Size = New-Object System.Drawing.Size(510, 24); $form.Controls.Add($detail)
    $cancel = New-Object System.Windows.Forms.Button; $cancel.Text = $S.Cancel; $cancel.Location = New-Object System.Drawing.Point(390, 130); $cancel.Size = New-Object System.Drawing.Size(140, 30); $form.Controls.Add($cancel)
    $started = [DateTime]::UtcNow; $lastTime = $started; $lastBytes = 0L
    $cancel.Add_Click({ $cancel.Enabled = $false; $status.Text = $S.Cancelling; Add-Content -LiteralPath $LogPath -Value "GRAAL_INSTALL_CANCEL_REQUESTED" -Encoding UTF8; try { $Process.StandardInput.WriteLine("cancel"); $Process.StandardInput.Flush() } catch { } })
    $timer = New-Object System.Windows.Forms.Timer; $timer.Interval = 150
    $timer.Add_Tick({
        while ($Process.StandardOutput.Peek() -ge 0) {
            $line = $Process.StandardOutput.ReadLine(); Add-Content -LiteralPath $LogPath -Value ("STDOUT " + $line) -Encoding UTF8
            $match = [regex]::Match($line, '^GRAAL_RUNTIME_PROGRESS\s+(?<state>\S+)\s+(?<done>[0-9]+)/(?<total>[0-9]+)\s*(?<message>.*)$')
            if (-not $match.Success) { continue }
            $state = $match.Groups['state'].Value; $done = [long]$match.Groups['done'].Value; $total = [long]$match.Groups['total'].Value
            $now = [DateTime]::UtcNow; $seconds = [Math]::Max(0.001, ($now - $lastTime).TotalSeconds); $rate = [Math]::Max(0, [long](($done - $lastBytes) / $seconds)); $lastTime = $now; $lastBytes = $done
            if ($total -gt 0) { $progress.Maximum = 1000; $progress.Value = [Math]::Min(1000, [int](1000 * $done / $total)) }
            $detail.Text = $S.Progress -f (Format-ByteCount $done), (Format-ByteCount $total), (Format-ByteCount $rate)
            if ($state -eq 'DOWNLOADING') { $status.Text = $S.Downloading } elseif ($state -eq 'EXTRACTING') { $status.Text = $S.Extracting } elseif ($state -eq 'VERIFYING') { $status.Text = $S.Verifying } elseif ($state -eq 'READY') { $status.Text = $S.Ready } elseif ($state -eq 'CANCELLED') { $status.Text = $S.Cancelled }
        }
        $errorLine = $null
        while ($script:managedGraalErrors.TryDequeue([ref]$errorLine)) { Add-Content -LiteralPath $LogPath -Value ("STDERR " + $errorLine) -Encoding UTF8 }
        if ($Process.HasExited) { $script:managedGraalExit = $Process.ExitCode; Add-Content -LiteralPath $LogPath -Value ("GRAAL_INSTALL_EXIT code=" + $script:managedGraalExit) -Encoding UTF8; $timer.Stop(); $form.Close() }
    })
    $timer.Start(); [void]$form.ShowDialog(); $timer.Dispose(); return $script:managedGraalExit
}

$logPath = ""; $process = $null; $result = 1
try {
    $turboismHome = [System.IO.Path]::GetFullPath($HomePath).TrimEnd('\', '/')
    if (-not (Test-CubismNormalDirectory $turboismHome)) { throw "Turboism home is not an existing ordinary directory: $turboismHome" }
    $logs = Join-Path $turboismHome "logs\installer"; New-Item -ItemType Directory -Path $logs -Force | Out-Null
    $logPath = Join-Path $logs "managed-graal-install.log"
    $agent = Join-Path $turboismHome "turboism-agent.jar"; if (-not (Test-CubismNormalFile $agent)) { throw "Turboism agent is missing: $agent" }
    $javaExe = Find-TurboismInstallerJava -ExplicitJava $Java -TurboismHome $turboismHome
    if ([string]::IsNullOrWhiteSpace($javaExe)) { throw "No trusted Java 17 or newer runtime is available." }
    $process = Start-ManagedGraalProcess -JavaExe $javaExe -Agent $agent -TurboismHome $turboismHome
    if ($Gui) { $result = Invoke-ManagedGraalGui -Process $process -LogPath $logPath }
    else {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit(); $result = $process.ExitCode
        $stdout = $stdoutTask.Result; $stderr = $stderrTask.Result
        if (-not [string]::IsNullOrEmpty($stdout)) { [Console]::Out.Write($stdout); Add-Content -LiteralPath $logPath -Value ("STDOUT " + $stdout) -Encoding UTF8 }
        if (-not [string]::IsNullOrEmpty($stderr)) { [Console]::Error.Write($stderr); Add-Content -LiteralPath $logPath -Value ("STDERR " + $stderr) -Encoding UTF8 }
        Add-Content -LiteralPath $logPath -Value ("GRAAL_INSTALL_EXIT code=" + $result) -Encoding UTF8
    }
    if ($result -ne 0 -and $Gui) { [System.Windows.Forms.MessageBox]::Show(($S.Error -f $logPath), $S.Title, 'OK', 'Error') | Out-Null }
}
catch {
    if (-not [string]::IsNullOrWhiteSpace($logPath)) { try { Add-Content -LiteralPath $logPath -Value ("GRAAL_INSTALL_EXCEPTION " + $_.Exception.Message) -Encoding UTF8 } catch { } }
    Write-Error $_.Exception.Message; $result = 1
}
finally { if ($null -ne $process) { if (-not $process.HasExited) { try { $process.Kill() } catch { } }; $process.Dispose() } }
exit $result
