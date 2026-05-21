承知いたしました。今後の検証プロジェクトの「公式な記録（ベースライン）」として、ステートレスかつ維持費ゼロで動く **Cloud Run 全部乗せエンドポイント（mTLS終端＋ESTプロキシ構成）** のソースコード一式を書き出します。

この構成は、コンテナ起動時に使い捨てのルートCAとサーバー証明書を動的に生成し、NGINXでmTLSを強制しつつ、背後のESTサーバー（`estlib`）へリクエストを流す、非常に軽量な検証用モックです。

以下の3つのファイルを同じディレクトリに作成し、デプロイコマンドを叩くだけでオンライン検証環境が立ち上がります。

---

### 1. `Dockerfile`

Ubuntuをベースに、NGINXとOpenSSLをインストールし、スクリプトを動かすだけのシンプルな構成です。（※`estlib`のビルド済みバイナリがある前提の構成にしています）

```dockerfile
# ベースイメージはデバッグしやすいUbuntuを使用
FROM ubuntu:22.04

# 必要なパッケージのインストール
RUN apt-get update && apt-get install -y \
    nginx \
    openssl \
    && rm -rf /var/lib/apt/lists/*

# 設定ファイルと起動スクリプトのコピー
COPY nginx.conf /etc/nginx/nginx.conf
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# ※ここに Cisco estlib のコンパイル済みバイナリをコピーする処理を追加してください
# COPY estserver /usr/local/bin/estserver

# Cloud Runのデフォルトポート
EXPOSE 8080

# コンテナ起動時に実行
CMD ["/entrypoint.sh"]

```

### 2. `entrypoint.sh` (起動スクリプト)

コンテナが立ち上がる瞬間に、「俺々ルートCA（TA）」と「サーバー証明書」を自動生成し、ESTサーバーとNGINXを起動します。この仕組みにより、外部ストレージ（GCS）やSecret Managerすら不要になります。

```bash
#!/bin/bash
set -e

CERT_DIR="/etc/nginx/certs"
mkdir -p ${CERT_DIR}
cd ${CERT_DIR}

echo "=== 1. Root CA (TA) の自動生成 ==="
# NIAPテスト用のベースとなるルートCA（パスフレーズなし）
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes \
    -keyout RootCA.key -out RootCA.crt \
    -subj "/C=JP/O=Test Lab/CN=Validation Root CA"

echo "=== 2. サーバー証明書の自動生成 ==="
# Cloud Runのエンドポイント用サーバー証明書
openssl req -newkey rsa:2048 -nodes \
    -keyout server.key -out server.csr \
    -subj "/C=JP/O=Test Lab/CN=localhost"
openssl x509 -req -in server.csr \
    -CA RootCA.crt -CAkey RootCA.key -CAcreateserial \
    -out server.crt -days 365

echo "=== 3. バックグラウンドプロセスの起動 ==="
# ここで Cisco estlib のサーバーをバックグラウンドで起動します（ポート8085想定）
# 例: /usr/local/bin/estserver -p 8085 -c server.crt -k server.key -r RootCA.crt &
echo "EST Server would start here on port 8085."

echo "=== 4. NGINX の起動 ==="
# NGINXをフォアグラウンドで起動し、コンテナを維持する
nginx -g 'daemon off;'

```

### 3. `nginx.conf` (NGINX設定ファイル)

Cloud Runの前面で待ち受け、mTLS（クライアント証明書の要求）を処理し、成功した場合のみ背後のESTサーバーにプロキシします。

```nginx
events {
    worker_connections 1024;
}

http {
    # ログフォーマット（mTLSの検証結果やクライアント情報を出力）
    log_format mtls_log '$remote_addr - $remote_user [$time_local] '
                        '"$request" $status $body_bytes_sent '
                        'Client-Verify: $ssl_client_verify '
                        'Client-DN: $ssl_client_s_dn '
                        'Issuer-DN: $ssl_client_i_dn';

    access_log /var/log/nginx/access.log mtls_log;
    error_log /var/log/nginx/error.log debug;

    server {
        # Cloud Runのコンテナは 8080 でリクエストを受け取ります
        listen 8080 ssl;
        server_name _;

        # entrypoint.sh で生成したサーバー証明書
        ssl_certificate /etc/nginx/certs/server.crt;
        ssl_certificate_key /etc/nginx/certs/server.key;

        # ----------------------------------------------------
        # mTLS (クライアント証明書認証) の設定
        # ----------------------------------------------------
        ssl_client_certificate /etc/nginx/certs/RootCA.crt;
        
        # 'on' にすると証明書がない（または不正な）リクエストを即座に400エラーで弾きます。
        # デバッグ時は 'optional' にすると、弾かずにログ（$ssl_client_verify）に残せます。
        ssl_verify_client on;
        ssl_verify_depth 2;

        # ----------------------------------------------------
        # ESTプロトコルのルーティング (RFC 7030)
        # ----------------------------------------------------
        location /.well-known/est/ {
            # バックグラウンドの estlib (ポート8085) へ転送
            proxy_pass http://127.0.0.1:8085;

            # クライアントの証明書情報をHTTPヘッダーとしてESTサーバーへ渡す（検証やログ用）
            proxy_set_header X-SSL-Client-Verify $ssl_client_verify;
            proxy_set_header X-SSL-Client-Subject-DN $ssl_client_s_dn;
            proxy_set_header X-SSL-Client-Issuer-DN $ssl_client_i_dn;
            proxy_set_header X-SSL-Client-Serial $ssl_client_serial;
            
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        }

        # Cloud Runの正常性確認用エンドポイント
        location /health {
            access_log off;
            return 200 "OK\n";
        }
    }
}

```

---

### デプロイと検証の進め方（オンライン完結）

ファイルが用意できたら、Google Cloud CLI (`gcloud` コマンド) が使えるターミナル（Cloud Shellでも可）を開き、ファイルがあるディレクトリで以下のコマンドを実行します。

```bash
gcloud run deploy est-validation-endpoint \
  --source . \
  --region asia-northeast1 \
  --allow-unauthenticated \
  --max-instances 1 \
  --memory 512Mi

```

これで、数分後に `https://est-validation-endpoint-xxxxxx.a.run.app` というパブリックURLが発行されます。

**検証時の注意点（TAの取得）:**
この構成では、コンテナが起動するたびに「新しいRoot CA」が生成されます。そのため、クライアント（アプリ）側で接続テストを行う直前に、コンテナ内から `RootCA.crt` をダウンロード（またはログに出力させてコピー）し、アプリのTrust Storeにインポートする必要があります。

このコードベースをプロジェクトの記録（リファレンス実装）として保存し、状況に応じて設定値をチューニングしながらご活用ください。