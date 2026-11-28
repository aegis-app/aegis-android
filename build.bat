@echo off
rem One-command build (Windows): the build pipeline is a bash script, so this
rem runs it through WSL. Inside WSL the project must be reachable; run this
rem from the project root. For a release-signed APK, set RELEASE_KEYSTORE /
rem RELEASE_KEYSTORE_PASS / RELEASE_KEY_ALIAS in WSL and run ./build-apk.sh release.
wsl bash -lc "cd \"$(wslpath -a '%CD%')\" && ./build-apk.sh debug"
