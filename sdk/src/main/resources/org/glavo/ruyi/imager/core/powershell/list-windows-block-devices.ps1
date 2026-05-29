# Copyright (c) 2026 Glavo
# SPDX-License-Identifier: MPL-2.0

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$systemDrive = [System.Environment]::GetEnvironmentVariable('SystemDrive')
if ($systemDrive) {
    $systemDrive = $systemDrive.TrimEnd('\')
}

function Add-UniqueMountPoint {
    param(
        [System.Collections.ArrayList]$MountPoints,
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }
    if ($Path.StartsWith('\\?\Volume{', [System.StringComparison]::OrdinalIgnoreCase)) {
        return
    }
    if (-not $MountPoints.Contains($Path)) {
        [void]$MountPoints.Add($Path)
    }
}

function Get-DiskMountPoints {
    param(
        [int]$DiskNumber,
        [Microsoft.Management.Infrastructure.CimInstance]$Disk
    )

    $mountPoints = [System.Collections.ArrayList]::new()
    try {
        foreach ($partition in (Get-Partition -DiskNumber $DiskNumber -ErrorAction Stop)) {
            foreach ($accessPath in @($partition.AccessPaths)) {
                Add-UniqueMountPoint $mountPoints ([string]$accessPath)
            }
        }
    } catch {
        try {
            foreach ($partition in (Get-CimAssociatedInstance -InputObject $Disk -Association Win32_DiskDriveToDiskPartition)) {
                foreach ($logicalDisk in (Get-CimAssociatedInstance -InputObject $partition -Association Win32_LogicalDiskToPartition)) {
                    if ($logicalDisk.DeviceID) {
                        Add-UniqueMountPoint $mountPoints ((([string]$logicalDisk.DeviceID).TrimEnd('\')) + '\')
                    }
                }
            }
        } catch {
        }
    }
    return $mountPoints.ToArray()
}

function Test-SystemMountPoint {
    param(
        [string[]]$MountPoints
    )

    if (-not $systemDrive) {
        return $false
    }
    foreach ($mountPoint in $MountPoints) {
        if ($mountPoint.TrimEnd('\') -ieq $systemDrive) {
            return $true
        }
    }
    return $false
}

function Join-HardwareIdentity {
    param(
        [Microsoft.Management.Infrastructure.CimInstance]$Disk,
        [object]$Storage
    )

    $parts = @()
    if ($Storage -and $Storage.UniqueId) {
        $parts += ('uniqueId=' + [string]$Storage.UniqueId)
    }
    if ($Disk.PNPDeviceID) {
        $parts += ('pnpDeviceId=' + [string]$Disk.PNPDeviceID)
    }
    if ($Disk.SerialNumber) {
        $parts += ('serialNumber=' + ([string]$Disk.SerialNumber).Trim())
    }
    if ($parts.Count -eq 0) {
        return $null
    }
    return ($parts -join '|')
}

$storageByNumber = @{}
try {
    Get-CimInstance -Namespace root/Microsoft/Windows/Storage -ClassName MSFT_Disk |
        ForEach-Object { $storageByNumber[[int]$_.Number] = $_ }
} catch {
}

$items = @()
foreach ($disk in (Get-CimInstance -ClassName Win32_DiskDrive | Sort-Object Index)) {
    $diskNumber = [int]$disk.Index
    $mountPoints = Get-DiskMountPoints $diskNumber $disk
    $storage = $storageByNumber[$diskNumber]
    $readOnly = $false
    if ($null -ne $storage -and $null -ne $storage.IsReadOnly) {
        $readOnly = [bool]$storage.IsReadOnly
    }

    $busType = [string]$disk.InterfaceType
    $mediaType = [string]$disk.MediaType
    $removable = ($busType -ieq 'USB') -or ($mediaType -match 'Removable')
    $mounted = $mountPoints.Count -gt 0
    $system = Test-SystemMountPoint $mountPoints
    $hardwareId = Join-HardwareIdentity $disk $storage

    $items += [pscustomobject]@{
        index = $diskNumber
        deviceId = [string]$disk.DeviceID
        model = [string]$disk.Model
        busType = $busType
        mediaType = $mediaType
        sizeBytes = [string]$disk.Size
        removable = [bool]$removable
        system = [bool]$system
        mounted = [bool]$mounted
        readOnly = [bool]$readOnly
        hardwareId = $hardwareId
        mountPoints = @($mountPoints)
    }
}

ConvertTo-Json -InputObject @($items) -Compress -Depth 4
