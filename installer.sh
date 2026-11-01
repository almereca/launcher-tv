#!/bin/bash
# Compile le launcher et l'installe sur la TV.
# Usage : bash installer.sh              (la première fois, l'adresse IP de la TV est demandée puis retenue)
#         bash installer.sh 192.168.x.x  (autre TV, ou nouvelle adresse)
set -e
# Adresse de la TV : donnée en argument, sinon retenue dans .tv-ip (fichier local, jamais publié).
DIR="$(cd "$(dirname "$0")" && pwd)"
TV_IP="${1:-$(cat "$DIR/.tv-ip" 2>/dev/null)}"
if [ -z "$TV_IP" ]; then
  read -r -p "Adresse IP de la TV (Paramètres > Réseau, ex. 192.168.1.42) : " TV_IP
fi
TV_IP="${TV_IP%%:*}"
echo "$TV_IP" > "$DIR/.tv-ip"
TV="$TV_IP:5555"
PKG="be.alex.launcher"
SRC="$(cd "$(dirname "$0")" && pwd)"
# On compile hors iCloud pour ne pas faire synchroniser des milliers de fichiers temporaires.
BUILD="$HOME/Library/Caches/launcher-tv-build"

if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; elif [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"

if [ ! -f "$SRC/gradlew" ]; then echo "Lance d'abord : bash setup.sh"; exit 1; fi
# Les clés peuvent aussi être collées plus tard depuis le téléphone (Réglages > Clés API).
[ -f "$SRC/secrets.properties" ] || printf 'TMDB_KEY=\nYOUTUBE_KEY=\nUNSPLASH_KEY=\n' > "$SRC/secrets.properties"
grep -Eq '^TMDB_KEY=.+' "$SRC/secrets.properties" || echo "Note : pas de clé TMDB ici. Pas grave : le téléphone te guidera pour la coller à la première connexion."

echo "==> Compilation (la première fois prend quelques minutes)"
mkdir -p "$BUILD"
rsync -a --delete --exclude '.gradle' --exclude 'build' --exclude 'local.properties' "$SRC/" "$BUILD/"
echo "sdk.dir=$ANDROID_HOME" > "$BUILD/local.properties"
( cd "$BUILD" && bash gradlew --console=plain -q assembleDebug ) 2>&1 | tee "$SRC/derniere-compilation.log"
[ "${PIPESTATUS[0]}" -eq 0 ] || { echo ""; echo "La compilation a échoué. Le détail est dans derniere-compilation.log."; exit 1; }
APK="$BUILD/app/build/outputs/apk/debug/app-debug.apk"

echo "==> Connexion à la TV ($TV)"
adb connect "$TV" >/dev/null || true
echo "Si la TV affiche « Autoriser le débogage ? », coche « Toujours autoriser » puis OK."
for i in $(seq 1 30); do
  STATE="$(adb -s "$TV" get-state 2>/dev/null || true)"
  [ "$STATE" = "device" ] && break
  sleep 2
  adb connect "$TV" >/dev/null 2>&1 || true
done
if [ "$(adb -s "$TV" get-state 2>/dev/null || true)" != "device" ]; then
  echo "Impossible de joindre la TV. Vérifie que le débogage est activé et que la TV est allumée."
  exit 1
fi

echo "==> Installation"
adb -s "$TV" install -r "$APK"

echo "==> Permission « Reprendre / Épisode suivant »"
adb -s "$TV" shell pm grant "$PKG" com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null || echo "   (à accepter sur la TV au premier lancement)"

echo "==> Bouton Home"
adb -s "$TV" shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null 2>&1 || true
HOME_NOW="$(adb -s "$TV" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tr -d '\r' | tail -1)"
if [[ "$HOME_NOW" == "$PKG"* ]]; then
  echo "   Le launcher est l'accueil par défaut."
else
  echo "   La TV garde son accueil ($HOME_NOW) : le service du launcher prendra le relais."
fi

echo "==> Service du launcher (bouton Home de secours, touche Retour de la télécommande du téléphone)"
CUR="$(adb -s "$TV" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r')"
SVC="$PKG/$PKG.HomeService"
if [[ "$CUR" != *"$SVC"* ]]; then
  if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then NEW="$SVC"; else NEW="$CUR:$SVC"; fi
  adb -s "$TV" shell settings put secure enabled_accessibility_services "$NEW"
fi
adb -s "$TV" shell settings put secure accessibility_enabled 1

echo "==> Espace Enfants (détection de la fin d'un épisode ou d'un film)"
adb -s "$TV" shell cmd notification allow_listener "$PKG/$PKG.MediaWatcher" >/dev/null 2>&1 \
  && echo "   Accès accordé." || echo "   Accès refusé par la TV : l'option « À la fin du contenu » ne sera pas disponible."

echo "==> Minuteur (affichage par-dessus les apps)"
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
  && echo "   Autorisé." || echo "   Refusé : le minuteur préviendra seulement par un message."

echo "==> Lancement"
adb -s "$TV" shell am start -n "$PKG/.MainActivity" >/dev/null
echo ""
echo "Terminé. Regarde ta TV."
echo "Au premier appui sur une flèche de la télécommande du téléphone, la TV demandera"
echo "d'autoriser le débogage : coche « Toujours autoriser » et valide avec la vraie télécommande."
