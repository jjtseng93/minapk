# minapk

[繁體中文](README.md)

This is a Gradle-free Android APK build project. It uses command-line tools to
process resources, compile Java, run R8/DEX, package, zipalign, and sign an APK,
and embeds the [Buninu](https://www.npmjs.com/package/buninu) runtime in it.

This project is derived from
[Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab).

## 1. Change the app and APK name

Edit `appname.txt`:

```text
Hello2
```

This value controls:

- The Android application label.
- The full-build output name, such as `Hello2.apk`.
- The repack input and output names, such as `Hello2.apk` → `Hello2r.apk`.

Put only one name on the line, without the `.apk` suffix.

## 2. Change the Android package name

Edit `pkgname.txt`:

```text
com.drjohn.bunwv
```

This is the Android package name/application ID, not the APK filename. It must
use reverse-domain notation and be a valid Java package name. Run a complete
`build.sh` after changing it; `repack.sh` alone does not change the package
name.

The recommended form is `com.<6 characters>.<5 characters>`, keeping the full
package name at 16 ASCII characters, for example `com.drjohn.bunwv`. This is
not an Android requirement: `com.termux/files` is also 16 bytes, so matching
its length may allow fixed-size patching of that embedded path in Termux
binaries without relocating other binary data. The current build does not yet
perform this patch automatically.

## 3. Full build

```sh
./build.sh
```

`build.sh` performs the complete process:

1. Export `buninu.tgz` from the local `no_backup` and generate
   `buninu.stamp`. If no local copy exists, ask whether to export it with
   `npx buninu`.
2. Prepare the manifest, resources, and Java source according to `appname.txt`
   and `pkgname.txt`.
3. Compile resources and code with aapt2, ECJ, and R8.
4. Package the Buninu payload and native libraries.
5. Run zipalign.
6. Sign with `tools/debug.keystore`, creating it when absent.
7. Write `<appname>.apk` to the project root.

For example, the current configuration produces:

```text
Hello2.apk
```

The script uses POSIX shell syntax and can run on Android with:

```sh
/system/bin/sh ./build.sh
```

- `libmain.so`: Must be an Android Bun compiled /system/bin/linker64 executable (supported on Bun >= 1.4). Automatically runs at Android App startup. If the project root contains this file, the build packages it into the APK's native
`lib/arm64-v8a` directory; when absent, it is skipped without an error.

- Before packaging native libraries, if `libbun.so` is missing from the project
root, `build.sh` runs `which bun` and copies the discovered Bun to
`./libbun.so`. The build stops if Bun cannot be found or copied. That Bun must
be executable on the target Android arm64 environment. 

## Update only the Buninu payload

After at least one complete build has produced a root-level APK, run:

```sh
./repack.sh
```

`repack.sh` does not recompile Android resources, Java, or DEX. It:

1. Uses the root-level `<appname>.apk` as its source.
2. Re-exports `buninu.tgz` and generates `buninu.stamp`.
3. Replaces the payload in the APK.
4. Runs zipalign and signing again.
5. Writes `<appname>r.apk` to the project root without overwriting the source
   APK.

For example:

```text
Hello2.apk → Hello2r.apk
```

It can also be run explicitly with Android's system shell:

```sh
/system/bin/sh ./repack.sh
```

## Buninu payload source

Buninu npm package: <https://www.npmjs.com/package/buninu>

> [!IMPORTANT]
> `buninu.tgz` **must contain exactly one top-level directory**. The app strips
> that first component during extraction (`--strip-components=1`) and places
> its contents directly in the Buninu home.

The extraction destination is the app's private internal Buninu home:

```text
/data/data/<package-name>/no_backup
```

On some Android versions, the same app data directory may be represented as
`/data/user/0/<package-name>`; the actual path is obtained from
`ApplicationInfo.dataDir`. The archive's single top-level directory is only a
removable wrapper and does not create another nested directory inside
`no_backup`.

Valid:

```text
no_backup/
no_backup/bin/
no_backup/apps/
```

Invalid:

```text
no_backup/
other_directory/
```

The top-level directory does not have to be named `no_backup`, but every entry
in the archive must share one top-level name. On a first installation, an
invalid archive cannot create the Buninu home and the WebView displays an error
in Chinese and English. If an older installation exists, the app skips the
invalid payload and starts the existing version.

When a root-level `no_backup` exists, both scripts run:

```sh
bun no_backup/bin/init.js --export buninu.tgz
```

Otherwise, the script asks whether to run `npx buninu@latest --export`. It
downloads from npm only after an explicit `y` or `Y` response.

## Main external tools

- Bun
- aapt2
- zipalign
- zip
- Java
- keytool (only when creating the keystore)

Other build-time JARs are stored under `tools/`.

## APK signing and keytool

`build.sh` and `repack.sh` use this file by default:

```text
tools/debug.keystore
```

The scripts invoke `keytool` to create it only when it does not exist. Later
builds and repacks continue signing with the same file, so retain it when
updating an installed APK.

The default debug keystore is suitable for local testing. Use your own release
keystore for production distribution, and securely back up its private key and
passwords.

## License

This project is released under the [MIT License](LICENSE). Its original
upstream project is
[tinyapk-lab](https://github.com/Promastergame/tinyapk-lab). The Android SDK,
build tools, and other third-party components remain under their respective
licenses. See [NOTICE](NOTICE) and [LICENSES](LICENSES/README.md) for complete
tool versions, sources, and license information.
