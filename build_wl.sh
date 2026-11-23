#!/bin/bash
# ── 鸿蒙手表助手 构建脚本 ────────────────────────────────────────────
# 依赖：Android SDK（build-tools + platform android-34）、JDK 8+
#   aapt / zipalign / apksigner 需在 PATH 中，否则回退到 $BT/ 下同名工具
#   d8 使用 $BT/lib/d8.jar
#
# 用法：
#   bash build_wl.sh
#   SDK=/path/to/android-sdk OUT=my.apk bash build_wl.sh
#
# 环境变量：
#   SDK   Android SDK 根目录（默认 /root/android-sdk）
#   BT    build-tools 目录（默认 $SDK/build-tools/34.0.0）
#   PLAT  android.jar 路径（默认 $SDK/platforms/android-34/android.jar）
#   OUT   输出 APK 文件名（默认 鸿蒙手表助手_1.0.apk）
# ────────────────────────────────────────────────────────────────────
cd "$(dirname "$0")" || exit 1

SDK=${SDK:-/root/android-sdk}
BT=${BT:-$SDK/build-tools/34.0.0}
PLAT=${PLAT:-$SDK/platforms/android-34/android.jar}
OUT=${OUT:-鸿蒙手表助手_1.0.apk}

AAPT=$(command -v aapt || echo "$BT/aapt")
ZIPALIGN=$(command -v zipalign || echo "$BT/zipalign")
APKSIGNER=$(command -v apksigner || echo "$BT/apksigner")

[ -f "$PLAT" ] || { echo "缺少 android.jar: $PLAT（请设置 SDK 或 PLAT）"; exit 1; }

rm -rf build && mkdir -p build/gen build/classes build/dex

# 注意：versionCode / versionName 以 AndroidManifest.xml 为唯一有效来源，
#       aapt 的 --version-code/--version-name 不会覆盖清单里的值。
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$PLAT" \
  -J build/gen -F build/base.apk \
  --min-sdk-version 24 --target-sdk-version 34 || { echo "AAPT FAILED"; exit 2; }

find src build/gen -name '*.java' > build/files.txt
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath "$PLAT" \
  -d build/classes @build/files.txt 2>&1 | grep -v 'bootstrap class path' | head -40
ls build/classes/com/wlaz/MainActivity.class >/dev/null 2>&1 || { echo "JAVAC FAILED"; exit 4; }

find build/classes -name '*.class' > build/classes.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --min-api 24 --lib "$PLAT" \
  --output build/dex @build/classes.txt || { echo "D8 FAILED"; exit 5; }

cp build/base.apk build/app-unsigned.apk
(cd build/dex && zip -q -X ../app-unsigned.apk classes.dex)
"$ZIPALIGN" -f 4 build/app-unsigned.apk build/app-aligned.apk || { echo "ALIGN FAILED"; exit 6; }

# 调试签名：debug.keystore（口令 android，别名 androiddebugkey）；不存在则自动生成
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

"$APKSIGNER" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT" build/app-aligned.apk || { echo "SIGN FAILED"; exit 7; }
"$APKSIGNER" verify --print-certs "$OUT" | head -4
ls -lh "$OUT"