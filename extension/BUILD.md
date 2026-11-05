# Building the Respiro extension (for addons.mozilla.org reviewers)

The extension is plain, hand-written JavaScript, HTML and CSS. There is no
transpiling, bundling, minification or third-party code, and no npm/node step.

The only build step copies two files shared with the Android app and wraps one
of them in a function:

| Source file | Output | Processing |
|---|---|---|
| `app/src/main/assets/sites.json` | `sites.json` | copied as-is |
| `app/src/main/assets/filter.js` | `filter.js` | wrapped in `window.__rspStart = function () { … };` and the placeholder `__CFG__` replaced by `window.__RSP_CFG` (one `sed` substitution) |
| `extension/*` | everything else | copied as-is |

Then `manifest.json` gets its `version` field set and, for Firefox, the
`background.service_worker` key (used only by Chrome) is removed.

## Requirements

- Linux or macOS
- bash 4 or newer (tested with 5.3)
- Python 3.8 or newer (tested with 3.14), standard library only
- Info-ZIP `zip` 3.0
- sed (GNU or BSD), cp, rm, mkdir (standard on both systems)

On Debian/Ubuntu: `sudo apt install python3 zip`. On macOS all of these come
with the system (or `brew install python zip`).

## Steps

```sh
unzip respiro-extension-<version>-source.zip -d respiro
cd respiro
RESPIRO_VERSION=<version> ./scripts/build-extension.sh   # e.g. RESPIRO_VERSION=1.1.3
```

Output:

- `build/extension-firefox/`: the exact contents of the submitted add-on
- `build/respiro-extension-<version>-firefox.zip`: the same files, zipped

`<version>` is the version of the submitted add-on (the `version` field of its
`manifest.json`). Inside a git checkout it can be omitted: the script uses the
latest `v*` tag.

To compare with the submitted package:

```sh
mkdir submitted && unzip -q respiro-extension-<version>-firefox.zip -d submitted
diff -r submitted build/extension-firefox   # no output = identical
```
