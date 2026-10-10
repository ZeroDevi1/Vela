#!/usr/bin/env bash
# Build an unsigned iOS Release app, re-sign it with zsign, and install it on a
# physical iPhone / iPad via devicectl.
#
# Each device has its own signing folder holding:
#   证书文件.p12  描述文件.mobileprovision  密码.txt ("密码：<pw>")
# The profiles use explicit App IDs, so the bundle id is rewritten to the one in
# the profile, and the target device defaults to the profile's only
# ProvisionedDevices entry.
#
# --unsigned skips all of the above and only packs an unsigned IPA for public
# releases; users re-sign it with their own certificate.
#
# zsign: https://github.com/zhlynn/zsign (build/macos -> make).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SIGN_ROOT="${VELA_IOS_SIGN_ROOT:-$HOME/Library/Mobile Documents/com~apple~CloudDocs/Downloads}"
TARGET="iPhone"
DEVICE=""
SKIP_BUILD=0
LAUNCH=1
INSTALL=1
UNSIGNED=0
DERIVED="$ROOT/build/ios"
APP_PRODUCT="$DERIVED/Build/Products/Release-iphoneos/Vela.app"

usage() {
  cat <<EOF
Usage:
  $0 [options]

Build iosApp (Release, unsigned), re-sign with zsign using the signing folder
of the chosen device into build/ios/Vela-<iPhone|iPad>.ipa, then install
with devicectl and launch. With --unsigned, only pack
build/ios/Vela-unsigned.ipa (no signing files, zsign or device needed).

Options:
  --iphone          use \$SIGN_ROOT/iPhone (default)
  --ipad            use \$SIGN_ROOT/iPad
  --device ID       devicectl device id (default: the profile's provisioned UDID)
  --skip-build      re-sign and install the already-built Vela.app
  --no-launch       install only, do not start the app
  --no-install      build and sign only (device not connected)
  --unsigned        build and pack an unsigned IPA only (CI / public release)
  -h, --help        show this help

Environment:
  VELA_IOS_SIGN_ROOT  parent of the iPhone/ and iPad/ folders
                      (default: iCloud Drive/Downloads)
  ZSIGN               zsign binary (default: zsign in PATH)

Examples:
  $0
  $0 --ipad
  $0 --skip-build --no-launch
  $0 --ipad --no-install
  $0 --unsigned
EOF
}

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing $1" >&2
    exit 1
  }
}

# 密码.txt holds "密码：<pw>" rather than the bare password; strip the label.
read_password() {
  tr -d '\r\n' < "$1" | sed -E 's/^.*(：|:)//; s/^[[:space:]]+//; s/[[:space:]]+$//'
}

plist_get() {
  /usr/libexec/PlistBuddy -c "Print :$2" "$1" 2>/dev/null
}

while [ $# -gt 0 ]; do
  case "$1" in
    --iphone) TARGET="iPhone" ;;
    --ipad) TARGET="iPad" ;;
    --device)
      shift
      DEVICE="${1:-}"
      [ -n "$DEVICE" ] || { echo "--device needs a value" >&2; exit 1; }
      ;;
    --device=*) DEVICE="${1#--device=}" ;;
    --skip-build) SKIP_BUILD=1 ;;
    --no-launch) LAUNCH=0 ;;
    --no-install) INSTALL=0 ;;
    --unsigned) UNSIGNED=1 ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

