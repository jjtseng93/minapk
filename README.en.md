# minapk

- [繁體中文讀我檔](README.md)
- Wrap your Bun single-file executable into APK

- minapk is a Gradle-free Android APK build project. It uses command-line tools to process resources, compile Java, run R8/DEX, package, zipalign, and sign an APK, and embeds the [Buninu](https://www.npmjs.com/package/buninu) runtime in it.

- Put `libmain.so` at the project root to make it run on startup (details below)

- This project is derived from [Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab).

## 1. App and APK name

```sh
npx @drxiaozhi/minapk -n MyApp
```

`-n`/`--appname <name>` controls:

- The Android application label.
- The build output name, such as `MyApp.apk`.
- The repack input and output names, such as `MyApp.apk` → `MyAppr.apk`.

Without `-n`, the current value of the project root's `appname.txt` (`Hello2`)
is used as the default; minapk never writes to or modifies that file, so a run
without `-n` always builds the same, predictable name.

## 2. Android package name

```sh
npx @drxiaozhi/minapk -p com.drjohn.bunwv
```

`-p`/`--pkgname <pkgname>` controls the Android package name/application ID,
not the APK filename. It must use reverse-domain notation and be a valid Java
package name; `repack.sh` does not change the package name, only a full build
applies it.

The recommended form is `com.<6 characters>.<5 characters>`, keeping the full
package name at 16 ASCII characters, for example `com.drjohn.bunwv`. This is
not an Android requirement: `com.termux/files` is also 16 bytes, so matching
its length may allow fixed-size patching of that embedded path in Termux
binaries without relocating other binary data. The current build does not yet
perform this patch automatically.

Without `-p`, the current value of the project root's `pkgname.txt`
(`com.drjohn.bunwv`) is used as the default, likewise never written to or
modified by minapk.

## 3. Build

```sh
npx @drxiaozhi/minapk [/path/to/your.elf]
```

This is the main entry point; the whole process is driven by `index.js`:

1. If an elf path is given, this build packages it as `libmain.so` (never
   written to the project root, only affecting this one run; without an elf,
   the project root's existing `libmain.so`, if any, is used -- see below).
2. Prepare the Buninu payload and the manifest/resources/Java source, compile
   with aapt2, ECJ, and R8, package the Buninu payload and native libraries,
   run zipalign, and sign with `tools/debug.keystore` (created automatically
   when absent), writing the result to the project root as `<appname>.apk`
   (for example, the current configuration produces `Hello2.apk`).
3. On a successful build, copy that APK to the current working directory:
   named after the elf (extension stripped, also handled correctly when the
   elf has none) when one was given, otherwise named after `<appname>`. This
   makes the result reachable even when running through a real `npx` install,
   where the package itself lives in npx's ephemeral cache rather than
   anywhere you'd find it afterwards.

With a local checkout, this can also be run directly (same effect):

```sh
bun ./index.js
```

The actual compilation is performed by `build.sh`, which `index.js` spawns
internally; it is a POSIX shell script and needs a `/bin/sh` (or Android's
`/system/bin/sh`) to run. You normally don't need to call it directly.

- `libmain.so`: Must be an Android Bun compiled /system/bin/linker64 executable (supported on Bun >= 1.4). Automatically runs at Android App startup. If the project root contains this file, the build packages it into the APK's native
`lib/arm64-v8a` directory; when absent, it is skipped without an error.

- Before packaging native libraries, if `libbun.so` is missing from the project
root, the build runs `which bun` and copies the discovered Bun to
`./libbun.so`. The build stops if Bun cannot be found or copied. That Bun must
be executable on the target Android arm64 environment. 

Remove the `build/` scratch folder:

```sh
npm run clean
# same as
bun ./clean.js
```

## 4. Advanced configuration

### Replace the Buninu `package.json` packaged into the APK with `--config`

```sh
npx @drxiaozhi/minapk /path/to/your.elf --config /path/to/package.json
```

The file `--config` points to **completely replaces** the `package.json`
inside the Buninu payload packaged into the APK (a full swap, not a merge).
Use it to customize startup settings such as `buninu.shell`, `buninu.command`,
and `buninu.exitAfterCmd` without touching the original files under
`no_backup`.

How it works:

1. Just like `build.sh`'s own logic, first export a `buninu.tgz` from the
   local `no_backup` (or ask whether to run `npx buninu@latest --export` when
   `no_backup` is absent).
2. Append the `--config` file's content, unmodified, as a second
   `<top-level dir>/package.json` entry at the tail of the tar stream --
   without repacking the whole tgz, so the thousand-plus other entries
   (including symlinks and executable permissions) are left completely
   untouched. tar extracts entries in order and later writes win, so the app
   ends up with the copy `--config` provided.
3. Pass the resulting tgz path to `build.sh`, skipping its own export step.

> [!IMPORTANT]
> The `--config` file must be a **complete** `package.json` (including
> `name`/`version`/`scripts`/`bin`, etc.), not just the `buninu` section,
> since it's a full replacement rather than a merge. You can start from
> `npx buninu@latest --export-config`, which produces a complete `buninu.json`
> -- edit its `buninu` section and use that as `--config`'s input.

### Single-field override with `-c`/`--command`

If you don't want to hand-write a full `package.json` just to change one
field, use this flag on its own:

```sh
npx @drxiaozhi/minapk /path/to/your.elf -c "echo custom startup command"
```

`-c`/`--command <command>` overwrites the **entire** `buninu.command` field of
the `package.json` packaged into the APK with this string (`buninu` itself
also accepts `"command": "a string"` as shorthand, applying it to every
platform; minapk only ever builds Android, so there's no need to worry about
how the `default`/`android`/`linux` sub-fields would otherwise merge).

> [!WARNING]
> Buninu's default `buninu.command.android` is:
> ```sh
> if command -v libmain.so >/dev/null 2>&1; then libmain.so; else printf ...; fi
> ```
> i.e. it detects `libmain.so` and launches it automatically. `-c` **replaces
> the whole field**, it doesn't add to that logic -- so your elf (the one
> packaged as `libmain.so` via the elf positional argument) **will not start automatically** unless
> your own `command` explicitly invokes `libmain.so` (for example
> `-c "libmain.so"`, or as part of your own logic). Forgetting this usually
> shows up as: the APK builds fine, the app opens fine, but your program never
> actually starts.

`-c` **composes** with `--config` instead of being mutually exclusive with it:

- With `--config`: the `--config` file's content is the base; `-c` only
  overrides its `buninu.command`, every other field stays as written in the
  `--config` file.
- Without `--config`: the `package.json` already inside this run's exported
  Buninu payload is the base; `-c` likewise only overrides `buninu.command`,
  with no need to prepare a `--config` file at all.

### Disable the fall-back-to-shell behavior with `--no-shell`

```sh
npx @drxiaozhi/minapk /path/to/your.elf -c "echo custom command" --no-shell
```

`--no-shell` takes no value; its presence sets `buninu.exitAfterCmd` to `true`
(default `false`, see the `exitAfterCmd` note in
[Buninu's README](https://www.npmjs.com/package/buninu)): once
`buninu.command` finishes, the shell/PTY exits immediately instead of falling
back to an interactive shell like the default does. It composes the same way
`-c` does -- layered on top of `--config` when given, or on top of this run's
freshly exported `package.json` otherwise, leaving every other field alone.

`-c`/`--no-shell`/`--config` can be combined freely with `-n`/`-p` (sections 1
and 2) and the elf positional argument, for example:

```sh
npx @drxiaozhi/minapk /path/to/your.elf -n MyApp -p com.example.myapp -c "echo hello" --no-shell
```

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

## On-screen key bar

The built app has a row of terminal-friendly keys at the bottom; a short tap
and a long press often do different things:

| Key | Short press | Long press |
| --- | --- | --- |
| ESC | Esc | Ctrl+Q |
| SHFT | Toggle the Shift modifier | **Ctrl+D** |
| ^C x | Ctrl+C | Ctrl+X |
| HOME | Home | Ctrl+U |
| END | End | Ctrl+K |
| TAB | Tab | Shift+Tab |
| PGU | Page Up | Hold to scroll up continuously (mouse wheel) |
| PGD | Page Down | Hold to scroll down continuously (mouse wheel) |
| ↑ ↓ ← → | Arrow keys | Hold for auto-repeat |
| Ent | Enter | Forward Delete |
| CTRL / ALT | Toggle the Ctrl / Alt modifier | (none) |

CTRL, ALT, and SHFT are Termux-style one-shot modifiers: a short tap
highlights the button to show it's armed, and it's automatically cleared once
applied to the next key you press -- so typing Ctrl+C is just tap CTRL, then
tap `^C x` (or any letter key), no need to hold two keys down with multi-touch.
There's also a very narrow, near-invisible text field in the top-right corner
that brings up the system IME to type or paste text directly.

## Roadmap
- Fix xterm scrolling of jsgotty
- ~~Add Ctrl Alt Shift keys~~ (done)
- Wrap until we reach
  * ~~npx @drxiaozhi/minapk your_binary~~ (done, see "Build")
  * npx @drxiaozhi/minapk myapp.md
- Native bridge (architecture still being designed)
- `BUN_BE_BUN` mechanism: `libmain.so` is, under the hood, a Bun executable
  with a standalone module graph appended to it (the output of
  `bun build --compile`), and normally detects and boots that embedded app
  directly. Bun's own single-file-executable docs document the `BUN_BE_BUN=1`
  environment variable, which makes that same binary behave like a plain
  `bun` CLI instead, skipping the standalone-graph detection. In principle
  `libmain.so` could double as `libbun.so` (invoke it with `BUN_BE_BUN=1`)
  instead of packaging a whole separate Bun executable, saving significant
  APK space. Not settled yet -- shipping `libbun.so`/`libmain.so` as two
  separate copies today means each can fall back for the other (e.g. if
  `libmain.so`'s standalone graph or `BUN_BE_BUN` behavior misbehaves, an
  independent `libbun.so` is still there), and sharing one file needs to
  weigh giving up that safety net

## License

This project is released under the [MIT License](LICENSE). Its original
upstream project is
[tinyapk-lab](https://github.com/Promastergame/tinyapk-lab). The Android SDK,
build tools, and other third-party components remain under their respective
licenses. See [NOTICE](NOTICE) and [LICENSES](LICENSES/README.md) for complete
tool versions, sources, and license information.
