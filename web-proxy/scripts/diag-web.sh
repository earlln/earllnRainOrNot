#!/usr/bin/env bash
# 웹앱 'Failed to fetch' 진단: Worker CORS(출처별), Gemini 브라우저 호출(CORS 사전요청), earlln.com 주소 처리
w="$1"
for o in https://earlln.com https://www.earlln.com http://earlln.com; do
  echo "== Worker /nearby  Origin: $o"
  curl -s -m 20 -o /tmp/nb.json -D - -H "Origin: $o" "$w/nearby?lat=37.5665&lon=126.9780&radius=3" | grep -iE '^(HTTP|access-control-allow-origin)'
  head -c 120 /tmp/nb.json; echo
done
echo "== Worker preflight (OPTIONS)"
curl -s -m 15 -o /dev/null -D - -X OPTIONS -H 'Origin: https://earlln.com' -H 'Access-Control-Request-Method: GET' "$w/nearby" | grep -iE '^(HTTP|access-control)'
echo "== Gemini CORS preflight from https://earlln.com"
curl -s -m 15 -o /dev/null -D - -X OPTIONS -H 'Origin: https://earlln.com' -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: content-type,x-goog-api-key' \
  "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent" | grep -iE '^(HTTP|access-control)'
echo "== earlln.com addresses"
for u in http://earlln.com/rain/ https://earlln.com/rain/ https://www.earlln.com/rain/ https://earlln.com/rain/config.js; do
  printf '%s -> ' "$u"; curl -s -m 15 -o /tmp/p -w '%{http_code} %{redirect_url}\n' "$u"
done
echo "== config.js content"; cat /tmp/p; echo
