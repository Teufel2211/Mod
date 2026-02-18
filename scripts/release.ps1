param(
    [ValidateSet("release", "beta", "alpha")]
    [string]$ReleaseType = "release",
    [switch]$ForceUpload,
    [switch]$Matrix,
    [string]$LaneFile = "scripts/minecraft-lanes.json",
    [switch]$GenerateLaneTemplate,
    [switch]$SkipFailedLanes,
    [switch]$AutoResolveDeps,
    [switch]$StrictLaneComplete
)

$ErrorActionPreference = "Stop"

Write-Host "== Core Mod Release Pipeline =="

$multiRoot = "multiloader"
$multiGradleProps = Join-Path $multiRoot "gradle.properties"
$gradlePrefix = @("-p", $multiRoot)
$env:RELEASE_TYPE = $ReleaseType

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

# Single-project mode: if no loader-specific IDs are set, reuse one shared project ID.
if ($env:MODRINTH_PROJECT_ID) {
    if (-not $env:MODRINTH_PROJECT_ID_FABRIC) { $env:MODRINTH_PROJECT_ID_FABRIC = $env:MODRINTH_PROJECT_ID }
    if (-not $env:MODRINTH_PROJECT_ID_FORGE) { $env:MODRINTH_PROJECT_ID_FORGE = $env:MODRINTH_PROJECT_ID }
    if (-not $env:MODRINTH_PROJECT_ID_NEOFORGE) { $env:MODRINTH_PROJECT_ID_NEOFORGE = $env:MODRINTH_PROJECT_ID }
}
if ($env:CURSEFORGE_PROJECT_ID) {
    if (-not $env:CURSEFORGE_PROJECT_ID_FABRIC) { $env:CURSEFORGE_PROJECT_ID_FABRIC = $env:CURSEFORGE_PROJECT_ID }
    if (-not $env:CURSEFORGE_PROJECT_ID_FORGE) { $env:CURSEFORGE_PROJECT_ID_FORGE = $env:CURSEFORGE_PROJECT_ID }
    if (-not $env:CURSEFORGE_PROJECT_ID_NEOFORGE) { $env:CURSEFORGE_PROJECT_ID_NEOFORGE = $env:CURSEFORGE_PROJECT_ID }
}

function Get-ModVersion {
    $line = Get-Content $multiGradleProps | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
    if (-not $line) { return "unknown" }
    return ($line -split '=', 2)[1].Trim()
}

function Set-ModVersion {
    param([Parameter(Mandatory = $true)][string]$Version)
    $lines = Get-Content $multiGradleProps
    $updated = $false
    $newLines = $lines | ForEach-Object {
        if ($_ -match '^mod_version=') {
            $updated = $true
            "mod_version=$Version"
        } else {
            $_
        }
    }
    if (-not $updated) { $newLines += "mod_version=$Version" }
    Set-Content -Path $multiGradleProps -Value $newLines
}

function Set-GradleProperty {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    $lines = Get-Content $FilePath
    $updated = $false
    $newLines = $lines | ForEach-Object {
        if ($_ -match "^$([regex]::Escape($Key))=") {
            $updated = $true
            "$Key=$Value"
        } else {
            $_
        }
    }
    if (-not $updated) { $newLines += "$Key=$Value" }
    Set-Content -Path $FilePath -Value $newLines
}

function Get-GradleProperty {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Key
    )
    $line = Get-Content $FilePath | Where-Object { $_ -match "^$([regex]::Escape($Key))=" } | Select-Object -First 1
    if (-not $line) { return "" }
    return ($line -split "=", 2)[1].Trim()
}

function Invoke-JsonGet {
    param([Parameter(Mandatory = $true)][string]$Url)
    for ($i = 1; $i -le 3; $i++) {
        try {
            return Invoke-RestMethod -Method Get -Uri $Url -TimeoutSec 25 -Headers @{ "User-Agent" = "core-mod-release-script" }
        } catch {
            if ($i -eq 3) {
                Write-Warning "Auto-resolve request failed: $Url ($($_.Exception.Message))"
                return $null
            }
            Start-Sleep -Seconds 1
        }
    }
}

