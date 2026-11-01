#!/bin/bash
# Installe une fois pour toutes les outils nécessaires pour compiler le launcher.
# Usage : bash setup.sh
set -e
cd "$(dirname "$0")"

echo "==> 1/4 Homebrew"
if ! command -v brew >/dev/null 2>&1; then
  if [ -x /opt/homebrew/bin/brew ]; then
    eval "$(/opt/homebrew/bin/brew shellenv)"
  elif [ -x /usr/local/bin/brew ]; then
    eval "$(/usr/local/bin/brew shellenv)"
  else
    echo "Homebrew n'est pas installé : installation (ton mot de passe Mac sera demandé)."
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
    if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; else eval "$(/usr/local/bin/brew shellenv)"; fi
  fi
fi

echo "==> 2/4 Java 17, Gradle et outils Android"
brew install openjdk@17 gradle
brew install --cask android-commandlinetools android-platform-tools

JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
export JAVA_HOME ANDROID_HOME

echo "==> 3/4 SDK Android (quelques minutes)"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0"

echo "==> 4/4 Gradle wrapper"
if [ ! -f gradlew ]; then
  TMP="$(mktemp -d)"
  ( cd "$TMP" && touch settings.gradle.kts && gradle wrapper --gradle-version 8.11.1 -q )
  cp -R "$TMP/gradlew" "$TMP/gradle" .
  rm -rf "$TMP"
fi

echo ""
echo "C'est prêt. Étape suivante :"
echo "  1. Colle tes clés dans secrets.properties"
echo "  2. Lance : bash installer.sh"
