const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store, no-cache, must-revalidate",
  "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer"
};
const ACCESS_TTL_SECONDS = 900;
const REFRESH_TTL_DAYS = 90;
const PAIRING_TTL_SECONDS = 600;
const OPENAI_MODEL = "gpt-realtime-2.1";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
function fail(code, status, message) { return json({ error: code, message }, status); }
function b64url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function randomToken(bytes = 32) {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return b64url(data);
}
async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}
async function hmacKey(secret) {
  return crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
async function issueAccessToken(deviceId, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(new TextEncoder().encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const payload = b64url(new TextEncoder().encode(JSON.stringify({ sub: deviceId, iat: now, exp: now + ACCESS_TTL_SECONDS, iss: "bellota-backend", aud: "bellota-app" })));
  const input = header + "." + payload;
  const signature = await crypto.subtle.sign("HMAC", await hmacKey(secret), new TextEncoder().encode(input));
  return input + "." + b64url(new Uint8Array(signature));
}
function decodeB64url(value) {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  return atob(base64 + "=".repeat((4 - base64.length % 4) % 4));
}
async function verifyAccessToken(token, secret) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const input = parts[0] + "." + parts[1];
    const signature = Uint8Array.from(decodeB64url(parts[2]), c => c.charCodeAt(0));
    const valid = await crypto.subtle.verify("HMAC", await hmacKey(secret), signature, new TextEncoder().encode(input));
    if (!valid) return null;
    const payload = JSON.parse(decodeB64url(parts[1]));
    if (payload.iss !== "bellota-backend" || payload.aud !== "bellota-app" ||
        typeof payload.sub !== "string" || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload.sub;
  } catch { return null; }
}
async function readJson(request) {
  if (!request.headers.get("content-type")?.toLowerCase().includes("application/json")) return null;
  try { return await request.json(); } catch { return null; }
}
function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : "";
}
function clientIp(request) { return request.headers.get("CF-Connecting-IP") || "unknown"; }
async function rateLimit(env, key, max, windowSeconds) {
  const now = Math.floor(Date.now() / 1000);
  const windowStart = Math.floor(now / windowSeconds) * windowSeconds;
  const result = await env.DB.prepare(
    "INSERT INTO rate_limits (bucket_key, window_start, hit_count) VALUES (?, ?, 1) " +
    "ON CONFLICT(bucket_key) DO UPDATE SET " +
    "hit_count = CASE WHEN rate_limits.window_start = excluded.window_start THEN rate_limits.hit_count + 1 ELSE 1 END, " +
    "window_start = excluded.window_start RETURNING hit_count"
  ).bind(await sha256(key), windowStart).first();
  return !!result && result.hit_count <= max;
}
async function authenticatedDevice(request, env) {
  if (!env.JWT_SECRET) return null;
  const token = bearer(request);
  if (!token) return null;
  const deviceId = await verifyAccessToken(token, env.JWT_SECRET);
  if (!deviceId) return null;
  const device = await env.DB.prepare("SELECT id FROM devices WHERE id = ? AND revoked_at IS NULL AND refresh_expires_at > ?")
    .bind(deviceId, Math.floor(Date.now() / 1000)).first();
  return device ? device.id : null;
}
async function createPairingCode(request, env) {
  if (!env.ADMIN_TOKEN || !env.JWT_SECRET) return fail("server_not_configured", 503, "El backend no está configurado.");
  if (!(await rateLimit(env, "admin:" + clientIp(request), 5, 300)))
    return fail("rate_limited", 429, "Demasiadas solicitudes de códigos. Esperá unos minutos.");
  const supplied = bearer(request);
  if (!supplied || supplied.length !== env.ADMIN_TOKEN.length) return fail("unauthorized", 401, "No autorizado.");
  const key = await hmacKey(env.ADMIN_TOKEN);
  const a = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(supplied)));
  const b = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(env.ADMIN_TOKEN)));
  let difference = a.length ^ b.length;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  if (difference !== 0) return fail("unauthorized", 401, "No autorizado.");
  const code = randomToken(6).replace(/[-_]/g, "").toUpperCase();
  const now = Math.floor(Date.now() / 1000);
  await env.DB.prepare("INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)")
    .bind(await sha256(code), now, now + PAIRING_TTL_SECONDS).run();
  await env.DB.prepare("DELETE FROM pairing_codes WHERE expires_at < ? OR consumed_at IS NOT NULL").bind(now - 86400).run();
  return json({ code, expires_at: now + PAIRING_TTL_SECONDS, expires_in_seconds: PAIRING_TTL_SECONDS });
}
async function pairDevice(request, env) {
  if (!env.JWT_SECRET) return fail("server_not_configured", 503, "El backend no está configurado.");
  if (!(await rateLimit(env, "pair:" + clientIp(request), 10, 600)))
    return fail("rate_limited", 429, "Demasiados intentos de vinculación. Esperá unos minutos.");
  const body = await readJson(request);
  if (!body || typeof body.code !== "string") return fail("invalid_request", 400, "Falta el código de vinculación.");
  const code = body.code.trim().toUpperCase();
  if (!/^[A-Z0-9_-]{6,32}$/.test(code)) return fail("invalid_code", 400, "El código no es válido.");
  const now = Math.floor(Date.now() / 1000);
  const consumed = await env.DB.prepare(
    "UPDATE pairing_codes SET consumed_at = ? WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > ? RETURNING id"
  ).bind(now, await sha256(code), now).first();
  if (!consumed) return fail("pairing_code_invalid", 401, "El código venció o ya fue utilizado.");
  const deviceId = crypto.randomUUID();
  const refreshToken = randomToken(32);
  const refreshHash = await sha256(refreshToken);
  const label = typeof body.device_name === "string" ? body.device_name.trim().slice(0, 80) : "Bellota Android";
  await env.DB.prepare(
    "INSERT INTO devices (id, device_name, refresh_token_hash, created_at, refresh_expires_at) VALUES (?, ?, ?, ?, ?)"
  ).bind(deviceId, label || "Bellota Android", refreshHash, now, now + REFRESH_TTL_DAYS * 86400).run();
  await env.DB.prepare("INSERT INTO refresh_tokens (token_hash, device_id, created_at, status) VALUES (?, ?, ?, 'active')")
    .bind(refreshHash, deviceId, now).run();
  return json({ device_id: deviceId, refresh_token: refreshToken, access_token: await issueAccessToken(deviceId, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}
async function refreshDevice(request, env) {
  const body = await readJson(request);
  if (!body || typeof body.refresh_token !== "string" || body.refresh_token.length < 32 || body.refresh_token.length > 256)
    return fail("invalid_request", 400, "Token de renovación inválido.");
  if (!(await rateLimit(env, "refresh:" + clientIp(request), 30, 300)))
    return fail("rate_limited", 429, "Demasiadas renovaciones. Esperá unos minutos.");
  const now = Math.floor(Date.now() / 1000);
  const oldHash = await sha256(body.refresh_token);
  const device = await env.DB.prepare(
    "SELECT id FROM devices WHERE refresh_token_hash = ? AND revoked_at IS NULL AND refresh_expires_at > ?"
  ).bind(oldHash, now).first();
  if (!device) {
    const used = await env.DB.prepare("SELECT device_id FROM refresh_tokens WHERE token_hash = ? AND status = 'rotated'").bind(oldHash).first();
    if (used) {
      await env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").bind(now, used.device_id).run();
      await env.DB.prepare("UPDATE refresh_tokens SET status = 'revoked' WHERE device_id = ?").bind(used.device_id).run();
      return fail("refresh_replayed", 401, "Se detectó la reutilización de un token anterior. El dispositivo fue revocado y debe vincularse nuevamente.");
    }
    return fail("refresh_invalid", 401, "La vinculación venció o fue revocada. Es necesario volver a vincular Bellota.");
  }
  const nextRefresh = randomToken(32);
  const nextHash = await sha256(nextRefresh);
  const nextExpiry = now + REFRESH_TTL_DAYS * 86400;
  const results = await env.DB.batch([
    env.DB.prepare("INSERT INTO refresh_tokens (token_hash, device_id, created_at, status) SELECT ?, id, ?, 'active' FROM devices WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL")
      .bind(nextHash, now, device.id, oldHash),
    env.DB.prepare("UPDATE devices SET refresh_token_hash = ?, refresh_expires_at = ? WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL")
      .bind(nextHash, nextExpiry, device.id, oldHash),
    env.DB.prepare("UPDATE refresh_tokens SET status = 'rotated', replaced_by_hash = ?, rotated_at = ? WHERE token_hash = ? AND status = 'active'")
      .bind(nextHash, now, oldHash)
  ]);
  if (!results[1]?.meta || results[1].meta.changes !== 1) {
    const used = await env.DB.prepare("SELECT device_id FROM refresh_tokens WHERE token_hash = ? AND status = 'rotated'").bind(oldHash).first();
    if (used) {
      await env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").bind(now, used.device_id).run();
      await env.DB.prepare("UPDATE refresh_tokens SET status = 'revoked' WHERE device_id = ?").bind(used.device_id).run();
      return fail("refresh_replayed", 401, "Se detectó la reutilización de un token anterior. El dispositivo fue revocado.");
    }
    return fail("refresh_invalid", 401, "No se pudo renovar la vinculación.");
  }
  return json({ refresh_token: nextRefresh, access_token: await issueAccessToken(device.id, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}
async function revokeDevice(request, env) {
  const deviceId = await authenticatedDevice(request, env);
  if (!deviceId) return fail("device_unauthorized", 401, "La vinculación del dispositivo no es válida.");
  const now = Math.floor(Date.now() / 1000);
  await env.DB.batch([
    env.DB.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL").bind(now, deviceId),
    env.DB.prepare("UPDATE refresh_tokens SET status = 'revoked' WHERE device_id = ?").bind(deviceId)
  ]);
  return json({ revoked: true });
}
async function createRealtimeSecret(request, env) {
  if (!env.OPENAI_API_KEY) return fail("provider_not_configured", 503, "OpenAI todavía no está configurado en el backend.");
  const deviceId = await authenticatedDevice(request, env);
  if (!deviceId) return fail("device_unauthorized", 401, "La vinculación del dispositivo no es válida.");
  if (!(await rateLimit(env, "realtime:" + deviceId, 20, 300)))
    return fail("rate_limited", 429, "Se alcanzó el límite temporal de sesiones de voz.");
  const body = await readJson(request) || {};
  const instructions = typeof body.instructions === "string" ? body.instructions.trim().slice(0, 12000) : "";
  const voice = typeof body.voice === "string" && /^[a-z0-9_-]{2,32}$/i.test(body.voice) ? body.voice : "marin";
  const session = {
    type: "realtime",
    model: OPENAI_MODEL,
    output_modalities: ["audio"],
    instructions: instructions || "Sos Bellota, una compañera virtual conversacional. Hablá en español rioplatense natural.",
    audio: {
      input: {
        transcription: { model: "gpt-4o-mini-transcribe", language: "es" },
        turn_detection: { type: "server_vad" }
      },
      output: { voice }
    }
  };
  let response;
  try {
    response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: { authorization: "Bearer " + env.OPENAI_API_KEY, "content-type": "application/json" },
      body: JSON.stringify({ expires_after: { anchor: "created_at", seconds: 600 }, session })
    });
  } catch {
    return fail("provider_network", 502, "No se pudo conectar con OpenAI.");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) return fail("provider_authentication", 502, "El backend no pudo autenticar con OpenAI.");
    if (response.status === 429) return fail("provider_rate_limit", 429, "OpenAI limitó temporalmente las solicitudes.");
    if (response.status === 402) return fail("provider_quota", 402, "La cuenta de OpenAI no tiene crédito o cuota disponible.");
    return fail("provider_error", 502, "OpenAI no pudo crear la credencial temporal.");
  }
  await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(Math.floor(Date.now() / 1000), deviceId).run();
  return json({ value: data.value, expires_at: data.expires_at, model: OPENAI_MODEL });
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health")
      return json({ ok: true, service: "bellota-openai-realtime", configured: !!(env.DB && env.JWT_SECRET && env.ADMIN_TOKEN && env.OPENAI_API_KEY) });
    if (request.method === "POST" && url.pathname === "/admin/pairing-code") return createPairingCode(request, env);
    if (request.method === "POST" && url.pathname === "/v1/pair") return pairDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/auth/refresh") return refreshDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/devices/revoke") return revokeDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/realtime/client-secret") return createRealtimeSecret(request, env);
    return fail("not_found", 404, "Ruta no encontrada.");
  }
};
