# Copyright (c) 2026 Glavo
# SPDX-License-Identifier: MPL-2.0

param(
    [Parameter(Mandatory = $true)]
    [int]$DiskNumber,

    [Parameter(Mandatory = $true)]
    [string]$OutputFile,

    [Parameter(Mandatory = $true)]
    [string]$ErrorFile
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$utf8NoBom = New-Object System.Text.UTF8Encoding -ArgumentList $false

function Set-TargetDiskOffline {
    param(
        [int]$DiskNumber
    )

    try {
        Set-Disk -Number $DiskNumber -IsOffline $true -ErrorAction Stop -Confirm:$false
    } catch {
        throw "Set-Disk failed to set disk $($DiskNumber) offline: $($_.Exception.Message)"
    }
}

try {
    $disk = Get-Disk -Number $DiskNumber
    if ($disk.IsBoot -or $disk.IsSystem) {
        throw 'Refusing to prepare a system disk.'
    }
    if ($disk.IsReadOnly) {
        throw 'Refusing to prepare a read-only disk.'
    }

    foreach ($partition in (Get-Partition -DiskNumber $DiskNumber)) {
        $removableAccessPaths = @()
        foreach ($accessPath in @($partition.AccessPaths)) {
            if ($accessPath) {
                if ($accessPath.StartsWith('\\?\Volume{', [System.StringComparison]::OrdinalIgnoreCase)) {
                    continue
                }
                $removableAccessPaths += $accessPath
            }
        }

        for ($index = 0; $index -lt $removableAccessPaths.Count; $index++) {
            $accessPath = $removableAccessPaths[$index]
            $mode = if ($index -eq ($removableAccessPaths.Count - 1)) { '/p' } else { '/d' }
            & "$env:SystemRoot\System32\mountvol.exe" $accessPath $mode
            if ($LASTEXITCODE -ne 0) {
                throw "mountvol $mode failed for access path $accessPath with exit code $LASTEXITCODE."
            }
        }
    }

    Set-TargetDiskOffline $DiskNumber

    [System.IO.File]::WriteAllText($OutputFile, 'prepared', $utf8NoBom)
    exit 0
} catch {
    [System.IO.File]::WriteAllText($ErrorFile, $_.Exception.Message, $utf8NoBom)
    exit 1
}
