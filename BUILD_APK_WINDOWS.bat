@echo off
setlocal
cd /d "%~dp0"
echo === ZlapEgzamin Android TEST - budowanie APK ===
echo Wymagane: Android Studio z SDK Platform 35 i internet do pobrania Gradle.
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JAVA_HOME (
  echo [BLAD] Nie znaleziono JDK. Zainstaluj Android Studio lub ustaw JAVA_HOME.
  pause
  exit /b 1
)
if not defined ANDROID_HOME (
  echo [BLAD] Nie znaleziono Android SDK. Otworz Android Studio i zainstaluj SDK 35.
  pause
  exit /b 1
)
set "GRADLE_DIR=%USERPROFILE%\.gradle\zlap-local-gradle"
set "GRADLE_BIN=%GRADLE_DIR%\gradle-8.13\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
  echo Pobieram Gradle 8.13 (tylko raz, wymagany internet)...
  if not exist "%GRADLE_DIR%" mkdir "%GRADLE_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; $zip=Join-Path $env:TEMP 'zlap-gradle-8.13.zip'; Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath $env:USERPROFILE\.gradle\zlap-local-gradle -Force; Remove-Item $zip -Force"
  if errorlevel 1 (
    echo [BLAD] Nie udalo sie pobrac Gradle. Mozesz otworzyc projekt recznie w Android Studio.
    pause
    exit /b 1
  )
)
echo Buduje debug APK...
call "%GRADLE_BIN%" --no-daemon assembleDebug
if errorlevel 1 (
  echo [BLAD] Gradle nie zbudowal APK. Sprawdz blad powyzej.
  pause
  exit /b 1
)
echo.
echo GOTOWE: %~dp0app\build\outputs\apk\debug\app-debug.apk
pause
