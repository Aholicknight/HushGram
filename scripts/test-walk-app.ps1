<#
.SYNOPSIS
    Contract tests for walk-app.ps1: the result a walk earns, and the receipts publish will and
    won't honor.

.DESCRIPTION
    walk-app.ps1 decides a walk's result with Resolve-WalkOutcome and writes it with New-WalkReceipt.
    These dot-source walk-app.ps1 (its main body is skipped when the script is dot-sourced) and drive
    the decision with no device: a crash earns FAIL, a skipped screen earns PARTIAL, and only a whole
    clean walk earns PASS. Then the receipts those results produce are held to Test-WalkReceipt, the
    same gate release/publish-release.ps1 uses, so a FAIL or PARTIAL walk can't back a release and a
    PASS walk of a different bundle can't either.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
$scratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('hushgram-walk-app-' + [guid]::NewGuid().ToString('N'))))
$passed = 0

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}

. (Join-Path $PSScriptRoot 'walk-app.ps1')

try {
    New-Item -ItemType Directory -Force -Path $scratch | Out-Null

    # Resolve-WalkOutcome: the result a walk earns.
    Assert-True ((Resolve-WalkOutcome -Screens 12 -Crashes 1 -Problems @() -Full $true) -ceq 'FAIL') `
        'A crash during a full walk should earn FAIL.'
    Assert-True ((Resolve-WalkOutcome -Screens 12 -Crashes 0 -Problems @('instagram://feed would not open') -Full $true) -ceq 'FAIL') `
        'A screen that would not open should earn FAIL.'
    Assert-True ((Resolve-WalkOutcome -Screens 12 -Crashes 0 -Problems @() -Full $false) -ceq 'PARTIAL') `
        'A skipped screen (an incomplete walk) should earn PARTIAL.'
    Assert-True ((Resolve-WalkOutcome -Screens 0 -Crashes 0 -Problems @() -Full $true) -ceq 'PARTIAL') `
        'A walk that opened no screen should earn PARTIAL.'
    Assert-True ((Resolve-WalkOutcome -Screens 12 -Crashes 0 -Problems @() -Full $true) -ceq 'PASS') `
        'A whole clean walk should earn PASS.'
    # A crash outranks an otherwise-complete walk.
    Assert-True ((Resolve-WalkOutcome -Screens 12 -Crashes 2 -Problems @() -Full $false) -ceq 'FAIL') `
        'A crash should outrank an incomplete walk.'

    # The receipts those results produce, held to the publish gate.
    $bundle = Join-Path $scratch 'patches-0.0.0.mpp'
    [IO.File]::WriteAllText($bundle, 'walk-app contract fixture bundle')
    $bundleSha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()
    $started = [DateTimeOffset]::Parse('2026-10-21T00:00:00Z')
    $finished = $started.AddMinutes(6)

    function New-WalkReceiptFile([string]$Name, [string]$Result, [bool]$Full, [int]$Screens, [int]$Crashes, [string]$Sha, [string[]]$Problems) {
        $receipt = New-WalkReceipt -Result $Result -Full $Full -Serial 'emulator-5554' -Device 'sdk_gphone64_x86_64' `
            -AppVersion '452.1.0.53.64' -BundleSha256 $Sha -InstalledApkSha256 ('0' * 64) `
            -Started $started -Finished $finished -Screens $Screens -Crashes $Crashes -Problems $Problems
        $path = Join-Path $scratch $Name
        [IO.File]::WriteAllText($path, (ConvertTo-Json -InputObject $receipt -Depth 4) + "`n")
        return $path
    }

    $passWalk = New-WalkReceiptFile 'pass.json' 'PASS' $true 12 0 $bundleSha256 @()
    Assert-True (Test-WalkReceipt -Path $passWalk -BundlePath $bundle).Valid `
        'A whole clean walk of this bundle should back a release.'

    $crashWalk = New-WalkReceiptFile 'crash.json' 'FAIL' $false 9 1 $bundleSha256 @('Instagram crashed during the walk: ...')
    Assert-True (-not (Test-WalkReceipt -Path $crashWalk -BundlePath $bundle).Valid) `
        'A walk that recorded a crash must not back a release.'

    $skipWalk = New-WalkReceiptFile 'skip.json' 'PARTIAL' $false 3 0 $bundleSha256 @()
    Assert-True (-not (Test-WalkReceipt -Path $skipWalk -BundlePath $bundle).Valid) `
        'A walk that skipped part of the app must not back a release.'

    $otherWalk = New-WalkReceiptFile 'other-bundle.json' 'PASS' $true 12 0 ('a' * 64) @()
    Assert-True (-not (Test-WalkReceipt -Path $otherWalk -BundlePath $bundle).Valid) `
        'A clean walk of a different bundle must not back this one.'

    # The push gate runs this suite when the walk tooling changes. (Dot-sourcing walk-app.ps1 above
    # ran its param block in this scope, so $Root is no longer ours; pre-push.ps1 sits beside us.)
    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    Assert-True (Test-PushGateRunsSuite (Join-Path $PSScriptRoot 'pre-push.ps1') 'scripts/test-walk-app.ps1') `
        'The push gate does not run this suite when the walk tooling changes.'

    Write-Host "[walk-app] $passed checks passed"
} finally {
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $scratch.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe walk-app fixture cleanup path.' }
    if (Test-Path -LiteralPath $scratch) { Remove-Item -LiteralPath $scratch -Recurse -Force }
}
