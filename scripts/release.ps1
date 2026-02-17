param(
    [ValidateSet("release", "beta", "alpha")]
    [string]$ReleaseType = "release"
)

$ErrorActionPreference = "Stop"

Write-Host "== Core Mod Release Pipeline =="

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$localConfig = Join-Path $scriptDir "release-config.ps1"
if (Test-Path $localConfig) {
    . $localConfig
    Write-Host "Loaded local release config: scripts/release-config.ps1"
} else {
    Write-Host "Tip: copy scripts/release-config.example.ps1 -> scripts/release-config.ps1 and fill your upload data."
}

if (-not $env:MODRINTH_TOKEN) {
    Write-Warning "MODRINTH_TOKEN is not set. Modrinth upload will be skipped."
}
if (-not $env:CURSEFORGE_TOKEN) {
    Write-Warning "CURSEFORGE_TOKEN is not set. CurseForge upload will be skipped."
}
if (-not $env:MODRINTH_PROJECT_ID) {
    Write-Warning "MODRINTH_PROJECT_ID is not set. Modrinth upload will be skipped."
}
if (-not $env:CURSEFORGE_PROJECT_ID) {
    Write-Warning "CURSEFORGE_PROJECT_ID is not set. CurseForge upload will be skipped."
}

function Invoke-GradleChecked {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )
    & ./gradlew @Arguments
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) { $exitCode = 0 }
    if ($exitCode -ne 0) {
        throw "Gradle command failed (exit $exitCode): ./gradlew $($Arguments -join ' ')"
    }
}

Write-Host "1) Bump version..."
Invoke-GradleChecked @("bumpModVersion")

Write-Host "2) Build with new version..."
Invoke-GradleChecked @("build", "-PreleaseType=$ReleaseType")

Write-Host "3) Generate release notes..."
Invoke-GradleChecked @("generateReleaseNotes", "-PreleaseType=$ReleaseType")

Write-Host "4) Upload to platforms (if tokens/project ids are set)..."
Invoke-GradleChecked @("releaseAll", "-PreleaseType=$ReleaseType")

Write-Host "Done."
