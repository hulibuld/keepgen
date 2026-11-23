#!/bin/bash
# 出包脚本。必须跑 gen_buildstamp.sh，否则 60 天有效期会沿用旧的构建时间。
set -e

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-build/sdk}"
BT="$ANDROID_SDK_ROOT/build-tools/34.0.0"
AJAR="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
HERE="$(cd "$(dirname "$0")" && pwd)"

# 版本号：用环境变量覆盖，或改这里
VCODE=${VCODE:-21}
VNAME=${VNAME:-4.0}

cd "$HERE"

echo "[0/6] 生成 BuildStamp（烧入当前时间，60 天从此刻算）"
bash gen_buildstamp.sh

echo "[1/6] 同步版本号"
sed -i '' "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VCODE\"/" AndroidManifest.xml
sed -i '' "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VNAME\"/" AndroidManifest.xml
sed -i '' "s/static final int VERSION_CODE = [0-9]*;/static final int VERSION_CODE = $VCODE;/" src/com/qianyuzhou/keepgen/BuildConfig.java
sed -i '' "s/static final String VERSION_NAME = \"[^\"]*\";/static final String VERSION_NAME = \"$VNAME\";/" src/com/qianyuzhou/keepgen/BuildConfig.java

echo "[2/6] javac"
rm -rf build && mkdir -p build/classes build/dex
"$JAVA_HOME/bin/javac" -source 17 -target 17 -encoding UTF-8 \
  -classpath "$AJAR" -d build/classes $(find src -name '*.java')

echo "[3/6] R8 混淆 + dex"
#
# 用 R8（不是 d8）。R8 做三件事：dex 编译、类名/方法名混淆、无用代码消除。
# 之前误以为 SDK 里没有 r8.jar，实际在 cmdline-tools/latest/lib/ 下（8.9.27）。
#
# 混淆之后：License -> a.b.c，doGenerate -> a.b.d，
# 用 strings 扫 dex 再也看不到 Lcom/qianyuzhou/keepgen/License; 这种明文。
# 破解从「看一眼就找到」变成「得先反混淆再逐个试」。
R8JAR="$HOME/android-build/sdk/cmdline-tools/latest/lib/r8.jar"
if [ ! -f "$R8JAR" ]; then
  echo "!! 找不到 r8.jar：$R8JAR"
  exit 1
fi
"$JAVA_HOME/bin/java" -cp "$R8JAR" com.android.tools.r8.R8 \
  --release --min-api 21 --lib "$AJAR" \
  --pg-conf proguard-rules.pro \
  --output build/dex \
  $(find build/classes -name '*.class')

echo "[4/6] aapt2 link"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  -R build/res.zip --auto-add-overlay -A assets \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code "$VCODE" --version-name "$VNAME"

echo "[5/6] 组装（resources.arsc 必须不压缩，否则 Android 11+ 装不上）"
cd build
rm -rf pkg && mkdir pkg && cd pkg
unzip -q ../base.apk
cp ../dex/classes.dex .
rm -f ../unsigned.apk
zip -q -r -X ../unsigned.apk . -x resources.arsc
zip -q -X -0 ../unsigned.apk resources.arsc
cd ..
"$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
cd ..

echo "[6/6] 签名"
mkdir -p "$HERE/out"
#
# 密钥放在仓库外：~/.keepgen-keys/。
# 红队 2026-11-22（R5/L-1）指出：私钥和口令一起进仓库，等于任何人拿到仓库
# 都能用完全相同的签名发一个改过的包，覆盖安装零告警。
# 所以这里不再有默认口令，只从仓库外读；找不到就直接失败，不静默降级。
KEYDIR="${KEEP_KEYDIR:-$HOME/.keepgen-keys}"
KEYSTORE="${KEEP_KEYSTORE:-$KEYDIR/keepgen-release.keystore}"
PASSFILE="${KEEP_PASSFILE:-$KEYDIR/pass.txt}"
if [ ! -f "$KEYSTORE" ] || [ ! -f "$PASSFILE" ]; then
  echo "!! 找不到签名密钥或口令："
  echo "     keystore: $KEYSTORE"
  echo "     passfile: $PASSFILE"
  echo "   密钥必须放在仓库外。生成方法见 keep-apk/签名密钥说明.md"
  exit 1
fi
KS_PASS="$(cat "$PASSFILE")"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out "$HERE/out/keepgen.apk" build/aligned.apk
"$BT/apksigner" verify "$HERE/out/keepgen.apk"
cp "$HERE/out/keepgen.apk" "$HERE/运动数据生成-$VNAME.apk"

echo ""
echo "OK -> $HERE/out/keepgen.apk"
echo "     $HERE/运动数据生成-$VNAME.apk"
