// .wrangler/tmp/bundle-ZNr0z8/checked-fetch.js
var urls = /* @__PURE__ */ new Set();
function checkURL(request, init) {
  const url = request instanceof URL ? request : new URL(
    (typeof request === "string" ? new Request(request, init) : request).url
  );
  if (url.port && url.port !== "443" && url.protocol === "https:") {
    if (!urls.has(url.toString())) {
      urls.add(url.toString());
      console.warn(
        `WARNING: known issue with \`fetch()\` requests to custom HTTPS ports in published Workers:
 - ${url.toString()} - the custom port will be ignored when the Worker is published using the \`wrangler deploy\` command.
`
      );
    }
  }
}
globalThis.fetch = new Proxy(globalThis.fetch, {
  apply(target, thisArg, argArray) {
    const [request, init] = argArray;
    checkURL(request, init);
    return Reflect.apply(target, thisArg, argArray);
  }
});

// src/lib.js
var CROSSWALK_KEYWORDS = ["\uAD50\uCC28\uB85C", "\uD6A1\uB2E8\uBCF4\uB3C4", "\uC0BC\uAC70\uB9AC", "\uC0AC\uAC70\uB9AC", "\uC624\uAC70\uB9AC", "\uB124\uAC70\uB9AC", "\uC0AC\uAC70", "\uC0BC\uAC70", "\uAD50\uCC28", "\uAD11\uC7A5", "\uD130\uBBF8\uB110", "\uC5ED\uC55E", "\uC5ED \uC55E"];
function hasCrosswalk(name) {
  return CROSSWALK_KEYWORDS.some((k) => (name || "").includes(k)) ? 1 : 0;
}
function haversineKm(lat1, lon1, lat2, lon2) {
  const r = 6371, toRad = (d) => d * Math.PI / 180;
  const dLat = toRad(lat2 - lat1), dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function b64url(bytes) {
  let s = "";
  for (const b of bytes)
    s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function b64urlDecodeToString(s) {
  s = s.replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4)
    s += "=";
  const bin = atob(s);
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}
async function hmac(key, data) {
  if (!key)
    throw new Error("SIGNING_KEY not configured");
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return b64url(new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(data))));
}
async function signUrl(origin, target, key, ttlSec, now = Date.now()) {
  const u = b64url(new TextEncoder().encode(target));
  const e = String(Math.floor(now / 1e3) + ttlSec);
  const s = await hmac(key, u + "." + e);
  return `${origin}/media?u=${u}&e=${e}&s=${s}`;
}
async function verifySignature(params, key, now = Date.now()) {
  const u = params.get("u"), e = params.get("e"), s = params.get("s");
  if (!u || !e || !s)
    return null;
  if (Number(e) < Math.floor(now / 1e3))
    return null;
  const expected = await hmac(key, u + "." + e);
  if (expected.length !== s.length)
    return null;
  let diff = 0;
  for (let i = 0; i < s.length; i++)
    diff |= expected.charCodeAt(i) ^ s.charCodeAt(i);
  if (diff !== 0)
    return null;
  const target = b64urlDecodeToString(u);
  return /^https?:\/\//i.test(target) ? target : null;
}
function resolveUrl(base, ref) {
  return new URL(ref, base).toString();
}
function mediaTypeOf(target, contentType) {
  const ct = (contentType || "").toLowerCase();
  if (ct.includes("mpegurl") || /\.m3u8(\?|$)/i.test(target))
    return "playlist";
  return "binary";
}
async function rewritePlaylist(text, base, origin, key, ttlSec, now = Date.now()) {
  const lines = text.split(/\r?\n/);
  const out = [];
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) {
      out.push(raw);
      continue;
    }
    if (line.startsWith("#")) {
      const m = line.match(/URI="([^"]+)"/);
      if (m) {
        const signed = await signUrl(origin, resolveUrl(base, m[1]), key, ttlSec, now);
        out.push(line.replace(m[0], `URI="${signed}"`));
      } else
        out.push(raw);
      continue;
    }
    out.push(await signUrl(origin, resolveUrl(base, line), key, ttlSec, now));
  }
  return out.join("\n");
}