function Invoke-TextGet {
    param([Parameter(Mandatory = $true)][string]$Url)
    for ($i = 1; $i -le 3; $i++) {
        try {
            return Invoke-WebRequest -Method Get -Uri $Url -TimeoutSec 25 -Headers @{ "User-Agent" = "core-mod-release-script" }
        } catch {
            if ($i -eq 3) {
                Write-Warning "Auto-resolve request failed: $Url ($($_.Exception.Message))"
                return $null
            }
            Start-Sleep -Seconds 1
        }
    }
}

function Get-VersionWeight {
    param([Parameter(Mandatory = $true)][string]$Version)
    $parts = [regex]::Matches($Version, '\d+') | ForEach-Object { [int]$_.Value }
    if (-not $parts -or $parts.Count -eq 0) { return "0000000000" }
    return ($parts | Select-Object -First 6 | ForEach-Object { "{0:D4}" -f $_ }) -join ""
}

function Select-LatestVersion {
    param([Parameter(Mandatory = $true)][object[]]$Versions)
    $clean = $Versions | Where-Object { $_ -and -not [string]::IsNullOrWhiteSpace($_.ToString()) } | ForEach-Object { $_.ToString() } | Select-Object -Unique
    if (-not $clean -or $clean.Count -eq 0) { return "" }
    return ($clean | Sort-Object { Get-VersionWeight $_ } -Descending | Select-Object -First 1)
}

function Compare-MinecraftVersion {
    param(
        [Parameter(Mandatory = $true)][string]$A,
        [Parameter(Mandatory = $true)][string]$B
    )
    $ap = $A.Split(".") | ForEach-Object { [int]$_ }
    $bp = $B.Split(".") | ForEach-Object { [int]$_ }
    $max = [Math]::Max($ap.Count, $bp.Count)
    for ($i = 0; $i -lt $max; $i++) {
        $av = if ($i -lt $ap.Count) { $ap[$i] } else { 0 }
        $bv = if ($i -lt $bp.Count) { $bp[$i] } else { 0 }
        if ($av -lt $bv) { return -1 }
        if ($av -gt $bv) { return 1 }
    }
    return 0
}

function Get-ForgeVersionForMc {
    param([Parameter(Mandatory = $true)][string]$MinecraftVersion)
    $resp = Invoke-TextGet -Url "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    if (-not $resp) { return "" }
    try {
        [xml]$xml = $resp.Content
        $all = @($xml.metadata.versioning.versions.version | ForEach-Object { $_.ToString() })
        $prefix = "$MinecraftVersion-"
        $filtered = $all | Where-Object { $_ -like "$prefix*" }
        if (-not $filtered -or $filtered.Count -eq 0) { return "" }
        return Select-LatestVersion -Versions $filtered
    } catch {
        return ""
    }
}

function Get-NeoForgeVersionForMc {
    param([Parameter(Mandatory = $true)][string]$MinecraftVersion)
    $resp = Invoke-TextGet -Url "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml"
    if (-not $resp) { return "" }
    try {
        [xml]$xml = $resp.Content
        $all = @($xml.metadata.versioning.versions.version | ForEach-Object { $_.ToString() })
        if (-not $all -or $all.Count -eq 0) { return "" }
        $mcParts = $MinecraftVersion.Split(".")
        if ($mcParts.Count -lt 3) { return "" }
        $prefix = "$($mcParts[1]).$($mcParts[2])."
        $filtered = $all | Where-Object { $_ -like "$prefix*" }
        if ($filtered.Count -eq 0) { return "" }
        return Select-LatestVersion -Versions $filtered
    } catch {
        return ""
    }
}

