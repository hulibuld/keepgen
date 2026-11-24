#!/bin/bash
# 用你自己的 Apple ID（免费开发者账号 / 个人团队）给 KeepGen.app 重签，
# 固定带上 HealthKit 两项 entitlements，避免签名工具把 com.apple.developer.healthkit 丢掉。
#
# 用法：
#   export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
#   ./build-ipa.sh                      # 先出包（未签名 / ad-hoc）
#   security find-identity -v -p codesigning   # 查可用签名身份，复制其中一条
#   TEAM_ID=ABCDE12345 \
#   SIGN_IDENTITY="Apple Development: 你的名字 (ABCDE12345)" \
#   ./sign-with-team.sh [可选：要签的 .app 路径]
#
# 默认签 build/Release-iphoneos/KeepGen.app；要签解包出来的目录就传 Payload/KeepGen.app。
#
# 说明：
#   · 免费 Apple ID 的证书 7 天过期，过期后重跑这条命令即可；
#   · 手机上要能跑，还需要把 .app 装到设备（Xcode / AltStore / Sideloadly 等），
#     或用本脚本签好后自己重新打成 ipa；
#   · HealthKit 能力必须包含 com.apple.developer.healthkit 与
#     com.apple.developer.healthkit.access，脚本直接喂 App/KeepGen.entitlements，
#     并补一份 com.apple.developer.team-identifier；
#   · 签完自动跑 codesign -d --entitlements - 自检，缺键就非 0 退出。
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -d /Applications/Xcode.app ]; then
  echo "没找到 /Applications/Xcode.app，先装 Xcode（免费）" >&2
  exit 1
fi
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer

APP="${1:-build/Release-iphoneos/KeepGen.app}"

: "${TEAM_ID:?请先设置 TEAM_ID，例如 TEAM_ID=ABCDE12345}"
: "${SIGN_IDENTITY:?请先设置 SIGN_IDENTITY，例如 SIGN_IDENTITY=\"Apple Development: 你的名字 (ABCDE12345)\"}"

SRC_ENT="App/KeepGen.entitlements"
[ -f "$SRC_ENT" ] || { echo "找不到 $SRC_ENT" >&2; exit 1; }
[ -d "$APP" ] || { echo "找不到 $APP，先跑 ./build-ipa.sh" >&2; exit 1; }

WORK="$(mktemp -d)"
ENT="$WORK/team-entitlements.plist"
cp "$SRC_ENT" "$ENT"

# 补 team-identifier（免费账号签名需要它）；已有则覆盖
if /usr/libexec/PlistBuddy -c "Print :com.apple.developer.team-identifier" "$ENT" >/dev/null 2>&1; then
  /usr/libexec/PlistBuddy -c "Set :com.apple.developer.team-identifier $TEAM_ID" "$ENT"
else
  /usr/libexec/PlistBuddy -c "Add :com.apple.developer.team-identifier string $TEAM_ID" "$ENT"
fi

echo "==> 清理扩展属性"
xattr -cr "$APP" 2>/dev/null || true

echo "==> 给 Frameworks 签名"
if [ -d "$APP/Frameworks" ]; then
  for fw in "$APP/Frameworks"/*.framework; do
    [ -e "$fw" ] || continue
    codesign -f -s "$SIGN_IDENTITY" --entitlements "$ENT" --timestamp=none "$fw"
  done
fi

echo "==> 给 .app 签名"
codesign -f -s "$SIGN_IDENTITY" --entitlements "$ENT" --timestamp=none "$APP"

echo "==> 校验"
codesign --verify --deep --strict --verbose=2 "$APP" || {
  echo "✗ codesign --verify 失败" >&2
  exit 1
}
OUT="$(codesign -d --entitlements - "$APP" 2>/dev/null || true)"
printf '%s\n' "$OUT"
if ! printf '%s' "$OUT" | grep -q "com.apple.developer.healthkit"; then
  echo "✗ 签名后 entitlements 里没有 com.apple.developer.healthkit，HealthKit 写入会失败" >&2
  exit 1
fi

if [ ! -f "$APP/KeepGen.entitlements" ]; then
  echo "注意：$APP 里没有 KeepGen.entitlements 文件（签名本身没受影响，但解包工具读不到）" >&2
fi

echo "✓ 完成：$APP 已用「$SIGN_IDENTITY」重签，HealthKit entitlement 在位"
echo "  如需 ipa：mkdir -p Payload && cp -R \"$APP\" Payload/ && zip -qry KeepGen-signed.ipa Payload"
rm -rf "$WORK"