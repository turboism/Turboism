# Samples the java process hosting the perf fixture: RSS (WorkingSet64),
# private committed bytes, handle count, thread count, and total CPU seconds.
param(
    [string]$FixtureToken = 'fixture.cmo3',
    [int]$IntervalMillis = 500,
    [int]$DurationSeconds = 300,
    [string]$OutFile = 'process-metrics.csv'
)
$end = (Get-Date).AddSeconds($DurationSeconds)
"epoch_ms,working_set_bytes,private_bytes,handles,threads,cpu_seconds" | Out-File -Encoding ascii $OutFile
while ((Get-Date) -lt $end) {
    $p = Get-CimInstance Win32_Process -Filter "Name like 'java%'" |
        Where-Object { $_.CommandLine -match [regex]::Escape($FixtureToken) } |
        Select-Object -First 1
    if ($p) {
        $proc = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
        if ($proc) {
            '{0},{1},{2},{3},{4},{5}' -f [long]([DateTimeOffset]::Now.ToUnixTimeMilliseconds()),
                $proc.WorkingSet64, $proc.PrivateMemorySize64, $proc.HandleCount,
                $proc.Threads.Count, $proc.TotalProcessorTime.TotalSeconds |
                Out-File -Append -Encoding ascii $OutFile
        }
    }
    Start-Sleep -Milliseconds $IntervalMillis
}
Write-Output "sampler done -> $OutFile"
