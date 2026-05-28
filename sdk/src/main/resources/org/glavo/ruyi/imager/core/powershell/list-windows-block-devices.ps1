# Copyright (c) 2026 Glavo
# SPDX-License-Identifier: MPL-2.0

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$systemDrive = [System.Environment]::GetEnvironmentVariable('SystemDrive')
if ($systemDrive) {
    $systemDrive = $systemDrive.TrimEnd('\')
}

$storageByNumber = @{}
try {
    Get-CimInstance -Namespace root/Microsoft/Windows/Storage -ClassName MSFT_Disk |
        ForEach-Object { $storageByNumber[[int]$_.Number] = $_ }
} catch {
}

$items = @()
foreach ($disk in (Get-CimInstance -ClassName Win32_DiskDrive | Sort-Object Index)) {
    $letters = @()
    $mountPoints = @()
    try {
        foreach ($partition in (Get-CimAssociatedInstance -InputObject $disk -Association Win32_DiskDriveToDiskPartition)) {
            foreach ($logicalDisk in (Get-CimAssociatedInstance -InputObject $partition -Association Win32_LogicalDiskToPartition)) {
                if ($logicalDisk.DeviceID) {
                    $letter = [string]$logicalDisk.DeviceID
                    $letters += $letter
                    $mountPoints += ($letter.TrimEnd('\') + '\')
                }
            }
        }
    } catch {
    }

    $storage = $storageByNumber[[int]$disk.Index]
    $readOnly = $false
    if ($null -ne $storage -and $null -ne $storage.IsReadOnly) {
        $readOnly = [bool]$storage.IsReadOnly
    }

    $busType = [string]$disk.InterfaceType
    $mediaType = [string]$disk.MediaType
    $removable = ($busType -ieq 'USB') -or ($mediaType -match 'Removable')
    $mounted = $letters.Count -gt 0
    $system = $false
    foreach ($letter in $letters) {
        if ($systemDrive -and ($letter -ieq $systemDrive)) {
            $system = $true
        }
    }

    $items += [pscustomobject]@{
        index = [int]$disk.Index
        deviceId = [string]$disk.DeviceID
        model = [string]$disk.Model
        busType = $busType
        mediaType = $mediaType
        sizeBytes = [string]$disk.Size
        removable = [bool]$removable
        system = [bool]$system
        mounted = [bool]$mounted
        readOnly = [bool]$readOnly
        mountPoints = @($mountPoints)
    }
}

ConvertTo-Json -InputObject @($items) -Compress -Depth 4
