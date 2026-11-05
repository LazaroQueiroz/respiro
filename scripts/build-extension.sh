#!/usr/bin/env bash
# Monta a extensão a partir dos arquivos compartilhados com o app e gera o .zip.
#  - assets/sites.json  → extension/sites.json (mesmas redes e seletores)
#  - assets/filter.js   → extension/filter.js, embrulhado em window.__rspStart(), com a
#    configuração vinda de window.__RSP_CFG (preenchida pelo content.js) em vez de __CFG__.
# Depois disso dá para carregar extension/ sem compactar no navegador.
# Saída: build/respiro-extension-<versão>.zip (Chrome) e -firefox.zip (addons.mozilla.org).
# A versão vem da última tag v*, como no APK.
set -euo pipefail
cd "$(dirname "$0")/.."
assets=app/src/main/assets
cp "$assets/sites.json" extension/sites.json
{
  echo '// GERADO por scripts/build-extension.sh a partir de app/src/main/assets/filter.js. Não edite aqui.'
  echo 'window.__rspStart = function () {'
  sed 's/__CFG__/window.__RSP_CFG/' "$assets/filter.js"
  echo '};'
} > extension/filter.js

version=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null | sed 's/^v//' || true)
version=${version:-0.0.0}
out=build/extension
rm -rf "$out" && mkdir -p "$out"
cp -r extension/. "$out/"
rm -f "$out/icons/icon.svg"
python3 -I - "$out/manifest.json" "$version" <<'PY'
import json, sys
p, v = sys.argv[1], sys.argv[2]
m = json.load(open(p)); m["version"] = v
json.dump(m, open(p, "w"), ensure_ascii=False, indent=2)
PY
zip_path="$PWD/build/respiro-extension-$version.zip"
rm -f "$zip_path"
(cd "$out" && zip -qr "$zip_path" .)
echo "$zip_path"

# Versão para o addons.mozilla.org: o Firefox não usa "service_worker" (usa background.scripts).
ff="build/extension-firefox"
rm -rf "$ff" && cp -r "$out" "$ff"
python3 -I - "$ff/manifest.json" <<'PY'
import json, sys
p = sys.argv[1]
m = json.load(open(p)); m["background"].pop("service_worker", None)
json.dump(m, open(p, "w"), ensure_ascii=False, indent=2)
PY
ff_zip="$PWD/build/respiro-extension-$version-firefox.zip"
rm -f "$ff_zip"
(cd "$ff" && zip -qr "$ff_zip" .)
echo "$ff_zip"