// src/index.js
var UA = "Mozilla/5.0";
var MEDIA_TTL_SEC = 15 * 60;
var MAX_RADIUS_KM = 50;
var MAX_TOPIS_DETAIL = 10;
var src_default = {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const cors = corsHeaders(request, env);
    if (request.method === "OPTIONS")
      return new Response(null, { status: 204, headers: cors });
    if (request.method !== "GET")
      return json({ error: "method not allowed" }, 405, cors);
    try {
      if (url.pathname === "/health")
        return json({ ok: true }, 200, cors);
      if (url.pathname === "/nearby")
        return await nearby(url, env, ctx, cors);
      if (url.pathname === "/media")
        return await media(url, env, cors);
      return json({ error: "not found" }, 404, cors);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 502, cors);
    }
  }
};
function corsHeaders(request, env) {
  const origin = request.headers.get("Origin") || "";
  const allowed = (env.ALLOWED_ORIGINS || "").split(",").map((s) => s.trim()).filter(Boolean);
  const h = { "Vary": "Origin" };
  if (allowed.includes(origin)) {
    h["Access-Control-Allow-Origin"] = origin;
    h["Access-Control-Allow-Methods"] = "GET, OPTIONS";
    h["Access-Control-Allow-Headers"] = "Content-Type";
  }
  return h;
}
function json(obj, status, cors) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...cors, "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}
async function nearby(url, env, ctx, cors) {
  const lat = Number(url.searchParams.get("lat"));
  const lon = Number(url.searchParams.get("lon"));
  const radius = Math.min(MAX_RADIUS_KM, Math.max(0.5, Number(url.searchParams.get("radius")) || 10));
  if (!Number.isFinite(lat) || !Number.isFinite(lon))
    return json({ error: "lat/lon required" }, 400, cors);
  const [topis, its] = await Promise.all([
    topisNearby(lat, lon, radius, env, ctx).catch(() => []),
    itsNearby(lat, lon, radius, env).catch(() => [])
  ]);
  const seen = /* @__PURE__ */ new Set();
  const items = [...its, ...topis].filter((c) => c.url && !seen.has(c.url) && seen.add(c.url)).map((c) => ({ ...c, distanceKm: haversineKm(lat, lon, c.lat, c.lon) })).filter((c) => c.distanceKm <= radius).sort((a, b) => hasCrosswalk(b.name) - hasCrosswalk(a.name) || a.distanceKm - b.distanceKm);
  const origin = url.origin;
  const out = await Promise.all(items.map(async (c) => ({
    id: c.id,
    name: c.name,
    lat: c.lat,
    lon: c.lon,
    type: c.type,
    source: c.source,
    distanceKm: Math.round(c.distanceKm * 1e3) / 1e3,
    crosswalk: hasCrosswalk(c.name),
    media: await signUrl(origin, c.url, env.SIGNING_KEY, MEDIA_TTL_SEC)
  })));
  return json({ items: out, its: !!env.ITS_API_KEY }, 200, cors);
}
async function cachedJson(cacheKey, ttlSec, ctx, loader) {
  const cache = caches.default;
  const key = new Request("https://cache.internal/" + encodeURIComponent(cacheKey));
  const hit = await cache.match(key);
  if (hit)
    return hit.json();
  const data = await loader();
  ctx.waitUntil(cache.put(key, new Response(JSON.stringify(data), {
    headers: { "Content-Type": "application/json", "Cache-Control": `max-age=${ttlSec}` }
  })));
  return data;
}
function topisHeaders(env) {
  return {
    "Referer": env.TOPIS_BASE + "/map/openCctvMap.do",
    "X-Requested-With": "XMLHttpRequest",
    "User-Agent": UA
  };
}
async function topisNearby(lat, lon, radius, env, ctx) {
  const list = await cachedJson("topis-list", 30 * 60, ctx, async () => {
    const r = await fetch(env.TOPIS_BASE + "/map/cctv/selectCctvList.do", { headers: topisHeaders(env) });
    const j = await r.json();
    return (j.rows || []).map((x) => ({ camId: x.camId, camName: x.camName, lat: Number(x.lat), lng: Number(x.lng) }));
  });
  const near = list.filter((x) => x.camId && Number.isFinite(x.lat) && Number.isFinite(x.lng)).map((x) => ({ ...x, d: haversineKm(lat, lon, x.lat, x.lng) })).filter((x) => x.d <= radius).sort((a, b) => a.d - b.d).slice(0, MAX_TOPIS_DETAIL);
  const details = await Promise.all(near.map(
    (x) => cachedJson("topis-detail-" + x.camId, 60 * 60, ctx, async () => {
      const body = new URLSearchParams({ camId: x.camId, cctvSourceCd: "HP" });
      const r = await fetch(env.TOPIS_BASE + "/map/selectCctvInfo.do", {
        method: "POST",
        body,
        headers: { ...topisHeaders(env), "Content-Type": "application/x-www-form-urlencoded" }
      });
      const row = ((await r.json()).rows || [])[0] || {};
      return { url: row.hlsUrl || row.backupUrl || "" };
    }).catch(() => ({ url: "" }))
  ));
  return near.map((x, i) => ({
    id: "topis-" + x.camId,
    name: (x.camName || "").trim() || "\uC774\uB984 \uC5C6\uB294 CCTV",
    lat: x.lat,
    lon: x.lng,
    type: "1",
    source: "topis",
    url: details[i].url
  })).filter((c) => c.url);
}
async function itsNearby(lat, lon, radius, env) {
  if (!env.ITS_API_KEY)
    return [];
  const d = Math.min(radius, 500) / 111;
  const results = await Promise.all(["1", "2", "3"].map(async (type) => {
    const q = new URLSearchParams({
      apiKey: env.ITS_API_KEY,
      type: "its",
      cctvType: type,
      minX: lon - d,
      maxX: lon + d,
      minY: lat - d,
      maxY: lat + d,
      getType: "json"
    });
    const r = await fetch(env.ITS_BASE + "/cctvInfo?" + q);
    const j = await r.json();
    return (j.response && j.response.data || []).map((x) => ({
      id: "its-" + (x.roadsectionid || "") + "-" + (x.cctvname || ""),
      name: (x.cctvname || "").trim() || "\uC774\uB984 \uC5C6\uB294 CCTV",
      lat: Number(x.coordy),
      lon: Number(x.coordx),
      type: String(x.cctvtype || type),
      source: "its",
      url: String(x.cctvtype) === "3" && x.cctvurl2 ? x.cctvurl2 : x.cctvurl
    }));
  }));
  return results.flat().filter((c) => Number.isFinite(c.lat) && Number.isFinite(c.lon));
}
async function media(url, env, cors) {
  const target = await verifySignature(url.searchParams, env.SIGNING_KEY);
  if (!target)
    return json({ error: "invalid or expired signature" }, 403, cors);
  const upstream = await fetch(target, {
    headers: { "User-Agent": UA, "Referer": env.TOPIS_BASE + "/" },
    cf: { cacheTtl: 2 }
  });
  if (!upstream.ok)
    return json({ error: "upstream " + upstream.status }, 502, cors);
  const type = mediaTypeOf(target, upstream.headers.get("Content-Type"));
  if (type === "playlist") {
    const text = await upstream.text();
    const body = await rewritePlaylist(text, target, url.origin, env.SIGNING_KEY, MEDIA_TTL_SEC);
    return new Response(body, {
      headers: { ...cors, "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-store" }
    });
  }
  const headers = { ...cors, "Cache-Control": "no-store" };
  const ct = upstream.headers.get("Content-Type");
  if (ct)
    headers["Content-Type"] = ct;
  const len = upstream.headers.get("Content-Length");
  if (len)
    headers["Content-Length"] = len;
  return new Response(upstream.body, { headers });
}

