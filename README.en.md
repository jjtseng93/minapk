# minapk

- [繁體中文讀我檔](README.md)
- Wrap your Bun single-file executable into APK

- minapk is a Gradle-free Android APK build project. It uses command-line tools to process resources, compile Java, run R8/DEX, package, zipalign, and sign an APK, and embeds the [Buninu](https://www.npmjs.com/package/buninu) runtime in it.

- `npx @drxiaozhi/minapk /path/to/your.elf` -- just pass the elf path as a positional argument (details below)

- This project is derived from [Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab).

## 0. Install dependencies

### Termux

```sh
pkg install aapt aapt2 zip openjdk-21 nodejs npm
npm install -g bun
```

### Debian / Ubuntu (apt)

```sh
sudo apt install aapt zipalign zip openjdk-21-jdk-headless nodejs npm
curl -fsSL https://bun.sh/install | bash
```

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

> [!NOTE]
> Run via `npx` (not a local checkout), the first build hits up to 3
> confirmation prompts in a row, verified in practice; answer `y` (or `Y`) to
> each -- this is expected:
> 1. `npx` asks whether to install `@drxiaozhi/minapk` itself.
> 2. With no local `no_backup` (the published npm package never ships one),
>    `build.sh` asks whether to run `npx buninu@latest --export`.
> 3. That `npx` call in turn asks whether to install `buninu`.
>
> Since every `npx` run is a fresh, throwaway environment, steps 2 and 3
> reappear on nearly every run.

This is the main entry point; the whole process is driven by `index.js`:

1. If an elf path is given, this build packages it as `libmain.so` (never
   written to the project root, only affecting this one run; without an elf,
   the project root's existing `libmain.so`, if any, is used -- see below).
2. Prepare the Buninu payload and the manifest/resources/Java source, compile
   with aapt2, ECJ, and R8, package the Buninu payload and native libraries,
   run zipalign, and sign with `tools/debug.keystore` (created automatically
   when absent), writing the result to the project root as `<appname>.apk`
   (for example, the current configuration produces `Hello2.apk`).
3. On a successful build, copy that APK to **the directory you ran the
   command from (cwd)**: named after the elf (extension stripped, also
   handled correctly when the elf has none) when one was given, otherwise
   named after `<appname>` -- for example, running
   `npx @drxiaozhi/minapk` with no arguments at all, with the current
   configuration, produces `Hello2.apk` in whatever directory you ran it
   from. This makes the result reachable even when running through a real
   `npx` install, where the package itself lives in npx's ephemeral cache
   rather than anywhere you'd find it afterwards; when your cwd already is
   the project root, this step is skipped since the APK is already right
   there.

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

The project ships a ready-made `tools/debug.keystore` on purpose, so that even
an environment without `keytool` (or without Java at all) can still complete
signing and produce an installable APK -- in a doomsday-survival scenario,
getting an installable APK out the door matters more than anything else.

> [!WARNING]
> The bundled `tools/debug.keystore` is **the same private key shared by every
> user who hasn't replaced it** (password is the fixed `android`), because it
> ships publicly through npm and anyone can get it. That means:
> - An APK signed with the default keystore is signed with the exact same key
>   as everyone else's default-keystore APK.
> - Anyone can re-sign a different APK with that same public key, and as long
>   as the package name matches, Android will accept it as a legitimate
>   update.
>
> If `keytool` is available in your environment, delete `tools/debug.keystore`
> and build once more -- the scripts will generate a fresh key that's yours
> alone. Do this before any real release or before handing the APK to anyone
> else, or switch to your own release keystore and back up its private key and
> password securely.

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

## Native clipboard support

The app ships a bridge from Buninu to the Android native layer
(`no_backup/apps/native-bridge`): `MainActivity` opens a unix socket that
exposes Toast and system-clipboard read/write to the Bun process running
inside Buninu. Buninu's bundled `xclip` command (`apps/xclip`) is built on
top of that bridge:

```sh
echo hello | xclip -selection clipboard   # write to the Android system clipboard
xclip -o -selection clipboard             # read it back
```

`-selection primary` (the default when `-selection` is omitted) stays
local-file-only and never touches the native clipboard, matching real X11
semantics; only `-selection clipboard`/`-clip` goes through native-bridge to
the real Android clipboard. jsmdcui's own clipboard backend detection already
uses `xclip` once it detects it, so jsmdcui's middle-click paste, selection
auto-sync, and `PastePrimary` command all work inside this app with no extra
setup.

To call the bridge directly instead of going through `xclip`, `import {
toast, clipboardRead, clipboardWrite } from` that path inside a `js back`
block; every call times out after 5 seconds by default, so an unresponsive
Android side can never hang the caller. See the "Commands inside the shell"
section of `no_backup/README.md` for the full API.

The same bridge also covers Text-To-Speech(tts): `tts "hello"` speaks text and waits for
it to finish, `-a` returns immediately instead. Without the app it falls
back to desktop commands (`espeak-ng`/`say`/PowerShell) instead.

## Producing a single-file executable

Both routes below produce the kind of elf minapk wants -- an arm64 executable
whose loader is `/system/bin/linker64`. Both need a canary build of Bun:

```sh
bun upgrade --canary
```

### Route 1: a one-line `bun build`

`hlw.js`:

```js
console.log("Hello from Bun single-file executable")
```

```sh
bun build --format=esm --compile --minify --bytecode ./hlw.js
npx @drxiaozhi/minapk hlw
```

That gives you `hlw.apk`. The output name needs no flag: the extension is
stripped to give `hlw` (or `hlw.exe` on Windows).

### Route 2: a Markdown app

`hlw.md`:

````markdown
#!/usr/bin/env jsmdcui

## Question 問題
- What is 1+2+3+4+..+..+∞

```text#ans
-1/12
```
- [Submit 提交](javascript:checkAns())
- [Where am I? 我在哪？](javascript:whereAmI())

```js front
export function checkAns()
{
  if($('#ans').val().trim()=='-1/12')
    $('#ans').val('答對🥳Right!');
  else
    $('#ans').val('答錯😫Wrong!');
}

export async function whereAmI()
{
  const r = await rpc.sysinfo();
  alert(Object.entries(r).map(([k, v]) => `${k}: ${v}`).join('\n'));
}
```

```js back
export function sysinfo()
{
  return {
    bun: Bun.version,
    platform: process.platform,
    arch: process.arch,
    buninu: process.env.BUNINU_HOME ?? '(not under Buninu)',
    android: process.env.PKG_DDIR ?? '(not inside an APK)',
  };
}
```
````

```sh
npx jsmdcui --build-md-exe hlw.md
npx @drxiaozhi/minapk mdcui
```

> [!NOTE]
> The built executable is always named `mdcui`, not `hlw`, so feeding it
> straight to minapk produces `mdcui.apk`. Rename it with `mv` first.
> (Don't reach for `--outfile` to rename it: that path is not resolved
> against your cwd, and the file lands inside jsmdcui's own directory
> instead.)

`checkAns` is pure front-end answer checking; `whereAmI` calls into the
`js back` block through `await rpc.sysinfo()` to report where the app is
running. Run that same executable directly in Termux and it reports
`(not inside an APK)`; install it as an APK and the very same button reports
the real Buninu home and Android private data directory paths.

## Google Play distribution and policy

> [!IMPORTANT]
> An APK built by minapk is **not something you can publish as-is**. The two
> points below are mechanical requirements, not matters of policy
> interpretation -- uploading the current output to the Play Console gets
> stopped on both:
>
> - **Format**: new apps have only been accepted as
>   [Android App Bundles (AAB)](https://developer.android.com/guide/app-bundle/faq)
>   since August 2021; minapk produces an APK.
> - **Signing**: new apps go through Play App Signing, where you hold only an
>   upload key -- there is no way to keep using the shared
>   `tools/debug.keystore` described under "APK signing and keytool" above.
>
> In other words, publishing requires at minimum switching to an AAB flow and
> moving to your own key. Only once all of that is done does the murkier
> policy question below even come up.

### The policy side

Play's [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646)
policy states that an app may not download executable code (dex, JAR, `.so`)
from a source other than Google Play, and may not update itself by any means
other than Play's update mechanism -- with an exception for code that runs in
a virtual machine or an interpreter.

How an APK built by minapk actually executes:

- `libbun.so` and `libmain.so` are **packaged inside the APK** under
  `lib/arm64-v8a/`, and the app runs them from `nativeLibraryDir` via
  `ProcessBuilder`. That directory is one of the few locations that keeps
  execute permission under the Android 10 (API 29) W^X restriction.
- The Buninu payload (JS) is likewise **packaged inside the APK** in assets,
  extracted to `no_backup` only on first launch, and then interpreted by that
  same `libbun.so`.
- Nothing in this flow downloads a native executable over the network.

How comparable projects do it:

- `libnode.so` ([nodejs-mobile](https://github.com/JaneaSystems/nodejs-mobile))
  takes the more conservative route: Node is compiled as a genuine JNI shared
  library, loaded with `System.loadLibrary("node")` into the app's own
  process, with no forked/exec'd child process -- so it is simply "a native
  library inside the APK".
- minapk instead execs Bun as a standalone executable, which is closer to
  [Termux's Play Store build](https://github.com/termux-play-store) (which
  uses `system_linker_exec` to deal with the Android 10+ W^X restriction).
  Termux itself went years without Play updates because of the target-API-29
  execution restriction, and only returned to Play through that fork.

What you still have to weigh yourself: by default the app opens an
interactive shell in which the user can run arbitrary programs, and Buninu's
`bunx` installs and runs packages from npm at run time. Obtaining code at run
time like that is exactly what review scrutinizes most.

> [!NOTE]
> I am not a legal professional. The above is only a comparison of the public
> policy text against what similar projects do, and is not legal or compliance
> advice. `--no-shell` (that is, `buninu.exitAfterCmd`) is already provided so
> your command can finish and exit outright instead of falling back to an
> interactive shell, as one way to narrow that exposure. The actual review
> standards and outcomes are left for users to discover.

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
