#!/bin/bash
# Captures d'écran de la TV pour l'article, en mode démo (faux agenda et note sympa).
# Usage : bash captures.sh            (TV en Wi-Fi, adresse par défaut)
#         PHOTO="beach" bash captures.sh   (autre recherche Unsplash pour le fond)
#         bash captures.sh 192.168.x.x
# Les images arrivent dans le dossier captures/ à côté de ce script.

# Adresse de la TV : donnée en argument, sinon retenue dans .tv-ip (fichier local, jamais publié).
DIR="$(cd "$(dirname "$0")" && pwd)"
TV_IP="${1:-$(cat "$DIR/.tv-ip" 2>/dev/null)}"
if [ -z "$TV_IP" ]; then
  read -r -p "Adresse IP de la TV (Paramètres > Réseau, ex. 192.168.1.42) : " TV_IP
fi
TV_IP="${TV_IP%%:*}"
echo "$TV_IP" > "$DIR/.tv-ip"
TV="$TV_IP:5555"
cd "$DIR"
OUT="captures"
mkdir -p "$OUT"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb est introuvable. Lance d'abord : bash setup.sh"
  exit 1
fi

adb connect "$TV" >/dev/null 2>&1
if ! adb -s "$TV" get-state >/dev/null 2>&1; then
  echo "Impossible de joindre la TV ($TV). Elle est allumée et sur le même Wi-Fi ?"
  exit 1
fi

HOME_ACT="be.alex.launcher/.MainActivity"
# Les arguments sont protégés pour le shell de la TV (sinon « forest mist » serait coupé en deux).
go()  { adb -s "$TV" shell "am start -n $HOME_ACT -f 0x20000000 $(printf '%q ' "$@")" >/dev/null 2>&1; }
key() { adb -s "$TV" shell input keyevent "$@"; }
cap() {
  adb -s "$TV" exec-out screencap -p > "$OUT/$1.png"
  echo "  ✓ $1.png"
}

PHOTO="${PHOTO:-forest mist}"
echo "==> Mode démo activé : faux agenda, et une photo Unsplash (« $PHOTO ») à la place de ton fond d'écran"
adb -s "$TV" shell run-as be.alex.launcher rm -f files/demo-wallpaper.jpg >/dev/null 2>&1
go --ez demo true --ei demo_timer -1 --es demo_unsplash "$PHOTO"

# On attend la photo Unsplash : sans elle, pas de capture (sinon ton vrai fond d'écran apparaîtrait).
OKPHOTO=""
for i in $(seq 1 12); do
  sleep 3
  # L'accueil marque le crédit de la photo de démo : on le cherche dans ce qui est affiché.
  if adb -s "$TV" shell "uiautomator dump /sdcard/lt-ui.xml >/dev/null 2>&1 && grep -c demo-photo /sdcard/lt-ui.xml" 2>/dev/null | grep -q '^[1-9]'; then OKPHOTO=1; break; fi
done
if [ -z "$OKPHOTO" ]; then
  go --ez demo false
  echo ""
  echo "La photo Unsplash n'a pas pu être chargée, captures annulées (ton fond d'écran n'a pas bougé)."
  echo "Vérifie : 1) que « bash installer.sh » est allé jusqu'au bout ; 2) que UNSPLASH_KEY est rempli dans secrets.properties."
  exit 1
fi
sleep 4

echo "==> Captures"
go --es demo_focus next; sleep 3;  cap 01-accueil-a-suivre
go --es demo_focus card; sleep 3;  cap 02-accueil-carte
go --es demo_focus app;  sleep 3;  cap 03-accueil-apps

go --es demo_open "search:Lupin"; sleep 7; cap 04-recherche
key KEYCODE_BACK; sleep 2

go --es demo_open allapps; sleep 3; cap 05-toutes-les-apps
key KEYCODE_BACK; sleep 2

for cat in look home timer android; do
  go --es demo_open "settings:$cat"; sleep 3; cap "06-reglages-$cat"
  key KEYCODE_BACK; sleep 2
done

go --es demo_focus next --ei demo_timer 754 --es demo_timer_label "Pâtes"; sleep 3
cap 07-minuteur

go --ei demo_timer 5; sleep 8
cap 08-c-est-l-heure
go --ei demo_timer -1; sleep 1

# Défis : trois captures (le type de question est tiré au hasard à chaque fois).
for n in 1 2 3; do
  go --ez demo_defi true; sleep 3
  cap "09-defi-$n"
  go --ez demo_defi_skip true; sleep 2
done

echo "==> Retour à la normale"
go --ez demo false --es demo_focus next
echo
echo "Terminé. Les images sont dans : $(pwd)/$OUT"
open "$OUT" 2>/dev/null || true
