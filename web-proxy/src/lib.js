// Worker 와 테스트가 함께 쓰는 순수 함수들

const CROSSWALK_KEYWORDS = ['교차로', '횡단보도', '삼거리', '사거리', '오거리', '네거리', '사거', '삼거', '교차', '광장', '터미널', '역앞', '역 앞'];

export function hasCrosswalk(name) {
  return CROSSWALK_KEYWORDS.some(k => (name || '').includes(k)) ? 1 : 0;
}

export function haversineKm(lat1, lon1, lat2, lon2) {
  const r = 6371, toRad = d => d * Math.PI / 180;
  const dLat = toRad(lat2 - lat1), dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function b64urlDecodeToString(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  return new TextDecoder().decode(Uint8Array.from(bin, c => c.charCodeAt(0)));
}

async function hmac(key, data) {
  if (!key) throw new Error('SIGNING_KEY not configured');
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return b64url(new Uint8Array(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(data))));
}

/** 원본 주소를 만료 시각과 함께 서명한 /media 주소로 바꾼다. */
export async function signUrl(origin, target, key, ttlSec, now = Date.now()) {
  const u = b64url(new TextEncoder().encode(target));
  const e = String(Math.floor(now / 1000) + ttlSec);
  const s = await hmac(key, u + '.' + e);
  return `${origin}/media?u=${u}&e=${e}&s=${s}`;
}

/** 서명이 맞고 만료되지 않았으면 원본 주소(http/https 만)를, 아니면 null */
export async function verifySignature(params, key, now = Date.now()) {
  const u = params.get('u'), e = params.get('e'), s = params.get('s');
  if (!u || !e || !s) return null;
  if (Number(e) < Math.floor(now / 1000)) return null;
  const expected = await hmac(key, u + '.' + e);
  if (expected.length !== s.length) return null;
  let diff = 0;
  for (let i = 0; i < s.length; i++) diff |= expected.charCodeAt(i) ^ s.charCodeAt(i);
  if (diff !== 0) return null;
  const target = b64urlDecodeToString(u);
  return /^https?:\/\//i.test(target) ? target : null;
}

export function resolveUrl(base, ref) {
  return new URL(ref, base).toString();
}

export function mediaTypeOf(target, contentType) {
  const ct = (contentType || '').toLowerCase();
  if (ct.includes('mpegurl') || /\.m3u8(\?|$)/i.test(target)) return 'playlist';
  return 'binary';
}

/** HLS 플레이리스트의 모든 하위 주소(세그먼트, 변형 플레이리스트, URI="...")를 서명된 /media 주소로 바꾼다. */
export async function rewritePlaylist(text, base, origin, key, ttlSec, now = Date.now()) {
  const lines = text.split(/\r?\n/);
  const out = [];
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) { out.push(raw); continue; }
    if (line.startsWith('#')) {
      const m = line.match(/URI="([^"]+)"/);
      if (m) {
        const signed = await signUrl(origin, resolveUrl(base, m[1]), key, ttlSec, now);
        out.push(line.replace(m[0], `URI="${signed}"`));
      } else out.push(raw);
      continue;
    }
    out.push(await signUrl(origin, resolveUrl(base, line), key, ttlSec, now));
  }
  return out.join('\n');
}
