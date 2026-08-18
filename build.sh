#!/bin/sh

sd=$(dirname "$(realpath "$0")")

jdir=$sd/tools

# required ext tools: 
#   aapt2 zipalign zip java
# if you need to genrate keys:
#   keytool
AAPT2=$(which aapt2)
ZIPALIGN=$(which zipalign)

ANDROID_JAR=$jdir/android.jar
ECJ_JAR=$jdir/ecj-3.45.0.jar
D8_JAR=$jdir/d8.jar
APKSIGNER_JAR=$jdir/apksigner.jar

KEYSTORE=$jdir/debug.keystore
KEY_ALIAS=androiddebugkey
KEY_PASS=android

PROJ=$sd/app/src/main
BUILD=$sd/build

# com.<6 chars>.<5 chars>
PKG_NAME=$(cat "$sd"/pkgname.txt | tr -d '\r\n')
# com/<6 chars>/<5 chars>
PKG_PATH=$(cat "$sd"/pkgname.txt | tr -d '\r\n' | tr '.' '/')

app_name=$(cat "$sd"/appname.txt | tr -d '\r\n')

BUNINU_TGZ=$sd/buninu.tgz

RESET=$(printf '\033[0m')
BOLD_CYAN=$(printf '\033[1;36m')
BOLD_GREEN=$(printf '\033[1;32m')
YELLOW=$(printf '\033[33m')

heading() {
  printf '%s%s%s\n' "$BOLD_CYAN" "$1" "$RESET"
}

progress() {
  printf '%s%s.%s %s%s%s\n' "$BOLD_GREEN" "$1" "$RESET" "$YELLOW" "$2" "$RESET"
}

heading "Building $PKG_NAME APK → $app_name.apk"

# Archives must contain exactly one top-level directory; the Android installer
# strips that directory and extracts directly into its Buninu home. Prefer a
# local checkout, and require explicit consent before downloading from npm.
if [ -n "$1" ]; then
  case "$1" in
    *.tgz)
      BUNINU_TGZ=$(realpath "$1")
      if [ ! -f "$BUNINU_TGZ" ]; then
        echo "Provided Buninu payload not found: $BUNINU_TGZ" >&2
        exit 1
      fi
      heading "Using provided Buninu payload: $BUNINU_TGZ"
      ;;
    *)
      echo "Unrecognized argument: $1 (expected a .tgz path)" >&2
      exit 1
      ;;
  esac
elif [ -d "$sd/no_backup" ]; then
  heading "Creating Buninu payload from local no_backup: $BUNINU_TGZ"
  if ! bun "$sd/no_backup/bin/init.js" --export "$BUNINU_TGZ"; then
    echo "Local Buninu export failed" >&2
    exit 1
  fi
else
  printf 'Local no_backup not found. Run npx buninu@latest --export? [y/N] ' >&2
  answer=
  read -r answer
  case "$answer" in
    y|Y)
      if ! npx buninu@latest --export "$BUNINU_TGZ"; then
        echo "npm Buninu export failed" >&2
        exit 1
      fi
      ;;
    *)
      echo "Cancelled: Buninu payload was not exported" >&2
      exit 1
      ;;
  esac
fi

if [ ! -f "$BUNINU_TGZ" ]; then
  echo "Buninu export did not create: $BUNINU_TGZ" >&2
  exit 1
fi
BUNINU_STAMP=$(bun "$sd/make-stamp.js" "$BUNINU_TGZ" | head -n 1)
if [ -z "$BUNINU_STAMP" ] || [ ! -f "$BUNINU_STAMP" ]; then
  echo "Failed to create stamp for: $BUNINU_TGZ" >&2
  exit 1
fi


heading "Preparing source files"

rm -r "$sd"/app/src/main/java
mkdir -p "$sd"/app/src/main/java/"$PKG_PATH"
sed \
  -e "s/com.drjohn.test1/$PKG_NAME/" \
  "$sd"/MainActivity.java > "$sd"/app/src/main/java/"$PKG_PATH"/MainActivity.java
  
  
# { 
cd "$sd"/app/src/main

cat "$sd"/template/strings.xml | sed "s/Hello1/$app_name/">./res/values/strings.xml.tmp
mv ./res/values/strings.xml.tmp ./res/values/strings.xml

cat "$sd"/template/AndroidManifest.xml | sed "s/com.drjohn.test1/$PKG_NAME/">AndroidManifest.xml.tmp
mv AndroidManifest.xml.tmp AndroidManifest.xml

cd -
# }


heading "Compiling $PKG_NAME APK, name: $app_name"

# Clean old build
progress 1 "Recreating build folder"
if [ -d "$BUILD" ] ; then
  rm -rf "$BUILD"
fi

mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/dex" "$BUILD/apk" "$BUILD/apk_final"


progress 2 "Link manifest/resources..."

