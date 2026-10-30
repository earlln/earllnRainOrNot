import { test } from 'node:test';
import assert from 'node:assert/strict';
import { signUrl, verifySignature, rewritePlaylist, hasCrosswalk, mediaTypeOf } from '../src/lib.js';

const KEY = 'test-key';
const params = u => new URL(u).searchParams;

test('signed url round-trips and rejects tampering/expiry', async () => {
  const now = 1_700_000_000_000;
  const s = await signUrl('https://w.dev', 'http://cctv.example:1935/live/a.m3u8', KEY, 60, now);
  assert.equal(await verifySignature(params(s), KEY, now), 'http://cctv.example:1935/live/a.m3u8');
  assert.equal(await verifySignature(params(s), 'other-key', now), null);
  assert.equal(await verifySignature(params(s), KEY, now + 61_000), null);
  const tampered = s.replace(/u=[^&]+/, 'u=' + Buffer.from('http://evil.example/').toString('base64url'));
  assert.equal(await verifySignature(params(tampered), KEY, now), null);
});

test('non-http targets are refused even if signed', async () => {
  const s = await signUrl('https://w.dev', 'file:///etc/passwd', KEY, 60);
  assert.equal(await verifySignature(params(s), KEY), null);
});

test('playlist lines and URI attributes are rewritten to signed urls', async () => {
  const pl = '#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:2.0,\nseg1.ts\n#EXTINF:2.0,\n/abs/seg2.ts?x=1\n';
  const out = await rewritePlaylist(pl, 'http://cctv.example:1935/live/chunk.m3u8', 'https://w.dev', KEY, 60);
  const lines = out.split('\n');
  assert.equal(lines[0], '#EXTM3U');
  const seg1 = lines.find(l => l.startsWith('https://w.dev/media') && !l.includes('URI'));
  assert.equal(await verifySignature(params(seg1), KEY), 'http://cctv.example:1935/live/seg1.ts');
  const seg2 = lines.filter(l => l.startsWith('https://w.dev/media'))[1];
  assert.equal(await verifySignature(params(seg2), KEY), 'http://cctv.example:1935/abs/seg2.ts?x=1');
  const map = lines.find(l => l.startsWith('#EXT-X-MAP'));
  const mapUrl = map.match(/URI="([^"]+)"/)[1];
  assert.equal(await verifySignature(params(mapUrl), KEY), 'http://cctv.example:1935/live/init.mp4');
});

test('helpers', () => {
  assert.equal(hasCrosswalk('강남역 사거리'), 1);
  assert.equal(hasCrosswalk('올림픽대로'), 0);
  assert.equal(mediaTypeOf('http://a/b.m3u8', null), 'playlist');
  assert.equal(mediaTypeOf('http://a/b', 'application/vnd.apple.mpegurl'), 'playlist');
  assert.equal(mediaTypeOf('http://a/b.jpg', 'image/jpeg'), 'binary');
});
