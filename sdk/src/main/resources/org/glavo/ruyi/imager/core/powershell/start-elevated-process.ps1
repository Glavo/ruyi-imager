# Copyright (c) 2026 Glavo
# SPDX-License-Identifier: MPL-2.0

param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath,

    [Parameter(Mandatory = $true)]
    [string]$ArgumentsBase64
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Join-NullSeparated {
    param(
        [string[]]$Arguments
    )

    return ($Arguments -join [char]0)
}

try {
    $argumentText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ArgumentsBase64))
    if ($argumentText.Length -eq 0) {
        $arguments = @()
    } else {
        $arguments = $argumentText.Split([char]0)
    }

    $payloadBase64 = [Convert]::ToBase64String(
        [System.Text.Encoding]::UTF8.GetBytes((Join-NullSeparated (@($FilePath) + $arguments))))
    $runner = @"
`$ErrorActionPreference = 'Stop'
try {
    `$payloadText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$payloadBase64'))
    `$parts = `$payloadText.Split([char]0)
    `$target = `$parts[0]
    if (`$parts.Length -gt 1) {
        `$targetArguments = `$parts[1..(`$parts.Length - 1)]
    } else {
        `$targetArguments = @()
    }
    & `$target @targetArguments
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
    exit $process.ExitCode
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