if find "$PROJ/res" -type f | grep -q .; then
  "$AAPT2" compile --dir "$PROJ/res" -o "$BUILD/res/compiled.zip"
  "$AAPT2" link \
    -o "$BUILD/apk/resources.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    --auto-add-overlay \
    "$BUILD/res/compiled.zip"
else
  "$AAPT2" link \
    -o "$BUILD/apk/resources.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml"
fi

progress 3 "Compile Java..."
printf '%sNote: the onBackPressed() deprecation warning is expected and safe to ignore.%s\n' "$BOLD_GREEN" "$RESET"
printf '%s注意：onBackPressed() 的棄用警告是正常的，可以安全忽略。%s\n' "$BOLD_GREEN" "$RESET"
java -jar "$ECJ_JAR" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  "$PROJ/java/$PKG_PATH/MainActivity.java"

progress 4 "Run R8..."
find "$BUILD/classes" -name "*.class" > "$BUILD/classes.list"
set --
while IFS= read -r class_file; do
  set -- "$@" "$class_file"
done < "$BUILD/classes.list"
java -cp "$D8_JAR" com.android.tools.r8.R8 \
  --release \
  --dex \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --pg-conf "$sd"/template/proguard.pro \
  --pg-map-output "$BUILD/mapping.txt" \
  --output "$BUILD/dex" \
  "$@"

progress 5 "Package APK..."
cp "$BUILD/apk/resources.apk" "$BUILD/apk_final/app-unsigned.apk"
(cd "$BUILD/dex" && zip -q -u "../apk_final/app-unsigned.apk" classes.dex)

progress 5a "Packaging Buninu payload assets..."
mkdir -p "$BUILD/assets/assets"
cp "$BUNINU_TGZ" "$BUILD/assets/assets/buninu.tgz"
cp "$BUNINU_STAMP" "$BUILD/assets/assets/buninu.stamp"
(cd "$BUILD/assets" && zip -q -u "../apk_final/app-unsigned.apk" assets/buninu.tgz assets/buninu.stamp)

if [ ! -f "$sd/libbun.so" ]; then
  bun_path=$(which bun 2>/dev/null)
  if [ -z "$bun_path" ] || [ ! -f "$bun_path" ]; then
    echo "libbun.so is missing and bun was not found in PATH" >&2
    exit 1
  fi
  heading "libbun.so not found; copying from: $bun_path"
  if ! cp "$bun_path" "$sd/libbun.so"; then
    echo "Failed to copy bun to: $sd/libbun.so" >&2
    exit 1
  fi
fi

progress 5b "Packaging native libraries..."
mkdir -p "$BUILD/native_libs/lib/arm64-v8a"
for native_lib in libbun.so libsh-loader.so libld-musl.so libmain.so; do
  if [ -f "$sd/$native_lib" ]; then
    cp "$sd/$native_lib" "$BUILD/native_libs/lib/arm64-v8a/$native_lib"
  fi
done
(cd "$BUILD/native_libs" && zip -q -r -u "../apk_final/app-unsigned.apk" lib)

progress 5c "Packaging license notices..."
mkdir -p "$BUILD/licenses/assets/licenses"
cp "$sd/LICENSE" "$BUILD/licenses/assets/licenses/LICENSE"
cp "$sd/NOTICE" "$BUILD/licenses/assets/licenses/NOTICE"
cp -r "$sd/LICENSES" "$BUILD/licenses/assets/licenses/LICENSES"
(cd "$BUILD/licenses" && zip -q -r -u "../apk_final/app-unsigned.apk" assets/licenses)

progress 6 "Zipalign..."
"$ZIPALIGN" -f 4 "$BUILD/apk_final/app-unsigned.apk" "$BUILD/apk_final/app-aligned.apk"

progress 7 "Create Keystore if needed"
if [ ! -f "$KEYSTORE" ]; then
    echo "Create Promaster keystore..."
    keytool -genkeypair \
      -alias "$KEY_ALIAS" \
      -keyalg EC \
      -groupname secp256r1 \
      -sigalg SHA256withECDSA \
      -validity 10000 \
      -keystore "$KEYSTORE" \
      -storetype PKCS12 \
      -storepass "$KEY_PASS" \
      -keypass "$KEY_PASS" \
      -dname "CN=Promaster,O=Promaster Development,C=US"
fi

progress 8 "Sign APK..."
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass pass:"$KEY_PASS" \
  --key-pass pass:"$KEY_PASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --v4-signing-enabled false \
  --out "$BUILD/$app_name-release.apk" \
  "$BUILD/apk_final/app-aligned.apk"

cp "$BUILD/$app_name-release.apk" "$sd/$app_name.apk"

SIZE=$(wc -c < "$sd/$app_name.apk")
echo ""
printf '%sDone:%s %s.apk - %s bytes\n' "$BOLD_CYAN" "$RESET" "$app_name" "$SIZE"
