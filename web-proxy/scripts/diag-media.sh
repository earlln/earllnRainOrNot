#!/usr/bin/env bash
# CCTV 영상 서버 진단: 인증서 체인, 인증서 무시 접속, CORS 헤더 (GitHub 러너=해외 IP 에서 실행)
host="$1"; path="$2"
echo "== TLS chain ($host:443)"
timeout 15 openssl s_client -connect "$host:443" -servername "$host" -showcerts </dev/null 2>/dev/null \
  | grep -E '^( [0-9] s:| *i:|Verify return code|Certificate chain)' | head -12 || echo "(TLS connect failed)"
echo "== HTTPS ignoring cert (curl -k), Origin earlln.com"
curl -sk -m 15 -o /dev/null -D - -H 'Origin: https://earlln.com' -H 'Referer: https://topis.seoul.go.kr/' "https://$host$path" \
  | grep -iE '^(HTTP|content-type|access-control|server)' || echo "(no response / timeout)"
echo "== HTTP port 80"
curl -s -m 10 -o /dev/null -w '%{http_code}\n' "http://$host$path" || echo "(timeout)"
