# エージェント実行のためのプロジェクト知見

## 1. 禁じられた事柄 / 重大な教訓

*   **`AdbDeviceRule.kt` の変更・削除は厳禁**: このファイルはテスト関連のコードであり、将来的な拡張のために存在します。プロダクションコードのビルドエラーを修正する際に、このファイルの内容を安易に削除したり、大きく変更したりしてはいけません。依存関係の問題は、Gradle設定で解決すべきです。
*   **コンパイルエラーの蔓延に注意**: 大規模な変更を一度に行うと、コンパイルエラーが広範囲に及び、原因特定が困難になります。変更は小分けにし、都度ビルドチェックを行うことが重要です。エラーが一定以上広がった場合、速やかに変更をロールバックし、アプローチを再検討する必要があります。
*   **依存関係の理解**: 特にマルチプラットフォームプロジェクトでは、`commonMain`, `jvmMain`, `commonTest` などのソースセット間で依存関係がどのように解決されるかを正確に理解することが不可欠です。誤ったソースセットに依存関係を追加すると、予期せぬビルドエラーを引き起こします。
*   **コンパイルエラーを確認してから引き渡し（ループ時以外）**: エージェントが自律的にループ処理している場面を除き、コード変更を行った後は一度ビルド（`./gradlew assemble`等）を行い、コンパイルエラーが出ないことを確認してからユーザーへ回答を引き渡すこと。
*   **Compose Desktopでの `TextOverflow.Ellipsis`**: `androidx.compose.ui.text.font.TextOverflow.Ellipsis` はCompose Desktop環境では利用できない場合があります。Compose Multiplatformのバージョンによってはサポートされていない可能性があるので、代替手段を検討するか、使用を避けるべきです。

## 2. Adamライブラリの実装に関する知見

Adamライブラリを使用したADB操作に関する重要な実装方法です。

*   **Adamクライアントの取得**: `AdbDeviceRule`クラス内で`AndroidDebugBridgeClientFactory().build()`を使用して`Adam`クライアントのインスタンスを取得します。`AdbObserver`のようなロジッククラスは、この`AdbDeviceRule`の`adb`プロパティを通じて`Adam`クライアントにアクセスします。
*   **シェルコマンドの実行**:
    *   一般的なシェルコマンドは `adamClient.execute(ShellCommandRequest("your command"), serial)` の形式で実行します。
    *   例: `input text "string"` コマンドでは、スペースを含む文字列は `text.replace(" ", "%s")` のようにエスケープする必要があります。
