#!/bin/bash

SERIAL="58051JEA303914"

# デバイス上の一時マウントフォルダの初期化
adb -s "$SERIAL" shell "su 0 rm -rf /data/local/tmp/cacerts && su 0 mkdir -p /data/local/tmp/cacerts"
# 既存の CA 証明書をコピー
adb -s "$SERIAL" shell "su 0 cp -r /system/etc/security/cacerts/* /data/local/tmp/cacerts/"
# 自前の証明書（/data/local/tmp/ にプッシュ済みのもの）をコピー
adb -s "$SERIAL" shell "su 0 cp /data/local/tmp/96f2d990.0 /data/local/tmp/cacerts/"
adb -s "$SERIAL" shell "su 0 cp /data/local/tmp/a1e74968.0 /data/local/tmp/cacerts/"
adb -s "$SERIAL" shell "su 0 cp /data/local/tmp/c23c704e.0 /data/local/tmp/cacerts/"
adb -s "$SERIAL" shell "su 0 cp /data/local/tmp/0a55a09e.0 /data/local/tmp/cacerts/"

# パーミッションおよび所有者を設定
adb -s "$SERIAL" shell "su 0 chmod 644 /data/local/tmp/cacerts/*"
adb -s "$SERIAL" shell "su 0 chown root:root /data/local/tmp/cacerts/*"

# システムCAストアのディレクトリにバインドマウント
adb -s "$SERIAL" shell "su 0 mount --bind /data/local/tmp/cacerts /system/etc/security/cacerts"

echo "✅ CA certificates installed and overlaid on device system CA store."
