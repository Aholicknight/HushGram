<#
.SYNOPSIS
    Walk the whole patched app on a leased phone or an emulator and leave a walk receipt.

.DESCRIPTION
    release/publish-release.ps1 refuses to publish a bundle without a receipt that a whole-app
    walk of that exact bundle left (walk-receipt.ps1). This runs the walk: it patches Instagram
    from the repo's release bundle with patch-for-device.ps1, installs it on the device, opens the
    Hush settings screen and each patched screen, watches the crash buffer for an Instagram crash,
    and writes walk-receipt.json with New-WalkReceipt. The receipt names the SHA-256 of the .mpp the
    walked APK was patched with, which is what publish holds it to.

    The result the walk writes:
      PASS      the whole app was walked, every screen opened, no crash.
      PARTIAL   a screen or the settings page was skipped, so it was not the whole app.
      FAIL      an Instagram crash, or a screen that would not open.
    Only a PASS backs a release.

    A screen is opened by its deep link (am start -W -a VIEW -d ...), and the Hush settings by the
    same intent the home-screen shortcut uses: the main activity carrying the OPEN_SETTINGS extra.

    Device safety: the walk takes an exclusive serial lease and releases it when it's done. It never
    changes the screen-stay-awake setting and never sends sleep or power, on a phone or an emulator
    (the phones are set to stay awake for good). It never uninstalls or clears the installed app; an
    install that can't update the app in place is a problem the walk reports, not one it forces past.

.EXAMPLE
    scripts/walk-app.ps1 -Serial emulator-5590 -ExpectedModel sdk_gphone64_x86_64 -ExpectedAvd HushGram_API36

.EXAMPLE
    # Walk a build already patched and installed from this bundle (records its APK hash):
    scripts/walk-app.ps1 -Serial R5CT139QJ5F -ExpectedModel SM-S908U1 -SkipPatch

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$ExpectedModel = $env:HUSHGRAM_EXPECTED_DEVICE_MODEL,
    [string]$ExpectedAvd = $env:HUSHGRAM_EXPECTED_AVD,
    [string]$Package = 'com.instagram.android',
    # The patched screens to open, each a deep link. The Hush settings page is always walked too,
    # unless -SkipSettings. A link that won't open is a problem, so the walk fails rather than skips.
    [string[]]$Visit = @('instagram://mainfeed', 'instagram://explore', 'instagram://reels_home', 'instagram://direct-inbox'),
    # Walk the app that's already installed instead of patching and installing a fresh one. The
    # installed build must have been patched from the release bundle; the receipt still names the
    # bundle's hash, so an install from any other build would make a receipt publish won't honor.
    [switch]$SkipPatch,
    # Leave the Hush settings page out of the walk. Makes the walk PARTIAL, not a release walk.
    [switch]$SkipSettings,
    [int]$SettleSeconds = 4,
    [string]$OutDir = (Join-Path $env:TEMP 'hushgram-walk'),
    # Passed through to patch-for-device.ps1 so the build is signed with the key the installed app
    # already uses (an emulator or the S25 carry the Manager key, not the sideload key).
    [string]$Keystore,
    [string]$KeyAlias,
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword,
    [string]$Sdk,
    [string]$Java,
    # The checkout whose release bundle is walked, the one holding this script unless given.
    [string]$Root
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'native-runner-device.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
. (Join-Path $PSScriptRoot 'walk-receipt.ps1')

$MainActivity = 'com.instagram.mainactivity.InstagramMainActivity'
$OpenSettingsExtra = 'app.hushgram.extension.instagram.OPEN_SETTINGS'

function Resolve-WalkOutcome {
    <#
    .SYNOPSIS
        The result a walk earns from what it saw. A crash or an unopened screen fails it, a skipped
        part makes it partial, and only a whole clean walk passes.
    #>
    param(
        [int]$Screens,
        [int]$Crashes,
        [string[]]$Problems = @(),
        [Parameter(Mandatory = $true)][bool]$Full
    )
    if ($Crashes -gt 0 -or @($Problems).Count -gt 0) { return 'FAIL' }
    if (-not $Full -or $Screens -lt 1) { return 'PARTIAL' }
    return 'PASS'
}

