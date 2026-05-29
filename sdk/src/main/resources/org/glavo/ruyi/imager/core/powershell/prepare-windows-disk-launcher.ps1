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

function Quote-ProcessArgument {
    param(
        [AllowEmptyString()]
        [string]$Argument
    )

    if ($Argument.Length -gt 0 -and $Argument.IndexOfAny([char[]]@(' ', "`t", "`r", "`n", '"')) -lt 0) {
        return $Argument
    }

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $backslashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
            continue
        }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
            continue
        }
        if ($backslashes -gt 0) {
            [void]$builder.Append(('\' * $backslashes))
            $backslashes = 0
        }
        [void]$builder.Append($character)
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append(('\' * ($backslashes * 2)))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Join-ProcessArguments {
    param(
        [string[]]$Arguments
    )

    $quoted = @()
    foreach ($argument in $Arguments) {
        $quoted += Quote-ProcessArgument $argument
    }
    return ($quoted -join ' ')
}

try {
    $arguments = @(
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
    )

    $process = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList (Join-ProcessArguments $arguments) `
        -Verb RunAs `
        -Wait `
        -PassThru `
        -WindowStyle Hidden

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
