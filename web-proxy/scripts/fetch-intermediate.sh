#!/usr/bin/env bash
# CCTV 서버가 빠뜨린 중간 인증서를 AIA 주소에서 받아, 실제로 서버 인증서를 서명했는지 검증한 뒤 PEM 을 출력한다.
set -euo pipefail
host="${1:-topiscctv1.eseoul.go.kr}"
work=$(mktemp -d)
timeout 15 openssl s_client -connect "$host:443" -servername "$host" </dev/null 2>/dev/null | openssl x509 > "$work/leaf.pem"
echo "leaf:   $(openssl x509 -in "$work/leaf.pem" -noout -subject -enddate | tr '\n' ' ')"
aia=$(openssl x509 -in "$work/leaf.pem" -noout -text | grep -o 'CA Issuers - URI:[^ ]*' | head -1 | sed 's/CA Issuers - URI://')
echo "AIA:    $aia"
curl -sS -m 20 -o "$work/inter.der" "$aia"
openssl x509 -inform DER -in "$work/inter.der" -out "$work/inter.pem" 2>/dev/null || cp "$work/inter.der" "$work/inter.pem"
echo "inter:  $(openssl x509 -in "$work/inter.pem" -noout -subject -issuer -enddate | tr '\n' ' ')"
echo "sha256: $(openssl x509 -in "$work/inter.pem" -noout -fingerprint -sha256)"
echo "== verify leaf with system roots + this intermediate"
openssl verify -untrusted "$work/inter.pem" "$work/leaf.pem"
echo "== PEM"
cat "$work/inter.pem"
