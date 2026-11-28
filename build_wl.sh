#!/bin/bash
cd "/storage/emulated/0/hap/腕连助手_项目全量导出" || exit 1
SDK=/root/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex


aapt package -f -M AndroidManifest.xml -S res -A assets -I $PLAT \
  -J build/gen -F build/base.apk \
  --version-code 100 --version-name 1.0 \
  --min-sdk-version 24 --target-sdk-version 34 || { echo "AAPT FAILED"; exit 2; }

find src build/gen -name '*.java' > build/files.txt
javac -nowarn -source 8 -target 8 -encoding UTF-8 -bootclasspath $PLAT \
  -d build/classes @build/files.txt 2>&1 | grep -v 'bootstrap class path' | head -40
ls build/classes/com/wlaz/MainActivity.class >/dev/null 2>&1 || { echo "JAVAC FAILED"; exit 4; }

find build/classes -name '*.class' > build/classes.txt
java -cp $BT/lib/d8.jar com.android.tools.r8.D8 --min-api 24 --lib $PLAT --output build/dex @build/classes.txt || { echo "D8 FAILED"; exit 5; }

cp build/base.apk build/app-unsigned.apk
(cd build/dex && zip -q -X ../app-unsigned.apk classes.dex)
zipalign -f 4 build/app-unsigned.apk build/app-aligned.apk || { echo "ALIGN FAILED"; exit 6; }
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out 鸿蒙手表助手_1.0.apk build/app-aligned.apk || { echo "SIGN FAILED"; exit 7; }
apksigner verify --print-certs 鸿蒙手表助手_1.0.apk | head -4
ls -lh 鸿蒙手表助手_1.0.apk