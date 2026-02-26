# GitHub Actions Workflow仕様

このリポジトリの配布フローは、以下の3ワークフローに分割されています。

1. `Build Desktop Artifacts` (`.github/workflows/build.yaml`)
2. `Repackage and Zip Desktop Artifacts` (`.github/workflows/repackage-zip.yaml`)
3. `Create GitHub Release` (`.github/workflows/release.yaml`)

## 1. Build Desktop Artifacts

### 目的
- 各OS向けのネイティブ配布物をビルドしてArtifact化する。

### 起動条件
- `workflow_dispatch` (手動)
- `push tags: v*` (例: `v1.2.3`)

### 出力Artifact
- `TestbedCore-windows-latest`
- `TestbedCore-ubuntu-latest`
- `TestbedCore-macos-latest`

## 2. Repackage and Zip Desktop Artifacts

### 目的
- Build成果物をダウンロードして再配置し、OS別ZIPを生成する。
- Linux/macOSはBuild成果物を `tar.gz` で受け取り、実行権限を保持したまま展開する。

### 起動条件
- `workflow_run` (Build成功時に自動起動)
- `workflow_dispatch` (手動再実行)

### 手動実行時の入力
- `build_run_id` (必須): 参照したい Build 実行の Run ID

### 出力Artifact
- `release-zip-windows` (`TestbedCore-windows.zip`)
- `release-zip-ubuntu` (`TestbedCore-ubuntu.zip`)
- `release-zip-macos` (`TestbedCore-macos.zip`)

### macOSで `Permission denied` が出る場合
- まず最新Workflow（権限保持対応後）で作り直したZIPか確認してください。
- それでも発生する場合は、ダウンロード由来の quarantine 属性が原因の可能性があります。
  - 例: `xattr -dr com.apple.quarantine TestbedCore.app`

## 3. Create GitHub Release

### 目的
- ZIP Artifactを使ってGitHub Releaseを作成または更新する。

### 起動条件
- `workflow_dispatch` のみ (手動)

### 入力
- `package_run_id` (必須): Repackage and Zip の Run ID
- `tag_name` (必須): 例 `v1.2.3`
- `release_name` (任意): 空欄なら `tag_name` を利用
- `draft` (必須): Draftで作成するか
- `prerelease` (必須): Prerelease扱いにするか
- `confirm` (必須): `RELEASE` 固定

### 前提条件
- `tag_name` が未存在でも `Create GitHub Release` 実行時に自動作成されます。
- タグは `package_run_id` が指す実行の `head_sha` に作成されます。

### 実行前チェック
- `TestbedCore-windows.zip`
- `TestbedCore-ubuntu.zip`
- `TestbedCore-macos.zip`

上記3つが揃っていない場合、Release作成は失敗します。

### トークンと権限
- 既定では `GITHUB_TOKEN` を使用
- リポジトリシークレット `RELEASE_TOKEN` がある場合はそちらを優先使用

`HTTP 403: Resource not accessible by integration` が出る場合:

1. リポジトリ設定 `Settings > Actions > General > Workflow permissions` を `Read and write permissions` に変更
2. もしくは `RELEASE_TOKEN` を追加
   - Classic PAT: `repo`, `workflow` scope
   - Fine-grained PAT: 対象Repoの `Contents: Read and write`, `Workflows: Read and write`

## 実運用手順

1. 必要ならタグ作成してpush (`v*`) するか、`Build Desktop Artifacts` を手動実行する。
2. 通常は Build 成功後に `Repackage and Zip` が自動実行される。
3. 再実行したい場合は `Repackage and Zip` を手動実行し、対象 `build_run_id` を入れる。
4. 最後に `Create GitHub Release` を手動実行し、`package_run_id` と `confirm=RELEASE` を入れる。

## Run ID の確認方法

- Actions画面で対象Workflowの実行履歴を開く。
- URLの `.../actions/runs/<数字>` の `<数字>` が Run ID。

## 質問への回答

- 「workflow実施時に直前のタスクのidを聞かれるか？」

答えは「手動実行時のみ聞かれる」です。

- Build: 入力なし
- Repackage(手動): `build_run_id` が必要
- Release(手動): `package_run_id` が必要
- Repackage(自動起動): `build_run_id` は不要
