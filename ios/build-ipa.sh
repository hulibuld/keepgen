#!/bin/bash
# 一条命令出两个 .ipa：通用未签名版 + ad-hoc 签名版（含 HealthKit 两项 entitlements）。
# 依赖：Xcode（提供 iOS SDK）、xcodegen（brew install xcodegen）。
# 用法：./build-ipa.sh [版本号]，默认取 Info.plist 里的 CFBundleShortVersionString。
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -d /Applications/Xcode.app ]; then
  echo "没找到 /Applications/Xcode.app，先装 Xcode（免费）" >&2
  exit 1
fi
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

command -v xcodegen >/dev/null || { echo "缺 xcodegen：brew install xcodegen" >&2; exit 1; }

echo "==> 生成工程"
xcodegen generate

echo "==> 编译 Release（arm64，不签名）"
rm -rf build
xcodebuild -project KeepGen.xcodeproj -target KeepGen -sdk iphoneos -configuration Release \
  SYMROOT="$PWD/build" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  build | grep -E "error:|warning: .*deprecated|BUILD" || true

APP="build/Release-iphoneos/KeepGen.app"
[ -d "$APP" ] || { echo "编译没产出 $APP" >&2; exit 1; }

VER="${1:-$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Info.plist")}"
OUT="dist"

# HealthKit entitlement 校验：签名后必须能看到 com.apple.developer.healthkit，
# 否则 HealthKit 写入会直接失败（自签工具重签时最容易把这个键丢掉）。
check_healthkit() {
  local app="$1"
  local out
  out="$(codesign -d --entitlements - "$app" 2>/dev/null || true)"
  if ! printf '%s' "$out" | grep -q "com.apple.developer.healthkit"; then
    echo "✗ $app 的签名里没有 com.apple.developer.healthkit" >&2
    echo "  HealthKit 写不进去。检查 App/KeepGen.entitlements 是否在 .app 里、签名用的 entitlements 是否含该键。" >&2
    exit 1
  fi
  echo "✓ entitlements 校验：com.apple.developer.healthkit 已在签名里"
}

# .app 里必须保留一份 entitlements 文件（给解包 / 第三方签名工具读取）
check_entitlements_file() {
  local app="$1"
  if [ ! -f "$app/KeepGen.entitlements" ]; then
    echo "✗ $app 里没有 KeepGen.entitlements，别把包发出去" >&2
    exit 1
  fi
  echo "✓ .app 内 KeepGen.entitlements 在位（$(wc -c < "$app/KeepGen.entitlements" | tr -d ' ') 字节）"
}
rm -rf "$OUT" && mkdir -p "$OUT"

check_entitlements_file "$APP"

echo "==> 通用未签名 ipa"
rm -rf "$OUT/Payload" && mkdir -p "$OUT/Payload"
cp -R "$APP" "$OUT/Payload/"
(cd "$OUT" && COPYFILE_DISABLE=1 zip -qry "KeepGen-${VER}-unsigned.ipa" Payload)
rm -rf "$OUT/Payload"

echo "==> 就地 ad-hoc 签名 build 里的 .app（让 codesign 能校验 entitlements）"
cat > "$PWD/build/ent.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>platform-application</key><true/>
    <key>com.apple.private.security.no-container</key><true/>
    <key>get-task-allow</key><true/>
    <key>com.apple.developer.healthkit</key><true/>
    <key>com.apple.developer.healthkit.access</key><array/>
</dict>
</plist>
PLIST
ENT="$PWD/build/ent.plist"

# 工程目录被文件同步驱动（FileProvider）盯着时，`xattr -cr` 清掉 FinderInfo 后一两秒会回弹，
# codesign 就报 "resource fork, Finder information, or similar detritus not allowed"。
# 签到工程目录里的 .app / .framework 时每次先清 xattr 再签，失败重来几次。
sign_in_place() {
  local target="$1" attempt
  for attempt in 1 2 3 4 5; do
    xattr -cr "$target"
    if codesign -f -s - --entitlements "$ENT" --timestamp=none "$target" 2>"$PWD/build/codesign.err"; then
      return 0
    fi
    sleep 1
  done
  echo "✗ codesign 失败：$target" >&2
  cat "$PWD/build/codesign.err" >&2
  return 1
}

for fw in "$APP/Frameworks"/*.framework; do
  sign_in_place "$fw"
done
sign_in_place "$APP"
check_healthkit "$APP"

echo "==> ad-hoc 签名版 ipa（同一份 entitlements）"
WORK="$(mktemp -d)"
cp -R "$APP" "$WORK/KeepGen.app"
xattr -cr "$WORK/KeepGen.app"
for fw in "$WORK/KeepGen.app/Frameworks"/*.framework; do
  codesign -f -s - --entitlements "$ENT" --timestamp=none "$fw"
done
codesign -f -s - --entitlements "$ENT" --timestamp=none "$WORK/KeepGen.app"
check_healthkit "$WORK/KeepGen.app"
mkdir -p "$WORK/Payload" && cp -R "$WORK/KeepGen.app" "$WORK/Payload/"
(cd "$WORK" && COPYFILE_DISABLE=1 zip -qry "KeepGen-${VER}-adhoc.ipa" Payload)
mv "$WORK/KeepGen-${VER}-adhoc.ipa" "$OUT/"
rm -rf "$WORK"

echo
echo "==> 完成"
for f in "$OUT"/*.ipa; do
  printf '%s\t%s 字节\n' "$(basename "$f")" "$(stat -f%z "$f")"
  shasum -a 256 "$f"
done