@echo off
set CMD=%2
if "%CMD%"=="mkdir" (
    rem mkdir -p /opt/kafka44 -> %3 is -p, %4 is path
    if "%3"=="-p" (
        mkdir "%~4" 2>nul
    ) else (
        mkdir "%~3" 2>nul
    )
    exit /b 0
)
if "%CMD%"=="tar" (
    rem tar -xzf dest -C extract --strip-components=1 -> %3=-xzf, %4=dest, %5=-C, %6=extract
    mkdir "%~6" 2>nul
    echo Kafka Installed Successfully > "%~6\success.txt"
    exit /b 0
)
if "%CMD%"=="cp" (
    rem cp -r extract/. install/ -> %3=-r, %4=extract/., %5=install/
    set SRC=%~4
    set DEST=%~5
    rem Remove trailing /.
    set SRC=%SRC:~0,-2%
    xcopy /E /I /Y "%SRC%\*" "%DEST%" >nul
    exit /b 0
)
if "%CMD%"=="chown" exit /b 0
if "%CMD%"=="ln" exit /b 0
if "%CMD%"=="useradd" exit /b 0
if "%CMD%"=="rm" exit /b 0
if "%CMD%"=="systemctl" exit /b 0
if "%CMD%"=="sudo" exit /b 0

exit /b 0
