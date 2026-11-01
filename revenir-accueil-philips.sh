#!/bin/bash
# Filet de sécurité : rend l'accueil d'origine et désinstalle le launcher.
# Adresse de la TV : donnée en argument, sinon retenue dans .tv-ip (fichier local, jamais publié).
DIR="$(cd "$(dirname "$0")" && pwd)"
TV_IP="${1:-$(cat "$DIR/.tv-ip" 2>/dev/null)}"
if [ -z "$TV_IP" ]; then
  read -r -p "Adresse IP de la TV (Paramètres > Réseau, ex. 192.168.1.42) : " TV_IP
fi
TV_IP="${TV_IP%%:*}"
echo "$TV_IP" > "$DIR/.tv-ip"
TV="$TV_IP:5555"
if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)"; elif [ -x /usr/local/bin/brew ]; then eval "$(/usr/local/bin/brew shellenv)"; fi
adb connect "$TV" >/dev/null
CUR="$(adb -s "$TV" shell settings get secure enabled_accessibility_services | tr -d '\r')"
NEW="$(echo "$CUR" | tr ':' '\n' | grep -v 'be.alex.launcher' | paste -sd ':' -)"
adb -s "$TV" shell settings put secure enabled_accessibility_services "${NEW:-null}"
adb -s "$TV" uninstall be.alex.launcher
echo "Accueil d'origine rétabli."
