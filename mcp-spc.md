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