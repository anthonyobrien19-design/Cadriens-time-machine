#!/usr/bin/env bash
# Builds TimeMachine.apk inside the Codespace. (GitHub also builds it automatically on every push.)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -x "$HOME/gradle-8.9/bin/gradle" ] || bash scripts/setup-codespace.sh
export ANDROID_HOME="$HOME/android-sdk"
git pull --quiet || true   # pick up the signing key if GitHub already made it
if [ ! -f keystore/release.jks ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/release.jks -alias timemachine -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass timemachine -keypass timemachine -dname "CN=Time Machine, O=Family"
  git add keystore/release.jks && git commit -m "Save the app signing key" && git push
  echo "Created and saved the signing key."
fi
"$HOME/gradle-8.9/bin/gradle" assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk TimeMachine.apk
echo
echo "Done: TimeMachine.apk is in the file list on the left. Right-click it → Download."