need xcodebuild
# Check signing inputs before the slow build.
if [ "$UNSIGNED" -eq 0 ]; then
  ZSIGN="${ZSIGN:-zsign}"
  need "$ZSIGN"
  need xcrun
  SIGNED_IPA="$DERIVED/Vela-$TARGET.ipa"

  SIGN_DIR="$SIGN_ROOT/$TARGET"
  P12="$SIGN_DIR/证书文件.p12"
  PROFILE="$SIGN_DIR/描述文件.mobileprovision"
  PASSWORD_FILE="$SIGN_DIR/密码.txt"
  for f in "$P12" "$PROFILE" "$PASSWORD_FILE"; do
    [ -f "$f" ] || { echo "signing file not found: $f" >&2; exit 1; }
  done
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/vela-ios-sign.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if [ "$SKIP_BUILD" -eq 0 ]; then
  # Xcode checks for VelaData.xcframework while planning the build, before the
  # target's own run-script phase can produce it.
  echo "building VelaData.xcframework"
  (cd "$ROOT" && ./gradlew :data:copyFrameworkToIosApp)

  echo "building Vela (Release, iphoneos, unsigned)"
  xcodebuild \
    -project "$ROOT/iosApp/Vela.xcodeproj" \
    -scheme Vela \
    -configuration Release \
    -sdk iphoneos \
    -destination generic/platform=iOS \
    -derivedDataPath "$DERIVED" \
    CODE_SIGNING_ALLOWED=NO \
    ENABLE_USER_SCRIPT_SANDBOXING=NO \
    build
fi

[ -d "$APP_PRODUCT" ] || { echo "app not found: $APP_PRODUCT" >&2; exit 1; }
mkdir -p "$WORK/Payload"
cp -R "$APP_PRODUCT" "$WORK/Payload/Vela.app"

if [ "$UNSIGNED" -eq 1 ]; then
  UNSIGNED_IPA="$DERIVED/Vela-unsigned.ipa"
  rm -f "$UNSIGNED_IPA"
  # -y keeps framework symlinks intact.
  (cd "$WORK" && zip -qry "$UNSIGNED_IPA" Payload)
  echo "unsigned $UNSIGNED_IPA"
  exit 0
fi

# Read bundle id and device from the profile so the folders stay drop-in.
security cms -D -i "$PROFILE" > "$WORK/profile.plist"
APP_ID="$(plist_get "$WORK/profile.plist" "Entitlements:application-identifier")"
TEAM_ID="$(plist_get "$WORK/profile.plist" "TeamIdentifier:0")"
BUNDLE_ID="${APP_ID#"$TEAM_ID".}"
[ -n "$BUNDLE_ID" ] && [ "$BUNDLE_ID" != "$APP_ID" ] || {
  echo "could not read bundle id from $PROFILE" >&2
  exit 1
}
case "$BUNDLE_ID" in
  *'*'*) echo "wildcard profile ($APP_ID) not supported; pass an explicit App ID profile" >&2; exit 1 ;;
esac
if [ -z "$DEVICE" ]; then
  DEVICE="$(plist_get "$WORK/profile.plist" "ProvisionedDevices:0")" || {
    echo "profile has no ProvisionedDevices; pass --device" >&2
    exit 1
  }
fi

echo "target  $TARGET"
echo "team    $TEAM_ID"
echo "bundle  $BUNDLE_ID"
echo "device  $DEVICE"

# Sign the Payload/ copy so the build output stays unsigned and reusable for
# the other device; zsign only packs an IPA from a Payload/ layout.
echo "signing with zsign"
# zsign drops .zsign_cache/ in the working directory; keep it out of the repo.
(
  cd "$WORK"
  "$ZSIGN" \
    -k "$P12" \
    -p "$(read_password "$PASSWORD_FILE")" \
    -m "$PROFILE" \
    -b "$BUNDLE_ID" \
    -o "$SIGNED_IPA" \
    "$WORK/Payload/Vela.app"
)
echo "signed  $SIGNED_IPA"

if [ "$INSTALL" -eq 0 ]; then
  echo "done (install skipped)"
  exit 0
fi

echo "installing"
xcrun devicectl device install app --device "$DEVICE" "$WORK/Payload/Vela.app"

if [ "$LAUNCH" -eq 1 ]; then
  echo "launching $BUNDLE_ID"
  xcrun devicectl device process launch --device "$DEVICE" "$BUNDLE_ID"
fi

echo "done"
