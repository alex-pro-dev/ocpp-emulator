param([switch]$Check)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:Path
$launcherExit = 1

try {
    $candidates = @($env:JAVA_HOME)
    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCommand) {
        $candidates += Split-Path (Split-Path $javaCommand.Source -Parent) -Parent
    }

    $jdkRoots = @(
        "$env:USERPROFILE\.jdks",
        "$env:USERPROFILE\.gradle\jdks",
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\BellSoft"
    )
    foreach ($jdkRoot in $jdkRoots) {
        if (Test-Path -LiteralPath $jdkRoot -PathType Container) {
            $candidates += Get-ChildItem -LiteralPath $jdkRoot -Directory |
                Select-Object -ExpandProperty FullName
        }
    }

    $selectedJdk = $null
    foreach ($candidate in ($candidates | Where-Object { $_ } | Select-Object -Unique)) {
        $releaseFile = Join-Path $candidate 'release'
        if ((Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
            (Test-Path -LiteralPath $releaseFile)) {
            if (Select-String -LiteralPath $releaseFile -Pattern '^JAVA_VERSION="25(?:[.\-+" ])' -Quiet) {
                $selectedJdk = $candidate
                break
            }
        }
    }

    if (-not $selectedJdk) {
        throw 'JDK 25 was not found. Install JDK 25 into your .jdks folder or set JAVA_HOME to its installation directory.'
    }

    $env:JAVA_HOME = $selectedJdk
    $env:Path = "$selectedJdk\bin;$previousPath"
    Write-Host "Using JDK 25: $selectedJdk"
    Push-Location -LiteralPath $PSScriptRoot
    try {
        if ($Check) {
            & .\gradlew.bat --version
        } else {
            Write-Host 'Starting OCPP Emulator. The first Gradle build may take a few minutes.'
            & .\gradlew.bat :v16:run
        }
        $launcherExit = $LASTEXITCODE
        if ($launcherExit -ne 0) {
            Write-Host "Gradle failed (exit code $launcherExit). See the output above."
        }
    } finally {
        Pop-Location
    }
} catch {
    Write-Host "Unable to launch OCPP Emulator: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:Path = $previousPath
}

exit $launcherExit
