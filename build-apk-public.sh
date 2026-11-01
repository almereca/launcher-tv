#!/bin/bash
# Fabrique un APK à partager (par exemple sur GitHub), SANS aucune clé API dedans.
# Chacun colle ensuite ses propres clés depuis le téléphone (guide de démarrage).
# Usage : bash build-apk-public.sh   →   dist/launcher-tv.apk
set -e
SRC="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HOME/Library/Caches/launcher-tv-public"
if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; elif [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"

mkdir -p "$BUILD" "$SRC/dist"
rsync -a --delete --exclude '.gradle' --exclude 'build' --exclude 'local.properties' \
  --exclude 'secrets.properties' --exclude 'captures' --exclude 'Claude' --exclude 'dist' "$SRC/" "$BUILD/"
# Clés vides, quoi qu'il y ait dans ton secrets.properties.
printf 'TMDB_KEY=\nYOUTUBE_KEY=\nUNSPLASH_KEY=\n' > "$BUILD/secrets.properties"
echo "sdk.dir=$ANDROID_HOME" > "$BUILD/local.properties"
( cd "$BUILD" && bash gradlew --console=plain -q clean assembleDebug )
cp "$BUILD/app/build/outputs/apk/debug/app-debug.apk" "$SRC/dist/launcher-tv.apk"

# Vérification : aucune de tes clés ne doit se retrouver dans l'APK.
if [ -f "$SRC/secrets.properties" ]; then
  while IFS='=' read -r k v; do
    v="$(echo "$v" | tr -d '[:space:]')"
    [ ${#v} -ge 10 ] || continue
    if unzip -p "$SRC/dist/launcher-tv.apk" | LC_ALL=C grep -aq -- "$v"; then
      echo "ATTENTION : la clé $k se trouve dans l'APK. Ne le publie pas."
      rm -f "$SRC/dist/launcher-tv.apk"; exit 1
    fi
  done < "$SRC/secrets.properties"
fi
echo "APK sans clé prêt : dist/launcher-tv.apk"
