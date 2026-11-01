#!/bin/bash
# À lancer avant chaque « git push » : vérifie que rien de perso ne part sur GitHub.
cd "$(dirname "$0")"
PB=0
for f in secrets.properties .tv-ip local.properties; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then echo "✗ $f est suivi par git : retire-le (git rm --cached $f)"; PB=1; fi
done
if git ls-files | grep -Eq '^(captures|Claude|dist)/|\.mp4$|\.apk$'; then echo "✗ Des captures, articles, vidéos ou APK sont suivis par git."; PB=1; fi
if [ -f secrets.properties ]; then
  while IFS='=' read -r k v; do
    v="$(echo "$v" | tr -d '[:space:]')"
    [ ${#v} -ge 10 ] || continue
    if git grep -q -F -- "$v" $(git rev-parse --verify HEAD >/dev/null 2>&1 && echo HEAD) -- . 2>/dev/null || git grep -q -F --cached -- "$v" 2>/dev/null; then
      echo "✗ Ta clé $k apparaît dans un fichier suivi par git."; PB=1
    fi
  done < secrets.properties
fi
[ $PB -eq 0 ] && echo "✓ Rien de perso dans ce qui part sur GitHub." || exit 1