*   **Logcatストリームの取得**:
    *   Logcatをリアルタイムでストリームするには、`ChanneledLogcatRequest`を使用します。
    *   `adamClient.execute(request = ChanneledLogcatRequest(), serial = serial)` を呼び出すと、`ReceiveChannel<String>`が返されます。
    *   この`ReceiveChannel<String>`は、`consumeEach { line -> ... }` を使用して、各ログ行を非同期で処理する必要があります。
    *   関連ドキュメント: [Adam Logcat](https://malinskiy.github.io/adam/docs/logcat/logcat/)
*   **デバイスのリブート**:
    *   デバイスを特定のモードで再起動するには、`RebootRequest`を使用します。
    *   例: `adamClient.execute(RebootRequest(RebootMode.BOOTLOADER), serial)` でブートローダーモードへ再起動できます。
    *   `RebootRequest` と `RebootMode` は `com.malinskiy.adam.request.misc` パッケージに存在します。
*   **非同期処理とコルーチンスコープ**:
    *   重いADB操作は必ず `Dispatchers.IO` を使用したコルーチン内で実行し、UIスレッドをブロックしないようにします。
    *   `AdbObserver`のようなロジッククラス内で`Job`を管理し、`viewModel.viewModelScope.launch { ... }` を使用してライフサイクルと連携させることが推奨されます。

## 3. プロジェクト構造と依存関係の管理

*   **`libs.versions.toml`**: Gradle Version Catalogs (`libs.versions.toml`) を使用して、依存関係のバージョンを一元管理します。新しいライブラリを追加する際は、まずここにバージョンとエイリアスを定義します。
*   **`build.gradle.kts`**:
    *   `commonMain.dependencies`には、プラットフォーム固有ではない共通のロジックやUIで使用される依存関係（例: Compose Multiplatformのコアライブラリ、Adam、JUnitなど）を追加します。
    *   `jvmMain.dependencies`には、JVM固有の依存関係（例: Compose Desktop固有のライブラリ、`kotlinx-coroutines-swing`など）を追加します。
    *   **JUnitの配置**: `AdbDeviceRule.kt`のようにアプリがJUnitテストフレームワークの要素を直接利用する場合、その依存関係は`commonMain`に配置する必要があります。

## 4. ログ出力のベストプラクティス

*   `AppViewModel.log(tag: String, message: String, level: LogLevel = LogLevel.INFO)`: この形式の`log`関数は、呼び出し元がログレベルを省略できるため非常に便利です。
*   ログのフィルタリングと最大行数制限は、パフォーマンスとUXのために重要です。

## 5. MCPサーバーの動作確認・手動テスト手順

エージェント（Jetski等）が現在のコンテキストで直接MCPツール群を認識できない場合や、動作を単体で検証したい場合は、以下のスクリプトを利用してローカルのSSEサーバー（ポート11452）に対して手動でJSON-RPCリクエストを発行できます。

```bash
#!/bin/bash
# 1. SSEエンドポイントへの接続と SessionID の取得
rm -f /tmp/sse_out_$$
curl -sN http://localhost:11452/mcp > /tmp/sse_out_$$ &
SSE_PID=$!
sleep 2

# (重要) HTTPヘッダの改行コード(CR)が悪影響を及ぼすため tr -d '\r' すること！
SESSION_INFO=$(grep "^data: " /tmp/sse_out_$$ | head -n 1 | sed 's/^data: //' | tr -d '\r')

if [ -z "$SESSION_INFO" ]; then
    echo "Failed to connect to MCP server"
    kill $SSE_PID 2>/dev/null
    exit 1
fi

MSG_ENDPOINT="http://localhost:11452${SESSION_INFO}"

# 2. tools/list の呼び出し
echo -e "\n--- Calling tools/list ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'

sleep 1

# 3. tools/call によるツールの実行（例: get_logcat）
echo -e "\n--- Calling get_logcat ---"
curl -s -X POST "$MSG_ENDPOINT" \
     -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","method":"tools/call","id":2,"params":{"name":"get_logcat","arguments":{"tags":["ActivityManager"], "level":"I", "max_lines":5}}}'

sleep 2

# 4. 結果（SSEストリームに出力されるレスポンス）の確認
echo -e "\n--- SSE Output ---"
cat /tmp/sse_out_$$

kill $SSE_PID 2>/dev/null
rm -f /tmp/sse_out_$$
```

---
この知見が、今後のエージェント実行に役立つことを願っています。

## 6. Mutton Agent（Android側テストエージェント）デプロイと挙動の知見

エージェント自身（mutton-agent等）をAndroid端末へデプロイして動かす際のトラブルシューティングに関する教訓です。

*   **Google Play Protect によるインストールのブロック**: 未署名や野良のテスト用APKを `adb install` (あるいは `pm install`) でバックグラウンドインストールしようとすると、端末側のGoogle Play Protectが警告ダイアログを出し、コマンドが永遠にハングバックする（あるいはサイレントに失敗する）現象が発生します。エージェントのデプロイが原因不明で停止・ハングする場合は、**対象デバイスのPlay Protect設定がオフになっているか**を確実にご確認ください。
*   **ブロッキングを避けたエージェントの起動 (`am instrument`)**: エージェントを `am instrument` で起動する場合、通常このコマンドはプロセスが終了するまで戻ってきません。Adamライブラリ経由で実行した場合、その通信スレッドを占有してしまうため、非同期プロセスとして切り離す (`&` など) か、Coroutineの `launch(Dispatchers.IO)` 内で実行させるなど、**メインサーバ側の後続処理（特にKtorのHTTPリスポンスなど）をブロックしない**設計が必須です。起動の成否確認は `am instrument` の戻り値ではなく、TCPソケットなどを通じた `ping` / `pong` リクエストの疎通で判断するべきです。
*   **JVMアプリと `org.json` の非互換性**: JVM Desktop用（`composeApp`などのjvmMain環境）でJSON文字列をパースする際、Android環境と同じ感覚で `org.json.JSONObject` を使おうとすると依存関係のエラーや `NoClassDefFoundError`等を引き起こします。共通ロジックやDesktopで動くモジュールでは、**必ず `Gson` や `kotlinx.serialization` のようなJVM互換のモダンなパーサーを利用**してください。
*   **Android上のデバッグ出力は `Log` を使う**: エージェントアプリ側のデバッグ用出力に `println()` を多用すると、コマンドラインからの確認やLogcatからの情報収集が非常に困難になります。通信の送受信などの挙動のトレースには `println()` ではなく、**`android.util.Log.i(TAG, message)` を使用し、Logcat経由で一元的に追えるようにする**ことが、今後の解析を容易にするベストプラクティスです。
