@echo off
rem TTL VPN build: Go engine (gomobile) + Android APK.
rem   build.bat           engine + debug APK
rem   build.bat install   same, then install it on the connected phone
rem   build.bat app       skip the engine and reuse engine\build\ttlvpn.aar
rem   build.bat release   engine + signed release APK, verified, copied to dist\
rem   build.bat help      show usage
rem
rem Paths are resolved from this script's location, so it runs from any directory.
rem setlocal also restores the caller's current directory when the script exits.
rem Error messages avoid ( ) & | < > on purpose: they would break cmd's parsing.
setlocal EnableExtensions

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "ENGINE=%ROOT%\engine"
set "ANDROID_DIR=%ROOT%\android"
set "AAR=%ENGINE%\build\ttlvpn.aar"
set "APK=%ANDROID_DIR%\app\build\outputs\apk\debug\app-debug.apk"
set "GRADLE_TASK=assembleDebug"
set "BUILD_KIND=debug"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=all"
if not "%~2"=="" goto :bad_args
if /i "%MODE%"=="help" goto :usage
if /i "%MODE%"=="-h" goto :usage
if /i "%MODE%"=="--help" goto :usage
if /i "%MODE%"=="all" goto :prereqs
if /i "%MODE%"=="install" goto :prereqs
if /i "%MODE%"=="app" goto :prereqs
if /i "%MODE%"=="release" goto :release_mode
goto :bad_args

:release_mode
set "APK=%ANDROID_DIR%\app\build\outputs\apk\release\app-release.apk"
set "GRADLE_TASK=assembleRelease"
set "BUILD_KIND=signed release"
set "DIST=%ROOT%\dist"
goto :prereqs

rem ---------------------------------------------------------------- prerequisites
:prereqs
echo.
echo == Checking prerequisites [mode: %MODE%]

where go >nul 2>&1
if errorlevel 1 set "MSG=Go not found on PATH. Install it from https://go.dev/dl/ and reopen the terminal." & goto :die
echo    go        OK

if /i "%MODE%"=="app" goto :check_java
where gomobile >nul 2>&1
if errorlevel 1 set "MSG=gomobile not found on PATH. Run: go install golang.org/x/mobile/cmd/gomobile@latest - and add the Go bin folder, see: go env GOPATH, to PATH." & goto :die
where gobind >nul 2>&1
if errorlevel 1 set "MSG=gobind not found on PATH. Run: go install golang.org/x/mobile/cmd/gobind@latest" & goto :die
echo    gomobile  OK

:check_java
rem Gradle uses JAVA_HOME, not whatever java/javac happens to be first on PATH.
if not defined JAVA_HOME set "MSG=JAVA_HOME is not set. Point it to a JDK 17+, for example Android Studio's jbr folder." & goto :die
if exist "%JAVA_HOME%\bin\javac.exe" goto :java_ok
set "MSG=JAVA_HOME is not a JDK: no bin\javac.exe in %JAVA_HOME%"
goto :die
:java_ok
echo    JDK       %JAVA_HOME%

if not defined ANDROID_HOME set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME set "MSG=ANDROID_HOME is not set. Point it to the Android SDK, see Android Studio: Settings, Languages and Frameworks, Android SDK." & goto :die
if exist "%ANDROID_HOME%\" goto :sdk_ok
set "MSG=ANDROID_HOME points to a missing folder: %ANDROID_HOME%"
goto :die
:sdk_ok
echo    SDK       %ANDROID_HOME%

if /i not "%MODE%"=="release" goto :check_ndk
rem Only checks that signing is set up at all, so a missing setup fails before the
rem engine build; Gradle does the full check. Values are never read or printed here:
rem Gradle reads them itself, so no password ends up on a command line.
if defined TTLVPN_KEYSTORE_PROPERTIES goto :signing_set
if defined TTLVPN_KEYSTORE goto :signing_set
set "MSG=Release signing is not configured. Set TTLVPN_KEYSTORE_PROPERTIES to a properties file outside the repo, or the TTLVPN_KEYSTORE* variables. See android\keystore.properties.example and the README, Releasing."
goto :die
:signing_set
echo    signing   configured, checked by Gradle

rem apksigner from the newest build-tools. The sort is alphabetical, like the NDK one.
set "APKSIGNER="
for /f "delims=" %%d in ('dir /b /ad /o:n "%ANDROID_HOME%\build-tools" 2^>nul') do if exist "%ANDROID_HOME%\build-tools\%%d\apksigner.bat" set "APKSIGNER=%ANDROID_HOME%\build-tools\%%d\apksigner.bat"
if not defined APKSIGNER set "MSG=apksigner not found. Install Android SDK Build-Tools in Android Studio: SDK Manager, SDK Tools." & goto :die
echo    apksigner %APKSIGNER%

