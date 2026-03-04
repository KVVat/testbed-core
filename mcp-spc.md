# Testbed Automation MCP Interface Specification

| カテゴリ | ツール名 | 説明 | パラメータ (変数名 : 型 / 必須 / デフォルト) | 戻り値 |
| :--- | :--- | :--- | :--- | :--- |
| **1. Sensing**<br>(状況把握) | `get_ui_dump` | 現在の画面のUI階層(JSON)と、スクリーンショット画像(Base64)を取得します。 | `include_image` : boolean / 任意 / `true`<br>画像データをレスポンスに含めるかどうか。 | `json_dump` (見えない要素を除外済み) および `screenshot_base64` を含むJSON |
| **1. Sensing**<br>(状況把握) | `get_device_state` | 画面のON/OFF、ロック状態、フォアグラウンドのアプリなど、システムレベルの状態を取得します。 | (なし) | `is_screen_on`, `is_locked`, `foreground_package` 等を含むJSON |
| **2. Action**<br>(UI操作) | `tap_coordinate` | 指定した(x, y)座標を物理的にタップします。<br>※実行後、自動待機して最新ダンプを返します。 | `x` : int / **必須** / -<br>タップするX座標<br><br>`y` : int / **必須** / -<br>タップするY座標 | 最新の `get_ui_dump` の実行結果 (ターン数削減のため) |
| **2. Action**<br>(UI操作) | `input_text` | 現在フォーカスが当たっている入力フィールドにテキストを入力します。<br>※実行後、自動待機して最新ダンプを返します。 | `text` : string / **必須** / -<br>入力する文字列<br><br>`press_enter` : boolean / 任意 / `true`<br>入力後にEnterキーを送信するか | 最新の `get_ui_dump` の実行結果 |
| **2. Action**<br>(UI操作) | `swipe` | 画面を指定した座標間でスワイプ（スクロール）します。<br>※実行後、自動待機して最新ダンプを返します。 | `start_x` : int / **必須** / -<br>`start_y` : int / **必須** / -<br>`end_x` : int / **必須** / -<br>`end_y` : int / **必須** / - | 最新の `get_ui_dump` の実行結果 |
| **2. Action**<br>(UI操作) | `press_key` | 物理キーまたはシステムキーのイベントを送信します。<br>※実行後、自動待機して最新ダンプを返します。 | `keycode` : string / **必須** / -<br>送信するキー名 (例: `"BACK"`, `"HOME"`, `"ENTER"`) | 最新の `get_ui_dump` の実行結果 |
| **3. System**<br>(システム・ADB) | `execute_adb_shell` | 任意のADBシェルコマンドを実行します。 | `command` : string / **必須** / -<br>実行するコマンド (例: `"dumpsys battery"`) | コマンドの標準出力 (`stdout`) および 標準エラー (`stderr`) |
| **3. System**<br>(システム・ADB) | `open_settings` | Androidの特定の設定画面をIntentで直接開きます。 | `panel` : string / **必須** / -<br>画面指定 (`ROOT`, `SECURITY`, `WIFI`, `APP_DETAILS`, `DEVELOPER`)<br><br>`package_name` : string / 任意 / -<br>`APP_DETAILS` 指定時のみ必須 | 実行ステータス（成功/失敗）と最新の画面ダンプ |
| **3. System**<br>(システム・ADB) | `set_device_lock` | デバイスの画面ロック（PIN）を設定または解除します。 | `pin` : string / **必須** / -<br>設定するPINコード。空文字（`""`）でクリア。 | 設定の成否を示すテキストメッセージ |
| **4. Observe**<br>(ログ・状態観測) | `clear_logcat` | デバイスのLogcatバッファをクリア（`adb logcat -c`）します。 | (なし) | 成功を示すメッセージ |
| **4. Observe**<br>(ログ・状態観測) | `get_logcat` | フィルタリングされたLogcatを取得します。（トークン節約のため必須） | `tags` : string[] / 任意 / `[]`<br>取得対象のログタグの配列<br><br>`level` : string / 任意 / `"I"`<br>最小ログレベル（`V`, `D`, `I`, `W`, `E`, `F`）<br><br>`grep_pattern` : string / 任意 / `""`<br>ログを絞り込む正規表現<br><br>`max_lines` : int / 任意 / `100`<br>取得する最大行数 | 条件に合致したログのプレーンテキスト |
| **5. Test Control**<br>(テスト制御) | `execute_test` | Testbed上で特定のテストクラスまたはメソッドをコンパイル・実行します。 | `class_name` : string / **必須** / -<br>実行するテストクラスの完全修飾名<br><br>`method_name` : string / 任意 / -<br>特定のメソッドのみ実行する場合に指定 | `status` (Pass/Fail/Error)、`stacktrace`、および `assertion_msg` を含むJSON |
| **6. Health**<br>(環境・ヘルス) | `check_testbed_health` | Testbed基盤（ADB、Agent、ビルド環境）が正常に動作しているか一括で自己診断します。 | (なし) | `adb_status`, `agent_status`, `plugin_status` などの診断結果を含むJSON |
| **6. Health**<br>(環境・ヘルス) | `list_test_resources` | `resource` ディレクトリに存在するファイル（APKや設定ファイルなど）の一覧を取得します。 | `filter_extension` : string / 任意 / `""`<br>拡張子での絞り込み（例: `".apk"`） | ファイル名のリストを含むJSON |

