<#
.SYNOPSIS
    The record that ties a patch run's result.json to the APK the run produced.

.DESCRIPTION
    Dot-sourced by patch-for-device.ps1, which writes patch-record.json next to its result, and by
    native-runner-device.ps1, which takes a patch selection from a result only through it. The
    CLI's result names the package and version, never the file, so without the record a result
    from another run of the same version would vouch for this APK.

    The record keys on the APK's content: every entry except the signing files, each by name, size
    and bytes. Signing the APK again with another key keeps that content, and any other patch
    selection changes it.

.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
#>

function Get-ApkContentSha256 {
    <#
    .SYNOPSIS
        One SHA-256 over every entry of the APK outside META-INF/ and the source stamp, in
        ordinal name order, each as its name, its size and its uncompressed bytes.
    #>
    param([string]$Apk)
    $Apk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Apk)
    $hash = [Security.Cryptography.IncrementalHash]::CreateHash([Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        # apksigner rewrites META-INF/ and drops stamp-cert-sha256 when it signs again.
        [string[]]$names = @($zip.Entries | ForEach-Object FullName |
            Where-Object { -not $_.StartsWith('META-INF/', [StringComparison]::Ordinal) -and $_ -cne 'stamp-cert-sha256' -and -not $_.EndsWith('/') })
        if ($names.Count -eq 0) { throw "$(Split-Path -Leaf $Apk) has no entries outside its signing files." }
        if (@($names | Select-Object -Unique).Count -ne $names.Count) { throw "$(Split-Path -Leaf $Apk) names an entry twice." }
        [Array]::Sort($names, [StringComparer]::Ordinal)
        $buffer = [byte[]]::new(1 -shl 16)
        foreach ($name in $names) {
            $entry = $zip.GetEntry($name)
            $header = [Text.Encoding]::UTF8.GetBytes("$name`n$($entry.Length)`n")
            $hash.AppendData($header)
            $stream = $entry.Open()
            try {
                while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) { $hash.AppendData($buffer, 0, $read) }
            } finally { $stream.Dispose() }
        }
        ([BitConverter]::ToString($hash.GetHashAndReset())).Replace('-', '').ToLowerInvariant()
    } finally {
        $hash.Dispose()
        $zip.Dispose()
    }
}

function Write-PatchRecord {
    param([string]$Apk, [string]$Result, [string]$Path)
    $Path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $record = [ordered]@{
        schemaVersion = 1
        resultSha256 = (Get-FileHash -LiteralPath $Result -Algorithm SHA256).Hash.ToLowerInvariant()
        contentSha256 = Get-ApkContentSha256 -Apk $Apk
    }
    [IO.File]::WriteAllText($Path, ($record | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
}

function Test-PatchRecord {
    <#
    .SYNOPSIS
        Why the record doesn't tie this result to this APK, or $null when it does.
    #>
    param([string]$Apk, [string]$Result, [string]$Record)
    if (-not (Test-Path -LiteralPath $Record -PathType Leaf)) { return "there is no patch record at $Record" }
    try { $stamp = Get-Content -LiteralPath $Record -Raw | ConvertFrom-Json }
    catch { return "the patch record at $Record doesn't parse" }
    if ($stamp.schemaVersion -ne 1) { return "the patch record at $Record has schema $($stamp.schemaVersion), not 1" }
    if ([string]$stamp.resultSha256 -cne (Get-FileHash -LiteralPath $Result -Algorithm SHA256).Hash.ToLowerInvariant()) {
        return 'the patch result is not the one the record was written with'
    }
    if ([string]$stamp.contentSha256 -cne (Get-ApkContentSha256 -Apk $Apk)) {
        return "$(Split-Path -Leaf $Apk) is not the APK that patch run produced"
    }
    $null
}
