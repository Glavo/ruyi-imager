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

$volumeIoControlSource = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

public static class RuyiVolumeIoControl
{
    private const uint GenericRead = 0x80000000;
    private const uint GenericWrite = 0x40000000;
    private const uint FileShareRead = 0x00000001;
    private const uint FileShareWrite = 0x00000002;
    private const uint OpenExisting = 3;
    private const uint FsctlLockVolume = 0x00090018;
    private const uint FsctlDismountVolume = 0x00090020;
    private const uint FsctlUnlockVolume = 0x0009001c;

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern SafeFileHandle CreateFile(
        string fileName,
        uint desiredAccess,
        uint shareMode,
        IntPtr securityAttributes,
        uint creationDisposition,
        uint flagsAndAttributes,
        IntPtr templateFile);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DeviceIoControl(
        SafeFileHandle device,
        uint controlCode,
        IntPtr inBuffer,
        uint inBufferSize,
        IntPtr outBuffer,
        uint outBufferSize,
        out uint bytesReturned,
        IntPtr overlapped);

    public static void LockAndDismount(string volumePath)
    {
        string normalizedPath = NormalizeVolumePath(volumePath);
        using (SafeFileHandle handle = CreateFile(
            normalizedPath,
            GenericRead | GenericWrite,
            FileShareRead | FileShareWrite,
            IntPtr.Zero,
            OpenExisting,
            0,
            IntPtr.Zero))
        {
            if (handle.IsInvalid)
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Failed to open volume " + volumePath);
            }

            uint bytesReturned;
            if (!DeviceIoControl(
                handle,
                FsctlLockVolume,
                IntPtr.Zero,
                0,
                IntPtr.Zero,
                0,
                out bytesReturned,
                IntPtr.Zero))
            {
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Failed to lock volume " + volumePath);
            }

            try
            {
                if (!DeviceIoControl(
                    handle,
                    FsctlDismountVolume,
                    IntPtr.Zero,
                    0,
                    IntPtr.Zero,
                    0,
                    out bytesReturned,
                    IntPtr.Zero))
                {
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Failed to dismount volume " + volumePath);
                }
            }
            catch
            {
                DeviceIoControl(
                    handle,
                    FsctlUnlockVolume,
                    IntPtr.Zero,
                    0,
                    IntPtr.Zero,
                    0,
                    out bytesReturned,
                    IntPtr.Zero);
                throw;
            }
        }
    }

    private static string NormalizeVolumePath(string volumePath)
    {
        string path = volumePath.Trim();
        if (path.StartsWith(@"\\?\", StringComparison.OrdinalIgnoreCase))
        {
            while (path.EndsWith(@"\", StringComparison.Ordinal) && path.Length > 4)
            {
                path = path.Substring(0, path.Length - 1);
            }
            return path;
        }

        if (path.Length >= 2 && path[1] == ':')
        {
            return @"\\.\" + path.Substring(0, 2);
        }

        while (path.EndsWith(@"\", StringComparison.Ordinal) && path.Length > 0)
        {
            path = path.Substring(0, path.Length - 1);
        }
        return path;
    }
}
'@
Add-Type -TypeDefinition $volumeIoControlSource -Language CSharp

function Add-UniquePath {
    param(
        [System.Collections.ArrayList]$Paths,
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }
    if (-not $Paths.Contains($Path)) {
        [void]$Paths.Add($Path)
    }
}

function Dismount-VolumeAccessPath {
    param(
        [string]$AccessPath
    )

    try {
        [RuyiVolumeIoControl]::LockAndDismount($AccessPath)
    } catch {
        throw "Failed to lock and dismount volume $($AccessPath): $($_.Exception.Message)"
    }
}

try {
    $disk = Get-Disk -Number $DiskNumber -ErrorAction Stop
    if ($disk.IsBoot -or $disk.IsSystem) {
        throw 'Refusing to prepare a system disk.'
    }
    if ($disk.IsReadOnly) {
        throw 'Refusing to prepare a read-only disk.'
    }

    foreach ($partition in (Get-Partition -DiskNumber $DiskNumber -ErrorAction Stop)) {
        $volumeAccessPaths = [System.Collections.ArrayList]::new()
        $removableAccessPaths = @()
        foreach ($accessPath in @($partition.AccessPaths)) {
            if ($accessPath) {
                if ($accessPath.StartsWith('\\?\Volume{', [System.StringComparison]::OrdinalIgnoreCase)) {
                    Add-UniquePath $volumeAccessPaths $accessPath
                    continue
                }
                $removableAccessPaths += $accessPath
            }
        }

        foreach ($volumeAccessPath in $volumeAccessPaths) {
            Dismount-VolumeAccessPath $volumeAccessPath
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

    [System.IO.File]::WriteAllText($OutputFile, 'prepared', $utf8NoBom)
    exit 0
} catch {
    [System.IO.File]::WriteAllText($ErrorFile, $_.Exception.Message, $utf8NoBom)
    exit 1
}