:check_ndk

if /i "%MODE%"=="app" goto :check_adb
rem gomobile reads ANDROID_NDK_HOME. Without it, take the last NDK folder by name;
rem that sort is alphabetical, so set ANDROID_NDK_HOME when several are installed.
if defined ANDROID_NDK_HOME goto :ndk_given
for /f "delims=" %%d in ('dir /b /ad /o:n "%ANDROID_HOME%\ndk" 2^>nul') do set "ANDROID_NDK_HOME=%ANDROID_HOME%\ndk\%%d"
if not defined ANDROID_NDK_HOME set "MSG=No Android NDK found. Install it in Android Studio: SDK Manager, SDK Tools, NDK Side by side - or set ANDROID_NDK_HOME." & goto :die
:ndk_given
if exist "%ANDROID_NDK_HOME%\source.properties" goto :ndk_ok
set "MSG=ANDROID_NDK_HOME is not an NDK folder: %ANDROID_NDK_HOME%"
goto :die
:ndk_ok
echo    NDK       %ANDROID_NDK_HOME%

:check_adb
rem The phone is checked before building, so "install" never builds for minutes and
rem only then fails on a missing device.
if /i not "%MODE%"=="install" goto :prereqs_done
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if exist "%ADB%" goto :adb_found
set "ADB="
for /f "delims=" %%a in ('where adb 2^>nul') do if not defined ADB set "ADB=%%a"
if not defined ADB set "MSG=adb not found in the SDK platform-tools or on PATH. Install Android SDK Platform-Tools." & goto :die
:adb_found
echo    adb       %ADB%

if defined ANDROID_SERIAL goto :check_serial
set /a DEVICES=0
set /a UNAUTHORIZED=0
for /f "tokens=1,2" %%a in ('call "%ADB%" devices') do (
    if "%%b"=="device" set /a DEVICES+=1
    if "%%b"=="unauthorized" set /a UNAUTHORIZED+=1
)
if %UNAUTHORIZED% GTR 0 set "MSG=The phone is connected but not authorized. Unlock it and accept the USB debugging prompt." & goto :die
if %DEVICES%==0 set "MSG=No device connected. Plug in the phone with USB debugging enabled, then check: adb devices" & goto :die
if %DEVICES% GTR 1 set "MSG=%DEVICES% devices connected. Set ANDROID_SERIAL to the one to install on, see: adb devices" & goto :die
echo    device    OK
goto :prereqs_done

:check_serial
set "STATE="
for /f "delims=" %%s in ('call "%ADB%" -s %ANDROID_SERIAL% get-state 2^>nul') do set "STATE=%%s"
if not "%STATE%"=="device" set "MSG=Device %ANDROID_SERIAL% from ANDROID_SERIAL is not connected or not authorized." & goto :die
echo    device    %ANDROID_SERIAL%

:prereqs_done

rem ---------------------------------------------------------------- engine
if /i "%MODE%"=="app" goto :reuse_engine
echo.
echo == Building the Go engine
if not exist "%ENGINE%\build\" mkdir "%ENGINE%\build"
if errorlevel 1 set "MSG=Could not create engine\build." & goto :die
cd /d "%ENGINE%"
if errorlevel 1 set "MSG=Could not enter the engine folder." & goto :die
rem -s -w strip the ELF symbol table and DWARF debug info. Panic traces keep function
rem names and file:line: those come from Go's own pclntab, which is not stripped.
rem -trimpath records module-relative source paths instead of this machine's absolute
rem ones, which would otherwise ship in the APK with the Windows user name.
gomobile bind -target=android/arm64 -androidapi 24 -trimpath -ldflags="-s -w" -o build\ttlvpn.aar ./ttlvpn
if errorlevel 1 set "MSG=Engine build failed, see the gomobile output above." & goto :die
if not exist "%AAR%" set "MSG=gomobile reported success but the AAR is missing: %AAR%" & goto :die
echo    %AAR%
goto :build_app

:reuse_engine
echo.
echo == Skipping the engine, reusing the existing AAR
if not exist "%AAR%" set "MSG=Engine AAR not found: %AAR% - run build.bat without app first." & goto :die
echo    %AAR%

rem ---------------------------------------------------------------- app
:build_app
echo.
echo == Building the Android %BUILD_KIND% APK
cd /d "%ANDROID_DIR%"
if errorlevel 1 set "MSG=Could not enter the android folder." & goto :die
rem "call" is required: without it, control never returns from gradlew.bat. Full path,
rem since cmd may be set not to search the current directory for commands.
call "%ANDROID_DIR%\gradlew.bat" %GRADLE_TASK%
if errorlevel 1 set "MSG=Gradle build failed, see the output above." & goto :die
if not exist "%APK%" set "MSG=Gradle reported success but the APK is missing: %APK%" & goto :die