function Get-ArchitecturyVersion {
    $resp = Invoke-TextGet -Url "https://maven.architectury.dev/dev/architectury/architectury/maven-metadata.xml"
    if (-not $resp) { return "" }
    try {
        [xml]$xml = $resp.Content
        $all = @($xml.metadata.versioning.versions.version | ForEach-Object { $_.ToString() }) | Where-Object { $_ -notmatch '-' }
        if (-not $all -or $all.Count -eq 0) { return "" }
        return Select-LatestVersion -Versions $all
    } catch {
        return ""
    }
}

function Get-YarnMappingsForMc {
    param([Parameter(Mandatory = $true)][string]$MinecraftVersion)
    $json = Invoke-JsonGet -Url "https://meta.fabricmc.net/v2/versions/yarn/$MinecraftVersion"
    if (-not $json) { return "" }
    $firstStable = $json | Where-Object { $_.stable -eq $true } | Select-Object -First 1
    if ($firstStable -and $firstStable.version) { return $firstStable.version.ToString() }
    $firstAny = $json | Select-Object -First 1
    return ($firstAny.version.ToString())
}

function Get-FabricLoaderVersion {
    $json = Invoke-JsonGet -Url "https://meta.fabricmc.net/v2/versions/loader"
    if (-not $json) { return "" }
    $firstStable = $json | Where-Object { $_.stable -eq $true } | Select-Object -First 1
    if ($firstStable -and $firstStable.version) { return $firstStable.version.ToString() }
    $firstAny = $json | Select-Object -First 1
    return ($firstAny.version.ToString())
}

function Get-FabricApiVersionForMc {
    param([Parameter(Mandatory = $true)][string]$MinecraftVersion)
    $resp = Invoke-TextGet -Url "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
    if (-not $resp) { return "" }
    try {
        [xml]$xml = $resp.Content
        $all = @($xml.metadata.versioning.versions.version | ForEach-Object { $_.ToString() })
        $filtered = $all | Where-Object { $_ -like "*+$MinecraftVersion" }
        if (-not $filtered -or $filtered.Count -eq 0) { return "" }
        return Select-LatestVersion -Versions $filtered
    } catch {
        return ""
    }
}

function Resolve-DependenciesForMc {
    param([Parameter(Mandatory = $true)][string]$MinecraftVersion)
    $resolved = [ordered]@{
        minecraft_version = $MinecraftVersion
        yarn_mappings = Get-YarnMappingsForMc -MinecraftVersion $MinecraftVersion
        fabric_loader_version = Get-FabricLoaderVersion
        fabric_api_version = Get-FabricApiVersionForMc -MinecraftVersion $MinecraftVersion
        forge_version = Get-ForgeVersionForMc -MinecraftVersion $MinecraftVersion
        neoforge_version = Get-NeoForgeVersionForMc -MinecraftVersion $MinecraftVersion
        architectury_api_version = Get-ArchitecturyVersion
    }

    # Toolchain guard: Forge lanes below 1.20.4 currently fail during setup
    # (missing bootstrap-dev artifact in current Loom/Forge combo).
    if ((Compare-MinecraftVersion -A $MinecraftVersion -B "1.20.4") -lt 0) {
        $resolved.forge_version = ""
    }

    return $resolved
}

function Has-RequiredLaneDeps {
    param([Parameter(Mandatory = $true)]$Deps)
    return -not (
        [string]::IsNullOrWhiteSpace($Deps.yarn_mappings) -or
        [string]::IsNullOrWhiteSpace($Deps.fabric_loader_version) -or
        [string]::IsNullOrWhiteSpace($Deps.fabric_api_version) -or
        [string]::IsNullOrWhiteSpace($Deps.forge_version) -or
        [string]::IsNullOrWhiteSpace($Deps.neoforge_version) -or
        [string]::IsNullOrWhiteSpace($Deps.architectury_api_version)
    )
}

