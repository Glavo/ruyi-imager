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

function Join-NullSeparated {
    param(
        [string[]]$Arguments
    )

    return ($Arguments -join [char]0)
}

try {
    $payloadBase64 = [Convert]::ToBase64String(
        [System.Text.Encoding]::UTF8.GetBytes((Join-NullSeparated @(
            $PrepareScript,
            [string]$DiskNumber,
            $outputFile,
            $errorFile
        ))))
    $runner = @"
`$ErrorActionPreference = 'Stop'
try {
    `$payloadText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$payloadBase64'))
    `$parts = `$payloadText.Split([char]0)
    & `$parts[0] -DiskNumber ([int]`$parts[1]) -OutputFile `$parts[2] -ErrorFile `$parts[3]
    if (`$null -ne `$global:LASTEXITCODE) {
        exit `$global:LASTEXITCODE
    }
    exit 0
} catch {
    [Console]::Error.WriteLine(`$_.Exception.Message)
    exit 1
}
"@
    $encodedCommand = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($runner))

    $process = Start-Process `
        -FilePath 'powershell.exe' `
        -ArgumentList @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encodedCommand) `
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
