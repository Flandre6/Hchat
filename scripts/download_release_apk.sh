#!/bin/sh
set -e

REPO="ljh520134/Hchat"
OUT_DIR="dist"
TAG="${1:-}"
CHANNEL="${2:-${HCAT_RELEASE_CHANNEL:-}}"
MAIN_APK_NAME="Hchat-release-signed.apk"
ALT_APK_NAME="Hchat-alt-entry-release-signed.apk"

cd "$(dirname "$0")/.."
mkdir -p "$OUT_DIR"

if [ "$TAG" = "main" ] || [ "$TAG" = "alt-entry" ] || [ "$TAG" = "all" ]; then
  CHANNEL="$TAG"
  TAG=""
fi

if [ -z "$CHANNEL" ]; then
  CHANNEL="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || printf 'main')"
fi

if [ "$CHANNEL" = "all" ]; then
  APK_NAMES="$MAIN_APK_NAME $ALT_APK_NAME"
elif [ -n "${HCAT_APK_NAME:-}" ]; then
  APK_NAME="$HCAT_APK_NAME"
elif [ "$CHANNEL" = "alt-entry" ]; then
  APK_NAME="$ALT_APK_NAME"
else
  APK_NAME="$MAIN_APK_NAME"
fi
if [ -z "${APK_NAMES:-}" ]; then
  APK_NAMES="$APK_NAME"
fi

if [ -z "$TAG" ]; then
  for candidate in $(gh release list --repo "$REPO" --limit 100 --json tagName --jq '.[].tagName'); do
    case "$candidate" in
      v*) ;;
      *) continue ;;
    esac
    assets="$(gh release view "$candidate" --repo "$REPO" --json assets --jq '.assets[].name')"
    found=true
    for name in $APK_NAMES; do
      if ! printf '%s\n' "$assets" | grep -Fxq "$name"; then
        found=false
        break
      fi
    done
    if [ "$found" = "true" ]; then
      TAG="$candidate"
      break
    fi
  done
fi

if [ -z "$TAG" ]; then
  printf '未找到包含 %s 的发行版\n' "$APK_NAME" >&2
  exit 1
fi

for name in $APK_NAMES; do
  gh release download "$TAG" \
    --repo "$REPO" \
    --pattern "$name" \
    --dir "$OUT_DIR" \
    --clobber
  printf '已下载: %s/%s\n来源: %s\n' "$OUT_DIR" "$name" "$TAG"
done
