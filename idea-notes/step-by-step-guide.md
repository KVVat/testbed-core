# How to run MDSCert test case on Testbed Core 

## 導入と確認方法

### 1
下記のウェブサイトからTestbed-coreの配布ファイルと、テストパッケージを取得します 
https://github.com/KVVat/testbed-core/releases/tag/PR5

配布ファイルはTestbedCore-[your-os].zip。テストパッケージは`plugins-and-resources.zip`です

### 2
配布ファイルをインストールします。zipファイルを展開してREADME.mdの指示に従ってください。
adbなどの必要なsdkパッケージはこのとき自動でインストールされます。注意点は以下のようになります
- MacOSの場合はREADME.mdを確認してダウンロードファイルを実行できるようにしてください
- Windowsの場合は添付のbatファイルから初回は起動が必要です。batファイルの都合上展開フォルダに空白を含んではいけません。

### 3
Testbedcoreを起動後、adbでテストしたい端末を接続します。画面上部のパネルに接続状態が表示されます。
接続しない場合は、端末が正しく接続されているか。Android側で表示されている確認ダイアログに了承しているかを確認してください。

- Logcatのウィンドウがたちあがる場合がありますが、これはテストには直接関係ないため、閉じてしまって構いせまん。設定から起動しないようにもできます。

### 4
メイン画面左上のハンバーガーメニューをクリックしてTest Explorerを開きます。
表示されているImportボタンをクリックします。ファイル選択ダイアログが開くのでさきほどダウンロードしたテストパッケージplugins-and-resources.zipを指定してください。これによりテスト一覧にMDSCertのテストケースが展開されます。

### 5
パネルから確認したいテストを選んで実施します。個別のテストパネルはひとつのテストクラスに対応し、[Run all]ボタン、個別のテストの実行ボタンが存在します。端末が接続していれば開始できます。

### 6
テスト中はメインパネルにテストの進行状況が表示されます。テスト完了後はTest ExplorerのResultsボタンから直接結果ファイルをhtml形式で確認することができます。

## 注意点
- **CA証明書のインポート (重要)**: 
  Network/X.509/OCSP系のテストをパスさせるには、テスト用CA証明書を事前に端末のシステムに信頼させる必要があります。
  1. インポートしたテストパッケージ内の `resources/revocation/` フォルダにある各 root-ca（例: `root-ca.crt`、`cnsa/root-ca.crt`、`ecdsa/root-ca.crt` 等）を端末に転送（または `adb push`）します。
  2. 端末の `設定 -> セキュリティ -> 暗号化と認証情報 -> 認証情報のインストール -> CA証明書` を開き、転送した各 CA 証明書をすべてインストールしてください。
- **Play Protect / パッケージ検証の無効化**:
  テスト用の野良APKをバックグラウンドインストールする都合上、Playプロテクトの設定をオフにするか、または以下の adb コマンドを実行してパッケージ検証を事前に無効化してください。
  ```bash
  adb shell settings put global package_verifier_enable 0
  ```
- **パケットキャプチャの実行環境**:
  パケットキャプチャを行うテスト（`FcsTlscExtTest` 等）では、端末内の `tcpdump` を用いる都合で、端末が適切にルート化されている（`su` コマンドが使える）必要があります。userdebugビルドのOSを利用してください。
- **ホストOSの推奨環境**:
  Network系のテストはホスト側で `openssl` や `s_server` などのプロセスを背後で動かすため、**macOS** または **Ubuntu (Linux)** での実行を推奨します。

## テストの仕組み、修正
- 多くのテストはホスト側でJUnitを起動、adbを活用してapkや必要な設定ファイルを端末に導入、apkファイルを実行。Logcatのログを監視して実行結果を得るという形式で作成されています。
- Networkのテストではホスト側でサーバーを断ち開けてADBポートフォワードで接続してテストを行いますテストや導入されるapk、テストの開発環境はtestbedui-pluginsサブプロジェクトで管理されています。テストの動作
- 確認や修正を行いたい場合はこちらのプロジェクトを開き./gradlew zipPluginsAndResourcesコマンドでテストパッケージを作成してください。
 - https://github.com/KVVat/testbedui-plugins

## TestbecCoreの機能について
- TestbedCore本体はmcpに対応した自律開発サポート環境でもあります。testbedui-projectを開いた上でテストに対してIDEなどで疑問がある場合LLMに質問をすれば含まれたドキュメントからおおよ上の回答をしてくれると思います。
- ToolBoxとして、LogcatとFileExplorer、簡易なUIツリー確認機能を持ちます。
- MCPを設定することでLLMがAndroid端末の制御（画面取得、タップ、入力、ADBシェル実行、JUnit実行等）を行うことができるようになります。testbed-coreプロジェクトのREADME.mdを確認してください。