#!/bin/bash

BASE_DIR="/Users/wkouki/Library/Application Support/TestbedCore/resources/revocation"
PY_SCRIPT="/Users/wkouki/AndroidStudioProjects/testbedui-plugins/test-sample/scripts/revocation/ocsp_stapling_server.py"

cleanup() {
    echo "Stopping OCSP Mock servers..."
    pkill -f "ocsp_stapling_server.py"
    pkill -f "openssl ocsp"
}
trap cleanup EXIT

# 4443 (Valid)
python3 "$PY_SCRIPT" "$BASE_DIR/server-valid.crt" "$BASE_DIR/server-valid.key" "$BASE_DIR/valid.resp" 4443 > /tmp/ocsp_4443.log 2>&1 &
# 4444 (Revoked)
python3 "$PY_SCRIPT" "$BASE_DIR/server-revoked.crt" "$BASE_DIR/server-revoked.key" "$BASE_DIR/revoked.resp" 4444 > /tmp/ocsp_4444.log 2>&1 &
# 4445 (CNSA)
python3 "$PY_SCRIPT" "$BASE_DIR/cnsa/server-valid.crt" "$BASE_DIR/cnsa/server-valid.key" "$BASE_DIR/cnsa/valid.resp" 4445 > /tmp/ocsp_4445.log 2>&1 &
# 4446 (ECDSA)
python3 "$PY_SCRIPT" "$BASE_DIR/ecdsa/server-valid.crt" "$BASE_DIR/ecdsa/server-valid.key" "$BASE_DIR/ecdsa/valid.resp" 4446 > /tmp/ocsp_4446.log 2>&1 &
# 4447 (SHA512)
python3 "$PY_SCRIPT" "$BASE_DIR/sha512/server-valid.crt" "$BASE_DIR/sha512/server-valid.key" "$BASE_DIR/sha512/valid.resp" 4447 > /tmp/ocsp_4447.log 2>&1 &
# 4448 (SHA384 / Old CA)
python3 "$PY_SCRIPT" "$BASE_DIR/server-sha384.crt" "$BASE_DIR/server-valid.key" "$BASE_DIR/server-sha384.resp" 4448 > /tmp/ocsp_4448.log 2>&1 &

# 8888 (OpenSSL OCSP Responder - Default CA)
openssl ocsp -index "$BASE_DIR/index.txt" -port 8888 -rsigner "$BASE_DIR/responder.crt" -rkey "$BASE_DIR/responder.key" -CA "$BASE_DIR/root-ca.crt" > /tmp/openssl_ocsp_8888.log 2>&1 &

# 8889 (OpenSSL OCSP Responder - CNSA)
openssl ocsp -index "$BASE_DIR/cnsa/index.txt" -port 8889 -rsigner "$BASE_DIR/cnsa/responder.crt" -rkey "$BASE_DIR/cnsa/responder.key" -CA "$BASE_DIR/cnsa/root-ca.crt" > /tmp/openssl_ocsp_8889.log 2>&1 &

# 8890 (OpenSSL OCSP Responder - ECDSA)
openssl ocsp -index "$BASE_DIR/ecdsa/index.txt" -port 8890 -rsigner "$BASE_DIR/ecdsa/responder.crt" -rkey "$BASE_DIR/ecdsa/responder.key" -CA "$BASE_DIR/ecdsa/root-ca.crt" > /tmp/openssl_ocsp_8890.log 2>&1 &

# 8891 (OpenSSL OCSP Responder - SHA512)
openssl ocsp -index "$BASE_DIR/sha512/index.txt" -port 8891 -rsigner "$BASE_DIR/sha512/responder.crt" -rkey "$BASE_DIR/sha512/responder.key" -CA "$BASE_DIR/sha512/root-ca.crt" > /tmp/openssl_ocsp_8891.log 2>&1 &

echo "OCSP Mock servers started."

while true; do
    sleep 10
done
