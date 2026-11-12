# Verifies a Windows application image of PRISM by what it really does (ADR-0017). Windows PowerShell 5.1.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File verify-image.ps1 -Image <folder> -Results <folder>
#       -Environment host|clean [-ProbeJar <jar>] [-Culture tr-TR] [-Work <folder>] [-Scenarios a,b]
#
# Every check is PASS, FAIL, INFO, BLOCKED or NOT TESTED in results.tsv; a check that was not carried out is
# never counted as passed. An image is accepted only by a clean run in which every mandatory check of ADR-0017
# is PASS and nothing is FAIL, BLOCKED or NOT TESTED: exit 0 then, exit 1 otherwise, with the verdict in
# summary.txt. A host run can pass its own checks, and says so, but accepts nothing alone: it has a JDK beside
# it. Run it again whenever the JDK changes.
#
# It needs an idle desktop: it clicks through the real window, and each click first checks that PRISM is
# under the pointer. It starts, limits and kills only processes of the image and of its own test copies.
# Settings, logs and journals go to folders of its own (LOCALAPPDATA is redirected for each scenario). On
# -Environment host it opens no folder window and changes no Windows theme.
param(
  [Parameter(Mandatory)][string]$Image,
  [Parameter(Mandatory)][string]$Results,
  [Parameter(Mandatory)][ValidateSet('host', 'clean')][string]$Environment,
  [string]$ProbeJar = '',
  [string]$Culture = '',
  [string]$Work = '',
  [string[]]$Scenarios = @()
)
$ErrorActionPreference = 'Continue'
$Image = (Resolve-Path $Image).Path
if (-not $ProbeJar) { $ProbeJar = Join-Path $PSScriptRoot 'image-probe.jar' }
New-Item -ItemType Directory -Force $Results | Out-Null
$Results = (Resolve-Path $Results).Path
$Scenarios = @($Scenarios | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if (-not $Work) { $Work = Join-Path $Results 'work' }
New-Item -ItemType Directory -Force $Work | Out-Null
$tsv = Join-Path $Results 'results.tsv'
"scenario`tcheck`tresult`tdetail" | Set-Content $tsv -Encoding UTF8
$script:Current = 'setup'
$script:Outcomes = @{}
function Rep([string]$check, [string]$result, [string]$detail = '') {
  $clean = $detail -replace "[\r\n\t]+", ' '
  "$($script:Current)`t$check`t$result`t$clean" | Add-Content $tsv -Encoding UTF8
  $script:Outcomes["$($script:Current)/$check"] = $result
  Write-Host "$result $($script:Current)/$check $clean"
}
function Check([string]$check, [bool]$ok, [string]$detail = '') { if ($ok) { Rep $check 'PASS' $detail } else { Rep $check 'FAIL' $detail } }

Add-Type -AssemblyName System.Drawing, System.Windows.Forms, UIAutomationClient, UIAutomationTypes
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;using System.Collections.Generic;using System.Drawing;using System.Drawing.Imaging;using System.Runtime.InteropServices;using System.Text;
public static class W {
 public delegate bool P(IntPtr h, IntPtr l);
 [DllImport("user32")] public static extern bool EnumWindows(P p, IntPtr l);
 [DllImport("user32")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);
 [DllImport("user32",CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
 [DllImport("user32",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 [DllImport("user32")] public static extern bool IsWindowVisible(IntPtr h);
 [DllImport("user32")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
 [DllImport("user32")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
 [DllImport("user32")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32")] public static extern void mouse_event(uint f,int x,int y,int d,UIntPtr e);
 [DllImport("user32")] public static extern bool SetProcessDpiAwarenessContext(IntPtr c);
 [DllImport("user32")] public static extern IntPtr WindowFromPoint(POINT p);
 [DllImport("user32")] public static extern bool SetWindowPos(IntPtr h, IntPtr a, int x,int y,int cx,int cy,uint f);
 [DllImport("kernel32")] public static extern IntPtr OpenProcess(uint a, bool i, int pid);
 [DllImport("kernel32")] public static extern bool CloseHandle(IntPtr h);
 [DllImport("kernel32")] public static extern bool IsProcessInJob(IntPtr p, IntPtr j, out bool r);
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L,T,R,B; }
 [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X,Y; }
 public static string Text(IntPtr h){var s=new StringBuilder(512);GetWindowText(h,s,512);return s.ToString();}
 public static string Cls(IntPtr h){var s=new StringBuilder(256);GetClassName(h,s,256);return s.ToString();}
 public static List<IntPtr> ForPid(int pid){var o=new List<IntPtr>();EnumWindows((h,l)=>{int p;GetWindowThreadProcessId(h,out p);if(p==pid&&IsWindowVisible(h)&&Cls(h).StartsWith("Glass"))o.Add(h);return true;},IntPtr.Zero);return o;}
 public static string InJob(int pid){IntPtr h=OpenProcess(0x1000,false,pid);if(h==IntPtr.Zero)return "open failed";try{bool r;return IsProcessInJob(h,IntPtr.Zero,out r)?r.ToString():"query failed";}finally{CloseHandle(h);}}
 public static void Shot(IntPtr h,string f){RECT r;GetWindowRect(h,out r);int w=r.R-r.L,t=r.B-r.T;if(w<=0||t<=0)return;using(var b=new Bitmap(w,t)){using(var g=Graphics.FromImage(b)){IntPtr dc=g.GetHdc();PrintWindow(h,dc,2);g.ReleaseHdc(dc);}b.Save(f,ImageFormat.Png);}}
 public static double Brightness(IntPtr h){RECT r;GetWindowRect(h,out r);int w=r.R-r.L,t=r.B-r.T;using(var b=new Bitmap(w,t)){using(var g=Graphics.FromImage(b)){IntPtr dc=g.GetHdc();PrintWindow(h,dc,2);g.ReleaseHdc(dc);}double s=0;int n=0;for(int y=t/4;y<t*3/4;y+=9)for(int x=w/4;x<w*3/4;x+=9){s+=b.GetPixel(x,y).GetBrightness();n++;}return n==0?0:s/n;}}
 [DllImport("user32",CharSet=CharSet.Unicode)] public static extern IntPtr SendMessageTimeout(IntPtr h,uint m,IntPtr w,string l,uint f,uint t,out IntPtr r);
 public static void ColorSchemeChanged(){IntPtr r;SendMessageTimeout((IntPtr)0xFFFF,0x1A,IntPtr.Zero,"ImmersiveColorSet",2,3000,out r);}
 public static List<string> Cabinets(){var o=new List<string>();EnumWindows((h,l)=>{if(IsWindowVisible(h)&&(Cls(h)=="CabinetWClass"||Cls(h)=="ExploreWClass"))o.Add(Text(h));return true;},IntPtr.Zero);return o;}
 public static void CloseCabinets(){EnumWindows((h,l)=>{if(Cls(h)=="CabinetWClass"||Cls(h)=="ExploreWClass")PostMessage(h,0x10,IntPtr.Zero,IntPtr.Zero);return true;},IntPtr.Zero);}
 public static void Screen(string f,int w,int t){using(var b=new Bitmap(w,t)){using(var g=Graphics.FromImage(b)){g.CopyFromScreen(0,0,0,0,new Size(w,t));}b.Save(f,ImageFormat.Png);}}
 public static int PidAt(int x,int y){var q=new POINT();q.X=x;q.Y=y;int r;GetWindowThreadProcessId(WindowFromPoint(q),out r);return r;}
 public static bool RightClick(int want,int x,int y){if(PidAt(x,y)!=want)return false;SetCursorPos(x,y);mouse_event(8,0,0,0,UIntPtr.Zero);mouse_event(16,0,0,0,UIntPtr.Zero);return true;}
 public static bool LeftClick(int want,int x,int y){if(PidAt(x,y)!=want)return false;SetCursorPos(x,y);mouse_event(2,0,0,0,UIntPtr.Zero);mouse_event(4,0,0,0,UIntPtr.Zero);return true;}
 public static Bitmap Grab(IntPtr h){RECT r;GetWindowRect(h,out r);int w=r.R-r.L,t=r.B-r.T;var b=new Bitmap(Math.Max(w,1),Math.Max(t,1));using(var g=Graphics.FromImage(b)){IntPtr dc=g.GetHdc();PrintWindow(h,dc,2);g.ReleaseHdc(dc);}return b;}
 public static int Left(IntPtr h){RECT r;GetWindowRect(h,out r);return r.L;}
 public static int Top(IntPtr h){RECT r;GetWindowRect(h,out r);return r.T;}
 public static int BandDiff(Bitmap a,Bitmap b,int x,int y,int w,int t,int band){int n=0;for(int j=y-band;j<y+t+band;j++)for(int i=x-band;i<x+w+band;i++){bool inner=i>=x+band&&i<x+w-band&&j>=y+band&&j<y+t-band;if(inner||i<0||j<0||i>=a.Width||j>=a.Height||i>=b.Width||j>=b.Height)continue;if(a.GetPixel(i,j).ToArgb()!=b.GetPixel(i,j).ToArgb())n++;}return n;}
 public static bool InRing(int i,int j,int x,int y,int w,int t,int band){bool outer=i>=x-band&&i<x+w+band&&j>=y-band&&j<y+t+band;bool inner=i>=x+band&&i<x+w-band&&j>=y+band&&j<y+t-band;return outer&&!inner;}
 public static int BandDiffBesides(Bitmap a,Bitmap b,int x,int y,int w,int t,int band,int px,int py,int pw,int pt){int n=0;for(int j=y-band;j<y+t+band;j++)for(int i=x-band;i<x+w+band;i++){if(!InRing(i,j,x,y,w,t,band)||InRing(i,j,px,py,pw,pt,band)||i<0||j<0||i>=a.Width||j>=a.Height||i>=b.Width||j>=b.Height)continue;if(a.GetPixel(i,j).ToArgb()!=b.GetPixel(i,j).ToArgb())n++;}return n;}
 [DllImport("kernel32")] public static extern bool FreeConsole();
 [DllImport("user32")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
 public static void Key(byte vk){keybd_event(vk,0,0,UIntPtr.Zero);System.Threading.Thread.Sleep(40);keybd_event(vk,0,2,UIntPtr.Zero);}
 [DllImport("kernel32")] public static extern bool AttachConsole(int pid);
}
'@
[void][W]::SetProcessDpiAwarenessContext([IntPtr](-4))
if ($Culture) { Set-Culture $Culture }
$OriginalPath = $env:PATH; $OriginalJavaHome = $env:JAVA_HOME
Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue; Remove-Item Env:JDK_HOME -ErrorAction SilentlyContinue; Remove-Item Env:CLASSPATH -ErrorAction SilentlyContinue
$env:PATH = "$env:SystemRoot\System32;$env:SystemRoot"

$Title = 'PRISM ' + [char]0x2014 + ' Process & Resource Interactive System Manager'
$Dots = [string][char]0x2026
$uia = [System.Windows.Automation.AutomationElement]
$scope = [System.Windows.Automation.TreeScope]
$ct = [System.Windows.Automation.ControlType]
$PrismExe = Join-Path $Image 'PRISM.exe'
$DemoExe = Join-Path $Image 'prism-demo.exe'
$Copy = Join-Path $Work 'test-copy'
$script:Started = New-Object System.Collections.ArrayList

# ----- Windows and controls -----
function Named($root, [string]$name) { $root.FindFirst($scope::Descendants, (New-Object System.Windows.Automation.PropertyCondition($uia::NameProperty, $name))) }
function ByType($root, $type) { $root.FindFirst($scope::Descendants, (New-Object System.Windows.Automation.PropertyCondition($uia::ControlTypeProperty, $type))) }
function AllOfType($root, $type) { @($root.FindAll($scope::Descendants, (New-Object System.Windows.Automation.PropertyCondition($uia::ControlTypeProperty, $type)))) }
function Matching($root, [string]$regex) { foreach ($e in $root.FindAll($scope::Descendants, [System.Windows.Automation.Condition]::TrueCondition)) { if ($e.Current.Name -match $regex) { return $e } }; return $null }
function Invoke-El($el) { $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
function Toggle($el) { $p = $el.GetCurrentPattern([System.Windows.Automation.TogglePattern]::Pattern); if ($p.Current.ToggleState -ne 'On') { $p.Toggle() } }
function Pattern-Of($el, $pattern) { $o = $null; if ($el.TryGetCurrentPattern($pattern, [ref]$o)) { return $o }; return $null }
function Choose($el) {
  $s = Pattern-Of $el ([System.Windows.Automation.SelectionItemPattern]::Pattern)
  if ($s) { $s.Select(); return }
  $g = Pattern-Of $el ([System.Windows.Automation.TogglePattern]::Pattern)
  if ($g) { if ($g.Current.ToggleState -ne 'On') { $g.Toggle() }; return }
  Invoke-El $el
}
# The name of a radio button is the Text inside it; the control with the patterns is its parent.
function Radio($app, [string]$name) {
  $text = Named $app.Root $name
  if ($null -eq $text) { throw "no $name on the page" }
  if (Pattern-Of $text ([System.Windows.Automation.SelectionItemPattern]::Pattern)) { return $text }
  return [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($text)
}
function Describe($el) { "type=$($el.Current.ControlType.ProgrammaticName) patterns=$(($el.GetSupportedPatterns() | ForEach-Object { $_.ProgrammaticName }) -join ',')" }
function IsChosen($el) {
  $s = Pattern-Of $el ([System.Windows.Automation.SelectionItemPattern]::Pattern)
  if ($s) { return $s.Current.IsSelected }
  $g = Pattern-Of $el ([System.Windows.Automation.TogglePattern]::Pattern)
  if ($g) { return $g.Current.ToggleState -eq 'On' }
  throw "the state of [$($el.Current.Name)] cannot be read"
}
function Cells($r) { (($r.FindAll($scope::Children, [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { $_.Current.Name }) -join ' | ') }
function Dialogs($app) { @([W]::ForPid($app.Pid) | Where-Object { $_ -ne $app.H }) }
function Click-El($app, $el) {
  $r = $el.Current.BoundingRectangle
  [void][W]::SetForegroundWindow($app.H); Start-Sleep -Milliseconds 300
  return [W]::LeftClick($app.Pid, [int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2))
}
function Wait-Dialog($app, [int]$seconds = 10) {
  for ($i = 0; $i -lt ($seconds * 5); $i++) { $d = Dialogs $app; if ($d.Count) { return $d[0] }; Start-Sleep -Milliseconds 200 }
  return [IntPtr]::Zero
}
function Brightness-Of($app) { [math]::Round([W]::Brightness($app.H), 3) }
function Shot($h, [string]$name) { try { [W]::Shot($h, (Join-Path $Results ("$($script:Current)-$name.png"))) } catch { Write-Host "shot failed: $_" } }

# ----- Processes -----
function Threads([int]$id) {
  $p = Get-Process -Id $id -ErrorAction SilentlyContinue
  if (-not $p) { return [pscustomobject]@{ Alive = $false; All = 0; Suspended = 0 } }
  $all = 0; $s = 0
  foreach ($t in $p.Threads) { $all++; try { if ($t.ThreadState -eq 'Wait' -and $t.WaitReason -eq 'Suspended') { $s++ } } catch { } }
  return [pscustomobject]@{ Alive = $true; All = $all; Suspended = $s }
}
function CpuPercent([int]$id, [int]$seconds) {
  # Stops the scenario if the process is gone: a dead process must not read as one kept under its limit.
  $a = (Get-Process -Id $id -ErrorAction Stop).TotalProcessorTime.TotalMilliseconds; $t = [Diagnostics.Stopwatch]::StartNew(); Start-Sleep -Seconds $seconds
  $b = (Get-Process -Id $id -ErrorAction Stop).TotalProcessorTime.TotalMilliseconds
  return [math]::Round(100 * ($b - $a) / $t.ElapsedMilliseconds / [Environment]::ProcessorCount, 1)
}
# A cap has a floor too: a process stopped or starved is not one held at its limit.
function Near-Limit([double]$measured, [double]$most) { $measured -ge 2 -and $measured -le $most }
function Children([int]$parent) { @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parent" -ErrorAction SilentlyContinue) }
function CountByPath([string]$path) { @(Get-Process -ErrorAction SilentlyContinue | Where-Object { try { $_.Path -eq $path } catch { $false } }).Count }
function Lad([string]$name) { $d = Join-Path $Work "lad\$name"; New-Item -ItemType Directory -Force $d | Out-Null; return $d }
function Folder($lad, [string]$sub) { Join-Path $lad "Prism\SystemResourceManager\$sub" }
function Start-Prism([string]$lad, [string]$exe = $script:PrismExe, [switch]$NoWait) {
  $env:LOCALAPPDATA = $lad
  $p = Start-Process $exe -PassThru; $null = $p.Handle
  [void]$script:Started.Add($p)
  $h = [IntPtr]::Zero
  $watch = [Diagnostics.Stopwatch]::StartNew()
  while ($h -eq [IntPtr]::Zero -and $watch.Elapsed.TotalSeconds -lt 120 -and -not $p.HasExited) {
    foreach ($x in [W]::ForPid($p.Id)) { if ([W]::Text($x) -eq $Title) { $h = $x } }
    if ($h -eq [IntPtr]::Zero) { Start-Sleep -Milliseconds 100 }
  }
  if ($h -eq [IntPtr]::Zero) { throw "PRISM shows no window of its own process within 120 s (exited=$($p.HasExited))" }
  $app = [pscustomobject]@{ Proc = $p; Pid = $p.Id; H = $h; Root = $uia::FromHandle($h); Ms = $watch.ElapsedMilliseconds; Lad = $lad }
  if (-not $NoWait) {
    Start-Sleep -Seconds 5
    [void][W]::SetForegroundWindow($h)
    [void][W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x13)
  }
  return $app
}
function Close-Prism($app, [int]$withinMs = 15000) {
  $t = [Diagnostics.Stopwatch]::StartNew()
  [void][W]::PostMessage($app.H, 0x10, [IntPtr]::Zero, [IntPtr]::Zero)
  if ($app.Proc.WaitForExit($withinMs)) { return [pscustomobject]@{ Ended = $true; Code = $app.Proc.ExitCode; Ms = $t.ElapsedMilliseconds } }
  return [pscustomobject]@{ Ended = $false; Code = $null; Ms = $t.ElapsedMilliseconds }
}
function Start-Demo([string]$name, [string[]]$arguments, [string]$exe = $script:DemoExe) {
  $out = Join-Path $Results "$($script:Current)-$name-stdout.txt"
  $d = Start-Process $exe -ArgumentList $arguments -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError (Join-Path $Results "$($script:Current)-$name-stderr.txt")
  $null = $d.Handle
  [void]$script:Started.Add($d)
  Start-Sleep -Seconds 3
  $workPid = $d.Id
  $kids = @(Children $d.Id | Where-Object { $_.Name -like 'prism-*' })
  if ($kids.Count) { $workPid = [int]$kids[0].ProcessId; [void]$script:Started.Add((Get-Process -Id $workPid)) }
  return [pscustomobject]@{ Proc = $d; Pid = $workPid; Launcher = $d.Id; Out = $out; Children = $kids.Count }
}
function Stop-All {
  foreach ($p in $script:Started) { try { if (-not $p.HasExited) { $p.Kill() } } catch { } }
  $script:Started.Clear()
  foreach ($q in (Get-Process -ErrorAction SilentlyContinue | Where-Object { try { $_.Path -and ($_.Path.StartsWith($Image) -or $_.Path.StartsWith($Work)) } catch { $false } })) { try { $q.Kill() } catch { } }
  Start-Sleep -Milliseconds 500
}

# ----- Pages and limits -----
function Go($app, [string]$page) {
  # The navigation entries are toggle buttons, and an entry with a state badge is named "<page>, <state>"
  # (for example "OOM Guard, Warning"), so the entry is found by its pattern and not by the first element so named.
  $el = Nav-Entry $app $page
  Toggle $el; Start-Sleep -Seconds 1
}
function Row-Of($app, [int]$id) {
  Go $app 'Processes'
  (ByType $app.Root $ct::Edit).GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue("$id")
  for ($i = 0; $i -lt 50; $i++) {
    Start-Sleep -Milliseconds 300
    foreach ($r in (AllOfType $app.Root $ct::DataItem)) { if ((Cells $r) -match "(^| \| )$id( \| |$)") { Choose $r; Start-Sleep -Seconds 1; return $r } }
  }
  throw "no row for PID $id"
}
function Limit-Row($app, [int]$id) { foreach ($r in (AllOfType $app.Root $ct::DataItem)) { if ((Cells $r) -match "(^| \| )$id( \| |$)") { return $r } }; return $null }
function Manage($app, [int]$id) {
  $row = Row-Of $app $id
  [void][W]::SetForegroundWindow($app.H); Start-Sleep -Milliseconds 300
  $r = $row.Current.BoundingRectangle
  if (-not [W]::RightClick($app.Pid, [int]($r.X + 60), [int]($r.Y + $r.Height / 2))) { throw 'another program is under the pointer: no right click was made' }
  Start-Sleep -Seconds 1
  $item = $null
  foreach ($q in (Dialogs $app)) { $item = Named ($uia::FromHandle($q)) 'Manage'; if ($item) { break } }
  if ($null -eq $item) { throw 'the context menu has no Manage' }
  $m = $item.Current.BoundingRectangle
  if (-not [W]::LeftClick($app.Pid, [int]($m.X + $m.Width / 2), [int]($m.Y + $m.Height / 2))) { throw 'another program is under the pointer: no click was made' }
  Start-Sleep -Seconds 3
  Go $app 'Limits'; Start-Sleep -Seconds 1
  $l = Limit-Row $app $id
  if ($null -eq $l) { throw "no Limits row for PID $id" }
  Choose $l; Start-Sleep -Seconds 1
  return $l
}
# Opens the dialog of a limit by a real click (its action blocks in showAndWait), enters the value, sets it.
# Returns NONE, or the text of a dialog that followed, which is closed.
function Set-Limit($app, [string]$button, [string]$value) {
  $b = Named $app.Root ($button + $Dots)
  if ($null -eq $b -or -not $b.Current.IsEnabled) { throw "$button is not available: [$(if ($b) { $b.Current.HelpText })]" }
  if (-not (Click-El $app $b)) { throw 'another program is under the pointer: no click was made' }
  $dh = Wait-Dialog $app
  if ($dh -eq [IntPtr]::Zero) { throw 'no dialog' }
  $d = $uia::FromHandle($dh)
  (ByType $d $ct::Edit).GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue($value); Start-Sleep -Milliseconds 500
  Invoke-El (Named $d 'Set limit'); Start-Sleep -Seconds 4
  $more = Dialogs $app
  if ($more.Count -eq 0) { return 'NONE' }
  $texts = ((AllOfType ($uia::FromHandle($more[0])) $ct::Text | ForEach-Object { $_.Current.Name }) -join ' / ')
  [void][W]::PostMessage($more[0], 0x10, [IntPtr]::Zero, [IntPtr]::Zero); Start-Sleep -Seconds 2
  return $texts
}
# After a native refusal the page offers the emulated limit; it asks once more before it is used.
function Use-Emulated($app, [string]$percent) {
  [void](Set-Limit $app 'Set CPU limit' $percent)
  $b = Named $app.Root ('Use emulated limit' + $Dots)
  if ($null -eq $b -or -not $b.Current.IsEnabled) { throw 'the emulated limit is not offered' }
  if (-not (Click-El $app $b)) { throw 'another program is under the pointer: no click was made' }
  $dh = Wait-Dialog $app
  if ($dh -eq [IntPtr]::Zero) { throw 'the offer shows no dialog' }
  $d = $uia::FromHandle($dh)
  $texts = ((AllOfType $d $ct::Text | ForEach-Object { $_.Current.Name }) -join ' / ')
  Invoke-El (Named $d 'Use emulated limit'); Start-Sleep -Seconds 3
  return $texts
}
function Log-Text($lad) {
  $logs = Folder $lad 'logs'
  if (-not (Test-Path $logs)) { return '' }
  return ((Get-ChildItem $logs -File | Sort-Object LastWriteTime | ForEach-Object { Get-Content $_.FullName -Raw -Encoding UTF8 }) -join "`n")
}
function Keep-Log($lad, [string]$name) { $t = Log-Text $lad; if ($t) { Set-Content (Join-Path $Results "$($script:Current)-$name.log.txt") $t -Encoding UTF8 } }

# The entry of a page in the sidebar: the element of that name that toggles (a table may have the same name).
function Nav-Entry($app, [string]$page) {
  $toggles = $app.Root.FindAll($scope::Descendants, (New-Object System.Windows.Automation.PropertyCondition($uia::IsTogglePatternAvailableProperty, $true)))
  foreach ($e in $toggles) { if ($e.Current.Name -ceq $page) { return $e } }
  foreach ($e in $toggles) { if ($e.Current.Name.StartsWith("$page, ", [StringComparison]::Ordinal)) { return $e } }
  throw "no sidebar entry $page"
}
function Select-Radio($root, [string]$name) {
  $text = Named $root $name
  if ($null -eq $text) { throw "no $name" }
  $el = $text
  if (-not (Pattern-Of $el ([System.Windows.Automation.SelectionItemPattern]::Pattern))) { $el = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($text) }
  (Pattern-Of $el ([System.Windows.Automation.SelectionItemPattern]::Pattern)).Select()
}
# The element with the keyboard focus, from UI Automation's own focus: JavaFX gives its elements process id 0, so
# the window must be in front instead. A search of the window for HasKeyboardFocus is not used: the selected row
# of a table says it has the focus all the time, and a search finds it before the element that has it.
function Focused($app) {
  if ([W]::GetForegroundWindow() -ne $app.H) { return $null }
  try { $f = $uia::FocusedElement } catch { return $null }
  if ($null -eq $f -or ($f.Current.ProcessId -ne 0 -and $f.Current.ProcessId -ne $app.Pid)) { return $null }
  return $f
}
function Keys([string]$keys) { [System.Windows.Forms.SendKeys]::SendWait($keys); Start-Sleep -Milliseconds 400 }
# A key by its virtual-key code, for the keys SendKeys has no name for (the context-menu key, VK_APPS 0x5D).
function Key([byte]$vk) { [W]::Key($vk); Start-Sleep -Milliseconds 600 }
function Open-Menu($app) { foreach ($q in (Dialogs $app)) { $m = $uia::FromHandle($q); if (Named $m 'Add to OOM management') { return $m } }; return $null }
function Menu-Item-With-Focus($menu) { if ($null -eq $menu) { return '' }; (@(AllOfType $menu $ct::MenuItem | Where-Object { $_.Current.HasKeyboardFocus } | ForEach-Object { $_.Current.Name })) -join ',' }
# The demo's row is selected and gets the keyboard focus by a click on it; the menu is then opened from the keyboard,
# with the context-menu key or with Shift+F10.
function Menu-By-Keyboard($app, [int]$id, [string]$how = 'apps') {
  $row = Row-Of $app $id
  $r = $row.Current.BoundingRectangle
  [void][W]::SetForegroundWindow($app.H); Start-Sleep -Milliseconds 300
  if (-not [W]::LeftClick($app.Pid, [int]($r.X + 60), [int]($r.Y + $r.Height / 2))) { throw 'another program is under the pointer: no click was made' }
  Start-Sleep -Milliseconds 500
  if ($how -eq 'apps') { Key 0x5D } else { Keys '+{F10}'; Start-Sleep -Milliseconds 600 }
  return Open-Menu $app
}
# OOM management with the emergency action Suspend for the demo, Suspend at Emergency on, and thresholds above
# what is available, so that EMERGENCY begins without any memory being used. Only managed processes are acted on.
function Oom-Suspend-Demo($app, [int]$id) {
  $menu = Menu-By-Keyboard $app $id
  if ($null -eq $menu) { throw 'Shift+F10 opened no menu with Add to OOM management' }
  $add = Named $menu 'Add to OOM management'
  $m = $add.Current.BoundingRectangle
  if (-not [W]::LeftClick($app.Pid, [int]($m.X + $m.Width / 2), [int]($m.Y + $m.Height / 2))) { throw 'another program is under the pointer: no click was made' }
  Start-Sleep -Seconds 3
  Go $app 'OOM Guard'
  $row = $null
  for ($i = 0; $i -lt 20 -and $null -eq $row; $i++) { $row = Limit-Row $app $id; if ($null -eq $row) { Start-Sleep -Milliseconds 500 } }
  if ($null -eq $row) { throw "no OOM Guard row for PID $id" }
  Choose $row; Start-Sleep -Seconds 1
  if (-not (Click-El $app (Named $app.Root ('OOM management' + $Dots)))) { throw 'another program is under the pointer: no click was made' }
  $dh = Wait-Dialog $app
  if ($dh -eq [IntPtr]::Zero) { throw 'no OOM management dialog' }
  $d = $uia::FromHandle($dh)
  Select-Radio $d 'Suspend'; Start-Sleep -Milliseconds 300
  Invoke-El (Named $d 'Set'); Start-Sleep -Seconds 2
  Toggle (Named $app.Root 'Suspend at Emergency'); Start-Sleep -Seconds 1
  if (-not (Click-El $app (Named $app.Root 'Change the thresholds'))) { throw 'another program is under the pointer: no click was made' }
  $th = Wait-Dialog $app
  if ($th -eq [IntPtr]::Zero) { throw 'no thresholds dialog' }
  $d = $uia::FromHandle($th)
  # Warning, Critical, Emergency and the floor, in this order on the dialog.
  $fields = AllOfType $d $ct::Edit
  if ($fields.Count -lt 3) { throw "the thresholds dialog has $($fields.Count) fields" }
  $fields[0].GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue('90')
  $fields[1].GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue('89')
  $fields[2].GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern).SetValue('88')
  Start-Sleep -Milliseconds 300
  Invoke-El (Named $d 'Set thresholds'); Start-Sleep -Seconds 1
  $held = $false
  for ($i = 0; $i -lt 60 -and -not $held; $i++) { $tt = Threads $id; $held = $tt.All -gt 0 -and $tt.Suspended -eq $tt.All; if (-not $held) { Start-Sleep -Milliseconds 500 } }
  return $held
}
function Available-Percent { $os = Get-CimInstance Win32_OperatingSystem; [math]::Round(100.0 * $os.FreePhysicalMemory / $os.TotalVisibleMemorySize, 1) }
# A console of its own for a launcher, so that its console can be closed, or Ctrl+C sent to it, without touching this one.
function Console-Helper {
  $helper = Join-Path $Work 'console-helper.ps1'
  @'
param($Exe, $ArgumentFile, $Lad, $PidFile, $GoFile, $ClassFile, $Mode)
$Arguments = if ($ArgumentFile -and (Test-Path $ArgumentFile)) { (Get-Content $ArgumentFile -Raw).Trim() } else { '' }
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;using System.Text;
public static class C {
 [DllImport("kernel32")] public static extern bool FreeConsole();
 [DllImport("kernel32")] public static extern bool AllocConsole();
 [DllImport("kernel32")] public static extern IntPtr GetConsoleWindow();
 [DllImport("kernel32")] public static extern bool SetConsoleCtrlHandler(IntPtr h, bool add);
 [DllImport("kernel32")] public static extern bool GenerateConsoleCtrlEvent(uint e, uint g);
 [DllImport("user32")] public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
 [DllImport("user32",CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
 public static string Cls(IntPtr h){var s=new StringBuilder(256);GetClassName(h,s,256);return s.ToString();}
}
"@
[void][C]::FreeConsole(); [void][C]::AllocConsole(); [void][C]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)
$i = New-Object System.Diagnostics.ProcessStartInfo; $i.FileName = $Exe; $i.Arguments = $Arguments; $i.UseShellExecute = $false
if ($Lad -and $Lad -ne 'none') { $i.EnvironmentVariables['LOCALAPPDATA'] = $Lad }
$p = [System.Diagnostics.Process]::Start($i)
"$($p.Id)" | Set-Content $PidFile
[C]::Cls([C]::GetConsoleWindow()) | Set-Content $ClassFile
while (-not (Test-Path $GoFile)) { Start-Sleep -Milliseconds 100 }
if ($Mode -eq 'close') { [void][C]::PostMessage([C]::GetConsoleWindow(), 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
else { [void][C]::SetConsoleCtrlHandler([IntPtr]::Zero, $true); [void][C]::GenerateConsoleCtrlEvent(0, 0) }
Start-Sleep -Seconds 60
'@ | Set-Content $helper -Encoding ASCII
  return $helper
}
function Start-In-Console([string]$name, [string]$exe, [string]$arguments, [string]$lad, [string]$mode) {
  $helper = Console-Helper
  $f = @{ Pid = (Join-Path $Work "$name.pid"); Go = (Join-Path $Work "$name.go"); Class = (Join-Path $Work "$name.class"); Args = (Join-Path $Work "$name.args") }
  Remove-Item $f.Pid, $f.Go, $f.Class -ErrorAction SilentlyContinue
  Set-Content $f.Args $arguments -Encoding ASCII
  $hp = Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", "`"$exe`"", "`"$($f.Args)`"", "`"$lad`"", "`"$($f.Pid)`"", "`"$($f.Go)`"", "`"$($f.Class)`"", $mode -PassThru -WindowStyle Hidden
  [void]$script:Started.Add($hp)
  for ($i = 0; $i -lt 300 -and -not (Test-Path $f.Pid); $i++) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path $f.Pid)) { return $null }
  $id = [int](Get-Content $f.Pid)
  $p = Get-Process -Id $id -ErrorAction SilentlyContinue
  if ($p) { $null = $p.Handle; [void]$script:Started.Add($p) }
  Start-Sleep -Milliseconds 500
  return [pscustomobject]@{ Pid = $id; Proc = $p; Go = $f.Go; Class = $(if (Test-Path $f.Class) { (Get-Content $f.Class) -join '' } else { '' }) }
}
function Attach-Window($id, [string]$lad) {
  $h = [IntPtr]::Zero
  for ($i = 0; $i -lt 1200 -and $h -eq [IntPtr]::Zero; $i++) { foreach ($x in [W]::ForPid($id)) { if ([W]::Text($x) -eq $Title) { $h = $x } }; Start-Sleep -Milliseconds 100 }
  if ($h -eq [IntPtr]::Zero) { return $null }
  Start-Sleep -Seconds 5
  [void][W]::SetForegroundWindow($h); [void][W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x13)
  return [pscustomobject]@{ Proc = (Get-Process -Id $id); Pid = $id; H = $h; Root = $uia::FromHandle($h); Ms = 0; Lad = $lad }
}

# ----- Test copies: never delivered, and never what the mandatory checks of the launcher look at -----
function Test-Launcher([string]$name, [string]$fromCfg, [string]$extra = '', [string]$mainClass = '', [switch]$NoNoRestart, [switch]$Probe) {
  if (-not (Test-Path $Copy)) { Copy-Item $Image $Copy -Recurse }
  $exe = Join-Path $Copy "$name.exe"
  if (-not (Test-Path $exe)) {
    Copy-Item (Join-Path $Copy 'prism-demo.exe') $exe
    $text = Get-Content (Join-Path $Copy "app\$fromCfg") -Raw
    if ($NoNoRestart) { $text = $text -replace "win\.norestart=true\r?\n", '' }
    if ($mainClass) { $text = $text -replace 'app\.mainclass=.*', "app.mainclass=$mainClass" }
    if ($Probe) {
      Copy-Item $ProbeJar (Join-Path $Copy 'app\image-probe.jar')
      $text = $text -replace "\[JavaOptions\]", "app.classpath=`$APPDIR\image-probe.jar`r`n`r`n[JavaOptions]`r`njava-options=-Dstdout.encoding=UTF-8`r`njava-options=-Xmx64m"
    }
    Set-Content (Join-Path $Copy "app\$name.cfg") $text -Encoding ASCII
  }
  return $exe
}

# ----- Scenarios -----
function Scenario([string]$name, [scriptblock]$body) {
  if ($Scenarios.Count -and $Scenarios -notcontains $name) { return }
  $script:Current = $name
  Write-Host "=== $name"
  try { & $body } catch { Rep 'scenario' 'FAIL' "stopped by: $_" }
  Stop-All
}

Scenario 'environment' {
  $os = Get-CimInstance Win32_OperatingSystem
  Rep 'os' 'INFO' "$($os.Caption) build $($os.BuildNumber) culture=$((Get-Culture).Name) processors=$([Environment]::ProcessorCount) environment=$Environment"
  # Read from what the machine offered before this script cleared PATH and JAVA_HOME for its children.
  $onPath = @($OriginalPath -split ';' | Where-Object { $_ } | ForEach-Object { $d = $_; foreach ($n in 'java.exe', 'javac.exe') { try { $f = Join-Path $d $n; if (Test-Path -LiteralPath $f) { $f } } catch { } } })
  $reg = @('HKLM:\SOFTWARE\JavaSoft', 'HKCU:\SOFTWARE\JavaSoft', 'HKLM:\SOFTWARE\Eclipse Adoptium', 'HKLM:\SOFTWARE\Eclipse Foundation', 'HKLM:\SOFTWARE\Microsoft\JDK', 'HKLM:\SOFTWARE\Azul Systems', 'HKLM:\SOFTWARE\Amazon Corretto', 'HKLM:\SOFTWARE\BellSoft') | Where-Object { Test-Path $_ }
  $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, "$env:USERPROFILE\.jdks", "$env:LOCALAPPDATA\Programs") | Where-Object { $_ -and (Test-Path $_) }
  $dirs = @($roots | ForEach-Object { Resolve-Path "$_\*\bin\java.exe", "$_\*\*\bin\java.exe" -ErrorAction SilentlyContinue })
  $none = $onPath.Count -eq 0 -and @($reg).Count -eq 0 -and $dirs.Count -eq 0 -and -not $OriginalJavaHome
  $detail = "java on the original PATH=$($onPath.Count) JAVA_HOME set=$([bool]$OriginalJavaHome) registry keys=$(@($reg).Count) java.exe under the program folders=$($dirs.Count)"
  if ($Environment -eq 'clean') { Check 'no-jdk' $none $detail } else { Rep 'no-jdk' 'INFO' "host, JDK present is expected: $detail" }
  Check 'image-exists' (Test-Path $PrismExe) ("{0:N1} MiB" -f ((Get-ChildItem $Image -Recurse -File | Measure-Object Length -Sum).Sum / 1MB))
  foreach ($cfg in 'PRISM.cfg', 'prism-demo.cfg') { Check "cfg-$cfg" ((Get-Content (Join-Path $Image "app\$cfg") -Raw) -match 'win\.norestart=true') 'names win.norestart=true' }
  Rep 'runtime-java-exe' 'INFO' "runtime\bin\java.exe exists=$(Test-Path (Join-Path $Image 'runtime\bin\java.exe'))"
  $release = Get-Content (Join-Path $Image 'runtime\release') -Raw
  Check 'runtime-has-localedata' ($release -match 'jdk\.localedata') 'runtime\release MODULES'
  $help = Join-Path $Results 'demo-help.txt'
  $h = Start-Process $DemoExe -ArgumentList '--help' -PassThru -Wait -NoNewWindow -RedirectStandardOutput $help -RedirectStandardError (Join-Path $Results 'demo-help.err')
  Check 'demo-help' ($h.ExitCode -eq 0 -and (Get-Content $help -Raw) -match 'duration') "exit=$($h.ExitCode)"
  $short = Join-Path $Results 'demo-short.txt'
  $s = Start-Process $DemoExe -ArgumentList '--cpu', '1', '--duration', '1s' -PassThru -Wait -NoNewWindow -RedirectStandardOutput $short -RedirectStandardError (Join-Path $Results 'demo-short.err')
  $first = (Get-Content $short -TotalCount 1)
  Check 'demo-version' ($first -match '^prism-demo \d') "first line [$first], exit=$($s.ExitCode)"
}

# What the runtime provides and SAFE-2 as a result, with no button in between.
Scenario 'probe' {
  if (-not (Test-Path $ProbeJar)) { Rep 'probe' 'BLOCKED' "no probe jar at $ProbeJar"; return }
  $exe = Test-Launcher 'prism-probe' 'prism-demo.cfg' -mainClass 'prism.srm.app.ImageProbe' -Probe
  $env:LOCALAPPDATA = Lad 'probe'
  $out = Join-Path $Results 'probe-stdout.txt'
  $p = Start-Process $exe -ArgumentList 'check' -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError (Join-Path $Results 'probe-stderr.txt')
  $null = $p.Handle; [void]$script:Started.Add($p)
  $ended = $p.WaitForExit(180000)
  $v = @{}
  foreach ($l in (Get-Content $out -Encoding UTF8)) { $i = $l.IndexOf('='); if ($i -gt 0) { $v[$l.Substring(0, $i)] = $l.Substring($i + 1) } }
  Check 'ended' ($ended -and $p.ExitCode -eq 0) "ended=$ended exit=$(if ($ended) { $p.ExitCode }) last line [$((Get-Content $out -Encoding UTF8 | Select-Object -Last 1))]"
  Check 'localedata-module' ($v['module.jdk.localedata'] -eq 'true') "$($v['modules']) modules"
  Check 'turkish-number' ($v['tr.number'] -eq '1.234.567,5') $v['tr.number']
  Check 'turkish-percent' ($v['tr.percent'] -match '^%\s?25$') $v['tr.percent']
  Check 'turkish-date' ($v['tr.date'] -match '^15 Ocak 2026') $v['tr.date']
  Check 'turkish-upper-i' ($v['tr.upper-i'] -eq [string][char]0x130) $v['tr.upper-i']
  Rep 'product-formatter' 'INFO' $v['tr.product-bytes']
  Check 'javafx-toolkit' ($v['javafx.toolkit'] -eq 'true') 'Platform.startup returned on the image runtime'
  Check 'logback-service' ($v['logging.factory'] -match 'logback') $v['logging.factory']
  Check 'platform-lists-processes' ([int]$v['listing.processes'] -gt 0) "$($v['listing.processes']) processes through OSHI and JNA"
  Check 'platform-system-snapshots' ([int]$v['listing.system-snapshots'] -gt 0) "$($v['listing.system-snapshots']) snapshots"
  Check 'own-row-found' ($v['listing.own-row'] -eq 'true') "pid $($v['pid'])"
  Rep 'java' 'INFO' "$($v['java.vendor']) $($v['java.version']) locale=$($v['default.locale']) oom-signal=$($v['oom.signal'])"
  Rep 'control-profile' 'INFO' $v['control.profile']
  foreach ($a in 'CHANGE_PRIORITY', 'SUSPEND', 'END_TASK', 'FORCE_KILL') {
    $answer = $v["safe2.$a"]
    Check "safe2-$a-refused" (($answer -notmatch '^SUCCESS') -and ($answer -match 'own process') -and $v["safe2.$a.alive"] -eq 'true') "[$answer] alive afterwards=$($v["safe2.$a.alive"])"
  }
  $m = $v['safe2.MANAGE']
  Check 'safe2-MANAGE-refused' (($m -notmatch '^SUCCESS') -and ($m -match 'own process')) "[$m]"
  Check 'runtime-stopped' ($v['runtime.stopped'] -eq 'true') 'real runtime shut down in the probe'
}

Scenario 'single-process' {
  $lad = Lad 'single-process'
  $app = Start-Prism $lad
  Rep 'start' 'INFO' "$($app.Ms) ms to the window"
  Check 'window-in-started-process' $true "window belongs to the started PID $($app.Pid)"
  Check 'no-child-prism' (@(Children $app.Pid | Where-Object { $_.Name -like 'PRISM*' }).Count -eq 0) "children: $((Children $app.Pid | ForEach-Object { $_.Name }) -join ', ')"
  Check 'one-prism-process' ((CountByPath $PrismExe) -eq 1) "$(CountByPath $PrismExe) process(es) of PRISM.exe"
  $job = [W]::InJob($app.Pid)
  $mine = [W]::InJob($PID)
  if ($job -eq 'False') { Rep 'not-in-a-job' 'PASS' 'IsProcessInJob false' }
  elseif ($mine -eq 'True') { Rep 'not-in-a-job' 'INFO' "PRISM in-job=$job, and the test script itself runs in a job, which it inherits" }
  else { Rep 'not-in-a-job' 'FAIL' "PRISM in-job=$job while the test script is not in a job" }
  $mods = (Get-Process -Id $app.Pid).Modules | ForEach-Object { $_.FileName }
  Check 'javafx-and-jna-loaded' ((@($mods | Where-Object { $_ -match 'glass\.dll' }).Count -gt 0) -and (@($mods | Where-Object { $_ -match 'jnidispatch|jna[-0-9]*\.dll' }).Count -gt 0)) 'glass and JNA modules in the process'
  Check 'jvm-from-the-image' (@($mods | Where-Object { $_ -match 'jvm\.dll' -and $_.StartsWith($Image) }).Count -eq 1) 'jvm.dll is the one of the image'
  foreach ($page in 'Overview', 'Processes', 'Performance', 'Limits', 'OOM Guard', 'Logs', 'Settings') {
    try { Go $app $page; Start-Sleep -Seconds 1; Shot $app.H ("page-" + ($page -replace ' ', '-')); Rep "page-$page" 'PASS' 'opened' } catch { Rep "page-$page" 'FAIL' "$_" }
  }
  # An observation for the report, not a verdict: a machine may have no such readings to give (ADR-0017).
  Go $app 'Overview'; Start-Sleep -Seconds 3
  Rep 'overview-readings' 'INFO' ((AllOfType $app.Root $ct::Text | ForEach-Object { $_.Current.Name } | Where-Object { $_ }) -join ' / ')
  # CAP-2 through the real window: the own row offers nothing and says why. SAFE-2 as a result, with no
  # window in between, is the probe's; here the process must also come through every request untouched.
  $row = Row-Of $app $app.Pid
  Shot $app.H 'own-row'
  $names = 'Suspend', 'Resume', 'Priority', "End task$Dots", "Force kill$Dots"
  # The table has a column named Priority too; the control of the inspector is named with the current level.
  $states = @($names | ForEach-Object { $b = if ($_ -eq 'Priority') { Matching $app.Root '^Priority, current' } else { Named $app.Root $_ }; [pscustomobject]@{ Name = $_; Found = [bool]$b; Enabled = ($b -and $b.Current.IsEnabled); Help = $(if ($b) { $b.Current.HelpText }) } })
  Rep 'own-row-buttons' 'INFO' (($states | ForEach-Object { "$($_.Name) found=$($_.Found) enabled=$($_.Enabled)" }) -join ', ')
  Check 'ui-own-row-actions-disabled' (@($states | Where-Object { -not $_.Found -or $_.Enabled }).Count -eq 0) "every action found and disabled: $(@($states | Where-Object { $_.Found -and -not $_.Enabled }).Count) of $($names.Count)"
  $reason = Matching $app.Root "Prism's own process"
  $helps = @($states | Where-Object { $_.Help -match "Prism's own process" }).Count
  Check 'ui-own-row-says-why' ($null -ne $reason -and $helps -eq $names.Count) "line [$(if ($reason) { $reason.Current.Name })], $helps of $($names.Count) controls say it when pointed at"
  [void][W]::SetForegroundWindow($app.H); Start-Sleep -Milliseconds 300
  $r = $row.Current.BoundingRectangle
  if ([W]::RightClick($app.Pid, [int]($r.X + 60), [int]($r.Y + $r.Height / 2))) {
    Start-Sleep -Seconds 1
    $menu = @{}
    foreach ($q in (Dialogs $app)) { foreach ($n in 'Manage', "Set CPU limit$Dots", 'Add to OOM management', 'Suspend', "Force kill$Dots") { $e = Named ($uia::FromHandle($q)) $n; if ($e -and -not $menu.ContainsKey($n)) { $menu[$n] = $e.Current.IsEnabled } } }
    foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
    [System.Windows.Forms.SendKeys]::SendWait('{ESC}'); Start-Sleep -Milliseconds 500
    $detail = ($menu.Keys | Sort-Object | ForEach-Object { "$_ enabled=$($menu[$_])" }) -join ', '
    # The right click must have been on Prism's own row: the inspector still gives the reason afterwards.
    $stillOwn = $null -ne (Matching $app.Root "Prism's own process")
    Check 'ui-own-row-menu-disabled' ($stillOwn -and $menu.Count -eq 5 -and @($menu.Values | Where-Object { $_ }).Count -eq 0) "context menu: $detail; own process still selected: $stillOwn"
  } else { Rep 'ui-own-row-menu-disabled' 'NOT TESTED' 'another program was under the pointer: no right click was made' }
  Start-Sleep -Seconds 1
  $t = Threads $app.Pid
  $heard = [regex]::Matches((Log-Text $lad), 'Prism does not (act on|manage) .*own process').Count
  Check 'own-process-alive-and-running' ($t.Alive -and $t.Suspended -eq 0 -and -not $app.Proc.HasExited -and $heard -eq 0) "alive=$($t.Alive), $($t.Suspended) of $($t.All) threads suspended, requests that reached the service=$heard"
  # LIFE-7
  Start-Sleep -Seconds 1
  $env:LOCALAPPDATA = $lad
  $second = Start-Process $PrismExe -PassThru; $null = $second.Handle; [void]$script:Started.Add($second)
  $dlg = [IntPtr]::Zero; $mainSeen = $false; $said = $null
  for ($i = 0; $i -lt 300 -and $dlg -eq [IntPtr]::Zero -and -not $mainSeen -and -not $second.HasExited; $i++) {
    Start-Sleep -Milliseconds 100
    foreach ($x in [W]::ForPid($second.Id)) { if ([W]::Text($x) -eq $Title) { $mainSeen = $true } elseif ($dlg -eq [IntPtr]::Zero) { $dlg = $x } }
  }
  if ($dlg -ne [IntPtr]::Zero) { Start-Sleep -Seconds 1; $said = Matching ($uia::FromHandle($dlg)) 'already running' }
  foreach ($x in [W]::ForPid($second.Id)) { if ([W]::Text($x) -eq $Title) { $mainSeen = $true } }
  Check 'second-launch-shows-dialog' ($dlg -ne [IntPtr]::Zero -and -not $mainSeen -and $null -ne $said) "main window of the second launch=$mainSeen, dialog says [$(if ($said) { $said.Current.Name })]"
  if ($dlg -ne [IntPtr]::Zero) {
    Start-Sleep -Seconds 1; Shot $dlg 'second-launch'
    [void][W]::PostMessage($dlg, 0x10, [IntPtr]::Zero, [IntPtr]::Zero)
  }
  $ended = $second.WaitForExit(15000)
  Check 'second-launch-exits' ($ended -and $second.ExitCode -eq 0) "ended=$ended exit=$(if ($ended) { $second.ExitCode })"
  Check 'second-launch-one-main-window' (@([W]::ForPid($app.Pid) | Where-Object { [W]::Text($_) -eq $Title }).Count -eq 1 -and (Threads $app.Pid).Alive) 'the first copy runs on'
  $c = Close-Prism $app
  Check 'close-ends-the-process' ($c.Ended -and $c.Code -eq 0) "ended=$($c.Ended) exit=$($c.Code) in $($c.Ms) ms"
  Check 'nothing-left' ((CountByPath $PrismExe) -eq 0) "$(CountByPath $PrismExe) process(es) left"
  $log = Log-Text $lad
  Keep-Log $lad 'prism'
  Check 'log-says-shutdown-complete' ($log -match 'Shutdown complete: monitoring and its executors are stopped') 'the last step of LIFE-1 is in the file'
  Check 'log-no-linkage-error' ($log -notmatch 'NoClassDefFound|ClassNotFound|UnsatisfiedLink|LinkageError|ModuleNotFound|IllegalAccessError') 'no class or native loading failure'
}

# Native limits of the delivered demo launcher, from PRISM, measured from outside.
Scenario 'demo-native' {
  $lad = Lad 'demo-native'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--memory', '64MiB', '--duration', '600s')
  Check 'demo-is-one-process' ($demo.Children -eq 0 -and $demo.Pid -eq $demo.Launcher) "started $($demo.Launcher), works in $($demo.Pid), children=$($demo.Children)"
  $outside = [W]::InJob($demo.Pid)
  if ($outside -eq 'False') { Rep 'demo-starts-outside-a-job' 'PASS' 'IsProcessInJob false: the launcher made no job' }
  elseif ([W]::InJob($PID) -eq 'True') { Rep 'demo-starts-outside-a-job' 'INFO' "demo in-job=$outside, and the test script itself runs in a job, which it inherits" }
  else { Rep 'demo-starts-outside-a-job' 'FAIL' "demo in-job=$outside while the test script is not in a job" }
  $app = Start-Prism $lad
  $before = CpuPercent $demo.Pid 5
  Check 'load-is-measurable' ($before -ge 15) "$before % of the machine over 5 s before any limit"
  $null = Manage $app $demo.Pid
  Shot $app.H 'managed'
  $follow = Set-Limit $app 'Set CPU limit' '5'
  Check 'cpu-no-fallback-offered' ($follow -eq 'NONE') "after Set limit: $follow"
  Start-Sleep -Seconds 3
  Shot $app.H 'cpu-applied'
  $logged = (Log-Text $lad) -match "Set CPU limit: \S.*\(PID $($demo.Pid)\) is limited to 5 %.*Enforced by the system"
  Check 'cpu-reported-native' ($null -ne (Matching $app.Root '^Native hard$') -and $logged) "the log names the enforcement=$logged; page says: $((Matching $app.Root 'is limited to').Current.Name)"
  Check 'demo-in-a-job' ([W]::InJob($demo.Pid) -eq 'True') "in-job=$([W]::InJob($demo.Pid))"
  $limited = CpuPercent $demo.Pid 8
  Check 'cpu-applied-measured' (Near-Limit $limited 7) "$limited % over 8 s with a limit of 5 %"
  Invoke-El (Named $app.Root 'Remove CPU limit'); Start-Sleep -Seconds 3
  $free = CpuPercent $demo.Pid 6
  Check 'cpu-removed-measured' ($free -ge 15) "$free % over 6 s after the removal"
  $mem = Named $app.Root ('Set memory limit' + $Dots)
  Check 'memory-offered' ($mem -and $mem.Current.IsEnabled) "enabled=$(if ($mem) { $mem.Current.IsEnabled }) help=[$(if ($mem) { $mem.Current.HelpText })]"
  $follow = Set-Limit $app 'Set memory limit' '2GiB'
  Start-Sleep -Seconds 2
  Shot $app.H 'memory-applied'
  # What the limit does to a real allocation is in the scenarios after this one: this launcher cannot be told when to allocate.
  $logged = (Log-Text $lad) -match "Set memory limit: \S.*\(PID $($demo.Pid)\) may commit at most 2 GiB"
  Check 'memory-reported-native' ($null -ne (Matching $app.Root '^Native hard$') -and $follow -eq 'NONE' -and $logged) "after Set limit: $follow; the log names the limit=$logged"
  Check 'demo-survives-memory-limit' ((Threads $demo.Pid).Alive) 'alive'
  Invoke-El (Named $app.Root 'Remove memory limit'); Start-Sleep -Seconds 3
  Shot $app.H 'memory-removed'
  $logged = (Log-Text $lad) -match "Remove memory limit: The memory limit of \S.*\(PID $($demo.Pid)\) is removed"
  Check 'memory-removal-reported' $logged 'the log says so'
  # Both limits, then PRISM closes: LIFE-1 clears them; the demo goes on.
  [void](Set-Limit $app 'Set CPU limit' '5')
  [void](Set-Limit $app 'Set memory limit' '2GiB')
  Start-Sleep -Seconds 3
  $limited = CpuPercent $demo.Pid 6
  Check 'limit-before-close' (Near-Limit $limited 7) "$limited %"
  $c = Close-Prism $app
  Check 'close-with-limits-ends' ($c.Ended -and $c.Code -eq 0) "ended=$($c.Ended) exit=$($c.Code) in $($c.Ms) ms"
  $t = Threads $demo.Pid
  Check 'close-leaves-demo-running' ($t.Alive -and $t.Suspended -eq 0) "alive=$($t.Alive), $($t.Suspended) of $($t.All) threads suspended"
  $free = CpuPercent $demo.Pid 6
  Check 'close-clears-native-cpu' ($free -ge 15) "$free % after PRISM closed"
  $ledger = @(Get-ChildItem (Folder $lad 'state') -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'ledger|native' })
  Rep 'ledger-after-close' 'INFO' "$($ledger.Count) ledger file(s) left"
  Keep-Log $lad 'prism'
}

# Enforcement on a real process that goes on allocating: only a test launcher can be told when to start.
Scenario 'memory-enforced' {
  if (-not (Test-Path $ProbeJar)) { Rep 'enforced' 'BLOCKED' "no probe jar at $ProbeJar"; return }
  $exe = Test-Launcher 'prism-probe' 'prism-demo.cfg' -mainClass 'prism.srm.app.ImageProbe' -Probe
  $lad = Lad 'memory-enforced'
  $go = Join-Path $Work 'grow.go'
  Remove-Item $go -ErrorAction SilentlyContinue
  $out = Join-Path $Results 'memory-enforced-stdout.txt'
  $g = Start-Process $exe -ArgumentList 'grow', $go, '768' -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError (Join-Path $Results 'memory-enforced-stderr.txt')
  $null = $g.Handle; [void]$script:Started.Add($g)
  Start-Sleep -Seconds 3
  $app = Start-Prism $lad
  $null = Manage $app $g.Id
  $follow = Set-Limit $app 'Set memory limit' '512MiB'
  Start-Sleep -Seconds 2
  Shot $app.H 'limit-set'
  Check 'limit-set' ($follow -eq 'NONE') "after Set limit: $follow"
  'go' | Set-Content $go
  $failed = $null
  for ($i = 0; $i -lt 120 -and -not $failed -and -not $g.HasExited; $i++) { Start-Sleep -Milliseconds 500; $failed = Get-Content $out -ErrorAction SilentlyContinue | Where-Object { $_ -match '^grow\.failed=' } | Select-Object -First 1 }
  Check 'allocation-refused-at-the-limit' ([bool]$failed) "[$failed] exited=$($g.HasExited)"
  if ($g.HasExited) { Rep 'process-survives' 'FAIL' "ended with $($g.ExitCode)"; return }
  $committed = [math]::Round((Get-Process -Id $g.Id).PagedMemorySize64 / 1MB)
  Check 'committed-within-the-limit' ($committed -ge 450 -and $committed -le 520) "$committed MiB committed with a limit of 512 MiB"
  Invoke-El (Named $app.Root 'Remove memory limit')
  $done = $null
  for ($i = 0; $i -lt 120 -and -not $done -and -not $g.HasExited; $i++) { Start-Sleep -Milliseconds 500; $done = Get-Content $out -ErrorAction SilentlyContinue | Where-Object { $_ -match '^grow\.end=' } | Select-Object -First 1 }
  Check 'allocation-goes-on-after-removal' ([bool]$done) "[$done] exited=$($g.HasExited)"
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
}

# LIFE-1: a native memory limit is gone when PRISM has closed: the process stopped at the limit goes on growing.
Scenario 'memory-clears-at-close' {
  if (-not (Test-Path $ProbeJar)) { Rep 'enforced' 'BLOCKED' "no probe jar at $ProbeJar"; return }
  $exe = Test-Launcher 'prism-probe' 'prism-demo.cfg' -mainClass 'prism.srm.app.ImageProbe' -Probe
  $lad = Lad 'memory-clears-at-close'
  $go = Join-Path $Work 'grow-close.go'
  Remove-Item $go -ErrorAction SilentlyContinue
  $out = Join-Path $Results 'memory-clears-at-close-stdout.txt'
  $g = Start-Process $exe -ArgumentList 'grow', $go, '768' -PassThru -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError (Join-Path $Results 'memory-clears-at-close-stderr.txt')
  $null = $g.Handle; [void]$script:Started.Add($g)
  Start-Sleep -Seconds 3
  $app = Start-Prism $lad
  $null = Manage $app $g.Id
  $follow = Set-Limit $app 'Set memory limit' '512MiB'
  Start-Sleep -Seconds 2
  Check 'limit-set' ($follow -eq 'NONE') "after Set limit: $follow"
  'go' | Set-Content $go
  $failed = $null
  for ($i = 0; $i -lt 120 -and -not $failed -and -not $g.HasExited; $i++) { Start-Sleep -Milliseconds 500; $failed = Get-Content $out -ErrorAction SilentlyContinue | Where-Object { $_ -match '^grow\.failed=' } | Select-Object -First 1 }
  Check 'allocation-refused-at-the-limit' ([bool]$failed) "[$failed] exited=$($g.HasExited)"
  if ($g.HasExited) { Rep 'process-survives' 'FAIL' "ended with $($g.ExitCode)"; return }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  $done = $null
  for ($i = 0; $i -lt 120 -and -not $done -and -not $g.HasExited; $i++) { Start-Sleep -Milliseconds 500; $done = Get-Content $out -ErrorAction SilentlyContinue | Where-Object { $_ -match '^grow\.end=' } | Select-Object -First 1 }
  Check 'allocation-goes-on-after-close' ([bool]$done) "[$done] exited=$($g.HasExited)"
  Keep-Log $lad 'prism'
}

# LIFE-2 and LIFE-6: what the user suspended stays, is listed next time, and is resumed only on request.
Scenario 'user-suspension' {
  $lad = Lad 'user-suspension'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s')
  $app = Start-Prism $lad
  $null = Row-Of $app $demo.Pid
  Invoke-El (Named $app.Root 'Suspend'); Start-Sleep -Seconds 3
  $t = Threads $demo.Pid
  Check 'suspended' ($t.Suspended -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) threads suspended"
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Start-Sleep -Seconds 2
  $t = Threads $demo.Pid
  Check 'stays-suspended-after-close' ($t.Suspended -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) threads suspended"
  $app = Start-Prism $lad
  Start-Sleep -Seconds 5
  $t = Threads $demo.Pid
  Check 'next-start-resumes-nothing' ($t.Suspended -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) threads suspended, 10 s after the start"
  Go $app 'Processes'; Start-Sleep -Seconds 2
  $review = Matching $app.Root '^Review the processes an earlier session'
  Check 'next-start-lists-it' ($null -ne $review) 'Review is offered on the Processes page'
  Shot $app.H 'listed'
  if ($review) {
    if (Click-El $app $review) {
      $dh = Wait-Dialog $app
      $d = $uia::FromHandle($dh); Start-Sleep -Seconds 1
      Shot $dh 'review'
      $res = Matching $d '^Resume .* after a check'
      if ($res) {
        $r = $res.Current.BoundingRectangle
        [void][W]::SetForegroundWindow($dh); Start-Sleep -Milliseconds 300
        [void][W]::LeftClick($app.Pid, [int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2)); Start-Sleep -Seconds 2
        foreach ($q in (Dialogs $app)) { if ($q -ne $dh) { $u = Named ($uia::FromHandle($q)) 'Resume'; if ($u) { Invoke-El $u; break } } }
        Start-Sleep -Seconds 3
        $t = Threads $demo.Pid
        Check 'resumed-on-request' ($t.Suspended -eq 0 -and $t.Alive) "$($t.Suspended) of $($t.All) threads suspended"
        Check 'runs-after-resume' ((CpuPercent $demo.Pid 5) -ge 15) 'load is back'
      } else { Rep 'resumed-on-request' 'FAIL' 'no Resume in the list' }
      foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
      Start-Sleep -Seconds 1
    } else { Rep 'resumed-on-request' 'NOT TESTED' 'another program was under the pointer: no click was made' }
  }
  $c = Close-Prism $app
  Check 'second-close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

# LIFE-4: a hard kill leaves the native limit, the next start reports it and changes nothing.
Scenario 'hard-kill-native' {
  $lad = Lad 'hard-kill-native'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s')
  $app = Start-Prism $lad
  $null = Manage $app $demo.Pid
  $null = Set-Limit $app 'Set CPU limit' '5'
  Start-Sleep -Seconds 3
  $limited = CpuPercent $demo.Pid 6
  Check 'limit-before-kill' (Near-Limit $limited 7) "$limited %"
  $app.Proc.Kill(); [void]$app.Proc.WaitForExit(5000)
  $raw = Log-Text $lad
  $lastLine = ($raw.TrimEnd() -split "`n")[-1]
  Check 'log-complete-after-kill' ($lastLine -match '^\d{4}-\d\d-\d\d \S+ (INFO|WARN|ERROR) ') "last line [$lastLine]"
  Check 'log-holds-the-last-action' ($raw -match "Set CPU limit: \S.*\(PID $($demo.Pid)\) is limited to 5 %") 'what happened just before the kill is in the file'
  $t = Threads $demo.Pid
  Check 'demo-survives-kill' $t.Alive "alive=$($t.Alive)"
  $still = CpuPercent $demo.Pid 6
  Check 'limit-stays-after-kill' (Near-Limit $still 7) "$still % with PRISM gone (LIFE-4)"
  $app = Start-Prism $lad
  Start-Sleep -Seconds 8
  $log = Log-Text $lad
  Check 'next-start-reports-it' ($log -match "\(PID $($demo.Pid)\) may still be limited by an earlier Prism session") 'the log reports the limit of the earlier session'
  $still = CpuPercent $demo.Pid 6
  $t = Threads $demo.Pid
  Check 'next-start-changes-nothing' ((Near-Limit $still 7) -and $t.Alive -and $t.Suspended -eq 0) "$still % after the next start, $($t.Suspended) of $($t.All) threads suspended (LIM-5)"
  Go $app 'Limits'; Start-Sleep -Seconds 2; Shot $app.H 'limits-next-start'
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

# The emulated limit needs a process native limits are refused on: a demo in a job of its launcher, as an
# unpatched launcher starts it. A test launcher only: the delivered ones are not in such a job.
Scenario 'emulated-close' {
  $exe = Test-Launcher 'prism-demo-in-job' 'prism-demo.cfg' -NoNoRestart
  $lad = Lad 'emulated-close'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s') $exe
  Check 'test-demo-in-a-job' ([W]::InJob($demo.Pid) -eq 'True' -and $demo.Children -eq 1) "launcher $($demo.Launcher), works in $($demo.Pid)"
  $app = Start-Prism $lad
  $null = Manage $app $demo.Pid
  Rep 'offer' 'INFO' (Use-Emulated $app '5')
  Start-Sleep -Seconds 4
  $limited = CpuPercent $demo.Pid 8
  Check 'emulated-measured' (Near-Limit $limited 8) "$limited % over 8 s with an emulated limit of 5 %"
  Shot $app.H 'emulated'
  Check 'reported-emulated' ($null -ne (Matching $app.Root '^Emulated')) 'the Implementation column'
  # The close comes while the limiter holds the process, which is the case LIFE-1 has to release.
  for ($i = 0; $i -lt 400 -and (Threads $demo.Pid).Suspended -eq 0; $i++) { Start-Sleep -Milliseconds 10 }
  $held = (Threads $demo.Pid).Suspended
  Check 'held-at-close' ($held -gt 0) "$held threads held just before the close"
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  $worst = 0; $alive = $true; for ($i = 0; $i -lt 10; $i++) { $t = Threads $demo.Pid; $alive = $alive -and $t.Alive; if ($t.Suspended -gt $worst) { $worst = $t.Suspended }; Start-Sleep -Milliseconds 300 }
  Check 'no-thread-left-suspended' ($alive -and $worst -eq 0) "alive=$alive, at most $worst suspended in 3 s after PRISM closed (LIFE-1)"
  $free = CpuPercent $demo.Pid 6
  Check 'load-is-back' ($free -ge 15) "$free %"
  Keep-Log $lad 'prism'
}

# LIFE-4 and LIFE-6 with an emulated limit: killed at a moment it holds the process.
Scenario 'emulated-hard-kill' {
  $exe = Test-Launcher 'prism-demo-in-job' 'prism-demo.cfg' -NoNoRestart
  $lad = Lad 'emulated-hard-kill'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s') $exe
  $app = Start-Prism $lad
  $null = Manage $app $demo.Pid
  $null = Use-Emulated $app '5'
  Start-Sleep -Seconds 5
  $hit = $false
  for ($i = 0; $i -lt 400 -and -not $hit; $i++) {
    if ((Threads $demo.Pid).Suspended -gt 0) { $app.Proc.Kill(); [void]$app.Proc.WaitForExit(5000); $hit = (Threads $demo.Pid).Suspended -gt 0; if (-not $hit) { break } }
    else { Start-Sleep -Milliseconds 10 }
  }
  if (-not $app.Proc.HasExited) { $app.Proc.Kill(); [void]$app.Proc.WaitForExit(5000) }
  $t = Threads $demo.Pid
  if (-not $hit) { Rep 'killed-while-holding' 'NOT TESTED' "the kill landed while the limiter did not hold it; $($t.Suspended) of $($t.All) suspended"; return }
  Rep 'killed-while-holding' 'PASS' "$($t.Suspended) of $($t.All) threads suspended after the kill"
  Start-Sleep -Seconds 2
  $app = Start-Prism $lad
  Start-Sleep -Seconds 5
  $t = Threads $demo.Pid
  Check 'next-start-resumes-nothing' ($t.All -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) suspended, 10 s after the start (LIFE-6)"
  Go $app 'Processes'; Start-Sleep -Seconds 2
  $review = Matching $app.Root '^Review the processes an earlier session'
  Check 'next-start-lists-it' ($null -ne $review) 'Review is offered'
  Shot $app.H 'listed'
  if ($review -and (Click-El $app $review)) {
    $dh = Wait-Dialog $app; $d = $uia::FromHandle($dh); Start-Sleep -Seconds 1
    Shot $dh 'review'
    # LIFE-6: the limiter's entry names no threads, so it is for manual intervention, and Prism says so.
    Check 'no-resume-offered-without-recorded-threads' ($null -eq (Matching $d '^Resume .* after a check')) 'an anonymous suspension count does not show whose it is'
    $why = Matching $d 'did not record which threads'
    Check 'says-why-and-what-to-do' ($null -ne $why) "[$(if ($why) { $why.Current.Name })]"
    $t = Threads $demo.Pid
    Check 'still-suspended-after-review' ($t.Suspended -gt 0) "$($t.Suspended) of $($t.All)"
    foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
    Start-Sleep -Seconds 1
  } else { Rep 'no-resume-offered-without-recorded-threads' 'NOT TESTED' 'no list to read, or another program was under the pointer' }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

# LIFE-5: Ctrl+C reaches the JVM only through a console launcher, so through a test copy; the JVM's hook
# releases the limiter's hold and leaves what the user suspended.
Scenario 'ctrl-c' {
  $exe = Test-Launcher 'prism-console' 'PRISM.cfg'
  $demoInJob = Test-Launcher 'prism-demo-in-job' 'prism-demo.cfg' -NoNoRestart
  $lad = Lad 'ctrl-c'
  $limited = Start-Demo 'limited' @('--cpu', '20', '--duration', '600s') $demoInJob
  $users = Start-Demo 'users' @('--cpu', '5', '--duration', '600s')
  $pidFile = Join-Path $Work 'ctrlc.pid'; $goFile = Join-Path $Work 'ctrlc.go'; $endFile = Join-Path $Work 'ctrlc.end'
  Remove-Item $pidFile, $goFile, $endFile -ErrorAction SilentlyContinue
  $helper = Join-Path $Work 'ctrlc-helper.ps1'
  @'
param($PrismExe, $Lad, $PidFile, $GoFile, $EndFile)
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;
public static class C {
 [DllImport("kernel32")] public static extern bool FreeConsole();
 [DllImport("kernel32")] public static extern bool AllocConsole();
 [DllImport("kernel32")] public static extern bool SetConsoleCtrlHandler(IntPtr h, bool add);
 [DllImport("kernel32")] public static extern bool GenerateConsoleCtrlEvent(uint e, uint g);
}
"@
[void][C]::FreeConsole(); [void][C]::AllocConsole(); [void][C]::SetConsoleCtrlHandler([IntPtr]::Zero, $false)
$i = New-Object System.Diagnostics.ProcessStartInfo; $i.FileName = $PrismExe; $i.UseShellExecute = $false
$i.EnvironmentVariables['LOCALAPPDATA'] = $Lad
$p = [System.Diagnostics.Process]::Start($i)
"$($p.Id)" | Set-Content $PidFile
while (-not (Test-Path $GoFile)) { Start-Sleep -Milliseconds 100 }
[void][C]::SetConsoleCtrlHandler([IntPtr]::Zero, $true); [void][C]::GenerateConsoleCtrlEvent(0, 0)
$ended = $p.WaitForExit(30000)
"$ended $(if ($ended) { $p.ExitCode })" | Set-Content $EndFile
'@ | Set-Content $helper -Encoding ASCII
  $hp = Start-Process powershell.exe -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", "`"$exe`"", "`"$lad`"", "`"$pidFile`"", "`"$goFile`"", "`"$endFile`"" -PassThru -WindowStyle Hidden
  [void]$script:Started.Add($hp)
  for ($i = 0; $i -lt 300 -and -not (Test-Path $pidFile); $i++) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path $pidFile)) { Rep 'started' 'BLOCKED' 'the helper did not start the console launcher'; return }
  $cp = [int](Get-Content $pidFile)
  $h = [IntPtr]::Zero
  for ($i = 0; $i -lt 1200 -and $h -eq [IntPtr]::Zero; $i++) { foreach ($x in [W]::ForPid($cp)) { if ([W]::Text($x) -eq $Title) { $h = $x } }; Start-Sleep -Milliseconds 100 }
  if ($h -eq [IntPtr]::Zero) { Rep 'started' 'BLOCKED' 'no window from the console launcher'; return }
  Start-Sleep -Seconds 5
  [void][W]::SetForegroundWindow($h); [void][W]::SetWindowPos($h, [IntPtr](-1), 0, 0, 0, 0, 0x13)
  $app = [pscustomobject]@{ Proc = $null; Pid = $cp; H = $h; Root = $uia::FromHandle($h); Ms = 0; Lad = $lad }
  $null = Row-Of $app $users.Pid
  Invoke-El (Named $app.Root 'Suspend'); Start-Sleep -Seconds 3
  $null = Manage $app $limited.Pid
  $null = Use-Emulated $app '5'
  Start-Sleep -Seconds 5
  $t = Threads $users.Pid
  Check 'users-suspended-before' ($t.Suspended -eq $t.All -and $t.All -gt 0) "$($t.Suspended) of $($t.All)"
  $held = CpuPercent $limited.Pid 6
  Check 'limiter-at-work-before' (Near-Limit $held 8) "$held % with an emulated limit of 5 %"
  # The signal goes while the limiter holds the process, which is the case the hook is for.
  for ($i = 0; $i -lt 400 -and (Threads $limited.Pid).Suspended -eq 0; $i++) { Start-Sleep -Milliseconds 10 }
  $heldNow = (Threads $limited.Pid).Suspended
  Check 'signal-while-held' ($heldNow -gt 0) "$heldNow threads held just before the signal"
  'go' | Set-Content $goFile
  for ($i = 0; $i -lt 400 -and -not (Test-Path $endFile); $i++) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path $endFile)) { Rep 'ended' 'BLOCKED' 'the helper reports nothing'; return }
  $end = Get-Content $endFile
  Check 'ctrl-c-ends-prism' ($end -match '^True') "ended, exit code: $end"
  $worst = 0; $alive = $true; for ($i = 0; $i -lt 10; $i++) { $t = Threads $limited.Pid; $alive = $alive -and $t.Alive; if ($t.Suspended -gt $worst) { $worst = $t.Suspended }; Start-Sleep -Milliseconds 300 }
  Check 'hook-released-the-limiter' ($alive -and $worst -eq 0) "alive=$alive, at most $worst threads suspended in 3 s"
  $t = Threads $users.Pid
  Check 'hook-left-the-users' ($t.Suspended -eq $t.All -and $t.All -gt 0) "$($t.Suspended) of $($t.All) suspended (LIFE-2)"
  Keep-Log $lad 'prism'
}

# CTL-2 to CTL-6 through the real window, on the demo only: priority, suspend and resume, End task on a process
# without a window, Force kill declined from the keyboard and then confirmed.
Scenario 'process-control' {
  $lad = Lad 'process-control'
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s')
  $app = Start-Prism $lad
  $null = Row-Of $app $demo.Pid
  $priority = Matching $app.Root '^Priority, current'
  if ($priority -and (Click-El $app $priority)) {
    Start-Sleep -Seconds 1
    $item = $null
    foreach ($q in (Dialogs $app)) { $item = Named ($uia::FromHandle($q)) 'Below normal'; if ($item) { break } }
    if ($item) {
      $m = $item.Current.BoundingRectangle
      [void][W]::LeftClick($app.Pid, [int]($m.X + $m.Width / 2), [int]($m.Y + $m.Height / 2)); Start-Sleep -Seconds 3
      foreach ($q in (Dialogs $app)) { Rep 'priority-dialog' 'INFO' ((AllOfType ($uia::FromHandle($q)) $ct::Text | ForEach-Object { $_.Current.Name }) -join ' / ') }
      $class = (Get-Process -Id $demo.Pid).PriorityClass
      Check 'priority-changed' ("$class" -eq 'BelowNormal') "priority class of the demo: $class"
    } else { Rep 'priority-changed' 'FAIL' 'the Priority menu has no Below normal' }
  } else { Rep 'priority-changed' 'NOT TESTED' 'no Priority control, or another program was under the pointer' }
  foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
  Invoke-El (Named $app.Root 'Suspend'); Start-Sleep -Seconds 3
  $t = Threads $demo.Pid
  Check 'suspended' ($t.All -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) threads suspended"
  Invoke-El (Named $app.Root 'Resume'); Start-Sleep -Seconds 3
  $t = Threads $demo.Pid
  Check 'resumed' ($t.Alive -and $t.Suspended -eq 0) "$($t.Suspended) of $($t.All) threads suspended"
  Check 'runs-after-resume' ((CpuPercent $demo.Pid 5) -ge 10) 'load is back'
  # End task asks a window to close; the demo has none, so the result must say so and the demo must live on.
  if (Click-El $app (Named $app.Root ('End task' + $Dots))) {
    $dh = Wait-Dialog $app
    if ($dh -ne [IntPtr]::Zero) { Shot $dh 'end-task-confirm'; Invoke-El (Named ($uia::FromHandle($dh)) 'End task'); Start-Sleep -Seconds 4 }
    $line = [regex]::Match((Log-Text $lad), "End task: [^\r\n]*\(PID $($demo.Pid)\)[^\r\n]*").Value
    # The wording of ControlMessages for a process without a window, and no claim that it ended.
    $honest = $line -match 'has no window to ask to close' -and $line -notmatch '\b(ended|exited|closed)\b'
    Check 'end-task-honest' ((Threads $demo.Pid).Alive -and $honest) "demo alive=$((Threads $demo.Pid).Alive); log: $line"
  } else { Rep 'end-task-honest' 'NOT TESTED' 'another program was under the pointer: no click was made' }
  Shot $app.H 'after-end-task'
  # WP5: Cancel is the default of a confirmation, so Enter and Escape both decline.
  foreach ($k in @(@('enter', '{ENTER}'), @('escape', '{ESC}'))) {
    if (Click-El $app (Named $app.Root ('Force kill' + $Dots))) {
      $dh = Wait-Dialog $app
      if ($dh -eq [IntPtr]::Zero) { Rep "force-kill-$($k[0])-declines" 'FAIL' 'no confirmation'; continue }
      [void][W]::SetForegroundWindow($dh); Start-Sleep -Milliseconds 400
      Keys $k[1]; Start-Sleep -Seconds 2
      $open = @(Dialogs $app).Count
      $t = Threads $demo.Pid
      Check "force-kill-$($k[0])-declines" ($open -eq 0 -and $t.Alive) "dialogs open=$open, demo alive=$($t.Alive)"
      foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
    } else { Rep "force-kill-$($k[0])-declines" 'NOT TESTED' 'another program was under the pointer: no click was made' }
  }
  if (Click-El $app (Named $app.Root ('Force kill' + $Dots))) {
    $dh = Wait-Dialog $app
    if ($dh -ne [IntPtr]::Zero) { Invoke-El (Named ($uia::FromHandle($dh)) 'Force kill') }
    $ended = $demo.Proc.WaitForExit(10000)
    Check 'force-kill-ends-demo' $ended "demo ended=$ended"
    Start-Sleep -Seconds 3
    Shot $app.H 'after-force-kill'
    $line = [regex]::Match((Log-Text $lad), "Force kill: [^\r\n]*\(PID $($demo.Pid)\)[^\r\n]*").Value
    Check 'force-kill-logged' ($line -ne '') "log: $line"
  } else { Rep 'force-kill-ends-demo' 'NOT TESTED' 'another program was under the pointer: no click was made' }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

# OOM-5 to OOM-12 on the demo only: thresholds above what is available put the system in EMERGENCY without using
# memory, OOM Guard suspends the one process under its management, and the close lets it go (LIFE-1).
Scenario 'oom-guard' {
  $lad = Lad 'oom-guard'
  $available = Available-Percent
  Rep 'available-memory' 'INFO' "$available % of physical memory available"
  if ($available -ge 87) { Rep 'suspended-at-emergency' 'NOT TESTED' "$available % available: thresholds of at most 90 % cannot make an emergency"; return }
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s')
  $app = Start-Prism $lad
  $held = Oom-Suspend-Demo $app $demo.Pid
  Shot $app.H 'emergency'
  $t = Threads $demo.Pid
  Check 'suspended-at-emergency' $held "$($t.Suspended) of $($t.All) threads suspended"
  $state = Matching $app.Root '^Emergency'
  Rep 'page-state' 'INFO' "[$(if ($state) { $state.Current.Name })]"
  $line = [regex]::Match((Log-Text $lad), "[^\r\n]*OOM Guard[^\r\n]*\(PID $($demo.Pid)\)[^\r\n]*").Value
  Check 'action-logged' ($line -ne '') "log: $line"
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  $worst = 0; $alive = $true; for ($i = 0; $i -lt 10; $i++) { $t = Threads $demo.Pid; $alive = $alive -and $t.Alive; if ($t.Suspended -gt $worst) { $worst = $t.Suspended }; Start-Sleep -Milliseconds 300 }
  Check 'close-releases-the-hold' ($held -and $alive -and $worst -eq 0) "alive=$alive, at most $worst suspended in 3 s after PRISM closed"
  Check 'load-is-back' ((CpuPercent $demo.Pid 5) -ge 10) 'the demo runs'
  Keep-Log $lad 'prism'
}

# LIFE-6 for OOM Guard: killed while it holds the demo, the next start resumes nothing by itself and offers a
# resume after a check, because OOM Guard records the threads it suspended.
Scenario 'oom-hard-kill' {
  $lad = Lad 'oom-hard-kill'
  $available = Available-Percent
  if ($available -ge 87) { Rep 'killed-while-holding' 'NOT TESTED' "$available % available"; return }
  $demo = Start-Demo 'demo' @('--cpu', '20', '--duration', '600s')
  $app = Start-Prism $lad
  $held = Oom-Suspend-Demo $app $demo.Pid
  $app.Proc.Kill(); [void]$app.Proc.WaitForExit(5000)
  Start-Sleep -Seconds 1
  $t = Threads $demo.Pid
  Check 'killed-while-holding' ($held -and $t.All -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) threads suspended after the kill"
  $app = Start-Prism $lad
  Start-Sleep -Seconds 5
  $t = Threads $demo.Pid
  Check 'next-start-resumes-nothing' ($t.All -gt 0 -and $t.Suspended -eq $t.All) "$($t.Suspended) of $($t.All) suspended, 10 s after the start (LIFE-6)"
  Go $app 'Processes'; Start-Sleep -Seconds 2
  $review = Matching $app.Root '^Review the processes an earlier session'
  Check 'next-start-lists-it' ($null -ne $review) 'Review is offered'
  if ($review -and (Click-El $app $review)) {
    $dh = Wait-Dialog $app; $d = $uia::FromHandle($dh); Start-Sleep -Seconds 1
    Shot $dh 'review'
    $res = Matching $d '^Resume .* after a check'
    Check 'resume-offered' ($null -ne $res) 'OOM Guard recorded the threads it suspended'
    if ($res) {
      $r = $res.Current.BoundingRectangle
      [void][W]::SetForegroundWindow($dh); Start-Sleep -Milliseconds 300
      [void][W]::LeftClick($app.Pid, [int]($r.X + $r.Width / 2), [int]($r.Y + $r.Height / 2)); Start-Sleep -Seconds 2
      foreach ($q in (Dialogs $app)) { if ($q -ne $dh) { $u = Named ($uia::FromHandle($q)) 'Resume'; if ($u) { Invoke-El $u; break } } }
      Start-Sleep -Seconds 3
      $t = Threads $demo.Pid
      Check 'resumed-after-check' ($t.Alive -and $t.Suspended -eq 0) "$($t.Suspended) of $($t.All) threads suspended"
    }
    foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
    Start-Sleep -Seconds 1
  } else { Rep 'resume-offered' 'NOT TESTED' 'no list to read, or another program was under the pointer' }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

# WP5 with real keys: from the sidebar entry of each page, Tab walks the page and comes back. Each stop is named
# for UI Automation, and the band around it is drawn differently once it has the focus. The context-menu key opens the
# context menu of a row, the arrows move in it and Escape closes it; what Shift+F10 does is recorded.
Scenario 'keyboard' {
  $lad = Lad 'keyboard'
  $demo = Start-Demo 'demo' @('--cpu', '5', '--duration', '600s')
  $app = Start-Prism $lad
  $null = Row-Of $app $demo.Pid
  $lines = New-Object System.Collections.ArrayList
  $allBack = $true; $unnamed = @(); $unseen = @(); $unsteady = @(); $left = @(); $stops = 0
  foreach ($page in 'Overview', 'Processes', 'Performance', 'Limits', 'OOM Guard', 'Logs', 'Settings') {
    $nav = Nav-Entry $app $page
    if (-not (Click-El $app $nav)) { $allBack = $false; [void]$lines.Add("$page`: another program was under the pointer"); continue }
    Start-Sleep -Seconds 1
    [void]$lines.Add("== $page")
    $back = $false
    $prev = $nav.Current.BoundingRectangle
    for ($i = 0; $i -lt 30 -and -not $back; $i++) {
      $before = [W]::Grab($app.H)
      Keys '{TAB}'
      $f = Focused $app
      if ($null -eq $f) { $left += "$page step $i"; break }
      $after = [W]::Grab($app.H)
      # An empty table gives the focus to its placeholder for UI Automation. The
      # ring is drawn around the placeholder area inside the frame of the table, under its header, so the
      # whole rectangle of the table is looked at, and nothing outside it: a band as deep as half the
      # table, its outer edge on the edge of the table. Elsewhere a stop keeps its 3 px band.
      $band = $f; $around = ''; $inside = $false
      if ($f.Current.ControlType -eq $ct::Text) {
        $parent = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($f)
        if ($null -ne $parent -and $parent.Current.ControlType -eq $ct::Table) { $band = $parent; $inside = $true; $around = " (inside the table [$($parent.Current.Name)])" }
      }
      $r = $band.Current.BoundingRectangle
      $x = [int]$r.X - [W]::Left($app.H); $y = [int]$r.Y - [W]::Top($app.H)
      $bw = [int]$r.Width; $bh = [int]$r.Height; $depth = 3
      if ($inside) { $depth = [int][Math]::Floor([Math]::Min($bw, $bh) / 2); $x += $depth; $y += $depth; $bw -= 2 * $depth; $bh -= 2 * $depth }
      $px = [int]$prev.X - [W]::Left($app.H); $py = [int]$prev.Y - [W]::Top($app.H)
      # Back at the same element (a page whose only stop is its sidebar entry): there is no other ring to leave out.
      if ($prev -eq $r) { $px = -100000; $py = -100000 }
      # The ring of the control that just lost the focus is left out: its own ring going away is not this one's.
      $diff = [W]::BandDiffBesides($before, $after, $x, $y, $bw, $bh, $depth, $px, $py, [int]$prev.Width, [int]$prev.Height)
      # What changes in the same ring with no key at all (live values, a re-sorted table) is the noise of the stop.
      Start-Sleep -Milliseconds 400
      $still = [W]::Grab($app.H)
      $noise = [W]::BandDiffBesides($after, $still, $x, $y, $bw, $bh, $depth, $px, $py, [int]$prev.Width, [int]$prev.Height)
      $before.Dispose(); $after.Dispose(); $still.Dispose()
      $prev = $r
      $name = $f.Current.Name; $type = $f.Current.ControlType.ProgrammaticName -replace '^ControlType\.', ''
      $back = [System.Windows.Automation.Automation]::Compare($f, $nav)
      [void]$lines.Add("  $type [$name] band-diff=$diff noise=$noise$around$(if ($back) { ' (back at the start)' })")
      $stops++
      if (-not $name) { $unnamed += "$page`: $type" }
      if ($diff -eq 0) { $unseen += "$page`: $type [$name]" } elseif ($noise -gt 0 -and $diff -le 3 * $noise) { $unsteady += "$page`: $type [$name] $diff against $noise" }
    }
    if (-not $back) { $allBack = $false }
  }
  $lines | Set-Content (Join-Path $Results 'keyboard-routes.txt') -Encoding UTF8
  Check 'routes-come-back' ($allBack -and $left.Count -eq 0) "every page: Tab returns to its sidebar entry; focus left PRISM at: $($left -join ', ')"
  Check 'every-stop-named' ($stops -gt 0 -and $unnamed.Count -eq 0) "$stops stops; without a name: $($unnamed -join ', ')"
  Check 'focus-visible' ($stops -gt 0 -and $unseen.Count -eq 0 -and $unsteady.Count -eq 0) "$stops stops; drawn the same with and without the focus: $($unseen -join ', '); not told apart from what changes without a key: $($unsteady -join ', ')"
  $menu = Menu-By-Keyboard $app $demo.Pid 'apps'
  $items = if ($menu) { (AllOfType $menu $ct::MenuItem | ForEach-Object { $_.Current.Name }) -join ', ' } else { '' }
  Check 'context-menu-by-keyboard' ($null -ne $menu) "the context-menu key on the demo's row: [$items]"
  # The menu may open with its first item already highlighted: each Down must move the highlight further down.
  $order = @(if ($menu) { AllOfType $menu $ct::MenuItem | ForEach-Object { $_.Current.Name } })
  $start = Menu-Item-With-Focus (Open-Menu $app)
  Key 0x28; $first = Menu-Item-With-Focus (Open-Menu $app)
  Key 0x28; $second = Menu-Item-With-Focus (Open-Menu $app)
  $i0 = [array]::IndexOf($order, $start); $i1 = [array]::IndexOf($order, $first); $i2 = [array]::IndexOf($order, $second)
  Check 'arrows-move-in-menu' ($i1 -ge 0 -and $i1 -gt $i0 -and $i2 -gt $i1) "highlighted at the start: [$start], after Down: [$first], after Down again: [$second]"
  $openBefore = $null -ne (Open-Menu $app)
  Key 0x1B
  $still = @(Dialogs $app | Where-Object { Named ($uia::FromHandle($_)) 'Add to OOM management' }).Count
  $after = Focused $app
  Check 'escape-closes-menu' ($openBefore -and $still -eq 0) "open just before: $openBefore; windows of PRISM that still show the menu after one Escape: $still; focus after it [$(if ($after) { $after.Current.Name })]"
  # Shift+F10 opens the same menu, but on Windows it also puts the window into the system's menu mode, which takes
  # the next keys (JDK-8090022 describes the same). Recorded as it is; the context-menu key above is the route.
  $menu = Menu-By-Keyboard $app $demo.Pid 'shift-f10'
  $taken = 0
  for ($k = 1; $k -le 4 -and $null -ne (Open-Menu $app); $k++) { Key 0x1B; $taken = $k }
  $trapped = $null -ne (Open-Menu $app)
  if ($trapped) { Rep 'shift-f10' 'FAIL' "the menu opened by Shift+F10 was still open after $taken presses of Escape: a keyboard trap" }
  else { Rep 'shift-f10' 'INFO' "Shift+F10 opens the menu: $($null -ne $menu); Escape presses until it closed: $taken (known JavaFX on Windows limitation, JDK-8090022)" }
  foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
}

# LIFE-5: the console of a console launcher is closed while the limiter holds a process; the system gives the JVM a
# few seconds, in which its hook must release the limiter and leave what the user suspended.
Scenario 'console-close' {
  $exe = Test-Launcher 'prism-console' 'PRISM.cfg'
  $demoInJob = Test-Launcher 'prism-demo-in-job' 'prism-demo.cfg' -NoNoRestart
  $lad = Lad 'console-close'
  $limited = Start-Demo 'limited' @('--cpu', '20', '--duration', '600s') $demoInJob
  $users = Start-Demo 'users' @('--cpu', '5', '--duration', '600s')
  $console = Start-In-Console 'console-close' $exe '' $lad 'close'
  if ($null -eq $console) { Rep 'started' 'BLOCKED' 'the helper did not start the console launcher'; return }
  Rep 'console-window' 'INFO' "class of the console window: $($console.Class)"
  $app = Attach-Window $console.Pid $lad
  if ($null -eq $app) { Rep 'started' 'BLOCKED' 'no window from the console launcher'; return }
  $null = Row-Of $app $users.Pid
  Invoke-El (Named $app.Root 'Suspend'); Start-Sleep -Seconds 3
  $null = Manage $app $limited.Pid
  $null = Use-Emulated $app '5'
  Start-Sleep -Seconds 5
  $t = Threads $users.Pid
  Check 'users-suspended-before' ($t.Suspended -eq $t.All -and $t.All -gt 0) "$($t.Suspended) of $($t.All)"
  for ($i = 0; $i -lt 400 -and (Threads $limited.Pid).Suspended -eq 0; $i++) { Start-Sleep -Milliseconds 10 }
  $heldNow = (Threads $limited.Pid).Suspended
  Check 'close-while-held' ($heldNow -gt 0) "$heldNow threads held just before the console was closed"
  'go' | Set-Content $console.Go
  $ended = $app.Proc.WaitForExit(30000)
  Check 'console-close-ends-prism' $ended "ended=$ended"
  $worst = 0; $alive = $true; for ($i = 0; $i -lt 10; $i++) { $t = Threads $limited.Pid; $alive = $alive -and $t.Alive; if ($t.Suspended -gt $worst) { $worst = $t.Suspended }; Start-Sleep -Milliseconds 300 }
  Check 'hook-released-the-limiter' ($alive -and $worst -eq 0) "alive=$alive, at most $worst threads suspended in 3 s"
  $t = Threads $users.Pid
  Check 'hook-left-the-users' ($t.Suspended -eq $t.All -and $t.All -gt 0) "$($t.Suspended) of $($t.All) suspended (LIFE-2)"
  Keep-Log $lad 'prism'
}

# Ctrl+C to the demo in its own console while PRISM limits it natively: the demo ends, PRISM goes on and says it
# has ended, and closes cleanly afterwards.
Scenario 'demo-ctrl-c' {
  $lad = Lad 'demo-ctrl-c'
  $demo = Start-In-Console 'demo-ctrl-c' $DemoExe '--cpu 20 --duration 600s' 'none' 'ctrlc'
  if ($null -eq $demo) { Rep 'started' 'BLOCKED' 'the helper did not start the demo'; return }
  $app = Start-Prism $lad
  $null = Manage $app $demo.Pid
  $follow = Set-Limit $app 'Set CPU limit' '5'
  Start-Sleep -Seconds 3
  $limited = CpuPercent $demo.Pid 6
  Check 'limited-before' ($follow -eq 'NONE' -and (Near-Limit $limited 7)) "$limited % with a native limit of 5 %"
  'go' | Set-Content $demo.Go
  $ended = $demo.Proc.WaitForExit(15000)
  Check 'ctrl-c-ends-demo' $ended "ended=$ended"
  Start-Sleep -Seconds 6
  Shot $app.H 'after-demo-ended'
  Check 'prism-goes-on' (-not $app.Proc.HasExited) 'PRISM still runs'
  $line = [regex]::Match((Log-Text $lad), "[^\r\n]*\(PID $($demo.Pid)\)[^\r\n]*(ended|exited|no longer)[^\r\n]*").Value
  Check 'prism-noticed' ($line -match 'no longer managed') "log: $line"
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'prism'
}

Scenario 'settings' {
  $lad = Lad 'settings'
  $file = Join-Path (Folder $lad 'settings') 'prism.settings'
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  Shot $app.H 'first-start'
  Rep 'capabilities-shown' 'INFO' (((AllOfType $app.Root $ct::Text | ForEach-Object { $_.Current.Name }) -join ' / ') -replace [regex]::Escape($lad), '<lad>')
  Check 'nothing-written-before-a-choice' (-not (Test-Path $file)) 'no file at first start'
  Rep 'radio-element' 'INFO' (Describe (Radio $app 'Dark'))
  # UI-7: the first launch (no settings file) follows the system. Light is chosen first, which must be saved at once,
  # and Dark after it.
  Check 'first-start-system' (IsChosen (Radio $app 'System')) 'System is selected at the first start (UI-7)'
  Choose (Radio $app 'Light'); Start-Sleep -Seconds 2
  Check 'light-saved-at-once' ((Test-Path $file) -and ((Get-Content $file -Raw) -match 'appearance=LIGHT')) 'the file says LIGHT while PRISM runs'
  $light = Brightness-Of $app
  Choose (Radio $app 'Dark'); Start-Sleep -Seconds 2
  Check 'dark-saved-at-once' ((Test-Path $file) -and ((Get-Content $file -Raw) -match 'appearance=DARK')) 'the file says DARK while PRISM runs'
  Shot $app.H 'dark'
  $dark = Brightness-Of $app
  Rep 'dark-applied' 'INFO' "brightness of the page $light -> $dark"
  if ($Environment -eq 'clean') {
    foreach ($b in @(@('Open log folder', 'logs'), @('Open settings folder', 'settings'))) {
      $e = Named $app.Root $b[0]
      if ($e) {
        Invoke-El $e
        # Explorer shows a general title first and the folder's name a moment later.
        $seen = @(); for ($i = 0; $i -lt 40 -and @($seen | Where-Object { $_ -match $b[1] }).Count -eq 0; $i++) { Start-Sleep -Milliseconds 500; $seen = @([W]::Cabinets()) }
        Check "open-$($b[1])-in-the-file-manager" (@($seen | Where-Object { $_ -match $b[1] }).Count -gt 0) "explorer windows: [$($seen -join ' / ')]"
        [W]::Screen((Join-Path $Results "settings-open-$($b[1])-screen.png"), 1280, 800)
        [W]::CloseCabinets(); Start-Sleep -Seconds 1
      } else { Rep "open-$($b[1])" 'FAIL' 'no button' }
    }
  } else { Rep 'open-folders' 'NOT TESTED' 'on the host no folder window is opened; see the clean environment' }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  Check 'dark-kept-after-restart' (IsChosen (Radio $app 'Dark')) 'Dark is selected in the next session'
  $c = Close-Prism $app
  # A damaged file: moved aside, defaults, said on the page.
  'this is not a settings file' | Set-Content $file
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  Shot $app.H 'damaged'
  $aside = @(Get-ChildItem (Folder $lad 'settings') -Filter 'prism.settings.corrupt-*')
  Check 'damaged-moved-aside' ($aside.Count -eq 1) "$($aside.Count) file(s) kept aside"
  Check 'damaged-defaults-used' (IsChosen (Radio $app 'System')) 'System is selected'
  Check 'damaged-said-on-the-page' ($null -ne (Matching $app.Root 'was damaged')) 'the page tells it'
  $c = Close-Prism $app
  Check 'damaged-close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
  # A newer file: read by nothing, changed by nothing.
  $newer = "prism-settings 99`nappearance=DARK`n"
  [IO.File]::WriteAllText($file, $newer)
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  Shot $app.H 'newer'
  Check 'newer-defaults-used' (IsChosen (Radio $app 'System')) 'System is selected'
  Choose (Radio $app 'Light'); Start-Sleep -Seconds 2
  Check 'newer-file-untouched' ([IO.File]::ReadAllText($file) -ceq $newer) 'a choice in this session does not overwrite it'
  Check 'newer-said-on-the-page' ($null -ne (Matching $app.Root 'later version|newer')) 'the page tells it'
  $c = Close-Prism $app
  Check 'newer-close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
  Check 'newer-file-untouched-after-close' ([IO.File]::ReadAllText($file) -ceq $newer) 'still the same bytes'
  Keep-Log $lad 'prism'
}

Scenario 'closing' {
  # At start-up: the moment the window appears.
  $lad = Lad 'closing-startup'
  $app = Start-Prism $lad -NoWait
  $c = Close-Prism $app
  Check 'close-at-start-up' ($c.Ended -and $c.Code -eq 0) "ended=$($c.Ended) exit=$($c.Code) in $($c.Ms) ms"
  Keep-Log $lad 'startup'
  # With a dialog open: the window answers or waits, and PRISM always ends once the dialog is gone.
  $lad = Lad 'closing-dialog'
  $demo = Start-Demo 'demo' @('--cpu', '5', '--duration', '600s')
  $app = Start-Prism $lad
  $null = Manage $app $demo.Pid
  $b = Named $app.Root ('Set CPU limit' + $Dots)
  if (Click-El $app $b) {
    $dh = Wait-Dialog $app
    Check 'dialog-open' ($dh -ne [IntPtr]::Zero) 'CPU limit dialog'
    [void][W]::PostMessage($app.H, 0x10, [IntPtr]::Zero, [IntPtr]::Zero)
    $during = $app.Proc.WaitForExit(6000)
    Rep 'close-with-dialog-open' 'INFO' "ended while the dialog was open=$during"
    if (-not $during) {
      foreach ($q in (Dialogs $app)) { [void][W]::PostMessage($q, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) }
      Start-Sleep -Seconds 2
      if ($app.Proc.HasExited) { $during = $true } else { $during = (Close-Prism $app).Ended }
    }
    Check 'closes-after-the-dialog' ($during -and $app.Proc.ExitCode -eq 0) "exit=$($app.Proc.ExitCode)"
  } else { Rep 'close-with-dialog-open' 'NOT TESTED' 'another program was under the pointer: no click was made' }
  Keep-Log $lad 'dialog'
  # During a save: the file is whole afterwards.
  $lad = Lad 'closing-save'
  $file = Join-Path (Folder $lad 'settings') 'prism.settings'
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  $dark = Radio $app 'Dark'; $light = Radio $app 'Light'
  Choose $dark; Choose $light; Choose $dark
  $c = Close-Prism $app
  Check 'close-during-save-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code) in $($c.Ms) ms"
  $text = if (Test-Path $file) { Get-Content $file -Raw } else { '' }
  Check 'file-whole-after-close' ($text -match '(?m)^end ' -and $text -match 'appearance=') "$($text.Length) characters"
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  Check 'no-complaint-after-close-during-save' ($null -eq (Matching $app.Root 'was damaged')) 'the next start reads it'
  # A second launch while the first closes: never two running.
  $env:LOCALAPPDATA = $lad
  [void][W]::PostMessage($app.H, 0x10, [IntPtr]::Zero, [IntPtr]::Zero)
  Start-Sleep -Milliseconds 150
  $second = Start-Process $PrismExe -PassThru; $null = $second.Handle; [void]$script:Started.Add($second)
  $mostWindows = 0
  $watch = [Diagnostics.Stopwatch]::StartNew()
  while ($watch.Elapsed.TotalSeconds -lt 25) {
    $w = 0
    foreach ($q in (Get-Process -ErrorAction SilentlyContinue | Where-Object { try { $_.Path -eq $PrismExe } catch { $false } })) { $w += @([W]::ForPid($q.Id) | Where-Object { [W]::Text($_) -eq $Title }).Count }
    if ($w -gt $mostWindows) { $mostWindows = $w }
    if ($second.HasExited) { break }
    if ($app.Proc.HasExited -and $w -eq 1) { break }
    foreach ($x in [W]::ForPid($second.Id)) { if ([W]::Text($x) -ne $Title) { [void][W]::PostMessage($x, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) } }
    Start-Sleep -Milliseconds 100
  }
  Check 'never-two-running' ($mostWindows -le 1) "at most $mostWindows main window(s)"
  Check 'first-ended' $app.Proc.HasExited 'the first copy closed'
  Rep 'second-launch-outcome' 'INFO' "exited=$($second.HasExited) exit=$(if ($second.HasExited) { $second.ExitCode })"
  Stop-All
  $third = Start-Prism $lad
  Check 'next-start-works' (-not $third.Proc.HasExited) 'nothing stale stops a later start'
  $c = Close-Prism $third
  Check 'next-close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
}

Scenario 'paths' {
  $odd = "PRISM kopya " + [char]0x130 + [char]0x15F + [char]0x131
  $oddCopy = Join-Path $Work $odd
  Copy-Item $Image $oddCopy -Recurse
  $lad = Lad $odd
  $app = Start-Prism $lad (Join-Path $oddCopy 'PRISM.exe')
  Check 'starts-from-a-path-with-spaces-and-turkish-letters' (Test-Path (Folder $lad 'logs')) 'logs are in the folder with those letters'
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
  Check 'log-readable' ((Log-Text $lad) -match 'Shutdown complete') 'the file ends with the shutdown'
  Remove-Item $oddCopy -Recurse -Force -ErrorAction SilentlyContinue
}

# The system colour scheme, in a machine that is thrown away afterwards. UI-7 needs more: the title bar is
# outside this round (WP2), and nothing here says that it is met.
Scenario 'system-theme' {
  if ($Environment -ne 'clean') { Rep 'follows-the-system' 'NOT TESTED' 'the theme of the host is not changed'; return }
  $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize'
  $original = (Get-ItemProperty $key -Name AppsUseLightTheme -ErrorAction SilentlyContinue).AppsUseLightTheme
  $lad = Lad 'system-theme'
  $app = Start-Prism $lad
  Go $app 'Settings'; Start-Sleep -Seconds 1
  # System is the default (UI-7); it is chosen explicitly all the same, so the check does not rest on the default.
  Choose (Radio $app 'System'); Start-Sleep -Seconds 2
  Check 'system-chosen' (IsChosen (Radio $app 'System')) 'System is selected before the system theme changes'
  try {
    Set-ItemProperty $key -Name AppsUseLightTheme -Value 1 -Type DWord -Force
    [W]::ColorSchemeChanged(); Start-Sleep -Seconds 4
    $a = Brightness-Of $app
    Set-ItemProperty $key -Name AppsUseLightTheme -Value 0 -Type DWord -Force
    [W]::ColorSchemeChanged(); Start-Sleep -Seconds 4
    $b = Brightness-Of $app
    Shot $app.H 'system-dark'
    if ([math]::Abs($a - $b) -gt 0.2) { Rep 'follows-the-system' 'PASS' "brightness $a -> $b while running" }
    else { Rep 'follows-the-system' 'FAIL' "brightness $a -> ${b}: no change seen after the system's notification" }
  } finally {
    if ($null -ne $original) { Set-ItemProperty $key -Name AppsUseLightTheme -Value $original -Type DWord -Force } else { Remove-ItemProperty $key -Name AppsUseLightTheme -ErrorAction SilentlyContinue }
  }
  $c = Close-Prism $app
  Check 'close-ends' ($c.Ended -and $c.Code -eq 0) "exit=$($c.Code)"
}

Scenario 'leftovers' {
  Rep 'javafx-cache' 'INFO' "exists=$(Test-Path "$env:USERPROFILE\.openjfx\cache") files=$(@(Get-ChildItem "$env:USERPROFILE\.openjfx\cache" -Recurse -File -ErrorAction SilentlyContinue).Count)"
  Rep 'jna-temp' 'INFO' "$(@(Get-ChildItem $env:TEMP -Recurse -Depth 1 -Filter 'jna*.dll' -ErrorAction SilentlyContinue).Count) jna dll(s) under TEMP"
}

# ----- Verdict. ADR-0017: every one of these must be PASS, and nothing else may be unproved. -----
$script:Current = 'verdict'
$mandatory = @(
  'environment/image-exists', 'environment/cfg-PRISM.cfg', 'environment/cfg-prism-demo.cfg', 'environment/runtime-has-localedata', 'environment/demo-help', 'environment/demo-version',
  'probe/ended', 'probe/localedata-module', 'probe/turkish-number', 'probe/turkish-percent', 'probe/turkish-date', 'probe/turkish-upper-i', 'probe/javafx-toolkit', 'probe/logback-service', 'probe/platform-lists-processes', 'probe/own-row-found', 'probe/runtime-stopped',
  'probe/safe2-CHANGE_PRIORITY-refused', 'probe/safe2-SUSPEND-refused', 'probe/safe2-END_TASK-refused', 'probe/safe2-FORCE_KILL-refused', 'probe/safe2-MANAGE-refused',
  'single-process/no-child-prism', 'single-process/one-prism-process', 'single-process/jvm-from-the-image', 'single-process/ui-own-row-actions-disabled', 'single-process/ui-own-row-says-why', 'single-process/ui-own-row-menu-disabled', 'single-process/own-process-alive-and-running',
  'single-process/second-launch-shows-dialog', 'single-process/second-launch-exits', 'single-process/second-launch-one-main-window', 'single-process/close-ends-the-process', 'single-process/log-says-shutdown-complete', 'single-process/log-no-linkage-error',
  'demo-native/demo-is-one-process', 'demo-native/load-is-measurable', 'demo-native/cpu-reported-native', 'demo-native/demo-in-a-job', 'demo-native/cpu-applied-measured', 'demo-native/cpu-removed-measured', 'demo-native/memory-reported-native', 'demo-native/memory-removal-reported',
  'demo-native/limit-before-close', 'demo-native/close-with-limits-ends', 'demo-native/close-leaves-demo-running', 'demo-native/close-clears-native-cpu',
  'memory-enforced/limit-set', 'memory-enforced/allocation-refused-at-the-limit', 'memory-enforced/committed-within-the-limit', 'memory-enforced/allocation-goes-on-after-removal',
  'memory-clears-at-close/limit-set', 'memory-clears-at-close/allocation-refused-at-the-limit', 'memory-clears-at-close/close-ends', 'memory-clears-at-close/allocation-goes-on-after-close',
  'user-suspension/suspended', 'user-suspension/stays-suspended-after-close', 'user-suspension/next-start-resumes-nothing', 'user-suspension/next-start-lists-it', 'user-suspension/resumed-on-request',
  'hard-kill-native/limit-before-kill', 'hard-kill-native/log-complete-after-kill', 'hard-kill-native/log-holds-the-last-action', 'hard-kill-native/demo-survives-kill', 'hard-kill-native/limit-stays-after-kill', 'hard-kill-native/next-start-reports-it', 'hard-kill-native/next-start-changes-nothing',
  'emulated-close/emulated-measured', 'emulated-close/reported-emulated', 'emulated-close/held-at-close', 'emulated-close/no-thread-left-suspended', 'emulated-close/load-is-back',
  'emulated-hard-kill/killed-while-holding', 'emulated-hard-kill/next-start-resumes-nothing', 'emulated-hard-kill/next-start-lists-it', 'emulated-hard-kill/no-resume-offered-without-recorded-threads', 'emulated-hard-kill/still-suspended-after-review',
  'ctrl-c/users-suspended-before', 'ctrl-c/limiter-at-work-before', 'ctrl-c/signal-while-held', 'ctrl-c/ctrl-c-ends-prism', 'ctrl-c/hook-released-the-limiter', 'ctrl-c/hook-left-the-users',
  'settings/first-start-system', 'settings/light-saved-at-once', 'settings/dark-saved-at-once', 'settings/dark-kept-after-restart', 'settings/damaged-moved-aside', 'settings/damaged-defaults-used', 'settings/newer-file-untouched-after-close',
  'closing/close-at-start-up', 'closing/closes-after-the-dialog', 'closing/close-during-save-ends', 'closing/file-whole-after-close', 'closing/never-two-running', 'closing/first-ended', 'closing/next-start-works',
  'paths/starts-from-a-path-with-spaces-and-turkish-letters', 'paths/log-readable',
  'process-control/priority-changed', 'process-control/suspended', 'process-control/resumed', 'process-control/runs-after-resume', 'process-control/end-task-honest',
  'process-control/force-kill-enter-declines', 'process-control/force-kill-escape-declines', 'process-control/force-kill-ends-demo', 'process-control/force-kill-logged', 'process-control/close-ends',
  'oom-guard/suspended-at-emergency', 'oom-guard/action-logged', 'oom-guard/close-ends', 'oom-guard/close-releases-the-hold', 'oom-guard/load-is-back',
  'oom-hard-kill/killed-while-holding', 'oom-hard-kill/next-start-resumes-nothing', 'oom-hard-kill/next-start-lists-it', 'oom-hard-kill/resume-offered', 'oom-hard-kill/resumed-after-check',
  'keyboard/routes-come-back', 'keyboard/every-stop-named', 'keyboard/focus-visible', 'keyboard/context-menu-by-keyboard', 'keyboard/arrows-move-in-menu', 'keyboard/escape-closes-menu',
  'console-close/close-while-held', 'console-close/console-close-ends-prism', 'console-close/hook-released-the-limiter', 'console-close/hook-left-the-users',
  'demo-ctrl-c/limited-before', 'demo-ctrl-c/ctrl-c-ends-demo', 'demo-ctrl-c/prism-goes-on', 'demo-ctrl-c/prism-noticed', 'demo-ctrl-c/close-ends'
)
# The only checks that may stay open, and only on a host: this script does not touch its windows or its theme.
$byDesign = @()
if ($Environment -eq 'clean') { $mandatory += 'environment/no-jdk', 'settings/open-logs-in-the-file-manager', 'settings/open-settings-in-the-file-manager', 'system-theme/follows-the-system' }
else { $byDesign = @('settings/open-folders', 'system-theme/follows-the-system') }
$missing = @($mandatory | Where-Object { $script:Outcomes[$_] -ne 'PASS' })
$unproved = @($script:Outcomes.Keys | Where-Object { @('FAIL', 'BLOCKED', 'NOT TESTED') -contains $script:Outcomes[$_] -and $byDesign -notcontains $_ } | Sort-Object)
$counts = $script:Outcomes.Values | Group-Object | ForEach-Object { "$($_.Name)=$($_.Count)" }
$proved = $missing.Count -eq 0 -and $unproved.Count -eq 0
$verdict = if ($Scenarios.Count) { 'PARTIAL RUN: no verdict on the image' }
  elseif (-not $proved) { 'IMAGE NOT ACCEPTED' }
  elseif ($Environment -eq 'clean') { 'IMAGE ACCEPTED' }
  else { 'HOST CHECKS PASSED: acceptance needs a clean run as well' }
$summary = @("environment: $Environment", "verdict: $verdict", "checks: $($counts -join ' ')", "mandatory not PASS: $($missing -join ', ')", "unproved: $($unproved -join ', ')")
$summary | Set-Content (Join-Path $Results 'summary.txt') -Encoding UTF8
$summary | ForEach-Object { Write-Host $_ }
if ($proved -and -not $Scenarios.Count) { exit 0 } else { exit 1 }
