param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [ValidateSet(2,4)][int]$Streams = 4,
    [ValidateSet(30,60)][int]$Fps = 30,
    [ValidateSet('h264','hevc')][string]$Codec = 'h264',
    [int]$Seconds = 10,
    [int]$SegmentSeconds = 120,
    [switch]$AllowSoftwareFallback
)
$ErrorActionPreference = 'Stop'
if (($Streams -eq 4 -and $Fps -ne 30) -or ($Streams -eq 2 -and $Fps -ne 60)) { throw 'Use four@30 or two@60.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$hardware = if ($Codec -eq 'h264') { 'h264_nvenc' } else { 'hevc_nvenc' }
$software = if ($Codec -eq 'h264') { 'libx264' } else { 'libx265' }
$encoders = (& ffmpeg -hide_banner -encoders 2>&1) -join "`n"
$encoder = $hardware
if ($encoders -notmatch [regex]::Escape($hardware)) {
    if (!$AllowSoftwareFallback) { throw "$hardware is unavailable; pass -AllowSoftwareFallback to opt into $software." }
    Write-Warning "HARDWARE FALLBACK: using $software; exclude this run from hardware performance results."
    $encoder = $software
}
for ($i = 1; $i -le $Streams; $i++) {
    $cameraDirectory = Join-Path $OutputDirectory ("camera-{0:D2}" -f $i)
    New-Item -ItemType Directory -Force -Path $cameraDirectory | Out-Null
    $extension = if ($Codec -eq 'h264') { 'h264' } else { 'hevc' }
    $elementary = Join-Path $cameraDirectory "source.$extension"
    & ffmpeg -y -hide_banner -loglevel error -f lavfi -i "testsrc2=size=1920x1080:rate=$Fps" -t $Seconds -an -c:v $encoder -g $Fps -bf 0 -f $extension $elementary
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg encode failed for stream $i" }
    $pattern = Join-Path $cameraDirectory 'segment-%04d.mkv'
    & ffmpeg -y -hide_banner -loglevel error -r $Fps -i $elementary -map 0:v:0 -c copy -f segment -segment_time $SegmentSeconds -reset_timestamps 0 $pattern
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg compressed remux failed for stream $i" }
    Remove-Item -LiteralPath $elementary
}
Write-Output "Created and stream-copied $Streams $Codec sources with $encoder into segmented MKV in $OutputDirectory"
