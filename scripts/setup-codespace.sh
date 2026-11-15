#!/usr/bin/env bash
# Installs the Android build tools inside the Codespace (runs automatically when the Codespace is created).
set -euo pipefail
SDK="$HOME/android-sdk"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android command-line tools…"
  mkdir -p "$SDK/cmdline-tools"
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q /tmp/cmdtools.zip -d /tmp/cmdtools && mv /tmp/cmdtools/cmdline-tools "$SDK/cmdline-tools/latest"
fi
export ANDROID_HOME="$SDK"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-35" "build-tools;35.0.0" "platform-tools" >/dev/null
if [ ! -x "$HOME/gradle-8.9/bin/gradle" ]; then
  echo "Installing Gradle…"
  curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q /tmp/gradle.zip -d "$HOME"
fi
grep -q ANDROID_HOME "$HOME/.bashrc" || {
  echo "export ANDROID_HOME=$SDK" >> "$HOME/.bashrc"
  echo "export PATH=\$HOME/gradle-8.9/bin:\$PATH" >> "$HOME/.bashrc"
}
echo "sdk.dir=$SDK" > local.properties
echo "Ready. To build here, run:  bash scripts/build.sh"
