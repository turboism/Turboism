# -*- coding: utf-8 -*-
# Executable regression: tests for bootstrap-receipt.ps1 and receipt initialization flow.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $scriptDir 'cubism-launch-common.ps1')

$script:Assertions = 0
function Assert-ReceiptRegression {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Receipt regression failed: $Message" }
    $script:Assertions++
    Write-Output "PASS $Message"
}

function Assert-ReceiptThrows {
    param([scriptblock]$Action, [string]$Message)
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    Assert-ReceiptRegression $rejected $Message
}

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ('turboism-receipt-test-' + [guid]::NewGuid().ToString('N'))
[void][System.IO.Directory]::CreateDirectory($fixture)

try {
    $bootstrapScript = Join-Path $scriptDir 'bootstrap-receipt.ps1'
    Assert-ReceiptRegression (Test-Path -LiteralPath $bootstrapScript -PathType Leaf) 'bootstrap-receipt.ps1 exists'

    # 1. Parse check: script must have valid PowerShell syntax
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($bootstrapScript, [ref]$tokens, [ref]$parseErrors)
    Assert-ReceiptRegression ($parseErrors.Count -eq 0) 'bootstrap-receipt.ps1 parses without syntax errors'

    # 2. Legacy installation with no bootstrap documents: skips cleanly without creating receipt
    $legacyHome = Join-Path $fixture 'legacy-home'
    [void][System.IO.Directory]::CreateDirectory($legacyHome)
    [System.IO.File]::WriteAllText((Join-Path $legacyHome 'turboism-agent.jar'), 'synthetic-agent')
    $res = & $bootstrapScript -HomePath $legacyHome -PassThru
    Assert-ReceiptRegression ($res.Success -eq $true) 'legacy install succeeds'
    Assert-ReceiptRegression ($res.Status -eq 'SKIPPED_NO_DOCUMENTS') 'legacy install status is SKIPPED_NO_DOCUMENTS'
    Assert-ReceiptRegression ($res.ExitCode -eq 0) 'legacy install exit code is 0'
    $receiptPath = Join-Path $legacyHome 'state\runtime\update\installed-receipt.json'
    Assert-ReceiptRegression (-not (Test-Path -LiteralPath $receiptPath)) 'no receipt created for legacy install'

    # 3. Missing bootstrap documents with -Strict throws
    Assert-ReceiptThrows {
        & $bootstrapScript -HomePath $legacyHome -Strict
    } 'missing documents with -Strict fails closed'

    # 4. Upgraded receipt + stale bootstrap documents regression (Issue #50 review point 4):
    # Default auto-bootstrap mode must detect the existing upgraded receipt and NOT re-run stale bootstrap
    $staleHome = Join-Path $fixture 'stale-bootstrap-home'
    $staleState = Join-Path $staleHome 'state\runtime\update'
    [void][System.IO.Directory]::CreateDirectory($staleState)
    [System.IO.File]::WriteAllText((Join-Path $staleHome 'turboism-agent.jar'), 'agent-bytes')
    $staleBootstrap = Join-Path $staleHome 'bootstrap'
    [void][System.IO.Directory]::CreateDirectory($staleBootstrap)
    [System.IO.File]::WriteAllText((Join-Path $staleBootstrap 'manifest.json'), '{"version":"1.0.0"}')
    [System.IO.File]::WriteAllText((Join-Path $staleBootstrap 'manifest.json.sig'), 'sig-1.0.0')
    $upgradedReceipt = Join-Path $staleState 'installed-receipt.json'
    [System.IO.File]::WriteAllText($upgradedReceipt, '{"schemaVersion":1,"version":"1.1.0","upgraded":true}')
    $upgradedBytes = [System.IO.File]::ReadAllBytes($upgradedReceipt)

    $staleRes = & $bootstrapScript -HomePath $staleHome -PassThru
    Assert-ReceiptRegression ($staleRes.Success -eq $true) 'existing receipt skips stale bootstrap in default mode'
    Assert-ReceiptRegression ($staleRes.Status -eq 'ALREADY_INITIALIZED') 'status is ALREADY_INITIALIZED'
    Assert-ReceiptRegression ($staleRes.ExitCode -eq 0) 'exit code is 0 on already initialized'
    $currentBytes = [System.IO.File]::ReadAllBytes($upgradedReceipt)
    Assert-ReceiptRegression ([System.Linq.Enumerable]::SequenceEqual([byte[]]$upgradedBytes, [byte[]]$currentBytes)) 'upgraded receipt was NOT overwritten by stale bootstrap'

    # 5. Compile a synthetic helper Java class for unit test validation
    $javaSource = @'
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestReceiptHelper {
    public static void main(String[] args) throws Exception {
        Path root = null;
        Path stage = null;
        Path work = null;
        boolean initReceipt = false;
        for (int i = 0; i < args.length; i++) {
            if ("--root".equals(args[i])) root = Path.of(args[++i]);
            else if ("--stage".equals(args[i])) stage = Path.of(args[++i]);
            else if ("--work".equals(args[i])) work = Path.of(args[++i]);
            else if ("--initialize-receipt".equals(args[i])) initReceipt = true;
        }
        if (root == null || stage == null || work == null || !initReceipt) {
            System.err.println("usage error");
            System.exit(64);
        }
        Path manifest = stage.resolve("manifest.json");
        Path sig = stage.resolve("manifest.json.sig");
        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(sig)) {
            System.err.println("missing manifest or signature");
            System.exit(70);
        }
        String manifestContent = Files.readString(manifest, StandardCharsets.UTF_8);
        if (manifestContent.contains("TAMPER_FAIL")) {
            System.err.println("tampered payload verification failed");
            System.exit(74);
        }
        if (manifestContent.contains("REJECT_KEYS")) {
            System.err.println("rejected update: no active keys");
            System.exit(70);
        }
        Path receiptIndex = root.resolve("state/runtime/update/installed-receipt.json");
        if (Files.exists(receiptIndex)) {
            String existing = Files.readString(receiptIndex, StandardCharsets.UTF_8);
            if (existing.contains("DIFFERENT_RECEIPT")) {
                System.err.println("An existing different receipt must not be silently replaced");
                System.exit(74);
            }
        }
        Files.createDirectories(receiptIndex.getParent());
        Files.writeString(receiptIndex, "{\"format\":\"turboism.installed-receipt\",\"version\":\"1.0.0\"}\n", StandardCharsets.UTF_8);
        Path docsDir = root.resolve("state/runtime/update/receipts/doc-sha256");
        Files.createDirectories(docsDir);
        Files.copy(manifest, docsDir.resolve("manifest.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(sig, docsDir.resolve("manifest.json.sig"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("RECEIPT_INITIALIZED: 1.0.0");
        System.exit(10); // EXIT_NOTHING = 10 from production FrameworkUpdateHelper
    }
}
'@
    $srcDir = Join-Path $fixture 'src'
    [void][System.IO.Directory]::CreateDirectory($srcDir)
    $srcFile = Join-Path $srcDir 'TestReceiptHelper.java'
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($srcFile, $javaSource, $utf8NoBom)

    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -ne $javacCmd) {
        $compileOut = & javac -d $fixture $srcFile 2>&1
        Assert-ReceiptRegression ($LASTEXITCODE -eq 0) "compiled test helper: $compileOut"

        # 6. Successful receipt initialization with test helper
        $appHome = Join-Path $fixture 'app-home'
        [void][System.IO.Directory]::CreateDirectory($appHome)
        [System.IO.File]::WriteAllText((Join-Path $appHome 'turboism-agent.jar'), 'agent-bytes')
        $stageDir = Join-Path $fixture 'stage'
        [void][System.IO.Directory]::CreateDirectory($stageDir)
        [System.IO.File]::WriteAllText((Join-Path $stageDir 'manifest.json'), '{"version":"1.0.0"}')
        [System.IO.File]::WriteAllText((Join-Path $stageDir 'manifest.json.sig'), 'envelope-sig')

        $initRes = & $bootstrapScript -HomePath $appHome -StagePath $stageDir -Classpath $fixture -HelperClass 'TestReceiptHelper' -PassThru
        Assert-ReceiptRegression ($initRes.Success -eq $true) 'receipt initialization succeeds'
        Assert-ReceiptRegression ($initRes.Status -eq 'INITIALIZED') 'receipt initialization status is INITIALIZED'
        Assert-ReceiptRegression ($initRes.HelperExitCode -eq 10) 'helper exit code is 10 (EXIT_NOTHING)'
        $createdReceipt = Join-Path $appHome 'state\runtime\update\installed-receipt.json'
        Assert-ReceiptRegression (Test-Path -LiteralPath $createdReceipt -PathType Leaf) 'installed-receipt.json created'
        $publishedManifest = Join-Path $appHome 'state\runtime\update\receipts\doc-sha256\manifest.json'
        Assert-ReceiptRegression (Test-Path -LiteralPath $publishedManifest -PathType Leaf) 'published manifest exists'

        # 7. Idempotency: re-running with identical inputs succeeds in non-strict mode
        $idemRes = & $bootstrapScript -HomePath $appHome -StagePath $stageDir -Classpath $fixture -HelperClass 'TestReceiptHelper' -PassThru
        Assert-ReceiptRegression ($idemRes.Success -eq $true) 'idempotent receipt initialization succeeds'
        Assert-ReceiptRegression ($idemRes.Status -eq 'ALREADY_INITIALIZED') 'idempotent status reports ALREADY_INITIALIZED'

        # 8. Strict mode with different receipt fails closed (helper exit 74)
        $diffStrictHome = Join-Path $fixture 'diff-strict-home'
        $diffStrictState = Join-Path $diffStrictHome 'state\runtime\update'
        [void][System.IO.Directory]::CreateDirectory($diffStrictState)
        [System.IO.File]::WriteAllText((Join-Path $diffStrictHome 'turboism-agent.jar'), 'agent-bytes')
        [System.IO.File]::WriteAllText((Join-Path $diffStrictState 'installed-receipt.json'), '{"version":"0.9.0","flag":"DIFFERENT_RECEIPT"}')
        $diffStrictRes = & $bootstrapScript -HomePath $diffStrictHome -StagePath $stageDir -Classpath $fixture -HelperClass 'TestReceiptHelper' -Strict -PassThru
        Assert-ReceiptRegression ($diffStrictRes.Success -eq $false) 'strict mode with different receipt fails closed'
        Assert-ReceiptRegression ($diffStrictRes.HelperExitCode -eq 74) 'helper exit code is 74'

        # 9. Tampered payload fails closed (helper exit 74)
        $tamperStage = Join-Path $fixture 'tamper-stage'
        [void][System.IO.Directory]::CreateDirectory($tamperStage)
        [System.IO.File]::WriteAllText((Join-Path $tamperStage 'manifest.json'), '{"version":"1.0.0","tag":"TAMPER_FAIL"}')
        [System.IO.File]::WriteAllText((Join-Path $tamperStage 'manifest.json.sig'), 'envelope-sig')
        $tamperHome = Join-Path $fixture 'tamper-home'
        [void][System.IO.Directory]::CreateDirectory($tamperHome)
        [System.IO.File]::WriteAllText((Join-Path $tamperHome 'turboism-agent.jar'), 'agent-bytes')

        $tamperRes = & $bootstrapScript -HomePath $tamperHome -StagePath $tamperStage -Classpath $fixture -HelperClass 'TestReceiptHelper' -PassThru
        Assert-ReceiptRegression ($tamperRes.Success -eq $false) 'tampered payload fails initialization'
        Assert-ReceiptRegression ($tamperRes.HelperExitCode -eq 74) 'tampered payload helper exit code is 74'
        Assert-ReceiptRegression (-not (Test-Path -LiteralPath (Join-Path $tamperHome 'state\runtime\update\installed-receipt.json'))) 'no receipt created on tamper failure'

        # 10. Configurator -InitializeReceipt forwards to bootstrap-receipt.ps1
        $configuratorScript = Join-Path $scriptDir 'configure_turboism.ps1'
        $configHome = Join-Path $fixture 'config-home'
        [void][System.IO.Directory]::CreateDirectory($configHome)
        [System.IO.File]::WriteAllText((Join-Path $configHome 'turboism-agent.jar'), 'agent-bytes')
        Copy-Item -LiteralPath $bootstrapScript -Destination (Join-Path $configHome 'bootstrap-receipt.ps1')
        $cfgRes = & $configuratorScript -Home $configHome -InitializeReceipt -ReceiptStage $legacyHome
        Assert-ReceiptRegression ($LASTEXITCODE -eq 0) 'configurator -InitializeReceipt on legacy stage exits 0'

        # 11. Configurator -InitializeSelection propagates bootstrap-receipt failure
        $cfgFailHome = Join-Path $fixture 'config-fail-home'
        [void][System.IO.Directory]::CreateDirectory($cfgFailHome)
        [System.IO.File]::WriteAllText((Join-Path $cfgFailHome 'turboism-agent.jar'), 'agent-bytes')
        $cfgFailBootstrap = Join-Path $cfgFailHome 'bootstrap'
        [void][System.IO.Directory]::CreateDirectory($cfgFailBootstrap)
        [System.IO.File]::WriteAllText((Join-Path $cfgFailBootstrap 'manifest.json'), '{"version":"1.0.0","tag":"TAMPER_FAIL"}')
        [System.IO.File]::WriteAllText((Join-Path $cfgFailBootstrap 'manifest.json.sig'), 'envelope-sig')
        # Provide minimal valid cubism-installations.json so state loading succeeds
        $cfgFailState = Join-Path $cfgFailHome 'cubism-installations.json'
        [System.IO.File]::WriteAllText($cfgFailState, '{"schemaVersion":1,"candidates":[]}')
        Copy-Item -LiteralPath $bootstrapScript -Destination (Join-Path $cfgFailHome 'bootstrap-receipt.ps1')

        $prevEap = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            & $configuratorScript -Home $cfgFailHome -InitializeSelection 2>&1 | Out-Null
            $cfgFailExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prevEap
        }
        Assert-ReceiptRegression ($cfgFailExit -ne 0) 'configurator -InitializeSelection propagates bootstrap failure'
    } else {
        Write-Warning 'javac not found; Java-dependent mock tests skipped in this run'
    }

    Write-Host "`nAll $script:Assertions bootstrap receipt regression tests passed." -ForegroundColor Green
}
finally {
    if (Test-Path -LiteralPath $fixture) {
        try { Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue } catch { }
    }
}
