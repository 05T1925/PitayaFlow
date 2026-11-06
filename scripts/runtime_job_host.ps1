param(
  [ValidateSet('api','worker')][string]$Role,
  [Parameter(Mandatory)][string]$RunDir,
  [Parameter(Mandatory)][ValidatePattern('^[0-9a-f-]{36}$')][string]$LaunchNonce
)
$ErrorActionPreference='Stop'
if(-not $IsWindows){throw 'Windows Job Object supervision is the supported runtime host'}
$run=[IO.Path]::GetFullPath($RunDir)
$repo=Join-Path $run 'source'
$envFile=Join-Path $run 'private.env'
$jar=Join-Path $repo 'services/backend/target/backend-0.1.0-SNAPSHOT.jar'
if(-not (Test-Path -LiteralPath $envFile) -or -not (Test-Path -LiteralPath $jar)){throw 'Missing isolated configuration or built jar'}
Get-Content -LiteralPath $envFile -Encoding utf8 | ForEach-Object {
  if($_ -match '^([A-Z0-9_]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}
}
$env:PF_ROLE=$Role
$env:PF_API_PORT=if($Role -eq 'api'){$env:PF_API_PORT_RUN}else{$env:PF_WORKER_PORT_RUN}
$env:PF_CONTRACTS_DIR=Join-Path $repo 'packages/contracts'
$env:PF_DSH_SOURCE=Join-Path $run 'dsh'
$env:PF_AGENT_BRIDGE_URL="http://127.0.0.1:$($env:PF_API_PORT_RUN)"
$env:PF_PROBE_WORK_DIR=Join-Path $run "probe-$Role"
$env:PF_SHUTDOWN_FILE=Join-Path $run "stop-$Role"
if($Role -eq 'worker' -and (Test-Path -LiteralPath (Join-Path $run 'fault-ffprobe'))){$env:PF_PROBE_FAULT_STREAM_PACKETS='true'}
if($Role -eq 'worker' -and (Test-Path -LiteralPath (Join-Path $run 'fault-home'))){$env:PF_AGENT_INJECT_CLEANUP_FAILURE='1'}
if($Role -eq 'worker' -and (Test-Path -LiteralPath (Join-Path $run 'fault-review-invalid'))){$env:PF_REVIEW_SCENARIO='invalid'}
if($Role -eq 'worker' -and (Test-Path -LiteralPath (Join-Path $run 'fault-review-forged-spoken'))){$env:PF_REVIEW_SCENARIO='forged-spoken'}
New-Item -ItemType Directory -Force -Path $env:PF_PROBE_WORK_DIR | Out-Null
$ownedTemp=Join-Path $run "temp-$Role"
New-Item -ItemType Directory -Force -Path $ownedTemp | Out-Null
if(Test-Path -LiteralPath $env:PF_SHUTDOWN_FILE){throw 'Stale stop marker; use a fresh run directory or inspect prior shutdown'}
$java=(Get-Command java -ErrorAction Stop).Source
$log=Join-Path $run "$Role.log"
$pidFile=Join-Path $run "$Role.pid"
$statusFile=Join-Path $run "$Role.stop"
Add-Type -TypeDefinition @'
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Collections.Generic;
public static class PfRuntimeJob {
  const uint KILL_ON_CLOSE=0x2000;
  [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attr,string name);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int infoClass,byte[] data,uint length);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool TerminateJobObject(IntPtr job,uint exitCode);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job,int infoClass,byte[] data,uint length,out uint returned);
  [DllImport("kernel32.dll",SetLastError=true)] static extern bool CloseHandle(IntPtr handle);
  static int[] Members(IntPtr job) {
    var data=new byte[8192];uint returned;
    if(!QueryInformationJobObject(job,3,data,(uint)data.Length,out returned))throw new Exception("QueryInformationJobObject error="+Marshal.GetLastWin32Error());
    int count=Math.Min(BitConverter.ToInt32(data,4),(data.Length-8)/IntPtr.Size);
    var pids=new int[count];for(int i=0;i<count;i++)pids[i]=(int)BitConverter.ToInt64(data,8+i*8);return pids;
  }
  static int ActiveCount(IntPtr job) {
    var data=new byte[48];uint returned;
    if(!QueryInformationJobObject(job,1,data,(uint)data.Length,out returned))throw new Exception("Job accounting error="+Marshal.GetLastWin32Error());
    return BitConverter.ToInt32(data,40); // JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.ActiveProcesses
  }
  static void Observe(IntPtr job,Dictionary<int,long> owned) {
    foreach(int pid in Members(job))try{using(var member=Process.GetProcessById(pid))owned[pid]=member.StartTime.ToUniversalTime().Ticks;}
      catch(ArgumentException) { /* exited between query and handle open */ }
      catch(InvalidOperationException) { /* exited before StartTime was read */ }
      catch(System.ComponentModel.Win32Exception) { /* exited before StartTime was read */ }
  }
  static bool WaitEmpty(IntPtr job,int millis) {
    var until=Stopwatch.StartNew();
    do {if(ActiveCount(job)==0)return true;Thread.Sleep(50);}while(until.ElapsedMilliseconds<millis);
    return ActiveCount(job)==0;
  }
  static void Append(StreamWriter writer,object gate,string role,string line) {
    if(line==null)return;lock(gate){writer.WriteLine(role+" "+line);writer.Flush();}
  }
  static bool CleanOwned(string directory,string run) {
    string full=Path.GetFullPath(directory),prefix=Path.GetFullPath(run)+Path.DirectorySeparatorChar;
    if(!full.StartsWith(prefix,StringComparison.OrdinalIgnoreCase))throw new Exception("cleanup outside run directory");
    if(!Directory.Exists(full))return true;
    if((File.GetAttributes(full)&FileAttributes.ReparsePoint)!=0)throw new Exception("owned temp is reparse point");
    Directory.Delete(full,true);return !Directory.Exists(full);
  }
  public static int Run(string java,string jar,string cwd,string log,string pidFile,string statusFile,string stopFile,string run,string role,string nonce,string ownedTemp,string probeTemp) {
    IntPtr job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new Exception("CreateJobObject error="+Marshal.GetLastWin32Error());
    Process process=null;bool treeConfirmed=false;
    try {
      var limits=new byte[144];Array.Copy(BitConverter.GetBytes(KILL_ON_CLOSE),0,limits,16,4);
      if(!SetInformationJobObject(job,9,limits,(uint)limits.Length))throw new Exception("SetInformationJobObject error="+Marshal.GetLastWin32Error());
      var psi=new ProcessStartInfo(java);psi.ArgumentList.Add("-Djava.io.tmpdir="+ownedTemp);psi.ArgumentList.Add("-jar");psi.ArgumentList.Add(jar);
      psi.WorkingDirectory=cwd;psi.UseShellExecute=false;psi.CreateNoWindow=true;
      psi.RedirectStandardOutput=true;psi.RedirectStandardError=true;
      using(var writer=new StreamWriter(log,false)) {
        var gate=new object();process=new Process();process.StartInfo=psi;
        process.OutputDataReceived+=(s,e)=>Append(writer,gate,"OUT",e.Data);
        process.ErrorDataReceived+=(s,e)=>Append(writer,gate,"ERR",e.Data);
        if(!process.Start())throw new Exception("Java start returned false");
        // Assignment occurs before Spring can spawn its workers. Failure closes this exact process.
        if(!AssignProcessToJobObject(job,process.Handle)) {
          int error=Marshal.GetLastWin32Error();
          process.Kill(true);
          if(!process.WaitForExit(5000))throw new Exception("AssignProcessToJobObject cleanup unconfirmed error="+error);
          throw new Exception("AssignProcessToJobObject error="+error);
        }
        long rootStart=process.StartTime.ToUniversalTime().Ticks;
        File.WriteAllText(pidFile,process.Id+","+rootStart);
        process.BeginOutputReadLine();process.BeginErrorReadLine();
        bool stopRequested=false;var observed=new Dictionary<int,long>();observed[process.Id]=rootStart;
        while(!process.WaitForExit(200)) {
          Observe(job,observed);
          if(File.Exists(stopFile)){stopRequested=true;break;}
        }
        if(stopRequested && !process.WaitForExit(5000)) Append(writer,gate,"HOST","graceful stop deadline exceeded; terminating owned Job Object");
        Observe(job,observed);
        bool terminated=ActiveCount(job)==0 || TerminateJobObject(job,1);
        treeConfirmed=terminated && WaitEmpty(job,5000);
        Observe(job,observed);
        int[] owned=new int[observed.Count];observed.Keys.CopyTo(owned,0);Array.Sort(owned);
        var identities=new List<string>();foreach(int pid in owned)identities.Add(pid+":"+observed[pid]);
        CloseHandle(job);job=IntPtr.Zero; // KILL_ON_CLOSE covers descendants after Java exits too.
        process.WaitForExit(5000);
        bool homeRemoved=false,probeRemoved=false;
        if(treeConfirmed && process.HasExited){try{homeRemoved=CleanOwned(ownedTemp,run);}catch(Exception e){Append(writer,gate,"HOST","owned home cleanup="+e.GetType().Name);}
          try{probeRemoved=CleanOwned(probeTemp,run);}catch(Exception e){Append(writer,gate,"HOST","probe temp cleanup="+e.GetType().Name);}}
        File.WriteAllText(statusFile,"run="+run+"\nrole="+role+"\nlaunch_nonce="+nonce+"\nroot_pid="+process.Id+"\nroot_start_ticks="+rootStart+"\nroot_exited="+process.HasExited+"\nowned_pids="+String.Join(",",owned)+"\nowned_instances="+String.Join(",",identities)+"\ntermination_succeeded="+terminated+"\nowned_stopped="+treeConfirmed+"\nhome_removed="+homeRemoved+"\nprobe_temp_removed="+probeRemoved+"\nstop_requested="+stopRequested+"\n");
        return process.HasExited && treeConfirmed && homeRemoved && probeRemoved ? 0 : 2;
      }
    } finally {
      if(job!=IntPtr.Zero){try{if(ActiveCount(job)>0)TerminateJobObject(job,1);treeConfirmed=WaitEmpty(job,5000);}catch(Exception){}CloseHandle(job);}
      if(process!=null){try{if(!process.HasExited){process.Kill(true);process.WaitForExit(5000);}}catch(InvalidOperationException){}}
      // An exceptional exit has no complete stop record. Retain both directories
      // even if the Job became empty so the caller cannot mistake cleanup for proof.
      if(process!=null)process.Dispose();
    }
  }
}
'@
$code=1
try { $code=[PfRuntimeJob]::Run($java,$jar,$repo,$log,$pidFile,$statusFile,$env:PF_SHUTDOWN_FILE,$run,$Role,$LaunchNonce,$ownedTemp,$env:PF_PROBE_WORK_DIR) }
catch { [Console]::Error.WriteLine("runtime_job_host role=$Role classification=$($_.Exception.GetType().Name) message=$($_.Exception.Message)") }
finally { $code | Set-Content -LiteralPath (Join-Path $run "$Role.host.exit") -Encoding ascii }
exit $code