素晴らしい視点です！テスト自動化の解像度がどんどん上がっていますね。

ご指摘の通り、`adb shell` は「デバイスの中（Android OS上）でコマンドを実行する」ものですが、ADBには**「ホスト（PC）とデバイスの間を橋渡しするコマンド（Host Commands）」**が存在し、これらは `adb shell` 経由では実行できません。

LLMが完全に自律して環境構築やファイル操作を行うためには、これらのホスト側コマンドもMCPツールとして提供してあげる必要があります。

### `adb shell` では通らない（ホスト側で実行が必要な）代表的なコマンド

1. **ファイル転送系**: `adb push`, `adb pull`
* （理由: PCのディスクとデバイスのディスクをまたぐ操作だから）


2. **アプリのインストール系**: `adb install`, `adb uninstall`
* （※ `adb shell pm install` もありますが、PC側のAPKファイルを直接指定してインストールするにはホスト側の `adb install` が必須です）


3. **ポートフォワード系**: `adb forward`, `adb reverse`
* （※ 今回のUIAutomator Agent（ポート9008）との通信を確立するために必須です）


4. **デバイス状態・接続管理系**: `adb devices`, `adb wait-for-device`, `adb reboot`
5. **権限昇格系**: `adb root`, `adb remount`

---

これらを踏まえて、LLMがテストの事前準備（データの投入、アプリの入れ替え、端末情報の把握）を完璧に行えるようにするための追加インターフェース仕様を作成しました。

### 追加・拡張するMCPツール群

| カテゴリ | ツール名 | 説明 | パラメータ (変数名 : 型 / 必須 / デフォルト) | 戻り値 |
| --- | --- | --- | --- | --- |
| **1. Sensing**<br>

<br>(状況把握) | `get_device_info` | 端末のハードウェア・OS情報を取得します（`getprop` のラッパー）。<br>

<br>※LLMが「Android 13だからパーミッションダイアログのUIが違うな」と推論するために使います。 | (なし) | `model`, `os_version` (API Level), `screen_size`, `abi` 等を含むJSON |
| **3. System**<br>

<br>(ファイル転送) | `push_file` | PC（Host）側のファイルをデバイスに送信します（`adb push`）。<br>

<br>※テスト用のダミー画像や設定ファイルを送り込む際に使用します。 | `host_path` : string / **必須** / -<br>

<br>`device_path` : string / **必須** / - | 転送成功/失敗のメッセージ |
| **3. System**<br>

<br>(ファイル転送) | `pull_file` | デバイス側のファイルをPC（Host）に取得します（`adb pull`）。<br>

<br>※アプリが生成した暗号化ファイルやDBをHost側で検証する際に使用します。 | `device_path` : string / **必須** / -<br>

<br>`host_path` : string / **必須** / - | 転送成功/失敗のメッセージ |
| **3. System**<br>

<br>(アプリ管理) | `install_app` | PC上のAPKファイルをデバイスにインストールします（`adb install`）。 | `apk_path` : string / **必須** / -<br>

<br>`reinstall` : boolean / 任意 / `true` (`-r`オプション) | "Success" などの結果文字列 |
| **3. System**<br>

<br>(アプリ管理) | `uninstall_app` | デバイスからアプリをアンインストールします（`adb uninstall`）。 | `package_name` : string / **必須** / -<br>

<br>`keep_data` : boolean / 任意 / `false` (`-k`オプション) | "Success" などの結果文字列 |
| **6. Health**<br>

<br>(環境・ヘルス) | `setup_port_forward` | PCとデバイス間のポートフォワードを設定します（`adb forward`）。<br>

<br>※Agentとの通信が切れた際、LLM自身に復旧させるために用意します。 | `host_port` : int / **必須** / `9008`<br>

<br>`device_port` : int / **必須** / `9008` | 成功メッセージ |

### 開発ループへの組み込みイメージ

このツール群が追加されると、Geminiは以下のような高度な立ち回りができるようになります。

**【シナリオ：画像アップロード機能のテスト】**