if /i "%MODE%"=="release" goto :verify_release
if /i not "%MODE%"=="install" goto :done
echo.
echo == Installing on the phone
rem -r keeps the app's data. adb honors ANDROID_SERIAL if it is set.
"%ADB%" install -r "%APK%"
if errorlevel 1 set "MSG=adb install failed, see the output above." & goto :die

:done
echo.
echo == Done
echo    APK: %APK%
if /i "%MODE%"=="install" echo    Installed on the connected phone.
exit /b 0

rem ---------------------------------------------------------------- release
:verify_release
echo.
echo == Verifying the signature
rem Certificate details are public: anyone can print them from the APK with apksigner.
set "SIGN_INFO=%ANDROID_DIR%\app\build\outputs\apk\release\apksigner-verify.txt"
call "%APKSIGNER%" verify --verbose --print-certs "%APK%" > "%SIGN_INFO%" 2>&1
set "VERIFY_FAILED=%ERRORLEVEL%"
type "%SIGN_INFO%"
if not "%VERIFY_FAILED%"=="0" set "MSG=apksigner could not verify the release APK, see above." & goto :die
findstr /b /c:"Verifies" "%SIGN_INFO%" >nul
if errorlevel 1 set "MSG=apksigner did not report Verifies for the release APK." & goto :die
findstr /c:"CN=Android Debug" "%SIGN_INFO%" >nul
if not errorlevel 1 set "MSG=The release APK is signed with the DEBUG key. Check the signing setup." & goto :die
set "CERT_SHA="
for /f "tokens=2 delims=:" %%c in ('findstr /c:"Signer #1 certificate SHA-256 digest:" "%SIGN_INFO%"') do set "CERT_SHA=%%c"
if not defined CERT_SHA set "MSG=Could not read the signing certificate SHA-256 from apksigner." & goto :die
set "CERT_SHA=%CERT_SHA: =%"

rem The version comes from the line   versionName = "x.y.z"   in the app's Gradle file.
set "VERSION="
for /f "usebackq tokens=3" %%v in (`findstr /c:"versionName = " "%ANDROID_DIR%\app\build.gradle.kts"`) do if not defined VERSION set "VERSION=%%~v"
if not defined VERSION set "MSG=Could not read versionName from android\app\build.gradle.kts." & goto :die

if not exist "%DIST%\" mkdir "%DIST%"
if errorlevel 1 set "MSG=Could not create the dist folder." & goto :die
set "OUT=%DIST%\TTL-VPN-%VERSION%.apk"
copy /y "%APK%" "%OUT%" >nul
if errorlevel 1 set "MSG=Could not copy the APK to %OUT%" & goto :die

set "APK_SHA="
for /f "skip=1 delims=" %%h in ('certutil -hashfile "%OUT%" SHA256') do if not defined APK_SHA set "APK_SHA=%%h"
if not defined APK_SHA set "MSG=certutil could not hash %OUT%" & goto :die
set "APK_SHA=%APK_SHA: =%"

echo.
echo == Done: signed release %VERSION%
echo    APK:                          %OUT%
echo    APK SHA-256:                  %APK_SHA%
echo    Signing certificate SHA-256:  %CERT_SHA%
echo.
echo    The APK SHA-256 goes in the release notes. The certificate SHA-256 is the same
echo    for every release signed with this key: it goes in the README.
exit /b 0

rem ---------------------------------------------------------------- helpers
:usage
echo Usage: build.bat [install ^| app ^| release ^| help]
echo.
echo   build.bat           Build the Go engine, then the Android debug APK
echo   build.bat install   Same, then install it on the connected phone via adb
echo   build.bat app       Skip the engine and reuse engine\build\ttlvpn.aar
echo   build.bat release   Build the engine, then a signed release APK; verify it with
echo                       apksigner, copy it to dist\ and print its SHA-256 and the
echo                       signing certificate SHA-256
echo   build.bat help      Show this help
echo.
echo Needs: Go, gomobile + gobind, JAVA_HOME pointing to a JDK, ANDROID_HOME,
echo and the Android NDK. ANDROID_NDK_HOME is used if set. install also needs adb
echo and exactly one connected phone, or ANDROID_SERIAL set to choose one.
echo release also needs SDK Build-Tools and the signing setup: TTLVPN_KEYSTORE_PROPERTIES
echo or the TTLVPN_KEYSTORE* variables, see the README section Releasing.
exit /b 0

:bad_args
echo Unknown option: %*
echo.
call :usage
exit /b 1

:die
echo.
echo FAILED: %MSG%
exit /b 1
