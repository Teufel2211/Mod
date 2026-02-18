param(
    [string]$LaneFile = "scripts/minecraft-lanes.json",
    [ValidateSet("release", "beta", "alpha")]
    [string]$ReleaseType = "release",
    [switch]$ForceUpload,
    [switch]$SkipFailedLanes,
    [switch]$AutoResolveDeps
)

$args = @("-Matrix", "-LaneFile", $LaneFile, "-ReleaseType", $ReleaseType)
if ($ForceUpload) { $args += "-ForceUpload" }
if ($SkipFailedLanes) { $args += "-SkipFailedLanes" }
if ($AutoResolveDeps) { $args += "-AutoResolveDeps" }
& "$PSScriptRoot/release.ps1" @args
