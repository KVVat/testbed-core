# エージェント実行のためのプロジェクト知見

## 1. 禁じられた事柄 / 重大な教訓

*   **`AdbDeviceRule.kt` の変更・削除は厳禁**: このファイルはテスト関連のコードであり、将来的な拡張のために存在します。プロダクションコードのビルドエラーを修正する際に、このファイルの内容を安易に削除したり、大きく変更したりしてはいけません。依存関係の問題は、Gradle設定で解決すべきです。
*   **コンパイルエラーの蔓延に注意**: 大規模な変更を一度に行うと、コンパイルエラーが広範囲に及び、原因特定が困難になります。変更は小分けにし、都度ビルドチェックを行うことが重要です。エラーが一定以上広がった場合、速やかに変更をロールバックし、アプローチを再検討する必要があります。
*   **依存関係の理解**: 特にマルチプラットフォームプロジェクトでは、`commonMain`, `jvmMain`, `commonTest` などのソースセット間で依存関係がどのように解決されるかを正確に理解することが不可欠です。誤ったソースセットに依存関係を追加すると、予期せぬビルドエラーを引き起こします。
*   **コンパイルエラーを確認してから引き渡し**: 変更を行った後は一度ビルドを行いコンパイルエラーが出ないことを確認して引き渡すこと
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

---
この知見が、今後のエージェント実行に役立つことを願っています。
