// earllnRainOrNot 웹앱용 Cloudflare Worker
//   GET /nearby?lat=&lon=&radius=   주변 CCTV 목록 (TOPIS + ITS)
//   GET /media?u=&e=&s=             서명된 CCTV 이미지/영상 중계 (HLS 플레이리스트는 내부 주소도 서명해 재작성)
import { haversineKm, hasCrosswalk, signUrl, verifySignature, rewritePlaylist, mediaTypeOf } from './lib.js';

const UA = 'Mozilla/5.0';
const MEDIA_TTL_SEC = 15 * 60;
const MAX_RADIUS_KM = 50;
const MAX_TOPIS_DETAIL = 10;

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    // earlln.com/rain-api/* 경로로 들어오면 접두어를 떼고, 서명 주소에도 같은 접두어를 붙인다.
    const prefix = url.pathname.startsWith('/rain-api/') || url.pathname === '/rain-api' ? '/rain-api' : '';
    const path = url.pathname.slice(prefix.length) || '/';
    const base = url.origin + prefix;
    const cors = corsHeaders(request, env);
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: cors });
    if (request.method !== 'GET') return json({ error: 'method not allowed' }, 405, cors);
    try {
      if (path === '/health') return json({ ok: true }, 200, cors);
      if (path === '/nearby') return await nearby(url, base, env, ctx, cors);
      if (path === '/media') return await media(url, base, env, cors);
      return json({ error: 'not found' }, 404, cors);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502, cors);
    }
  },
};

function corsHeaders(request, env) {
  const origin = request.headers.get('Origin') || '';
  const allowed = (env.ALLOWED_ORIGINS || '').split(',').map(s => s.trim()).filter(Boolean);
  const h = { 'Vary': 'Origin' };
  if (allowed.includes(origin)) {
    h['Access-Control-Allow-Origin'] = origin;
    h['Access-Control-Allow-Methods'] = 'GET, OPTIONS';
    h['Access-Control-Allow-Headers'] = 'Content-Type';
  }
  return h;
}