// ../../../../tmp/claude-0/-home-user/5f09933e-8373-5809-aaf8-87e93893181d/scratchpad/wr/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
};
var middleware_ensure_req_body_drained_default = drainBody;

// ../../../../tmp/claude-0/-home-user/5f09933e-8373-5809-aaf8-87e93893181d/scratchpad/wr/node_modules/wrangler/templates/middleware/middleware-miniflare3-json-error.ts
function reduceError(e) {
  return {
    name: e?.name,
    message: e?.message ?? String(e),
    stack: e?.stack,
    cause: e?.cause === void 0 ? void 0 : reduceError(e.cause)
  };
}
var jsonError = async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } catch (e) {
    const error = reduceError(e);
    return Response.json(error, {
      status: 500,
      headers: { "MF-Experimental-Error-Stack": "true" }
    });
  }
};
var middleware_miniflare3_json_error_default = jsonError;

// .wrangler/tmp/bundle-ZNr0z8/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default,
  middleware_miniflare3_json_error_default
];
var middleware_insertion_facade_default = src_default;

// ../../../../tmp/claude-0/-home-user/5f09933e-8373-5809-aaf8-87e93893181d/scratchpad/wr/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}

// .wrangler/tmp/bundle-ZNr0z8/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof __Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  };
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      };
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = (request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    };
    #dispatcher = (type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    };
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default
};
//# sourceMappingURL=index.js.map
