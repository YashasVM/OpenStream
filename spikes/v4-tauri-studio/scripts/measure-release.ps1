param([int]$Launches = 5, [int]$IdleSeconds = 10, [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\results\local"))
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$studio = Join-Path $root "src-tauri\target\release\openstream-tauri-studio-spike.exe"
$service = Join-Path $root "src-tauri\target\release\openstream-tauri-pipe-service.exe"
if (!(Test-Path $studio) -or !(Test-Path $service)) { throw "Build both release binaries before measuring." }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$raw = Join-Path $OutputDirectory "measurements.jsonl"; Remove-Item -LiteralPath $raw -ErrorAction SilentlyContinue
Add-Type 'using System; using System.Runtime.InteropServices; public static class WindowProbe { [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h,int x,int y,int w,int z,bool r); }'
function Write-Row($value) { Add-Content -LiteralPath $raw -Value ($value | ConvertTo-Json -Compress -Depth 8) }
function Get-Tree([int]$RootPid) {
  $rows = @(Get-CimInstance Win32_Process | Select-Object ProcessId,ParentProcessId); $ids = [Collections.Generic.HashSet[int]]::new(); $ids.Add($RootPid) | Out-Null
  do { $before=$ids.Count; foreach($row in $rows){ if($ids.Contains([int]$row.ParentProcessId)){ $ids.Add([int]$row.ProcessId)|Out-Null } } } while($ids.Count-ne$before); @($ids)
}
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $pipe="\\.\pipe\openstream-v4-studio-spike-$sid"
for($trial=1;$trial-le$Launches;$trial++){
  $env:OPENSTREAM_TAURI_TEST_PIPE=$pipe; $env:OPENSTREAM_TAURI_TEST_NONCE=([guid]::NewGuid().ToString("N")+[guid]::NewGuid().ToString("N"))
  $stdout=Join-Path $OutputDirectory "service-$trial.out.txt"; $stderr=Join-Path $OutputDirectory "service-$trial.err.txt"
  $svc=Start-Process $service -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -WindowStyle Hidden
  $wait=[Diagnostics.Stopwatch]::StartNew(); while($wait.ElapsedMilliseconds-lt3000 -and !(Select-String -Quiet -LiteralPath $stdout -Pattern '^READY ' -ErrorAction SilentlyContinue)){Start-Sleep -Milliseconds 25}
  $clock=[Diagnostics.Stopwatch]::StartNew(); $app=Start-Process $studio -PassThru
  while($clock.ElapsedMilliseconds-lt10000){$app.Refresh();if($app.MainWindowHandle-ne0 -and $app.Responding){break};Start-Sleep -Milliseconds 10}; $ready=$clock.Elapsed.TotalMilliseconds
  if($app.MainWindowHandle-eq0){Stop-Process $app.Id -Force;throw "Studio did not expose a responsive window"}
  Wait-Process $svc.Id -Timeout 5 -ErrorAction SilentlyContinue; $svc.Refresh(); Start-Sleep -Seconds $IdleSeconds
  $tree=Get-Tree $app.Id; $before=Get-Process -Id $tree -ErrorAction SilentlyContinue; $cpu0=($before|Measure-Object CPU -Sum).Sum; Start-Sleep 2
  $after=Get-Process -Id $tree -ErrorAction SilentlyContinue; $cpu1=($after|Measure-Object CPU -Sum).Sum
  $resize=[Diagnostics.Stopwatch]::StartNew();foreach($size in @(@(900,620),@(1400,900),@(1180,760))){[WindowProbe]::MoveWindow($app.MainWindowHandle,40,40,$size[0],$size[1],$true)|Out-Null};$resize.Stop()
  Write-Row ([ordered]@{schemaVersion=1;trial=$trial;timestamp=(Get-Date).ToUniversalTime().ToString("o");startKind=if($trial-eq1){"cold-unprimed"}else{"warm"};launchResponsiveMs=[math]::Round($ready,2);pipeServiceExitCode=if($svc.HasExited){$svc.ExitCode}else{$null};processCount=$after.Count;privateBytes=($after|Measure-Object PrivateMemorySize64 -Sum).Sum;workingSetBytes=($after|Measure-Object WorkingSet64 -Sum).Sum;idleCpuPercent=[math]::Round((($cpu1-$cpu0)/2/[Environment]::ProcessorCount*100),3);resizeSequenceMs=[math]::Round($resize.Elapsed.TotalMilliseconds,2);pids=$tree})
  foreach($id in ($tree|Sort-Object -Descending)){Stop-Process $id -Force -ErrorAction SilentlyContinue};Stop-Process $svc.Id -Force -ErrorAction SilentlyContinue
}
$payload=(Get-Item $studio,$service|Measure-Object Length -Sum).Sum; $wv=(Get-ItemProperty 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\*' -ErrorAction SilentlyContinue|Where-Object name -Like '*WebView2*'|Select-Object -First 1)
Write-Row ([ordered]@{schemaVersion=1;kind="environment";commit=(git -C $root rev-parse HEAD);os=[Environment]::OSVersion.VersionString;logicalProcessors=[Environment]::ProcessorCount;rust=(rustc --version);node=(node --version);webView2Version=$wv.pv;webViewInstallMode="embedBootstrapper";releaseExecutableBytes=$payload;gpuCounter="NOT_RUN: no reliable process-attributed GPU counter in harness";osDpi="NOT_RUN: Windows scale change needs interactive sign-out";narrator="NOT_RUN: requires human judgment"})
Write-Output $raw
