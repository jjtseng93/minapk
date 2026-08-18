# minapk

- [English Readme](README.en.md)
- 把你的Bun單一可執行檔 包成APK

- minapk 是一個不使用 Gradle 的 Android APK 建置專案，以命令列工具完成資源處理、Java 編譯、R8/DEX、APK 封裝、zipalign 與簽章，並將 [Buninu](https://www.npmjs.com/package/buninu) 執行環境放入 APK。

- 只要把`libmain.so`放在專案根目錄就能在啟動時自動開啟(詳細說明在後面)

- 本專案衍生自 [Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。

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

這是主要入口，整個流程都由 `index.js` 驅動：

1. 若有帶 elf 路徑，這次建置就用它當 `libmain.so` 打包（不會寫入專案根目錄，只影響這一次；不帶 elf 時使用專案根目錄現有的 `libmain.so`，見下方說明）。
2. 準備 Buninu payload 與 manifest/資源/Java 原始碼，用 aapt2、ECJ、R8 編譯，封裝 Buninu payload 與原生函式庫，執行 zipalign，並用 `tools/debug.keystore` 簽章（不存在時自動建立），輸出到專案根目錄的 `<appname>.apk`（例如目前設定會是 `Hello2.apk`）。
3. build 成功且有帶 elf 時，把輸出的 APK 複製一份到 elf 原本所在的目錄，檔名是 elf 本身的檔名（去掉副檔名，若原本就沒有副檔名也一樣正確處理）加上 `.apk`。例如 `myapp.elf` 旁邊會多一個 `myapp.apk`。

在有 checkout 的情況下也可以直接跑（效果相同）：

```sh
bun ./index.js
```

實際編譯步驟由 `index.js` 內部 spawn 的 `build.sh` 完成，它是 POSIX shell script，執行環境需要有 `/bin/sh`（或 Android 的 `/system/bin/sh`）；一般不需要直接呼叫它。

- `libmain.so`: 必須是使用 Android Bun 編譯、以 /system/bin/linker64 為執行載入器的執行檔（Bun >= 1.4 支援）。Android App 啟動時會自動執行。如果專案根目錄包含此檔案，建置時會將它封裝到 APK 的原生 lib/arm64-v8a 目錄中；若不存在則會直接略過，不會報錯。

- 封裝原生函式庫前，若專案根目錄沒有 `libbun.so`，會執行
`which bun`，並將找到的 Bun 複製為根目錄的 `libbun.so`；找不到 Bun
或複製失敗時會停止建置。該 Bun 必須是可在目標 Android arm64 環境
執行的版本。

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

`-c`/`--no-shell`/`--config` 可以跟 `-n`/`-p`（第 1、2 節）以及 elf 位置參數任意組合，例如：

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

預設 debug keystore 適合本地測試；正式發佈時應改用你自己的 release keystore，並妥善備份私鑰與密碼。

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

## 未來規劃
- 修復xterm終端機捲動問題
- ~~加入Ctrl Alt Shift等按鍵~~（已完成）
- 一直包裝直到做到
  * ~~npx @drxiaozhi/minapk your_binary~~（已完成，見「建置」）
  * npx @drxiaozhi/minapk myapp.md

## License

本專案依照 [MIT License](LICENSE) 發布。原始上游專案為 [tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。Android SDK、建置工具及其他第三方元件各自適用其原有授權；完整工具版本、來源與授權對照請見 [NOTICE](NOTICE) 及 [LICENSES](LICENSES/README.md)。
