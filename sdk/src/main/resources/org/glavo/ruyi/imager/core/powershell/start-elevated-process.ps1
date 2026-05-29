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
    $argumentText = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ArgumentsBase64))
    if ($argumentText.Length -eq 0) {
        $arguments = @()
    } else {
        $arguments = $argumentText.Split([char]0)
    }

    $startParameters = @{
        FilePath = $FilePath
        Verb = 'RunAs'
        Wait = $true
        PassThru = $true
        WindowStyle = 'Hidden'
    }
    if ($arguments.Count -gt 0) {
        $startParameters.ArgumentList = Join-ProcessArguments $arguments
    }

    $process = Start-Process @startParameters
    exit $process.ExitCode
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
