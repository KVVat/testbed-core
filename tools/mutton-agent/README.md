# mutton-agent

Androidの実機およびエミュレータ上で動作し、UiDeviceを用いたUIダンプや各種コマンドを実行するためのAgentです。
以前は `app_process` 経由で動作するDexファイルとして実装されていましたが、現在は `UiAutomator(UiDevice)` を利用するため、**Android Instrumentation Test** として実装されています。

## ビルド方法

ルートディレクトリから以下のGradleコマンドを実行することで、Test APKのコンパイルおよび配置を行います。

```bash
./gradlew -p tools/mutton-agent copyTestApk
```

このコマンドは `assembleDebugAndroidTest` を実行し、生成された `mutton-agent-debug-androidTest.apk` を `testbed-core/composeApp/resources/mutton-agent-androidTest.apk` にコピーします。

## 実行方法 (Android端末上)

1. **APKのインストール**
   あらかじめ生成された `mutton-agent-androidTest.apk` を対象端末に `adb install` しておくか、または適宜デプロイしてください。(※フレームワーク側で自動的に Push および Install を行うことを想定しています)

2. **Instrumentコマンドの起動**
   以下のコマンドにより、テストとしてエージェントを起動します。(フォアグラウンドで常駐し、ソケットサーバーを立ち上げます)

   ```bash
   adb shell am instrument -w org.example.mutton.test/androidx.test.runner.AndroidJUnitRunner
   ```

## 通信仕様

- エージェントは `LocalServerSocket` を利用して `mutton_agent` という名前の Abstract Namespace ソケットで待ち受けを行っています。
- ホストPCからは `adb forward` などを利用してここに接続し、JSON形式のコマンドを送受信します。

### 基本コマンド一覧

#### `ping`
生存確認を行います。
```json
{"cmd": "ping"}
```
レスポンス:
```json
{"status": "pong", "message": "I am alive!"}
```

#### `dump`
現在のUIツリーを1回だけ取得します。同時に画面のスクリーンショットも取得し、Base64エンコードして返します。
```json
{"cmd": "dump"}
```
レスポンス:
```json
{"type": "dump_result", "status": "ok", "output": "{...ダンプデータ...}", "screenshot": "<Base64エンコードされたJPEG画像>"}
```

#### `shell`
AndroidシェルコマンドをJavaプロセス上で実行します。
```json
{"cmd": "shell", "args": "ls -l /sdcard"}
```

#### `start_stream` / `stop_stream`
画面ストリーミング（スクリーンショットの連続取得）を独立したスレッドで開始・停止します。
`fps` は小数点指定が可能です（例: 1.0 = 1秒に1枚）。
```json
{"cmd": "start_stream", "fps": 1.0}
```
ストリーム中のレスポンス（継続的に送られてきます）:
```json
{"type": "stream_frame", "data": "<Base64エンコードされたJPEG画像>"}
```
停止:
```json
{"cmd": "stop_stream"}
```

#### `start_dump_stream` / `stop_dump_stream`
UIツリーダンプのストリーミングを独立したスレッドで開始・停止します。負荷が高いため低fps（0.5など）を推奨します。
```json
{"cmd": "start_dump_stream", "fps": 0.5}
```
ストリーム中のレスポンス（継続的に送られてきます）:
```json
{"type": "dump_stream_frame", "data": "{...ダンプデータ(文字列)...}"}
```
停止:
```json
{"cmd": "stop_dump_stream"}
```

#### `exit`
エージェントプロセスを終了します。
```json
{"cmd": "exit"}
```
