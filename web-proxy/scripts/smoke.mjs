// 배포된 Worker 를 실제로 호출해 TOPIS 목록 → CCTV 플레이리스트 → 영상 세그먼트까지 확인한다.
// usage: node scripts/smoke.mjs https://<worker>.workers.dev
const base = process.argv[2];
const headers = { Origin: 'https://earlln.com' };
const warn = m => console.log(`::warning::${m}`);

const h = await fetch(`${base}/health`, { headers });
console.log('health', h.status, h.headers.get('access-control-allow-origin'));
if (!h.ok) { console.log('::error::Worker /health 실패'); process.exit(1); }

// 서울시청 반경 3km
const n = await fetch(`${base}/nearby?lat=37.5665&lon=126.9780&radius=3`, { headers });
const data = await n.json();
console.log('nearby', n.status, 'items', (data.items || []).length, 'its', data.its);
if (!data.items || !data.items.length) { warn('주변 CCTV 0개 — TOPIS 가 Cloudflare 접속을 막았거나 응답 형식이 바뀌었을 수 있습니다'); process.exit(0); }
console.log('first 3:', data.items.slice(0, 3).map(i => `${i.name}(${i.type}, ${i.distanceKm}km)`).join(' | '));

const hostOf = m => { try { const u = new URL(m).searchParams.get('u'); const t = new URL(Buffer.from(u, 'base64url').toString()); return `${t.protocol}//${t.host}`; } catch (_) { return '?'; } };
console.log('media hosts:', [...new Set(data.items.map(i => hostOf(i.media)))].join(', '));

let ok = 0;
for (const item of data.items.slice(0, 3)) {
  try {
    let url = item.media, text = '';
    for (let depth = 0; depth < 3; depth++) {
      const r = await fetch(url, { headers });
      const ct = r.headers.get('content-type') || '';
      if (!r.ok) throw new Error(`HTTP ${r.status} ${await r.text().then(t => t.slice(0, 120))}`);
      if (!ct.includes('mpegurl')) { const buf = await r.arrayBuffer(); console.log(`  ${item.name}: media ${ct} ${buf.byteLength}B`); ok++; break; }
      text = await r.text();
      const next = text.split('\n').map(s => s.trim()).filter(s => s && !s.startsWith('#')).pop();
      if (!next) throw new Error('playlist without entries');
      url = next;
    }
  } catch (e) { warn(`${item.name}: ${e.message}`); }
}
console.log(`media ok ${ok}/3`);
if (!ok) {
  warn('CCTV 영상을 하나도 중계하지 못했습니다');
  // 진단용: 첫 CCTV 의 호스트와 경로(쿼리 제외)를 파일로 남긴다
  try {
    const u = new URL(data.items[0].media).searchParams.get('u');
    const t = new URL(Buffer.from(u, 'base64url').toString());
    (await import('node:fs')).writeFileSync('diag-target.txt', `${t.host} ${t.pathname}\n`);
  } catch (_) { }
}
