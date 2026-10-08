# Bounded FPS driver: oscillates the Cubism editor main window size by 1px,
# forcing viewport re-layout + repaint (renderScene) each step, matching the
# repo's Linux fps-resize-driver semantics on native Windows.
param(
    [int]$DurationSeconds = 60,
    [int]$StepMillis = 34,
    [string]$TitlePattern = 'Cubism Editor'
)
$code = @'
using System;
using System.Runtime.InteropServices;
public static class WinRes {
    [DllImport("user32.dll")] public static extern IntPtr FindWindow(string c, string n);
    [DllImport("user32.dll", SetLastError=true)] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll", SetLastError=true)] public static extern bool MoveWindow(IntPtr h, int x, int y, int w, int ht, bool repaint);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);
    public struct RECT { public int Left, Top, Right, Bottom; }
}
'@
Add-Type -TypeDefinition $code
$procs = Get-Process | Where-Object { $_.MainWindowTitle -match $TitlePattern }
if (-not $procs) { Write-Error 'editor window not found'; exit 1 }
$h = ($procs | Select-Object -First 1).MainWindowHandle
$r = New-Object WinRes+RECT
[void][WinRes]::GetWindowRect($h, [ref]$r)
$x = $r.Left; $y = $r.Top
$w = $r.Right - $r.Left; $ht = $r.Bottom - $r.Top
Write-Output "driving window=$h at $x,$y ${w}x$ht for ${DurationSeconds}s step=${StepMillis}ms"
$end = (Get-Date).AddSeconds($DurationSeconds)
$i = 0
while ((Get-Date) -lt $end) {
    if (-not [WinRes]::IsWindow($h)) { Write-Output 'window gone'; break }
    $delta = ($i % 2)
    [void][WinRes]::MoveWindow($h, $x, $y, $w + $delta, $ht + $delta, $true)
    $i++
    Start-Sleep -Milliseconds $StepMillis
}
# restore
[void][WinRes]::MoveWindow($h, $x, $y, $w, $ht, $true)
Write-Output "driver done after $i steps"
