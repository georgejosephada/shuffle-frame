#!/usr/bin/env bash
# Publishes a new Shuffle Frame build to GitHub Releases; the tablet installs it within a day
# (or at once via Settings → Check for updates now).
#   ./release.sh "What changed"
set -euo pipefail
cd "$(dirname "$0")"
notes="${1:?usage: ./release.sh \"release notes\"}"
export JAVA_HOME="${JAVA_HOME:-$HOME/Android/jdk}"
GH="${GH:-$(command -v gh || echo "$HOME/.local/bin/gh")}"
repo="$(grep '^updateRepo=' gradle.properties | cut -d= -f2 || true)"

# Check everything that can fail before changing anything.
[ -n "$repo" ] || { echo "Set updateRepo=<owner>/<name> in gradle.properties" >&2; exit 1; }
git rev-parse --abbrev-ref '@{u}' >/dev/null 2>&1 || { echo "No git upstream; push the repo first" >&2; exit 1; }
"$GH" auth status >/dev/null 2>&1 || { echo "Run: $GH auth login" >&2; exit 1; }
"$GH" repo view "$repo" >/dev/null || { echo "GitHub repo $repo not found" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || echo "Note: uncommitted changes will be included in this release"

cp version.properties version.properties.bak
trap 'mv -f version.properties.bak version.properties 2>/dev/null || true' ERR
code=$(( $(grep '^versionCode=' version.properties | cut -d= -f2) + 1 ))
name="1.$((code - 1))"
sed -i "s/^versionCode=.*/versionCode=$code/; s/^versionName=.*/versionName=$name/" version.properties

./gradlew -q assembleRelease
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
cp app/build/outputs/apk/release/app-release.apk "$out/ShuffleFrame.apk"

rm -f version.properties.bak
trap - ERR
git add -A
git commit -q -m "Release $name (build $code)

$notes"
git push -q
"$GH" release create "build-$code" "$out/ShuffleFrame.apk" --repo "$repo" --title "Shuffle Frame $name" --notes "$notes"
echo "Released $name (build $code)"
