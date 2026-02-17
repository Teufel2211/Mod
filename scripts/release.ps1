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
        [string[]]$Arguments,
        [int]$Retries = 1,
        [int]$DelaySeconds = 4
    )
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        $gradleOut = @()
        & ./gradlew @Arguments 2>&1 | Tee-Object -Variable gradleOut
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) { $exitCode = 0 }
        $outText = ($gradleOut | ForEach-Object { $_.ToString() }) -join "`n"
        $isSuccessful = $outText -match "BUILD SUCCESSFUL"

        if ($exitCode -ne 0 -and $isSuccessful) {
            Write-Warning "Gradle returned exit $exitCode but reported BUILD SUCCESSFUL. Continuing."
            return
        }
        if ($exitCode -eq 0) {
            return
        }

        if ($attempt -lt $Retries) {
            Write-Warning "Gradle failed (attempt $attempt/$Retries). Retrying in $DelaySeconds sec..."
            Start-Sleep -Seconds $DelaySeconds
            continue
        }
        throw "Gradle command failed (exit $exitCode): ./gradlew $($Arguments -join ' ')"
    }
}

Write-Host "1) Bump version..."
Invoke-GradleChecked @("bumpModVersion")

Write-Host "2) Build artifact with new version..."
# Use remapJar to avoid plugin side effects that can hook upload tasks into build.
Invoke-GradleChecked @("clean", "remapJar", "sourcesJar", "-PreleaseType=$ReleaseType")

Write-Host "3) Generate release notes..."
Invoke-GradleChecked @("generateReleaseNotes", "-PreleaseType=$ReleaseType")

Write-Host "4) Upload to Modrinth/CurseForge (if configured)..."
$hasModrinth = ($env:MODRINTH_TOKEN -and $env:MODRINTH_PROJECT_ID)
$hasCurseforge = ($env:CURSEFORGE_TOKEN -and $env:CURSEFORGE_PROJECT_ID)

if ($hasModrinth) {
    Write-Host "4a) Upload Modrinth..."
    Invoke-GradleChecked @("modrinth", "-PreleaseType=$ReleaseType") -Retries 3 -DelaySeconds 5
} else {
    Write-Warning "Skipping Modrinth upload (missing token or project id)."
}

if ($hasCurseforge) {
    Write-Host "4b) Upload CurseForge..."
    Invoke-GradleChecked @("curseforge", "-PreleaseType=$ReleaseType") -Retries 3 -DelaySeconds 8
} else {
    Write-Warning "Skipping CurseForge upload (missing token or project id)."
}

Write-Host "Done."