function Invoke-WalkApp {
    param(
        [string]$Serial, [string]$ExpectedModel, [string]$ExpectedAvd, [string]$Package,
        [string[]]$Visit, [bool]$SkipPatch, [bool]$SkipSettings, [int]$SettleSeconds,
        [string]$OutDir, [hashtable]$Signing, [string]$Sdk, [string]$Java, [string]$Root
    )
    if (-not $Serial) { throw 'Name the device to walk with -Serial.' }
    if (-not $ExpectedModel) { throw 'Set -ExpectedModel (or HUSHGRAM_EXPECTED_DEVICE_MODEL) so the walk confirms the device.' }
    if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
    $Root = [IO.Path]::GetFullPath($Root)
    $OutDir = [IO.Path]::GetFullPath($OutDir)
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

    $tools = Resolve-NativeRunnerTools -Sdk $Sdk -Java $Java
    $adb = $tools.Adb
    # The bundle publish will hash, named in the receipt so a walk of any other build can't stand in.
    $version = Get-BundleVersion -Root $Root
    $bundle = Get-ReleaseBundlePath -Root $Root -Version $version
    if (-not (Test-Path -LiteralPath $bundle -PathType Leaf)) {
        throw "No release bundle at $bundle. Build it first (:patches:generatePatchesList then :patches:buildAndroid, through the governor)."
    }
    $bundleSha256 = (Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant()

    if (-not $SkipPatch) {
        $patchArguments = @('-OutDir', $OutDir, '-Root', $Root)
        if ($Sdk) { $patchArguments += @('-Sdk', $Sdk) }
        foreach ($name in @('Keystore', 'KeyAlias', 'KeystorePassword', 'KeyPassword')) {
            if ($Signing.ContainsKey($name)) { $patchArguments += @("-$name", $Signing[$name]) }
        }
        Write-Host "[walk] patching Instagram $version from $(Split-Path -Leaf $bundle)"
        $patchOut = @(& (Join-Path $PSScriptRoot 'patch-for-device.ps1') @patchArguments)
        if ($LASTEXITCODE -ne 0) { $patchOut | Out-Host; throw 'patch-for-device.ps1 did not finish, so there is nothing to walk.' }
        $installApk = @($patchOut | Where-Object { $_ -match '^\[device\] (.+\.apk)$' } | ForEach-Object { $Matches[1] })[-1]
        if (-not $installApk) { $patchOut | Out-Host; throw 'patch-for-device.ps1 printed no signed APK path.' }
    }

    $deviceLease = $null
    $problems = [Collections.Generic.List[string]]::new()
    $screens = 0
    try {
        $deviceLease = Enter-HushgramDeviceLease -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd
        Confirm-HushgramDevice -Adb $adb -DeviceLease $deviceLease
        function Invoke-WalkAdb([string[]]$Arguments) {
            Invoke-HushgramAdbCommand -Adb $adb -Arguments (@('-s', $Serial) + $Arguments) -DeviceLease $deviceLease
        }
        $deviceModel = @((Invoke-WalkAdb @('shell', 'getprop', 'ro.product.model')).Stdout)[0]

        if (-not $SkipPatch) {
            Write-Host "[walk] installing $(Split-Path -Leaf $installApk) on $Serial"
            $installed = Invoke-WalkAdb @('install', '-r', $installApk)
            if ($installed.ExitCode -ne 0) {
                throw (Format-HushgramAdbFailure -Message ("The build would not install on $Serial. If this is a signature clash, patch with the key the installed app uses " +
                    '(pass -Keystore/-KeyAlias/-KeystorePassword/-KeyPassword). The installed app and its data were left alone.') -Result $installed)
            }
        }
        $started = [DateTimeOffset]::UtcNow
        $startedEpoch = [long](@((Invoke-WalkAdb @('shell', 'date', '+%s')).Stdout)[0]).Trim()
        [void](Invoke-WalkAdb @('shell', 'am', 'force-stop', $Package))

        # Open Instagram on Home first, so a deep link lands in a running app, not a cold start.
        [void](Invoke-WalkAdb @('shell', 'am', 'start', '-W', '-n', "$Package/$MainActivity"))
        Start-Sleep -Seconds $SettleSeconds

        $steps = [Collections.Generic.List[object]]::new()
        if (-not $SkipSettings) {
            $steps.Add([pscustomobject]@{ Name = 'Hush settings'; Arguments =
                @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-n', "$Package/$MainActivity", '--ez', $OpenSettingsExtra, 'true') })
        }
        foreach ($link in $Visit) {
            $steps.Add([pscustomobject]@{ Name = $link; Arguments =
                @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d', $link, '-p', $Package) })
        }
        foreach ($step in $steps) {
            $launch = Invoke-WalkAdb $step.Arguments
            Start-Sleep -Seconds $SettleSeconds
            $launched = ($launch.Stdout -join "`n") -match '(?m)^Status:\s*ok\r?$'
            # The resumed-activity line is named differently by Android version: mResumedActivity=
            # (older), and topResumedActivity= / "ResumedActivity:" on API 36. Match the shared stem.
            $resumed = @((Invoke-WalkAdb @('shell', 'dumpsys', 'activity', 'activities')).Stdout |
                Where-Object { $_ -match 'ResumedActivity' }) -join ' '
            $foreground = $resumed -match [regex]::Escape($Package)
            if ($launched -and $foreground) {
                $screens++
                Write-Host "[walk] opened $($step.Name)"
            } else {
                $problems.Add("$($step.Name) would not open (status ok: $launched, Instagram in front: $foreground)")
                Write-Host "[walk] $($step.Name) would not open"
            }
        }

        $crashLines = @((Invoke-WalkAdb @('logcat', '-b', 'crash', '-d', '-v', 'epoch')).Stdout | Where-Object {
            $_ -match '^\s*(\d+)\.\d+\s' -and [long]$Matches[1] -ge $startedEpoch
        })
        $fatal = @($crashLines | Where-Object { $_ -match "Process:\s*$([regex]::Escape($Package))\b" })
        if ($fatal.Count -gt 0) {
            [IO.File]::WriteAllLines((Join-Path $OutDir 'crash-buffer.txt'), [string[]]$crashLines)
            $problems.Add("Instagram crashed during the walk: $($fatal[0].Trim())")
        }

        $appVersion = (@((Invoke-WalkAdb @('shell', 'dumpsys', 'package', $Package)).Stdout |
            Where-Object { $_ -match 'versionName=(.+)$' } | ForEach-Object { $Matches[1].Trim() }) | Select-Object -First 1)
        $apkPath = @((Invoke-WalkAdb @('shell', 'pm', 'path', $Package)).Stdout | Where-Object { $_ -like 'package:*base.apk' })[0]
        $installedSha = ''
        if ($apkPath) { $installedSha = (@((Invoke-WalkAdb @('shell', 'sha256sum', $apkPath.Substring(8))).Stdout)[0] -split '\s+')[0] }

        $full = (-not $SkipSettings) -and $problems.Count -eq 0
        $result = Resolve-WalkOutcome -Screens $screens -Crashes $fatal.Count -Problems ([string[]]$problems) -Full $full
        $receipt = New-WalkReceipt -Result $result -Full $full -Serial $Serial -Device $deviceModel `
            -AppVersion $appVersion -BundleSha256 $bundleSha256 -InstalledApkSha256 $installedSha `
            -Started $started -Finished ([DateTimeOffset]::UtcNow) -Screens $screens -Crashes $fatal.Count `
            -Problems ([string[]]$problems)
        $receiptPath = Join-Path $OutDir 'walk-receipt.json'
        [IO.File]::WriteAllText($receiptPath, (ConvertTo-Json -InputObject $receipt -Depth 4) + "`n")

        Write-Host "[walk] $result on $deviceModel ($Serial): $screens screens, $($fatal.Count) crashes, $($problems.Count) problems"
        foreach ($problem in $problems) { Write-Host "[walk]   $problem" }
        Write-Host "[walk] receipt $receiptPath"
        if ($result -ne 'PASS') { Write-Host '[walk] this walk does not back a release.' }
        return $receiptPath
    } finally {
        if ($deviceLease) { Exit-HushgramDeviceLease $deviceLease }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $signing = @{}
    foreach ($name in @('Keystore', 'KeyAlias', 'KeystorePassword', 'KeyPassword')) {
        if ($PSBoundParameters.ContainsKey($name)) { $signing[$name] = $PSBoundParameters[$name] }
    }
    Invoke-WalkApp -Serial $Serial -ExpectedModel $ExpectedModel -ExpectedAvd $ExpectedAvd -Package $Package `
        -Visit $Visit -SkipPatch:$SkipPatch -SkipSettings:$SkipSettings -SettleSeconds $SettleSeconds `
        -OutDir $OutDir -Signing $signing -Sdk $Sdk -Java $Java -Root $Root
}