function json(obj, status, cors) {
  return new Response(JSON.stringify(obj), {
    status, headers: { ...cors, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

// ── 주변 CCTV ─────────────────────────────────────────

async function nearby(url, base, env, ctx, cors) {
  const lat = Number(url.searchParams.get('lat'));
  const lon = Number(url.searchParams.get('lon'));
  const radius = Math.min(MAX_RADIUS_KM, Math.max(0.5, Number(url.searchParams.get('radius')) || 10));
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return json({ error: 'lat/lon required' }, 400, cors);

  const [topis, its] = await Promise.all([
    topisNearby(lat, lon, radius, env, ctx).catch(() => []),
    itsNearby(lat, lon, radius, env).catch(() => []),
  ]);
  const seen = new Set();
  const items = [...its, ...topis]
    .filter(c => c.url && !seen.has(c.url) && seen.add(c.url))
    .map(c => ({ ...c, distanceKm: haversineKm(lat, lon, c.lat, c.lon) }))
    .filter(c => c.distanceKm <= radius)
    .sort((a, b) => (hasCrosswalk(b.name) - hasCrosswalk(a.name)) || (a.distanceKm - b.distanceKm));

  const out = await Promise.all(items.map(async c => ({
    id: c.id, name: c.name, lat: c.lat, lon: c.lon, type: c.type, source: c.source,
    distanceKm: Math.round(c.distanceKm * 1000) / 1000,
    crosswalk: hasCrosswalk(c.name),
    // TOPIS 영상 서버는 CORS 를 허용하지만 중간 인증서를 빠뜨려 Worker 로는 중계할 수 없다.
    // 브라우저는 빠진 인증서를 스스로 채우므로 원본 주소(direct)를 먼저 쓰고, 실패하면 media(중계)를 쓴다.
    direct: c.url,
    media: await signUrl(base, c.url, env.SIGNING_KEY, MEDIA_TTL_SEC),
  })));
  return json({ items: out, its: !!env.ITS_API_KEY }, 200, cors);
}

async function cachedJson(cacheKey, ttlSec, ctx, loader) {
  const cache = caches.default;
  const key = new Request('https://cache.internal/' + encodeURIComponent(cacheKey));
  const hit = await cache.match(key);
  if (hit) return hit.json();
  const data = await loader();
  ctx.waitUntil(cache.put(key, new Response(JSON.stringify(data), {
    headers: { 'Content-Type': 'application/json', 'Cache-Control': `max-age=${ttlSec}` },
  })));
  return data;
}

function topisHeaders(env) {
  return {
    'Referer': env.TOPIS_BASE + '/map/openCctvMap.do',
    'X-Requested-With': 'XMLHttpRequest',
    'User-Agent': UA,
  };
}

async function topisNearby(lat, lon, radius, env, ctx) {
  const list = await cachedJson('topis-list', 30 * 60, ctx, async () => {
    const r = await fetch(env.TOPIS_BASE + '/map/cctv/selectCctvList.do', { headers: topisHeaders(env) });
    const j = await r.json();
    return (j.rows || []).map(x => ({ camId: x.camId, camName: x.camName, lat: Number(x.lat), lng: Number(x.lng) }));
  });
  const near = list
    .filter(x => x.camId && Number.isFinite(x.lat) && Number.isFinite(x.lng))
    .map(x => ({ ...x, d: haversineKm(lat, lon, x.lat, x.lng) }))
    .filter(x => x.d <= radius)
    .sort((a, b) => a.d - b.d)
    .slice(0, MAX_TOPIS_DETAIL);

  const details = await Promise.all(near.map(x =>
    cachedJson('topis-detail-' + x.camId, 60 * 60, ctx, async () => {
      const body = new URLSearchParams({ camId: x.camId, cctvSourceCd: 'HP' });
      const r = await fetch(env.TOPIS_BASE + '/map/selectCctvInfo.do', {
        method: 'POST', body,
        headers: { ...topisHeaders(env), 'Content-Type': 'application/x-www-form-urlencoded' },
      });
      const row = ((await r.json()).rows || [])[0] || {};
      return { url: row.hlsUrl || row.backupUrl || '' };
    }).catch(() => ({ url: '' }))
  ));
  return near.map((x, i) => ({
    id: 'topis-' + x.camId, name: (x.camName || '').trim() || '이름 없는 CCTV',
    lat: x.lat, lon: x.lng, type: '1', source: 'topis', url: details[i].url,
  })).filter(c => c.url);
}

async function itsNearby(lat, lon, radius, env) {
  if (!env.ITS_API_KEY) return [];
  const d = Math.min(radius, 500) / 111;
  const results = await Promise.all(['1', '2', '3'].map(async type => {
    const q = new URLSearchParams({
      apiKey: env.ITS_API_KEY, type: 'its', cctvType: type,
      minX: lon - d, maxX: lon + d, minY: lat - d, maxY: lat + d, getType: 'json',
    });
    const r = await fetch(env.ITS_BASE + '/cctvInfo?' + q);
    const j = await r.json();
    return (j.response && j.response.data || []).map(x => ({
      id: 'its-' + (x.roadsectionid || '') + '-' + (x.cctvname || ''),
      name: (x.cctvname || '').trim() || '이름 없는 CCTV',
      lat: Number(x.coordy), lon: Number(x.coordx), type: String(x.cctvtype || type), source: 'its',
      url: (String(x.cctvtype) === '3' && x.cctvurl2) ? x.cctvurl2 : x.cctvurl,
    }));
  }));
  return results.flat().filter(c => Number.isFinite(c.lat) && Number.isFinite(c.lon));
}

// ── 미디어 중계 ───────────────────────────────────────

async function media(url, base, env, cors) {
  const target = await verifySignature(url.searchParams, env.SIGNING_KEY);
  if (!target) return json({ error: 'invalid or expired signature' }, 403, cors);

  const upstream = await fetch(target, {
    headers: { 'User-Agent': UA, 'Referer': env.TOPIS_BASE + '/' },
    cf: { cacheTtl: 2 },
  });
  if (!upstream.ok) return json({ error: 'upstream ' + upstream.status }, 502, cors);

  const type = mediaTypeOf(target, upstream.headers.get('Content-Type'));
  if (type === 'playlist') {
    const text = await upstream.text();
    const body = await rewritePlaylist(text, target, base, env.SIGNING_KEY, MEDIA_TTL_SEC);
    return new Response(body, {
      headers: { ...cors, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' },
    });
  }
  const headers = { ...cors, 'Cache-Control': 'no-store' };
  const ct = upstream.headers.get('Content-Type');
  if (ct) headers['Content-Type'] = ct;
  const len = upstream.headers.get('Content-Length');
  if (len) headers['Content-Length'] = len;
  return new Response(upstream.body, { headers });
}
