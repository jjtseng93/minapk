# minapk

- [English Readme](README.en.md)
- 把你的Bun單一可執行檔 包成APK

- minapk 是一個不使用 Gradle 的 Android APK 建置專案，以命令列工具完成資源處理、Java 編譯、R8/DEX、APK 封裝、zipalign 與簽章，並將 [Buninu](https://www.npmjs.com/package/buninu) 執行環境放入 APK。

- `npx @drxiaozhi/minapk /path/to/your.elf` 把 elf 路徑當位置參數傳入即可（詳細說明在後面）

- 本專案衍生自 [Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。

## 0. 安裝依賴

### Termux

```sh
pkg update
pkg install aapt aapt2 zip unzip openjdk-21 nodejs npm
npm install -g bun
bun upgrade
```

### Debian / Ubuntu（apt）arm64 inside Termux proot

```sh
apt update
apt install aapt zipalign zip unzip openjdk-21-jdk-headless nodejs npm
npm i -g @oven/bun-linux-aarch64-android --force
export PATH=$(npm root -g)/@oven/bun-linux-aarch64-android/bin:$PATH
bun upgrade

# when running the build you need to see something like this: libbun.so not found; copying from: /usr/local/lib/node_modules/@oven/bun-linux-aarch64-android/bin/bun
```

## 1. 應用程式名稱與 APK 名稱

```sh
npx @drxiaozhi/minapk -n MyApp
```

`-n`/`--appname <name>` 決定：

- Android 顯示的應用程式名稱。
- 建置輸出的 APK 名稱，例如 `MyApp.apk`。
- repack 輸入與輸出名稱，例如 `MyApp.apk` → `MyAppr.apk`。

不帶 `-n` 時，會用專案根目錄 `appname.txt` 目前的值（`Hello2`）當預設；minapk 不會寫入或修改這個檔案，所以不帶 `-n` 的建置永遠得到同一個、可預期的名稱。

## 2. Android package name

```sh
npx @drxiaozhi/minapk -p com.drjohn.bunwv
```

`-p`/`--pkgname <pkgname>` 決定 Android 的 package name/application ID，不是 APK 檔名。它應使用反向網域格式，並且必須是有效的 Java package 名稱；`repack.sh` 不會改變 package name，只有完整建置會套用。

建議使用 `com.<6 字元>.<5 字元>` 的格式，讓完整 package name 維持
16 個 ASCII 字元，例如 `com.drjohn.bunwv`。這不是 Android 的強制限制；
採用此長度是因為字串 `com.termux/files` 同樣是 16 bytes，未來可能可以
對含有該固定路徑的 Termux binary 進行等長 patch，而不必移動 binary
內的其他資料。目前的 build 流程尚未自動進行這種 patch。

不帶 `-p` 時，會用專案根目錄 `pkgname.txt` 目前的值（`com.drjohn.bunwv`）當預設，同樣不會被 minapk 寫入或修改。

## 3. 建置

```sh
npx @drxiaozhi/minapk [/path/to/your.elf]
```

> [!NOTE]
> 用 `npx` 執行（不是本地 checkout）時，第一次建置實測會依序遇到最多 3 次確認提示，全部輸入 `y`（或 `Y`）即可，這是正常流程：
> 1. `npx` 詢問是否安裝 `@drxiaozhi/minapk` 本身。
> 2. 找不到本地 `no_backup`（npm 上發佈的套件本來就不含它）時，`build.sh` 詢問是否執行 `npx buninu@latest --export`。
> 3. 上一步的 `npx` 詢問是否安裝 `buninu`。
>
> 因為每次 `npx` 都是全新的暫存環境，第 2、3 步幾乎每次執行都會再問一次。

這是主要入口，整個流程都由 `index.js` 驅動：

1. 若有帶 elf 路徑，這次建置就用它當 `libmain.so` 打包（不會寫入專案根目錄，只影響這一次；不帶 elf 時使用專案根目錄現有的 `libmain.so`，見下方說明）。
2. 準備 Buninu payload 與 manifest/資源/Java 原始碼，用 aapt2、ECJ、R8 編譯，封裝 Buninu payload 與原生函式庫，執行 zipalign，並用 `tools/debug.keystore` 簽章（不存在時自動建立），輸出到專案根目錄的 `<appname>.apk`（例如目前設定會是 `Hello2.apk`）。
3. build 成功後，把輸出的 APK 複製一份到**你目前的工作目錄（cwd）**：有帶 elf 時檔名是 elf 本身的檔名（去掉副檔名，若原本就沒有副檔名也一樣正確處理）加上 `.apk`，例如 `myapp.elf` 會產生 `myapp.apk`；沒帶 elf 時就是 `<appname>.apk`——例如照目前設定直接執行 `npx @drxiaozhi/minapk`（不帶任何參數），會在你執行指令當下的目錄產生 `Hello2.apk`。這一步是為了透過真正的 `npx`（套件裝在 npx 暫存快取，跟你的 cwd 是兩回事）執行時也拿得到成品；如果你的 cwd 剛好就是專案根目錄，這一步會自動跳過（APK 本來就已經在那裡了）。

在有 checkout 的情況下也可以直接跑（效果相同）：

```sh
bun ./index.js
```

實際編譯步驟由 `index.js` 內部 spawn 的 `build.sh` 完成，它是 POSIX shell script，執行環境需要有 `/bin/sh`（或 Android 的 `/system/bin/sh`）；一般不需要直接呼叫它。

- `libmain.so`: 必須是使用 Android Bun 編譯、以 /system/bin/linker64 為執行載入器的執行檔（Bun >= 1.4 支援）。Android App 啟動時會自動執行。如果專案根目錄包含此檔案，建置時會將它封裝到 APK 的原生 lib/arm64-v8a 目錄中；若不存在則會直接略過，不會報錯。

- 封裝原生函式庫前，若專案根目錄沒有 `libbun.so`，會執行
`which bun`，並將找到的 Bun 複製為根目錄的 `libbun.so`；找不到 Bun
或複製失敗時會停止建置。該 Bun 必須是可在目標 Android arm64 環境
執行的版本。要明確指定用哪一份 Bun、而不是交給 `PATH` 決定，見第 4 節的
`-b`/`--bun-bin`。

> [!IMPORTANT]
> 這個 `which bun` 只在 `libbun.so` **不存在時**跑一次。複製過去之後，
> 之後每次建置都直接用根目錄那一份，不會再看 `PATH`——所以你後來
> `bun upgrade`、裝了新版、或切換到別的 Bun，建出來的 APK
> 裡還是舊的那顆。`5b` 印出的 revision 就是拿來確認這件事的。
> 要換成 `PATH` 上現在這顆，明確指定一次：
>
> ```sh
> npx @drxiaozhi/minapk -b "$(which bun)"
> ```

- 封裝原生函式庫那一步（`5b`）會執行 `libbun.so --revision` 並印出結果，
讓你知道這顆 APK 裡到底裝的是哪個版本的 Bun。因為 `libbun.so` 是
Android arm64 執行檔，不見得能在建置用的機器上直接跑；跑不起來時只會印
`unknown (not runnable on this build host)`，不會中斷建置。

清除 `build/` 暫存資料夾：

```sh
npm run clean
# 等同
bun ./clean.js
```

## 4. 進階設定

### 用 `--config` 完全取代封裝進 APK 的 Buninu `package.json`

```sh
npx @drxiaozhi/minapk /path/to/your.elf --config /path/to/package.json
```

`--config` 指到的檔案會**完整取代**封裝進 APK 的 Buninu payload 裡的 `package.json`（是整份換掉，不是合併），可以用來自訂 `buninu.shell`、`buninu.command`、`buninu.exitAfterCmd` 等啟動設定，而不用去修改 `no_backup` 裡的原始檔案。

運作方式：

1. 跟 `build.sh` 自己內部的做法一樣，先從本地 `no_backup` 匯出一份 `buninu.tgz`（沒有 `no_backup` 則詢問是否 `npx buninu@latest --export`）。
2. 把 `--config` 檔案的內容原封不動 append 成 tar 裡第二個同路徑的 `<頂層目錄>/package.json` entry，寫在既有內容的尾端——不重新打包整個 tgz，其餘上千筆 entry（含 symlink、可執行權限）完全不動。tar 解壓時後面的 entry 會覆蓋前面的，所以 App 實際解出來的就是 `--config` 提供的那份。
3. 把處理好的 tgz 路徑傳給 `build.sh`，跳過它自己的 export 步驟。

> [!IMPORTANT]
> `--config` 檔案必須是**完整**的 `package.json`（含 `name`/`version`/`scripts`/`bin` 等欄位），不是只寫 `buninu` 那一段，因為是整份取代、不是合併。可以先用 `npx buninu@latest --export-config` 產生一份完整的 `buninu.json` 當起點，直接拿來改 `buninu` 區段後當 `--config` 的輸入即可。

### 用 `-c`/`--command` 做單項覆蓋

不想為了改一個欄位就手寫一份完整 `package.json`，可以只用這個旗標：

```sh
npx @drxiaozhi/minapk /path/to/your.elf -c "echo custom startup command"
```

`-c`/`--command <command>` 把封裝進 APK 的 `package.json` 裡的 `buninu.command` **整個欄位**覆蓋成這個字串（`buninu` 本身也接受 `"command": "字串"` 這種簡寫，等同套用到所有平台，minapk 只建置 Android 所以不用管 `default`/`android`/`linux` 這些子欄位怎麼合併）。

> [!WARNING]
> Buninu 預設的 `buninu.command.android` 是：
> ```sh
> if command -v libmain.so >/dev/null 2>&1; then libmain.so; else printf ...; fi
> ```
> 也就是偵測到 `libmain.so` 就自動啟動它。用 `-c` 是**整個欄位覆蓋**，不是在這段邏輯上加東西，所以你的 elf（透過 elf 位置參數打包成 `libmain.so` 的那個）**不會自動被執行**，除非你自訂的 `command` 裡自己有呼叫 `libmain.so`（例如 `-c "libmain.so"` 或包在你自己的邏輯裡）。忘記這件事最常見的症狀就是：APK 建置成功、App 也能開，但你的程式完全沒有啟動。

`-c` 可以跟 `--config` **合併使用**而不是互斥：

- 有帶 `--config`：以 `--config` 檔案的內容當底，`-c` 只覆蓋其中的 `buninu.command`，其他欄位維持 `--config` 檔案原樣。
- 沒帶 `--config`：以本次 export 出來、Buninu payload 裡原本的 `package.json` 當底，一樣只覆蓋 `buninu.command`，其餘欄位維持原樣，不需要另外準備 `--config` 檔案。

### 用 `--no-shell` 停用「指令跑完掉回互動式 shell」

```sh
npx @drxiaozhi/minapk /path/to/your.elf -c "echo custom command" --no-shell
```

`--no-shell` 不用帶值，出現就把 `buninu.exitAfterCmd` 設成 `true`（預設 `false`，見 [Buninu README](https://www.npmjs.com/package/buninu) 的 `exitAfterCmd` 說明）：`buninu.command` 執行完後直接結束，不會像預設那樣掉回互動式 shell。合併規則跟 `-c` 一樣——有 `--config` 就疊加在它上面，沒有就疊加在本次 export 出來的原始 `package.json` 上，其餘欄位都不動。

### 用 `--no-back-to-console` 讓返回鍵直接離開 App

```sh
npx @drxiaozhi/minapk /path/to/your.elf --no-back-to-console
```

`--no-back-to-console` 不用帶值，出現就把 `buninu.backToConsole` 設成 `false`（預設 `true`）：在 app WebView 而且它自己沒有上一頁可回時，按返回鍵會照 Android 預設行為離開 App，而不是切回 console WebView。合併規則跟 `-c`／`--no-shell` 完全一樣——有 `--config` 就疊加在它上面，沒有就疊加在本次 export 出來的原始 `package.json` 上，其餘欄位都不動。

這個欄位是**由 Android 端的 App 讀的**，不是 Buninu 自己讀的，所以在 APK 以外的地方設它不會有任何效果。App 端讀不到檔案、JSON 壞掉、沒有這個欄位、值不是布林，一律當成 `true`（回到 console），不會因此丟出任何錯誤。

### 用 `-b`/`--bun-bin` 指定要封裝進 APK 的 Bun

```sh
npx @drxiaozhi/minapk /path/to/your.elf -b /path/to/android-arm64/bun
```

`-b`/`--bun-bin <path>` 把指定的 Bun 執行檔複製成專案根目錄的 `libbun.so`，
**取代**目前那一份，然後才開始建置。用來在不同版本的 Bun（例如 canary 與
stable）之間切換，而不必自己去 `cp` 或去動 `PATH`。該 Bun 一樣必須是可在
Android arm64 上執行的版本。

> [!IMPORTANT]
> 這個旗標跟 `-n`/`-p`/elf 位置參數**不一樣**：那三個只影響當次建置，磁碟上的
> `appname.txt`/`pkgname.txt`/`libmain.so` 永遠不會被寫入；`-b` 則是真的把
> `libbun.so` 覆蓋掉，之後不帶 `-b` 的建置也會繼續用這一份。
>
> 這是刻意的：`libbun.so` 本來就不是 checked-in 的檔案，而是 `build.sh` 自己
> 會寫的快取（不存在時它會把 `which bun` 找到的 Bun 複製過去），所以 `-b`
> 覆蓋掉的只是「上次從 `PATH` 撿來的那份」，沒有什麼可預期的預設值會被破壞。

不論有沒有帶 `-b`，建置到 `5b` 那一步都會印出實際封裝進去的 Bun 版本：

```text
5b. Packaging native libraries...
Packaged Bun (libbun.so) revision:
  1.4.0-canary.1+41c3f6fdb
```

`-c`/`--no-shell`/`--no-back-to-console`/`--config`/`-b` 可以跟 `-n`/`-p`（第 1、2 節）以及 elf 位置參數任意組合，例如：

```sh
npx @drxiaozhi/minapk /path/to/your.elf -n MyApp -p com.example.myapp -c "echo hello" --no-shell
```

## 只更新 Buninu payload

完成至少一次完整建置並已有根目錄 APK 後，可以執行：

```sh
./repack.sh
```

`repack.sh` 不會重新編譯 Android 資源、Java 或 DEX。它會：

1. 以根目錄的 `<appname>.apk` 為來源。
2. 重新匯出 `buninu.tgz` 並產生 `buninu.stamp`。
3. 替換 APK 內的 payload。
4. 重新執行 zipalign 與簽章。
5. 在根目錄輸出 `<appname>r.apk`，不覆蓋原始 APK。

例如：

```text
Hello2.apk → Hello2r.apk
```

也可以明確使用 Android system shell：

```sh
/system/bin/sh ./repack.sh
```

## Buninu payload 來源

Buninu npm 套件：<https://www.npmjs.com/package/buninu>

> [!IMPORTANT]
> `buninu.tgz` **必須只有一個頂層資料夾**。App 解壓時會移除第一層
>（`--strip-components=1`），再把其內容直接放入 Buninu home。

解壓縮的目的地是 Android App 的內部私有 Buninu home：

```text
/data/data/<package-name>/no_backup
```

Android 在部分版本可能將同一個 App data directory 表示為
`/data/user/0/<package-name>`；實際路徑由 `ApplicationInfo.dataDir` 取得。
壓縮檔的單一頂層只是可移除的包裝層，不會在 `no_backup` 裡再多建立一層。

正確：

```text
no_backup/
no_backup/bin/
no_backup/apps/
```

錯誤：

```text
no_backup/
other_directory/
```

頂層資料夾的名稱不限定為 `no_backup`，但整份 archive 中只能有一個頂層名稱。若首次安裝時不符合此要求，App 無法建立 Buninu home，WebView 會顯示中英文錯誤訊息；已有舊安裝時則會跳過該 payload 並啟動既有版本。

若根目錄存在 `no_backup`，兩支腳本會使用：

```sh
bun no_backup/bin/init.js --export buninu.tgz
```

若不存在，腳本會詢問是否執行 `npx buninu@latest --export`。只有明確輸入 `y` 或 `Y` 才會從 npm 匯出。

## 主要外部工具

- Bun
- aapt2
- zipalign
- zip
- Java
- keytool（只有建立 keystore 時需要）

其他建置用 JAR 位於 `tools/`。

## APK 簽章與 keytool

`build.sh` 與 `repack.sh` 預設使用：

```text
tools/debug.keystore
```

如果這個檔案不存在，腳本才會呼叫 `keytool` 自動建立 debug keystore。之後的 build 與 repack 會持續使用同一個檔案簽章，因此更新已安裝的 APK 時請保留它。

專案本身隨附一份現成的 `tools/debug.keystore`，刻意讓沒有 `keytool`（或整個 Java 環境）的環境也能完整跑完簽章這一步——末日生存情境下，能建置出可安裝的 APK 比什麼都重要。

> [!WARNING]
> 隨附的 `tools/debug.keystore` 是**所有沒有換掉它的使用者共用同一把私鑰**（密碼固定是 `android`），因為它透過 npm 公開發佈，任何人都拿得到。這代表：
> - 用預設 keystore 簽出來的 APK，跟其他人用同一份預設 keystore 簽出來的 APK，是同一把金鑰簽的。
> - 只要 package name 相同，任何人都能用這把公開金鑰重新簽署別的 APK，Android 會把它當成合法更新接受安裝。
>
> 只要環境裡有 `keytool`，刪掉 `tools/debug.keystore` 再重新建置一次，腳本就會自動幫你產生一把只有你自己有的新金鑰。正式發佈或給別人安裝之前，請務必這樣做一次，或改用你自己的 release keystore 並妥善備份私鑰與密碼。

## 螢幕按鍵列

建出來的 App 底部有一排終端機常用按鍵，很多鍵短按跟長按是不同功能：

| 按鍵 | 短按 | 長按 |
| --- | --- | --- |
| ESC | Esc | Ctrl+Q |
| SHFT | 切換 Shift 修飾鍵 | **Ctrl+D** |
| ^C x | Ctrl+C | Ctrl+X |
| HOME | Home | Ctrl+U |
| END | End | Ctrl+K |
| TAB | Tab | Shift+Tab |
| PGU | Page Up | 按住持續向上捲動（滑鼠滾輪） |
| PGD | Page Down | 按住持續向下捲動（滑鼠滾輪） |
| ↑ ↓ ← → | 方向鍵 | 按住連續輸入 |
| Ent | Enter | Forward Delete |
| CTRL / ALT | 切換 Ctrl / Alt 修飾鍵 | （無） |

CTRL、ALT、SHFT 是 Termux 風格的一次性（one-shot）修飾鍵：短按後按鈕會反白表示已啟用，套用到下一個按下的按鍵之後就會自動清除，所以要打 Ctrl+C 只要先點 CTRL 再點 `^C x`（或任何字母鍵），不需要多點觸控同時按住兩個鍵。畫面右上角還有一個很窄的隱形輸入框，可以喚出系統輸入法直接打字/貼上文字。

實體**音量鍵 +** 會攔截下來（不會真的調音量），改成跳出一個小選單：切換螢幕按鍵列、在 WebView 裡 eval JS、選取終端機文字、上一頁／下一頁、跳到指定網址、縮放、Eruda console、背景權限設定、切換 WebView（直接切到下一個，不再多一層選單；項目本身會標出要切去哪一個，例如 `Switch WebView → 1: app`）。

App 裡有兩個 WebView，從啟動就都存在、不會被建立或關閉：`0` 是 console（Buninu 起的 jsgotty 終端機），`1` 是 app WebView，一開始是空白的、擺在後面。按鍵列、音量鍵選單、返回鍵一律作用在**當前在前景的那一個** WebView 上，所以切換 WebView 就等於同時把這三者換過去。在 app WebView 沒有上一頁可回時按返回鍵，會切回 console 而不是結束 App——沒有任何東西被關掉，離開前景的那個 WebView 照樣繼續跑（這個行為由 `buninu.backToConsole` 決定，預設 `true`，見 `--no-back-to-console`）。切換的方式是音量鍵選單最後那個「Switch WebView →」項目（按下去就直接切，不會再問你要哪一個），或從 Buninu 裡呼叫下面的 `showWebView`。

## 原生剪貼簿支援

App 內建一座從 Buninu 通到 Android 原生層的橋（`no_backup/apps/native-bridge`），透過 `MainActivity` 開的一個 unix socket，把 Toast 與系統剪貼簿讀寫暴露給 Buninu 裡跑的 Bun 行程。Buninu 隨附的 `xclip` 指令（`apps/xclip`）就是建在這座橋上：

```sh
echo hello | xclip -selection clipboard   # 寫進 Android 系統剪貼簿
xclip -o -selection clipboard             # 讀出來
```

`-selection primary`（不帶 `-selection` 時的預設值）維持純本地檔案、不碰原生剪貼簿，對應真正 X11 的語意；只有 `-selection clipboard`/`-clip` 才會透過 native-bridge 走到 Android 系統剪貼簿。jsmdcui 的剪貼簿後端偵測本來就會在偵測到 `xclip` 時使用它，所以 jsmdcui 裡的滑鼠中鍵貼上、選取文字自動同步、`PastePrimary` 指令，在這個 App 裡不需要額外設定就能動作。

要直接呼叫這座橋、不透過 `xclip`，可以在 `js back` 區塊裡 `import { toast, clipboardRead, clipboardWrite } from` 該路徑；每次呼叫預設 5 秒逾時，Android 端沒有回應也不會卡住呼叫端。詳見 `no_backup/README.md` 的「Commands inside the shell」一節。

`xdg-open` 在 APK 裡預設是把 URL 丟給系統的預設處理程式（等於離開 App），設 `MINAPK_WEBVIEW=<id>` 就改成載進那個 WebView 並直接切到前景：

```sh
MINAPK_WEBVIEW=1 xdg-open https://example.com   # 開在 app WebView 並顯示
export MINAPK_WEBVIEW=1                         # 或整個 session 都這樣
```

`0` 是 console，會把終端機那頁導走（返回鍵可以回去、jsgotty 會重連，但通常不是你要的）；`-1` 是當前前景那個。只有 URL 會被導向，檔案路徑一律照舊走系統處理程式——WebView 從 API 30 起 `setAllowFileAccess` 預設 false，`file://` 讀不到 Buninu home 底下的檔案，而原生端本來就有 content:// provider 在服務同一個檔案。值不是純整數會在 stderr 提醒並當成沒設（不會亂猜），沒設或空字串維持原行為，指到不存在的 WebView 則印出錯誤後退回系統處理程式。

同一座橋也把上面那兩個 WebView 交給 Buninu 控制，共四個 function：`openWebView(id, url)`、`evalWebView(id, js)`、`showWebView(id)`、`currWebView()`。id 給 `-1` 代表「目前在前景的那一個」。

四個都各有一個短名 `openwv`／`evalwv`／`showwv`／`currwv`，跟剪貼簿的 `getcb`／`setcb` 同一套做法：Java 端兩種拼法都收，`_discover` 也兩種都列，所以 CLI、`rpcraw`、`import` 三種用法都通。

```sh
native-bridge openwv 1 https://example.com   # 載入，但畫面不動
native-bridge showwv 1                       # 這時候才切到前景
native-bridge evalwv 1 document.title
native-bridge currwv
```

`openWebView` **只載入、不切到前景**，所以「使用者還在看終端機，背景先把 app WebView 的頁面載好」是一次呼叫就做完的事；`showWebView` 才是唯一會改變畫面的那個，`showWebView -1` 不是空操作而是切到下一個（只有兩個 WebView 時就是來回切換）。`evalWebView` 回傳的是運算式真正的值（數字/字串/物件），不是包成字串的值；`undefined`、function、丟出例外都會變成 `null`，因為 WebView 本身就分不出這三者。同樣的四個 function 也能 `import` 進 `js back` 區塊用（另外還有 `WEBVIEW_CURRENT`／`WEBVIEW_CONSOLE`／`WEBVIEW_APP` 三個常數）。

同一座橋也接了語音朗讀：`tts "hello"` 會唸出文字並等講完才結束，`-a` 不等直接返回。沒有 App 可用時會退回 `espeak-ng`/`say`/PowerShell 等桌面平台指令，一樣可以用。

## 產生單一可執行檔

以下兩條路徑都會產生 minapk 要的那種 elf——以 `/system/bin/linker64` 為載入器的 arm64 執行檔。兩者都需要 Bun 1.4 以上，用最新的 1.4 正式版就可以，不需要 canary：

```sh
bun upgrade
```

### 途徑一：一行 `bun build`

`hlw.js`：

```js
console.log("Hello from Bun single-file executable")
```

```sh
bun build --format=esm --compile --minify --bytecode ./hlw.js
npx @drxiaozhi/minapk hlw
```

得到 `hlw.apk`。輸出檔名不用指定，會自動去掉副檔名變成 `hlw`（Windows 上則是 `hlw.exe`）。

### 途徑二：Markdown App

`hlw.md`：

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
> 輸出的執行檔固定叫 `mdcui`，不是 `hlw`，所以直接餵給 minapk 會得到 `mdcui.apk`。要別的名字就先 `mv`。（不要試圖用 `--outfile` 指定：它不是相對你的 cwd 解析的，檔案會跑到 jsmdcui 自己的目錄裡去。）

`checkAns` 是純前端的答案比對；`whereAmI` 則透過 `await rpc.sysinfo()` 呼叫到 `js back` 區塊，回報這個 app 現在跑在哪裡。同一個執行檔在 Termux 裡直接跑會顯示 `(not inside an APK)`，包成 APK 裝起來之後，同樣那顆按鈕就會顯示 Buninu home 與 Android app 私有目錄的實際路徑。

## Google Play 上架與政策

> [!IMPORTANT]
> minapk 建出來的 APK **不是一個可以直接上架的成品**。以下兩點是機制上的硬限制，跟政策解讀無關，照現況直接丟上 Play Console 就會被擋下來：
>
> - **格式**：新 App 自 2021 年 8 月起只收 [Android App Bundle（AAB）](https://developer.android.com/guide/app-bundle/faq)，minapk 產出的是 APK。
> - **簽章**：新 App 一律走 Play App Signing，你手上只留 upload key，不可能沿用上方「APK 簽章與 keytool」提到、所有人共用的那份 `tools/debug.keystore`。
>
> 換句話說，要上架至少得自行改用 AAB 流程、換成自己的金鑰。這些都做完之後，才輪到下面比較沒有標準答案的政策問題。

### 政策面

Play 的 [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646) 政策規定：App 不得從 Google Play 以外的來源下載可執行碼（dex、JAR、`.so`），也不得用 Play 更新機制以外的方式更新自己；但「在虛擬機或直譯器中執行的程式碼」不在此限。

minapk 建出來的 APK 目前是這樣執行的：

- `libbun.so`／`libmain.so` 都**隨 APK 打包**在 `lib/arm64-v8a/`，App 以 `ProcessBuilder` 從 `nativeLibraryDir` 執行。該目錄是 Android 10（API 29）W^X 限制下少數仍保有執行權限的位置。
- Buninu payload（JS）同樣**隨 APK 打包**在 assets，首次啟動才解壓到 `no_backup`，再由上面那個 `libbun.so` 當直譯器執行。
- 整個流程不會從網路下載任何原生可執行檔。

類似專案的做法對照：

- `libnode.so`（[nodejs-mobile](https://github.com/JaneaSystems/nodejs-mobile)）走更保守的路線——Node 編譯成真正的 JNI 共享函式庫，用 `System.loadLibrary("node")` 載入到 App 自己的行程內，不 fork/exec 子行程，所以它單純就是「APK 內的原生函式庫」。
- minapk 是把 Bun 當獨立執行檔 exec，比較接近 [Termux 在 Play 上的版本](https://github.com/termux-play-store)（該版本以 `system_linker_exec` 處理 Android 10+ 的 W^X 限制）。Termux 本身曾因 target API 29 的執行限制長期無法在 Play 更新，後來才以這個分支回到 Play。

仍需自行評估的部分：預設會開一個互動式 shell，使用者可以在裡面執行任意程式；Buninu 的 `bunx` 會在執行期從 npm 安裝並執行套件。這類「執行期才取得程式碼」的行為，正是審核最容易被盯上的地方。

> [!NOTE]
> 我本身不是法律專業，以上只是對照公開政策條文與類似專案做法的整理，不構成法律或合規建議。目前已提供 `--no-shell`（即 `buninu.exitAfterCmd`）讓指令跑完就直接結束、不落回互動式 shell，作為縮小暴露面的選項。實際的上架審核規範與結果，有待各位使用者自行發掘。

## 未來規劃
- 修復xterm終端機捲動問題
- ~~加入Ctrl Alt Shift等按鍵~~（已完成）
- 一直包裝直到做到
  * ~~npx @drxiaozhi/minapk your_binary~~（已完成，見「建置」）
  * npx @drxiaozhi/minapk myapp.md
- ~~原生 bridge（架構還在設計中）~~（已完成：`native-bridge`／`xclip`／`tts`，目前有 toast、剪貼簿、TTS；缺的是繼續暴露更多 Android 能力，見下）
- `BUN_BE_BUN` 機制：`libmain.so` 本質上是「Bun 執行檔本體 + 附加上去的 standalone module graph」（`bun build --compile` 的輸出），正常執行會直接偵測並啟動內嵌的 app。Bun 官方文件（single-file executable）記載了 `BUN_BE_BUN=1` 這個環境變數，設定後同一個檔案會改成表現得像單純的 `bun` CLI、跳過 standalone graph 偵測。理論上可以拿 `libmain.so` 兼職當作 `libbun.so` 用（呼叫時帶 `BUN_BE_BUN=1`），不用再額外打包一份完整 Bun 執行檔，省下可觀的 APK 空間。架構還沒定案——目前 `libbun.so`／`libmain.so` 各自一份的好處是彼此可以互相 fallback（例如 `libmain.so` 的 standalone graph 或 `BUN_BE_BUN` 行為出狀況時還有獨立的 `libbun.so` 可用），改成共用一份就要想清楚失去這層保險的取捨

## License

本專案依照 [MIT License](LICENSE) 發布。原始上游專案為 [tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。Android SDK、建置工具及其他第三方元件各自適用其原有授權；完整工具版本、來源與授權對照請見 [NOTICE](NOTICE) 及 [LICENSES](LICENSES/README.md)。
