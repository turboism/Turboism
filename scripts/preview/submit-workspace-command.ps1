# Submits one bounded workspace-validation command file.
#
# Usage:
#   .\submit-workspace-command.ps1 -Sequence <n> -Operation <op> [-Argument <id>]
#
#   -Sequence   strictly increasing positive integer without leading zeros (1, 2, 3, ...)
#   -Operation  status | current | readiness | switch | update-default | reset-default
#   -Argument   for switch only: an opaque workspace id from a previous result
#
# Example:
#   .\submit-workspace-command.ps1 -Sequence 2 -Operation switch -Argument modeling
#
# The command file is written as UTF-8 to a temporary non-.cmd file in the same directory and
# atomically renamed, so the probe can never observe a partial two-line switch command and no
# shell interpolation can corrupt the opaque id. The probe executes it once and writes
# state\dev.turboism.validation.workspace\results\<seq>-<op>.txt.

param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[1-9][0-9]{0,5}$')]
    [string]$Sequence,

    [Parameter(Mandatory = $true)]
    [ValidateSet('status', 'current', 'readiness', 'switch', 'update-default', 'reset-default')]
    [string]$Operation,

    [Parameter(Mandatory = $false)]
    [AllowEmptyString()]
    [string]$Argument = ''
)

$ErrorActionPreference = 'Stop'
if ($Operation -eq 'switch') {
    if ([string]::IsNullOrWhiteSpace($Argument)) {
        Write-Error 'switch requires an opaque workspace id argument.'
        exit 2
    }
    if ([System.Text.Encoding]::UTF8.GetByteCount($Argument) -gt 512) {
        Write-Error 'switch argument exceeds 512 UTF-8 bytes.'
        exit 2
    }
}

$commandsDir = Join-Path $PSScriptRoot 'state\dev.turboism.validation.workspace\commands'
New-Item -ItemType Directory -Force -Path $commandsDir | Out-Null
$final = Join-Path $commandsDir "$Sequence-$Operation.cmd"
if (Test-Path -LiteralPath $final) {
    Write-Error "command file already exists: $final"
    exit 2
}

if ($Operation -eq 'switch') {
    $content = "switch`n$Argument`n"
} else {
    $content = "$Operation`n"
}

$temporary = Join-Path $commandsDir ".$Sequence-$Operation.$([guid]::NewGuid().ToString('N')).tmp"
[System.IO.File]::WriteAllText($temporary, $content, [System.Text.UTF8Encoding]::new($false))
try {
    [System.IO.File]::Move($temporary, $final)
} catch {
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    throw
}

Write-Output "Submitted $final"
exit 0
