[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$DestinationRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$source = [System.IO.Path]::GetFullPath($SourceRoot)
$destination = [System.IO.Path]::GetFullPath($DestinationRoot)
if (-not [System.IO.Directory]::Exists($source)) {
    throw "JAR payload source does not exist."
}

[void][System.IO.Directory]::CreateDirectory($destination)
foreach ($sourceFile in [System.IO.Directory]::GetFiles(
    $source,
    "*.jar",
    [System.IO.SearchOption]::AllDirectories
)) {
    $relative = $sourceFile.Substring($source.Length).TrimStart(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )
    $destinationFile = Join-Path $destination $relative
    $unchanged = $false
    if ([System.IO.File]::Exists($destinationFile)) {
        $sourceInfo = [System.IO.FileInfo]::new($sourceFile)
        $destinationInfo = [System.IO.FileInfo]::new($destinationFile)
        if ($sourceInfo.Length -eq $destinationInfo.Length) {
            $sourceHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash
            $destinationHash = (Get-FileHash -LiteralPath $destinationFile -Algorithm SHA256).Hash
            $unchanged = $sourceHash -eq $destinationHash
        }
    }
    if ($unchanged) {
        Write-Output "SKIP|$relative"
        continue
    }

    $parent = [System.IO.Path]::GetDirectoryName($destinationFile)
    [void][System.IO.Directory]::CreateDirectory($parent)
    [System.IO.File]::Copy($sourceFile, $destinationFile, $true)
    Write-Output "WRITE|$relative"
}
