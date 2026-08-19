#!/usr/bin/env bash
#
# 内网自签 HTTPS 证书生成脚本
#
# 目的：让内网站点跑在 https:// 上，从而满足 Chromium 的"安全上下文"要求，
#      使 WebView 内的网页可以调用 getUserMedia（相机扫码）、crypto.subtle 等能力。
#
# 产出：一个内部根 CA + 一张服务器证书。
#      根 CA 打进 APK 作为信任锚点，服务器证书挂在宝塔站点上。
#      这样 WebView 会认为证书完全合法，不会触发 SSL 错误，也不需要
#      用 onReceivedSslError + proceed() 这种把 TLS 安全性归零的做法。
#
# 用法：
#   ./gen-intranet-cert.sh <域名> [服务器IP]
#   ./gen-intranet-cert.sh app.intranet.local
#   ./gen-intranet-cert.sh app.intranet.local 192.168.1.100
#
# 可选环境变量：
#   SERVER_DAYS=3650   服务器证书有效期（天），默认 10 年
#   CA_DAYS=3650       根 CA 有效期（天），默认 10 年
#
set -euo pipefail

DOMAIN="${1:-}"
EXTRA_IP="${2:-}"
CA_DAYS="${CA_DAYS:-3650}"
SERVER_DAYS="${SERVER_DAYS:-3650}"

if [ -z "$DOMAIN" ]; then
  echo "用法: $0 <域名> [服务器IP]"
  echo "例如: $0 app.intranet.local 192.168.1.100"
  exit 1
fi

OUT="certs-${DOMAIN}"
mkdir -p "$OUT"
cd "$OUT"

# ---------------------------------------------------------------------------
# 1. 内部根 CA
#    ca.crt 要交给 App 端放进 res/raw/；ca.key 是签发私钥，务必离线保管，
#    不要留在服务器上——它一旦泄露，任何人都能签出被这个 App 信任的证书。
# ---------------------------------------------------------------------------
if [ -f ca.key ]; then
  echo "==> 复用已存在的根 CA（ca.key / ca.crt）"
else
  echo "==> 生成内部根 CA（有效期 ${CA_DAYS} 天）"
  openssl req -x509 -newkey rsa:2048 -nodes -days "$CA_DAYS" \
    -keyout ca.key -out ca.crt \
    -subj "/CN=Intranet Root CA/O=Intranet" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

# ---------------------------------------------------------------------------
# 2. 服务器证书
#    关键：SAN（subjectAltName）必须写域名。Chromium 早已不再读取 CN 字段，
#    只认 SAN，缺了 SAN 会直接报 ERR_CERT_COMMON_NAME_INVALID。
#    额外带上 IP 是为了在内网 DNS 失效时还能用 https://<IP> 兜底访问。
# ---------------------------------------------------------------------------
SAN="DNS:${DOMAIN}"
[ -n "$EXTRA_IP" ] && SAN="${SAN},IP:${EXTRA_IP}"

echo "==> 生成服务器证书（SAN = ${SAN}，有效期 ${SERVER_DAYS} 天）"
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr \
  -subj "/CN=${DOMAIN}"

cat > server.ext <<EOF
subjectAltName = ${SAN}
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
EOF

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days "$SERVER_DAYS" -sha256 -extfile server.ext

# 宝塔面板 SSL 的「证书(PEM格式)」输入框需要完整证书链（服务器证书 + 上级 CA）
cat server.crt ca.crt > fullchain.pem

rm -f server.csr server.ext

echo
echo "======================================================================"
echo "生成完毕：$(pwd)"
echo
echo "  fullchain.pem   ->  宝塔 SSL 的「证书(PEM格式)」文本框"
echo "  server.key      ->  宝塔 SSL 的「密钥(KEY)」文本框"
echo "  ca.crt          ->  交给 App，放进 res/raw/intranet_ca.crt"
echo "  ca.key          ->  签发私钥，离线保管，不要上传服务器"
echo "======================================================================"
echo
openssl x509 -in server.crt -noout -subject -issuer -dates -ext subjectAltName
