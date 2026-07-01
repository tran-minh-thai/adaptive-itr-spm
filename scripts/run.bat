@echo off
REM Generic dispatcher: forwards CLI args to Main.
REM Runs from the results/ directory so that result_*.txt and Summary_Report_*.txt
REM are grouped there instead of scattered across the project root.
setlocal
REM Ensure console can display the Vietnamese UTF-8 headers/reports correctly.
chcp 65001 >nul
pushd "%~dp0.."
set SPM_HOME=%CD%
set JAR=%SPM_HOME%\target\adaptive-itr-spm.jar
if not exist "%JAR%" (
    echo [run] Fat jar not found at %JAR%
    echo [run] Building now...
    call mvn -B -DskipTests=true package || goto :error
)
if not exist "%SPM_HOME%\results" mkdir "%SPM_HOME%\results"
cd /d "%SPM_HOME%\results"

REM Heap size: tune via SPM_HEAP env var; default 16g.
if "%SPM_HEAP%"=="" set SPM_HEAP=16g

java -Xmx%SPM_HEAP% -Dfile.encoding=UTF-8 -Dspm.home="%SPM_HOME%" -jar "%JAR%" %*
set EXITCODE=%ERRORLEVEL%
popd
exit /b %EXITCODE%

:error
popd
exit /b 1
