param(
    [string]$OutputFile = "scripts/minecraft-lanes.json"
)

$versions = @(
    "1.21.10","1.21.9","1.21.8","1.21.7","1.21.6","1.21.5","1.21.4","1.21.3","1.21.2","1.21.1","1.21.0",
    "1.20.6","1.20.5","1.20.4","1.20.3","1.20.2","1.20.1",
    "1.19.4","1.19.3","1.19.2","1.19.1","1.19.0"
)

$lanes = foreach ($v in $versions) {
    [ordered]@{
        minecraft_version = $v
        yarn_mappings = ""
        fabric_loader_version = ""
        fabric_api_version = ""
        forge_version = ""
        neoforge_version = ""
        architectury_api_version = ""
    }
}

$json = [ordered]@{
    description = "Fill dependency versions per Minecraft version before running release-matrix.ps1"
    lanes = $lanes
} | ConvertTo-Json -Depth 6

Set-Content -Path $OutputFile -Value $json -Encoding UTF8
Write-Host "Generated lane file: $OutputFile"
