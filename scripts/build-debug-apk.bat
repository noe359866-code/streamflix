@echo off
REM Streamflix Reborn2 - Android TV VOD - Build Debug APK (Windows)
REM Ejecuta ./gradlew assembleDebug y genera el APK de pruebas

setlocal enabledelayedexpansion
set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."
pushd "%ROOT_DIR%"

echo ============================================
echo  Streamflix - Compilando APK Debug (TV VOD)
echo ============================================
echo Proyecto: %ROOT_DIR%
echo.

if not exist "gradlew.bat" (
  echo ERROR: gradlew.bat no encontrado en %ROOT_DIR%
  exit /b 1
)

echo [1/2] Verificando entorno Gradle...
call gradlew.bat --version
if errorlevel 1 exit /b %errorlevel%

echo.
echo [2/2] Ejecutando gradlew assembleDebug --stacktrace...
call gradlew.bat assembleDebug --stacktrace
if errorlevel 1 exit /b %errorlevel%

echo.
echo ============================================
echo  BUILD SUCCESSFUL
echo ============================================
set "APK_PATH=app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" (
  echo APK generado: %APK_PATH%
  dir "%APK_PATH%"
) else (
  echo APKs en app\build\outputs\apk\debug\:
  dir /s /b app\build\outputs\apk\debug\*.apk 2>nul
)
echo.
echo Instalacion en Android TV:
echo   adb install -r %APK_PATH%

popd
endlocal
