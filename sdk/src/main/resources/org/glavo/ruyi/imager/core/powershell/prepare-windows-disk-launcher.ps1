# Copyright (c) 2026 Glavo
# SPDX-License-Identifier: MPL-2.0

param(
    [Parameter(Mandatory = $true)]
    [string]$PrepareScript,

    [Parameter(Mandatory = $true)]
    [int]$DiskNumber
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$outputFile = [System.IO.Path]::GetTempFileName()
$errorFile = [System.IO.Path]::GetTempFileName()

try {
    $process = Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoProfile',
        '-NonInteractive',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $PrepareScript,
        '-DiskNumber',
        [string]$DiskNumber,
        '-OutputFile',
        $outputFile,
        '-ErrorFile',
        $errorFile
    ) -Verb RunAs -Wait -PassThru -WindowStyle Hidden

    if (Test-Path -LiteralPath $outputFile) {
        $outputBytes = [System.IO.File]::ReadAllBytes($outputFile)
        $stdout = [Console]::OpenStandardOutput()
        $stdout.Write($outputBytes, 0, $outputBytes.Length)
        $stdout.Flush()
    }
    if (Test-Path -LiteralPath $errorFile) {
        $errorBytes = [System.IO.File]::ReadAllBytes($errorFile)
        $stderr = [Console]::OpenStandardError()
        $stderr.Write($errorBytes, 0, $errorBytes.Length)
        $stderr.Flush()
    }
    exit $process.ExitCode
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    Remove-Item -LiteralPath $outputFile, $errorFile -Force -ErrorAction SilentlyContinue
}