function Get-MissingLaneDeps {
    param([Parameter(Mandatory = $true)]$Deps)
    $missing = @()
    if ([string]::IsNullOrWhiteSpace($Deps.yarn_mappings)) { $missing += "yarn_mappings" }
    if ([string]::IsNullOrWhiteSpace($Deps.fabric_loader_version)) { $missing += "fabric_loader_version" }
    if ([string]::IsNullOrWhiteSpace($Deps.fabric_api_version)) { $missing += "fabric_api_version" }
    if ([string]::IsNullOrWhiteSpace($Deps.forge_version)) { $missing += "forge_version" }
    if ([string]::IsNullOrWhiteSpace($Deps.neoforge_version)) { $missing += "neoforge_version" }
    if ([string]::IsNullOrWhiteSpace($Deps.architectury_api_version)) { $missing += "architectury_api_version" }
    return $missing
}

function Get-ReleaseStatePath {
    param(
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$Platform
    )
    $dir = Join-Path "." ".release-state"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    return Join-Path $dir "$Version.$Platform.done"
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
        & ./gradlew @gradlePrefix @Arguments 2>&1 | Tee-Object -Variable gradleOut
        $exitCode = $LASTEXITCODE
        if ($null -eq $exitCode) { $exitCode = 0 }
        $outText = ($gradleOut | ForEach-Object { $_.ToString() }) -join "`n"
        $isSuccessful = $outText -match "BUILD SUCCESSFUL"
        $isFailed = $outText -match "BUILD FAILED" -or $outText -match "FAILURE: Build failed"

        # Some Gradle/plugin combinations return non-zero even when the build is successful.
        # Treat it as success only when the log clearly reports success and no failure markers.
        if ($exitCode -ne 0 -and $isSuccessful -and -not $isFailed) {
            return $outText
        }
        if ($exitCode -eq 0) {
            return $outText
        }

        if ($attempt -lt $Retries) {
            Write-Warning "Gradle failed (attempt $attempt/$Retries). Retrying in $DelaySeconds sec..."
            Start-Sleep -Seconds $DelaySeconds
            continue
        }
        $tail = ($gradleOut | Select-Object -Last 60 | ForEach-Object { $_.ToString() }) -join "`n"
        throw "Gradle command failed (exit $exitCode): ./gradlew $($gradlePrefix + $Arguments -join ' ')`nLast output:`n$tail"
    }
}

$loaders = @("fabric", "forge", "neoforge")
$uploadRecords = New-Object System.Collections.Generic.List[object]

function Get-EnvByLoader {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("modrinth", "curseforge")] [string]$Platform,
        [Parameter(Mandatory = $true)][ValidateSet("fabric", "forge", "neoforge")] [string]$Loader
    )
    switch ("$Platform/$Loader") {
        "modrinth/fabric" { return $env:MODRINTH_PROJECT_ID_FABRIC }
        "modrinth/forge" { return $env:MODRINTH_PROJECT_ID_FORGE }
        "modrinth/neoforge" { return $env:MODRINTH_PROJECT_ID_NEOFORGE }
        "curseforge/fabric" { return $env:CURSEFORGE_PROJECT_ID_FABRIC }
        "curseforge/forge" { return $env:CURSEFORGE_PROJECT_ID_FORGE }
        "curseforge/neoforge" { return $env:CURSEFORGE_PROJECT_ID_NEOFORGE }
    }
}

