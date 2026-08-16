# minapk

[English](README.en.md)

這是一個不使用 Gradle 的 Android APK 建置專案，以命令列工具完成資源處理、Java 編譯、R8/DEX、APK 封裝、zipalign 與簽章，並將 [Buninu](https://www.npmjs.com/package/buninu) 執行環境放入 APK。

本專案衍生自 [Promastergame/tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。

## 1. 修改應用程式名稱與 APK 名稱

編輯 `appname.txt`：

```text
Hello2
```

這個值同時用於：

- Android 顯示的應用程式名稱。
- 完整建置輸出的 APK 名稱，例如 `Hello2.apk`。
- repack 輸入與輸出名稱，例如 `Hello2.apk` → `Hello2r.apk`。

檔案只需放一行名稱，不要加 `.apk`。

## 2. 修改 Android package name

編輯 `pkgname.txt`：

```text
com.drjohn.bunwv
```

這是 Android 的 package name/application ID，不是 APK 檔名。它應使用反向網域格式，並且必須是有效的 Java package 名稱。修改後需要執行完整的 `build.sh`；只有 `repack.sh` 不會改變 package name。

建議使用 `com.<6 字元>.<5 字元>` 的格式，讓完整 package name 維持
16 個 ASCII 字元，例如 `com.drjohn.bunwv`。這不是 Android 的強制限制；
採用此長度是因為字串 `com.termux/files` 同樣是 16 bytes，未來可能可以
對含有該固定路徑的 Termux binary 進行等長 patch，而不必移動 binary
內的其他資料。目前的 build 流程尚未自動進行這種 patch。

## 3. 完整建置

```sh
./build.sh
```

`build.sh` 會執行完整流程：

1. 從本地 `no_backup` 匯出 `buninu.tgz`，並產生 `buninu.stamp`。 沒有本地的時候會詢問是否 `npx buninu --export` 產生
2. 根據 `appname.txt` 與 `pkgname.txt` 準備 manifest、資源和 Java 原始碼。
3. 使用 aapt2、ECJ 與 R8 編譯資源及程式碼。
4. 封裝 Buninu payload 與原生函式庫。
5. 執行 zipalign。
6. 使用 `tools/debug.keystore` 簽章；不存在時會自動建立。
7. 在專案根目錄輸出 `<appname>.apk`。

例如目前的設定會輸出：

```text
Hello2.apk
```

腳本採用 POSIX shell 語法，可在 Android 上使用：

```sh
/system/bin/sh ./build.sh
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

封裝原生函式庫前，若專案根目錄沒有 `libbun.so`，`build.sh` 會執行
`which bun`，並將找到的 Bun 複製為根目錄的 `libbun.so`；找不到 Bun
或複製失敗時會停止建置。該 Bun 必須是可在目標 Android arm64 環境
執行的版本。若根目錄另有 `libmain.so`，build 也會將它封裝進 APK 的
`lib/arm64-v8a` 原生區；沒有此檔案時會直接略過，不會報錯。

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

## License

本專案依照 [MIT License](LICENSE) 發布。原始上游專案為 [tinyapk-lab](https://github.com/Promastergame/tinyapk-lab)。Android SDK、建置工具及其他第三方元件各自適用其原有授權；完整工具版本、來源與授權對照請見 [NOTICE](NOTICE) 及 [LICENSES](LICENSES/README.md)。
