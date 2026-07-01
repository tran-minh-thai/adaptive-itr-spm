@echo off
REM Build fat jar via Maven Shade plugin.
setlocal
pushd "%~dp0.."
call mvn -B -DskipTests=true clean package
set EXITCODE=%ERRORLEVEL%
popd
exit /b %EXITCODE%