function Remove-ModrinthVersion {
    param([Parameter(Mandatory = $true)][string]$VersionId)
    if (-not $env:MODRINTH_TOKEN) { return }
    try {
        Invoke-RestMethod -Method Delete `
            -Uri "https://api.modrinth.com/v2/version/$VersionId" `
            -Headers @{ Authorization = $env:MODRINTH_TOKEN; "User-Agent" = "core-mod-release-script" } `
            -TimeoutSec 30 | Out-Null
        Write-Host "Rollback: deleted Modrinth version $VersionId"
    } catch {
        Write-Warning "Rollback: failed to delete Modrinth version $VersionId ($($_.Exception.Message))"
    }
}

function Invoke-Rollback {
    param(
        [Parameter(Mandatory = $true)][string]$OriginalVersion,
        [Parameter(Mandatory = $false)][string]$CurrentVersion,
        [Parameter(Mandatory = $true)][bool]$VersionBumped,
        [int]$StartIndex = 0
    )
    Write-Warning "Release failed. Starting rollback..."
    $warnedCurseforgeManualDelete = $false

    if ($StartIndex -lt 0) { $StartIndex = 0 }
    if ($StartIndex -gt $uploadRecords.Count) { $StartIndex = $uploadRecords.Count }

    for ($i = $uploadRecords.Count - 1; $i -ge $StartIndex; $i--) {
        $rec = $uploadRecords[$i]
        if ($rec.Platform -eq "modrinth" -and $rec.VersionId) {
            Remove-ModrinthVersion -VersionId $rec.VersionId
        } elseif ($rec.Platform -eq "curseforge") {
            if (-not $warnedCurseforgeManualDelete) {
                Write-Warning "Rollback: CurseForge API delete is not supported by this script. Remove CurseForge uploads manually if needed."
                $warnedCurseforgeManualDelete = $true
            }
        }

        if ($rec.StatePath -and (Test-Path $rec.StatePath)) {
            Remove-Item -Force $rec.StatePath -ErrorAction SilentlyContinue
        }
    }

    if ($VersionBumped -and $OriginalVersion) {
        Set-ModVersion -Version $OriginalVersion
        Write-Host "Rollback: version reset to $OriginalVersion"
    }
}

function Invoke-UploadForSingleLoader {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("fabric", "forge", "neoforge")] [string]$Loader,
        [Parameter(Mandatory = $true)][ValidateSet("modrinth", "curseforge")] [string]$Platform
    )
    $state = Get-ReleaseStatePath -Version "$currentVersion-$Loader" -Platform $Platform
    if ((Test-Path $state) -and -not $ForceUpload) {
        Write-Warning "Skipping $Platform/${Loader}: already uploaded for version $currentVersion. Use -ForceUpload to override."
        return
    }
    Write-Host " - Upload $Platform ($Loader)..."
    $out = Invoke-GradleChecked -Arguments @(":${Loader}:$Platform") -Retries 3 -DelaySeconds 8
    Set-Content -Path $state -Value "$(Get-Date -Format o) releaseType=$ReleaseType"

    $versionId = ""
    if ($Platform -eq "modrinth") {
        $match = [regex]::Match([string]$out, "as version ID ([A-Za-z0-9]+)\.")
        if ($match.Success -and $match.Groups.Count -gt 1) {
            $versionId = $match.Groups[1].Value
        }
    }
    $uploadRecords.Add([PSCustomObject]@{
        Platform = $Platform
        Loader = $Loader
        ProjectId = (Get-EnvByLoader -Platform $Platform -Loader $Loader)
        Version = $currentVersion
        VersionId = $versionId
        StatePath = $state
    }) | Out-Null
}

function New-LaneTemplate {
    param([Parameter(Mandatory = $true)][string]$Path)
    $currentYarn = Get-GradleProperty -FilePath $multiGradleProps -Key "yarn_mappings"
    $currentFabricLoader = Get-GradleProperty -FilePath $multiGradleProps -Key "fabric_loader_version"
    $currentFabricApi = Get-GradleProperty -FilePath $multiGradleProps -Key "fabric_api_version"
    $currentForge = Get-GradleProperty -FilePath $multiGradleProps -Key "forge_version"
    $currentNeoForge = Get-GradleProperty -FilePath $multiGradleProps -Key "neoforge_version"
    $currentArchitectury = Get-GradleProperty -FilePath $multiGradleProps -Key "architectury_api_version"

    $versions = @(
        "1.21.11","1.21.10","1.21.9","1.21.8","1.21.7","1.21.6","1.21.5","1.21.4","1.21.3","1.21.2","1.21.1",
        "1.20.6","1.20.5","1.20.4","1.20.3","1.20.2","1.20.1",
        "1.19.4","1.19.3","1.19.2","1.19.1","1.19"
    )
    $lanes = foreach ($v in $versions) {
        [ordered]@{
            minecraft_version = $v
            yarn_mappings = $currentYarn
            fabric_loader_version = $currentFabricLoader
            fabric_api_version = $currentFabricApi
            forge_version = $currentForge
            neoforge_version = $currentNeoForge
            architectury_api_version = $currentArchitectury
        }
    }
    $obj = [ordered]@{
        description = "Fill dependency versions per Minecraft version before running -Matrix"
        lanes = $lanes
    }
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $obj | ConvertTo-Json -Depth 6 | Set-Content -Path $Path -Encoding UTF8
    Write-Host "Generated lane template: $Path"
}

function Invoke-SingleRelease {
    $script:originalVersion = Get-ModVersion
    $script:currentVersion = $script:originalVersion
    $script:versionBumped = $false
    $runUploadStartIndex = $uploadRecords.Count

    try {
        Write-Host "1) Preflight build (must pass before version bump/upload)..."
        [void](Invoke-GradleChecked -Arguments @(":common:build", ":fabric:build", ":forge:build", ":neoforge:build", "-x", "modrinth", "-x", "curseforge"))

        Write-Host "2) Bump version..."
        [void](Invoke-GradleChecked -Arguments @("bumpModVersion"))
        $script:versionBumped = $true
        $script:currentVersion = Get-ModVersion
        Write-Host "Current version: $script:currentVersion"

        Write-Host "3) Build artifact with bumped version..."
        [void](Invoke-GradleChecked -Arguments @(":common:build", ":fabric:remapJar", ":forge:remapJar", ":neoforge:remapJar", ":fabric:sourcesJar", ":forge:sourcesJar", ":neoforge:sourcesJar"))

        Write-Host "4) Generate release notes..."
        [void](Invoke-GradleChecked -Arguments @("generateReleaseNotes"))

        Write-Host "5) Upload to Modrinth/CurseForge (if configured)..."
        $hasModrinth = ($env:MODRINTH_TOKEN -and $env:MODRINTH_PROJECT_ID)
        $hasCurseforge = ($env:CURSEFORGE_TOKEN -and $env:CURSEFORGE_PROJECT_ID)

        foreach ($loader in $loaders) {
            Write-Host "5) Uploads for $loader on version $script:currentVersion..."
            if ($hasModrinth) {
                Invoke-UploadForSingleLoader -Loader $loader -Platform "modrinth"
            } else {
                Write-Warning "Skipping Modrinth upload (missing token or project id)."
            }
            if ($hasCurseforge) {
                Invoke-UploadForSingleLoader -Loader $loader -Platform "curseforge"
            } else {
                Write-Warning "Skipping CurseForge upload (missing token or project id)."
            }
        }

        if ($StrictLaneComplete) {
            foreach ($loader in $loaders) {
                if ($hasModrinth) {
                    $mState = Get-ReleaseStatePath -Version "$script:currentVersion-$loader" -Platform "modrinth"
                    if (-not (Test-Path $mState)) {
                        throw "Strict lane check failed: missing Modrinth state for $loader ($script:currentVersion)."
                    }
                }
                if ($hasCurseforge) {
                    $cState = Get-ReleaseStatePath -Version "$script:currentVersion-$loader" -Platform "curseforge"
                    if (-not (Test-Path $cState)) {
                        throw "Strict lane check failed: missing CurseForge state for $loader ($script:currentVersion)."
                    }
                }
            }
        }

        Write-Host "Done."
    } catch {
        Write-Warning ("Release error: " + $_.Exception.Message)
        Invoke-Rollback -OriginalVersion $script:originalVersion -CurrentVersion $script:currentVersion -VersionBumped $script:versionBumped -StartIndex $runUploadStartIndex
        throw
    }
}

if ($GenerateLaneTemplate) {
    New-LaneTemplate -Path $LaneFile
    if (-not $Matrix) {
        return
    }
}

if (-not $Matrix) {
    if ($AutoResolveDeps) {
        $mc = Get-GradleProperty -FilePath $multiGradleProps -Key "minecraft_version"
        if ($mc) {
            Write-Host "Auto-resolving dependencies for $mc ..."
            $d = Resolve-DependenciesForMc -MinecraftVersion $mc
            if (-not (Has-RequiredLaneDeps -Deps $d)) {
                throw "Auto-resolve failed for minecraft_version=$mc. Missing at least one dependency (yarn/fabric loader/fabric api/forge/neoforge/architectury)."
            }
            Set-GradleProperty -FilePath $multiGradleProps -Key "yarn_mappings" -Value $d.yarn_mappings
            Set-GradleProperty -FilePath $multiGradleProps -Key "fabric_loader_version" -Value $d.fabric_loader_version
            Set-GradleProperty -FilePath $multiGradleProps -Key "fabric_api_version" -Value $d.fabric_api_version
            Set-GradleProperty -FilePath $multiGradleProps -Key "forge_version" -Value $d.forge_version
            Set-GradleProperty -FilePath $multiGradleProps -Key "neoforge_version" -Value $d.neoforge_version
            Set-GradleProperty -FilePath $multiGradleProps -Key "architectury_api_version" -Value $d.architectury_api_version
        }
    }
    Invoke-SingleRelease
    return
}

if (-not (Test-Path $LaneFile)) {
    New-LaneTemplate -Path $LaneFile
    Write-Host "Lane file was missing and has been auto-generated with current dependency values."
}

$laneData = Get-Content -Raw $LaneFile | ConvertFrom-Json
if (-not $laneData.lanes -or $laneData.lanes.Count -eq 0) {
    throw "No lanes defined in $LaneFile"
}

$effectiveSkipFailedLanes = $SkipFailedLanes -or $AutoResolveDeps
$propsBackup = Get-Content -Raw $multiGradleProps
try {
    foreach ($lane in $laneData.lanes) {
        if (-not $lane.minecraft_version) { continue }
        Write-Host ""
        Write-Host "== Matrix lane: $($lane.minecraft_version) =="
        if ($AutoResolveDeps) {
            Write-Host "Auto-resolving dependencies for lane $($lane.minecraft_version) ..."
            $resolved = Resolve-DependenciesForMc -MinecraftVersion "$($lane.minecraft_version)"
            $lane.yarn_mappings = $resolved.yarn_mappings
            $lane.fabric_loader_version = $resolved.fabric_loader_version
            $lane.fabric_api_version = $resolved.fabric_api_version
            $lane.forge_version = $resolved.forge_version
            $lane.neoforge_version = $resolved.neoforge_version
            $lane.architectury_api_version = $resolved.architectury_api_version

            if (-not (Has-RequiredLaneDeps -Deps $lane)) {
                $missing = (Get-MissingLaneDeps -Deps $lane) -join ", "
                $msg = "Could not resolve full dependency set for $($lane.minecraft_version). Missing: $missing"
                if ($effectiveSkipFailedLanes) {
                    Write-Warning "$msg Skipping lane."
                    continue
                }
                throw $msg
            }
        }
        Set-GradleProperty -FilePath $multiGradleProps -Key "minecraft_version" -Value "$($lane.minecraft_version)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "yarn_mappings" -Value "$($lane.yarn_mappings)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "fabric_loader_version" -Value "$($lane.fabric_loader_version)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "fabric_api_version" -Value "$($lane.fabric_api_version)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "forge_version" -Value "$($lane.forge_version)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "neoforge_version" -Value "$($lane.neoforge_version)"
        Set-GradleProperty -FilePath $multiGradleProps -Key "architectury_api_version" -Value "$($lane.architectury_api_version)"
        try {
            Invoke-SingleRelease
        } catch {
            if ($effectiveSkipFailedLanes) {
                Write-Warning "Lane failed and was skipped: $($lane.minecraft_version). Reason: $($_.Exception.Message)"
                continue
            }
            throw
        }
    }
} finally {
    Set-Content -Path $multiGradleProps -Value $propsBackup
    Write-Host "Restored original $multiGradleProps"
}
