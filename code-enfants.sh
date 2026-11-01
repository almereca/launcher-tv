#!/bin/bash
# Secours : affiche le code de l'espace Enfants et peut déverrouiller la TV depuis le Mac.
# Usage : bash code-enfants.sh                  (affiche le code)
#         bash code-enfants.sh --deverrouiller  (déverrouille la TV)
DIR="$(cd "$(dirname "$0")" && pwd)"
TV_IP="$(cat "$DIR/.tv-ip" 2>/dev/null)"
[ -n "$TV_IP" ] || { echo "Lance d'abord bash installer.sh (il retient l'adresse de la TV)."; exit 1; }
TV="$TV_IP:5555"
PKG="be.alex.launcher"
if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; elif [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
adb connect "$TV" >/dev/null
XML="$(adb -s "$TV" shell run-as "$PKG" cat shared_prefs/kids.xml 2>/dev/null)"
CODE="$(echo "$XML" | sed -n 's/.*name="code">\([0-9]*\)<.*/\1/p')"
if [ -n "$CODE" ]; then echo "Code de l'espace Enfants : $CODE"; else echo "Aucun code enregistré pour l'instant."; fi
if [ "$1" = "--deverrouiller" ]; then
  NEW="$(echo "$XML" | sed 's/name="locked" value="true"/name="locked" value="false"/')"
  echo "$NEW" | adb -s "$TV" shell "run-as $PKG sh -c 'cat > shared_prefs/kids.xml'"
  adb -s "$TV" shell am force-stop "$PKG"
  adb -s "$TV" shell am start -n "$PKG/.MainActivity" >/dev/null
  echo "TV déverrouillée."
fi
