[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CubismRoot,
    [Parameter(Mandatory = $true)][string]$ProjectPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$bundleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$cubism = (Resolve-Path -LiteralPath $CubismRoot).Path
$project = (Resolve-Path -LiteralPath $ProjectPath).Path
$official = Join-Path $cubism "CubismEditor5.bat"
$agent = Join-Path $bundleRoot "turboism-agent.jar"

foreach ($required in @($official, $agent, (Join-Path $bundleRoot "plugins\history-validation-probe.jar"), (Join-Path $bundleRoot "config.json"))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file is missing: $required"
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $bundleRoot "logs") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bundleRoot "state") | Out-Null

$agentWindows = $agent -replace '^Z:', 'Z:'
$homeWindows = $bundleRoot -replace '^Z:', 'Z:'
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED -Dturboism.home=$homeWindows -javaagent:$agentWindows=home=$homeWindows;timeoutSeconds=180"

Push-Location $cubism
try {
    & $official $project
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}

exit $exitCode
