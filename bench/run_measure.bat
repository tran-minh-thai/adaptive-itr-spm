@echo off
rem Measured stream benchmark: runs every line of bench\plan.txt (or one phase of it) with the
rem prebuilt jar. Needs only a Java runtime: java on PATH, or JAVA_HOME set.
rem
rem   bench\run_measure.bat            every phase, in plan order
rem   bench\run_measure.bat main-time  one phase (main-time, main-live, tbr, phi, eta-lambda,
rem                                    batch-count, kosarak-scale)
rem
rem Results go to results\stream-bench\<COMPUTERNAME>\ : one CSV and one .meta.txt per run,
rem machine.txt, java-version.txt, runtime.lock and progress.log.
rem Running the same command again resumes: a run whose CSV exists is skipped (exit code 4).
rem Heap: set BENCH_XMX before running to change it (default 8g, used for -Xms and -Xmx).
rem
rem Before a long campaign: pause Windows Update, select the High performance power plan,
rem exclude this folder from Defender real-time scanning, keep the machine on mains power and
rem disable sleep (run.txt in the project keeps the machine awake while this script runs).

setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0.."
set "ONLY=%~1"
if not defined BENCH_XMX set "BENCH_XMX=8g"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
set "JAR=bench\adaptive-itr-spm-bench.jar"
if not exist "%JAR%" (
  echo [run_measure] missing %CD%\%JAR%
  exit /b 2
)
if not exist "bench\plan.txt" (
  echo [run_measure] missing %CD%\bench\plan.txt
  exit /b 2
)
set "OUT=results\stream-bench\%COMPUTERNAME%"
if not exist "%OUT%" mkdir "%OUT%"
"%JAVA%" -version > "%OUT%\java-version.txt" 2>&1
if errorlevel 1 (
  echo [run_measure] cannot start Java with "%JAVA%"
  exit /b 2
)
set /a NDONE=0, NSKIP=0, NLIMIT=0, NDIFF=0, NFAIL=0, NSEEN=0
for /f "usebackq eol=# tokens=1-6 delims=;" %%a in ("bench\plan.txt") do (
  set "GO=0"
  if "!ONLY!"=="" set "GO=1"
  if /i "!ONLY!"=="%%a" set "GO=1"
  if "!GO!"=="1" (
    set /a NSEEN+=1
    echo [!date! !time!] %%a  %%b  %%c  %%d  %%e
    "%JAVA%" -Xms%BENCH_XMX% -Xmx%BENCH_XMX% -cp "%JAR%" com.adaptive.itr.spm.StreamBench --config=%%b --arm=%%c --mode=%%d --tag=%%e --out=%OUT% --lock=%OUT%\runtime.lock %%f
    set "RC=!errorlevel!"
    >> "%OUT%\progress.log" echo !date! !time! phase=%%a config=%%b arm=%%c mode=%%d tag=%%e exit=!RC!
    if "!RC!"=="0" set /a NDONE+=1
    if "!RC!"=="4" set /a NSKIP+=1
    if "!RC!"=="5" set /a NLIMIT+=1
    if "!RC!"=="3" set /a NDIFF+=1
    if "!RC!"=="6" (
      echo [run_measure] the Java runtime differs from %OUT%\runtime.lock; stopping
      exit /b 6
    )
    if not "!RC!"=="0" if not "!RC!"=="3" if not "!RC!"=="4" if not "!RC!"=="5" set /a NFAIL+=1
  )
)
if "!NSEEN!"=="0" (
  echo [run_measure] no plan line matches phase "%ONLY%"
  exit /b 2
)
echo [run_measure] runs=!NSEEN! done=!NDONE! skipped_existing=!NSKIP! time_or_heap_limit=!NLIMIT! verify_diff=!NDIFF! failed=!NFAIL!
>> "%OUT%\progress.log" echo !date! !time! SUMMARY phase=%ONLY% runs=!NSEEN! done=!NDONE! skipped=!NSKIP! limit=!NLIMIT! diff=!NDIFF! failed=!NFAIL!
if not "!NFAIL!"=="0" exit /b 1
exit /b 0
