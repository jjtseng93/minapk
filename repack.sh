#!/bin/sh

sd=$(dirname "$(realpath "$0")")
jdir=$sd/tools
BUILD=$sd/build

ZIPALIGN=$(which zipalign)
APKSIGNER_JAR=$jdir/apksigner.jar
KEYSTORE=$jdir/debug.keystore
KEY_ALIAS=androiddebugkey
KEY_PASS=android

app_name=$(cat "$sd/appname.txt" | tr -d '\r\n')
SOURCE_APK=$sd/$app_name.apk
REPACK_APK=$BUILD/apk_final/app-repack-unsigned.apk
ALIGNED_APK=$BUILD/apk_final/app-repack-aligned.apk
BUNINU_TGZ=$sd/buninu.tgz
BUNINU_STAMP=$sd/buninu.stamp
OUTPUT_APK=$sd/${app_name}r.apk

RESET=$(printf '\033[0m')
BOLD_CYAN=$(printf '\033[1;36m')
BOLD_GREEN=$(printf '\033[1;32m')
YELLOW=$(printf '\033[33m')

progress() {
  printf '%s%s.%s %s%s%s\n' "$BOLD_GREEN" "$1" "$RESET" "$YELLOW" "$2" "$RESET"
}

printf '%sRepacking %s → %sr.apk%s\n' "$BOLD_CYAN" "$SOURCE_APK" "$app_name" "$RESET"

if [ ! -f "$SOURCE_APK" ]; then
  echo "Missing source APK: $SOURCE_APK" >&2
  exit 1
fi

if [ -d "$sd/no_backup" ]; then
  progress 1 "Exporting local Buninu payload..."
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

progress 2 "Creating payload stamp..."
bun "$sd/make-stamp.js" "$BUNINU_TGZ"
if [ ! -f "$BUNINU_STAMP" ]; then
  echo "Stamp was not created: $BUNINU_STAMP" >&2
  exit 1
fi

progress 3 "Updating payload assets..."
mkdir -p "$BUILD/apk_final"
cp "$SOURCE_APK" "$REPACK_APK"
mkdir -p "$BUILD/repack-assets/assets"
cp "$BUNINU_TGZ" "$BUILD/repack-assets/assets/buninu.tgz"
cp "$BUNINU_STAMP" "$BUILD/repack-assets/assets/buninu.stamp"
(cd "$BUILD/repack-assets" && zip -q -u "$REPACK_APK" assets/buninu.tgz assets/buninu.stamp)

progress 4 "Zipalign..."
"$ZIPALIGN" -f 4 "$REPACK_APK" "$ALIGNED_APK"

progress 5 "Creating keystore if needed..."
if [ ! -f "$KEYSTORE" ]; then
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

progress 6 "Signing APK..."
java -jar "$APKSIGNER_JAR" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass pass:"$KEY_PASS" \
  --key-pass pass:"$KEY_PASS" \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --v4-signing-enabled false \
  --out "$OUTPUT_APK" \
  "$ALIGNED_APK"

SIZE=$(wc -c < "$OUTPUT_APK")
echo ""
printf '%sDone:%s %sr.apk - %s bytes\n' "$BOLD_CYAN" "$RESET" "$app_name" "$SIZE"
