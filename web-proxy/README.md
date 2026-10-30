# web-proxy

earllnRainOrNot 웹앱(earlln.com/rain/)이 쓰는 Cloudflare Worker 중계 서버입니다.
브라우저는 CORS 때문에 TOPIS·ITS·CCTV 영상에 직접 접근할 수 없어 이 Worker 가 대신 받아 줍니다.

- `GET /nearby?lat=&lon=&radius=` : 주변 CCTV 목록 (서울 TOPIS + `ITS_API_KEY` 가 있으면 ITS)
- `GET /media?u=&e=&s=` : Worker 가 직접 서명한 주소(15분 유효)만 중계. 아무 주소나 중계하는 열린 프록시가 되지 않도록 HMAC 서명을 검사하고, HLS 플레이리스트 안의 주소도 서명해 바꿔 씁니다.
- CORS 는 `ALLOWED_ORIGINS`(wrangler.toml) 에 있는 earlln.com 에만 허용합니다.

배포: `.github/workflows/proxy-deploy.yml` (Secrets `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`).
테스트: `npm test`