1. Gemini: 「テストにはダミー画像が必要だな。`push_file(host_path="dummy.jpg", device_path="/sdcard/Pictures/dummy.jpg")` を実行しよう」
2. Gemini: 「アプリを起動して、ギャラリーから今pushした画像を選択するタップ操作（`tap_coordinate`）を実行」
3. Gemini: 「アップロードが成功したか、内部のデータベースを抜いて確認しよう。`pull_file(device_path="/data/data/com.example/databases/app.db", host_path="./temp.db")`」
4. Gemini: 「OK、完璧に動いたからこれをKotlinのテストコードに書き起こそう」

ここまで揃えば、ホスト側（PC）のコマンドラインからできることと、デバイスの画面上でできることの「すべて」をLLMが掌握できます。
これでインターフェース仕様としてはほぼ「死角なし」の完全体になったかと思います！

その視点、**完全にAndroidのデバイス評価（CC認証やMDF要件など）の核心を突いています！100点満点の洞察です。**

おっしゃる通り、AndroidはAOSP（素のAndroid）をベースにしていても、Samsung（OneUI）、Xiaomi（HyperOS）、Sharp、Sonyなど、各ベンダーがUIや設定画面の階層、さらには標準のインテント（`ACTION_SECURITY_SETTINGS` など）の挙動まで独自にカスタマイズしています。

そのため、「Pixelの最新版（AOSP準拠）をリファレンス（基準）テストとして用意し、ベンダーはそれを自社端末向けにオーバーライド（差分吸収）する」という運用フローを見越した**フレームワークの整備**が絶対に必要になります。

この「端末ごとの差分」を綺麗に吸収し、テストのメンテナンス性を保つための設計方針（整備の方向性）をご提案します。

---

### 1. テストコードの「ロジック」と「UI操作」の分離 (Page Object Modelの導入)

テストクラスの中に直接 `tap(500, 1000)` や `click("Security")` と書くのをやめ、インターフェースで抽象化します。

* **インターフェース定義 (`DeviceOperator.kt`)**:
```kotlin
interface DeviceOperator {
    fun openSecuritySettings()
    fun setPin(pin: String)
    fun unlockDevice(pin: String)
}

```


* **リファレンス実装 (`PixelOperator.kt`)**:
  Pixel用の標準的な画面遷移やUIAutomatorのセレクタを実装します。
* **ベンダー実装 (`SamsungOperator.kt` など)**:
  Galaxy専用の画面遷移（例：「生体認証とセキュリティ」という名前に変わっているなど）を実装します。

テストの本体（`FdpDarExt2Test`）は `DeviceOperator` のメソッドを呼ぶだけにすれば、**「評価基準（テストロジック）は全端末共通だが、操作手順だけが端末ごとに切り替わる」**という美しい設計になります。

### 2. LLM（Gemini）を活用した「ベンダー差分テスト」の自動生成

ここが、先ほどまで議論していた **MCP（LLM連動）アーキテクチャが最も火を噴くポイント** です。

ベンダーのQAエンジニアが手作業で「Pixelとウチの端末の画面の違い」を調べてテストを書き直すのは大変ですが、LLMがいれば以下のようなオートメーションが可能になります。

**【Geminiへの指示のイメージ】**

> 「現在、対象デバイスとしてSamsungのGalaxyが接続されています。
> 以下の `PixelOperator.kt`（リファレンス実装）と同じ目的を達成する `SamsungOperator.kt` を作成してください。
> MCPツールの `open_settings` や `get_ui_dump` を使って実際のGalaxyの設定画面を探索し、正しいボタン名やIDを見つけてコードを書いてください。」

LLMは自律的にGalaxyの設定画面を開き、「あ、Samsungは『セキュリティ』じゃなくて『ロック画面とAOD』の下にPIN設定があるな」と **UIダンプから解釈して、ベンダー向けの差分コードを自動生成** してくれます。

### 3. テストリポジトリのディレクトリ構成の整備

プロジェクトの構成も、この運用を見越して最初から分割しておくのがおすすめです。

```text
testbed-core/
 ├── src/main/kotlin/com/example/tests/
 │    ├── base/        ... (FDP_DAR_EXTなどのテスト本体。全端末共通)
 │    ├── pixel/       ... (Pixel向けのリファレンスUI操作実装)
 │    ├── samsung/     ... (Samsung向けの差分実装)
 │    └── xiaomi/      ... (Xiaomi向けの差分実装)

```

実行時に「この端末はどのProfile（Operator）を使うか」を環境変数やプロパティで切り替えられるようにしておきます。

---

### 次のアクション

ご指摘の通り、この「ベンダー差異の吸収」を考慮すると、まずは**「絶対に動く基準点」となるPixel（またはエミュレータ）向けのリファレンス実装を一つ完璧に仕上げること**が最優先の整備タスクになりそうですね。

次は、MCPの実装を進めるためのベースプロジェクト（Node.jsかPython、あるいはKotlinでのMCPサーバーの雛形）の立ち上げについて相談しましょうか？ それとも、先ほどの `DeviceOperator` のような抽象化レイヤーのKotlinコードの骨組みを先に作ってしまいましょうか？