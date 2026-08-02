[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$CorpusRoot,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$MediaFoundationProbe = "$PSScriptRoot/../../out/gpu-build/Release/openstream_mf_gpu_probe.exe",
    [string]$FfmpegProbe = "$PSScriptRoot/../../out/gpu-build/Release/openstream_ffmpeg_d3d11_probe.exe",
    [UInt64]$AdapterLuid = 0,
    [ValidateRange(1,86400)][int]$DurationSeconds = 3600,
    [ValidateRange(0,3600)][int]$WarmupSeconds = 60,
    [ValidateRange(1,600)][int]$CorpusSeconds = 10,
    [switch]$GenerateCorpus,
    [switch]$AllowSoftwareFallback
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$workloads = Get-Content -Raw "$PSScriptRoot/workloads/v4-gpu-workloads.json" | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $CorpusRoot,$OutputDirectory | Out-Null

function Write-JsonLine([string]$Path, [hashtable]$Value) {
    ($Value | ConvertTo-Json -Compress -Depth 12) | Add-Content -Encoding utf8 -Path $Path
}

function Get-EnvironmentFingerprint {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $gpus = @(Get-CimInstance Win32_VideoController | ForEach-Object {
        @{ name=$_.Name; driver_version=$_.DriverVersion; adapter_ram_bytes=[UInt64]$_.AdapterRAM }
    })
    return @{
        os="$($os.Caption) $($os.Version) build $($os.BuildNumber)"
        cpu=$cpu.Name; logical_processors=[int]$cpu.NumberOfLogicalProcessors; gpus=$gpus
        power_plan=(powercfg /getactivescheme | Out-String).Trim()
        ffmpeg_version=((& ffmpeg -version 2>&1 | Select-Object -First 1) -join '')
        commit=(git -C $repo rev-parse HEAD)
    }
}

function Resolve-Inputs($workload) {
    $directory = Join-Path $CorpusRoot $workload.id
    if ($GenerateCorpus -and !(Test-Path $directory)) {
        & "$repo/tools/stream-simulator/generate-media.ps1" -OutputDirectory $directory `
          -Streams $workload.streams -Fps $workload.fps -Codec $workload.codec -Seconds $CorpusSeconds
        if ($LASTEXITCODE -ne 0) { throw "corpus generation failed for $($workload.id)" }
    }
    $inputs = @(Get-ChildItem $directory -Recurse -Filter '*.mkv' | Sort-Object FullName | Select-Object -First $workload.streams)
    if ($inputs.Count -ne $workload.streams) { throw "$($workload.id) requires $($workload.streams) checked workload inputs" }
    return $inputs
}

function Invoke-Probe([string]$Backend, [string]$Executable, $Workload, $Inputs, [string]$MetricsPath) {
    if (!(Test-Path $Executable)) { throw "$Backend probe not found: $Executable" }
    $stdout = Join-Path $OutputDirectory "$($Workload.id)-$Backend-summary.json"
    $stderr = Join-Path $OutputDirectory "$($Workload.id)-$Backend-stderr.log"
    $high = [UInt32]($AdapterLuid -shr 32); $low = [UInt32]($AdapterLuid -band 0xffffffffL)
    $luidText = ('0x{0:X}:0x{1:X}' -f $high,$low)
    $arguments = @('--adapter-luid', $(if ($Backend -eq 'ffmpeg-d3d11') {$luidText} else {$AdapterLuid}), '--realtime', '--duration-seconds', $DurationSeconds)
    foreach ($input in $Inputs) { $arguments += @('--input', $input.FullName) }
    if ($Backend -eq 'ffmpeg-d3d11') {
        $arguments += @('--metrics', (Join-Path $OutputDirectory "$($Workload.id)-ffmpeg-native.jsonl"), '--queue-capacity', 4)
    } elseif ($AllowSoftwareFallback) { $arguments += '--allow-software-fallback' }
    $process = Start-Process -FilePath $Executable -ArgumentList $arguments -PassThru -NoNewWindow `
      -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $startedAt = $process.StartTime
    $previousCpu = [TimeSpan]::Zero
    $previousAt = Get-Date
    while (!$process.HasExited) {
        Start-Sleep -Seconds 1
        $process.Refresh()
        if ($process.HasExited) { break }
        $now = Get-Date
        $cpuDelta = ($process.TotalProcessorTime - $previousCpu).TotalSeconds
        $wallDelta = ($now - $previousAt).TotalSeconds
        $cpuPercent = if ($wallDelta -gt 0) { 100 * $cpuDelta / $wallDelta / [Environment]::ProcessorCount } else { 0 }
        $gpu = 0.0
        try {
            $samples = (Get-Counter '\GPU Engine(*)\Utilization Percentage' -ErrorAction Stop).CounterSamples |
              Where-Object InstanceName -match "pid_$($process.Id)_"
            if ($samples) { $gpu = ($samples | Measure-Object CookedValue -Sum).Sum }
        } catch { }
        Write-JsonLine $MetricsPath @{
            schema_version=1; type='sample'; backend=$Backend; workload=$Workload.id
            elapsed_s=[math]::Round(($now - $startedAt).TotalSeconds,3)
            warmup=(($now - $startedAt).TotalSeconds -lt $WarmupSeconds)
            cpu_percent=[math]::Round($cpuPercent,3); private_mib=[math]::Round($process.PrivateMemorySize64 / 1MB,3)
            gpu_process_percent=[math]::Round($gpu,3); adapter_luid=$AdapterLuid
        }
        $previousCpu = $process.TotalProcessorTime; $previousAt = $now
        if (($now - $startedAt).TotalSeconds -gt ($DurationSeconds + 120)) {
            Stop-Process -Id $process.Id -Force
            throw "$Backend exceeded duration guard"
        }
    }
    $process.WaitForExit()
    $process.Refresh()
    $summary = Get-Content -Raw $stdout | ConvertFrom-Json
    if ($summary.result -ne 'PASS') { throw "$Backend failed for $($Workload.id); see $stderr" }
    Write-JsonLine $MetricsPath @{schema_version=1; type='summary'; backend=$Backend; workload=$Workload.id; probe=$summary}
}

$metricsPath = Join-Path $OutputDirectory 'metrics.jsonl'
if (Test-Path $metricsPath) { Remove-Item -LiteralPath $metricsPath }
Write-JsonLine $metricsPath @{schema_version=1; type='environment'; environment=(Get-EnvironmentFingerprint); adapter_luid=$AdapterLuid}

foreach ($workload in $workloads.workloads) {
    $inputs = Resolve-Inputs $workload
    $hashes = @($inputs | ForEach-Object { @{path=$_.FullName; sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} })
    Write-JsonLine $metricsPath @{
        schema_version=1; type='workload'; workload=$workload; corpus=$hashes
        duration_seconds=$DurationSeconds; warmup_seconds=$WarmupSeconds
        queues=@($workloads.decoded_preview_queue,$workloads.programme_queue,$workloads.ingress_queue_per_stream,$workloads.iso_queue_per_stream)
    }
    Invoke-Probe 'media-foundation' $MediaFoundationProbe $workload $inputs $metricsPath
    Invoke-Probe 'ffmpeg-d3d11' $FfmpegProbe $workload $inputs $metricsPath
}

& python "$PSScriptRoot/validate-results.py" $metricsPath
if ($LASTEXITCODE -ne 0) { throw 'GPU results failed validation' }
Write-Output "GPU benchmark results: $metricsPath"
