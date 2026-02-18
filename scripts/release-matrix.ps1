param(
    [string]$LaneFile = "scripts/minecraft-lanes.json",
    [ValidateSet("release", "beta", "alpha")]
    [string]$ReleaseType = "release",
    [switch]$ForceUpload,
    [switch]$SkipFailedLanes,
    [switch]$AutoResolveDeps,
    [switch]$StrictLaneComplete
)

$params = @{
    Matrix = $true
    LaneFile = $LaneFile
    ReleaseType = $ReleaseType
}
if ($ForceUpload) { $params.ForceUpload = $true }
if ($SkipFailedLanes) { $params.SkipFailedLanes = $true }
if ($AutoResolveDeps) { $params.AutoResolveDeps = $true }
if ($StrictLaneComplete) { $params.StrictLaneComplete = $true }
& "$PSScriptRoot/release.ps1" @params
