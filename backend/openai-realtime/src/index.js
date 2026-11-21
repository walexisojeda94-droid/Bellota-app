const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
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
  return btoa(binary).replace(/=/g, "").replaceAll("+", "-").replaceAll("/", "_");
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
async function verifyAccessToken(token, secret) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const input = parts[0] + "." + parts[1];
  const signature = Uint8Array.from(atob(parts[2].replace(/-/g, "+").replace(/_/g, "/") + "===".slice((parts[2].length + 3) % 4)), c => c.charCodeAt(0));
  const valid = await crypto.subtle.verify("HMAC", await hmacKey(secret), signature, new TextEncoder().encode(input));
  if (!valid) return null;
  try {
    const payload = JSON.parse(atob(parts[1].replace(/-/g, "+").replace(/_/g, "/") + "===".slice((parts[1].length + 3) % 4)));
    if (payload.iss !== "bellota-backend" || payload.aud !== "bellota-app" || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload.sub;
  } catch { return null; }
}
async function readJson(request) {
  try { return await request.json(); } catch { return null; }
}
function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : "";
}
async function authenticatedDevice(request, env) {
  const token = bearer(request);
  if (!token) return null;
  const deviceId = await verifyAccessToken(token, env.JWT_SECRET);
  if (!deviceId) return null;
  const device = await env.DB.prepare("SELECT id FROM devices WHERE id = ? AND revoked_at IS NULL").bind(deviceId).first();
  return device ? device.id : null;
}

async function createPairingCode(request, env) {
  if (!env.ADMIN_TOKEN || !env.JWT_SECRET) return fail("server_not_configured", 503, "El backend no está configurado.");
  const supplied = bearer(request);
  const key = await hmacKey(env.ADMIN_TOKEN);
  const suppliedMac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(supplied)));
  const expectedMac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(env.ADMIN_TOKEN)));
  let difference = suppliedMac.length ^ expectedMac.length;
  for (let i = 0; i < Math.min(suppliedMac.length, expectedMac.length); i++) difference |= suppliedMac[i] ^ expectedMac[i];
  if (!supplied || difference !== 0) return fail("unauthorized", 401, "No autorizado.");
  const code = randomToken(6).replace(/[-_]/g, "").toUpperCase();
  const codeHash = await sha256(code);
  const expiresAt = Math.floor(Date.now() / 1000) + PAIRING_TTL_SECONDS;
  await env.DB.prepare("INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)")
    .bind(codeHash, Math.floor(Date.now() / 1000), expiresAt).run();
  return json({ code, expires_at: expiresAt, expires_in_seconds: PAIRING_TTL_SECONDS });
}

async function pairDevice(request, env) {
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
  return json({ device_id: deviceId, refresh_token: refreshToken, access_token: await issueAccessToken(deviceId, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}

async function refreshDevice(request, env) {
  const body = await readJson(request);
  if (!body || typeof body.refresh_token !== "string" || body.refresh_token.length < 32)
    return fail("invalid_request", 400, "Token de renovación inválido.");
  const oldHash = await sha256(body.refresh_token);
  const device = await env.DB.prepare(
    "SELECT id FROM devices WHERE refresh_token_hash = ? AND revoked_at IS NULL AND refresh_expires_at > ?"
  ).bind(oldHash, Math.floor(Date.now() / 1000)).first();
  if (!device) return fail("refresh_invalid", 401, "La vinculación venció o fue revocada. Es necesario volver a vincular Bellota.");
  const nextRefresh = randomToken(32);
  const nextHash = await sha256(nextRefresh);
  const updated = await env.DB.prepare(
    "UPDATE devices SET refresh_token_hash = ?, refresh_expires_at = ? WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL RETURNING id"
  ).bind(nextHash, Math.floor(Date.now() / 1000) + REFRESH_TTL_DAYS * 86400, device.id, oldHash).first();
  if (!updated) return fail("refresh_replayed", 401, "El token ya fue renovado. Solicitá una nueva vinculación si el problema continúa.");
  return json({ refresh_token: nextRefresh, access_token: await issueAccessToken(device.id, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}

async function createRealtimeSecret(request, env) {
  if (!env.OPENAI_API_KEY) return fail("provider_not_configured", 503, "OpenAI todavía no está configurado en el backend.");
  const deviceId = await authenticatedDevice(request, env);
  if (!deviceId) return fail("device_unauthorized", 401, "La vinculación del dispositivo no es válida.");
  const body = await readJson(request) || {};
  const instructions = typeof body.instructions === "string" ? body.instructions.trim().slice(0, 12000) : "";
  const voice = typeof body.voice === "string" && /^[a-z0-9_-]{2,32}$/i.test(body.voice) ? body.voice : "marin";
  const session = {
    type: "realtime",
    model: OPENAI_MODEL,
    output_modalities: ["audio"],
    instructions: instructions || "Sos Bellota, una compañera virtual conversacional. Hablá en español rioplatense natural.",
    audio: {
      input: { turn_detection: { type: "server_vad" } },
      output: { voice }
    }
  };
  const response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
    method: "POST",
    headers: { authorization: "Bearer " + env.OPENAI_API_KEY, "content-type": "application/json" },
    body: JSON.stringify({ expires_after: { anchor: "created_at", seconds: 600 }, session })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const status = response.status;
    if (status === 401 || status === 403) return fail("provider_authentication", 502, "El backend no pudo autenticar con OpenAI.");
    if (status === 429) return fail("provider_rate_limit", 429, "OpenAI limitó temporalmente las solicitudes.");
    if (status === 402) return fail("provider_quota", 402, "La cuenta de OpenAI no tiene crédito o cuota disponible.");
    return fail("provider_error", 502, "OpenAI no pudo crear la credencial temporal.");
  }
  await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(Math.floor(Date.now() / 1000), deviceId).run();
  return json({ value: data.value, expires_at: data.expires_at, model: OPENAI_MODEL });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true, service: "bellota-openai-realtime" });
    if (request.method === "POST" && url.pathname === "/admin/pairing-code") return createPairingCode(request, env);
    if (request.method === "POST" && url.pathname === "/v1/pair") return pairDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/auth/refresh") return refreshDevice(request, env);
    if (request.method === "POST" && url.pathname === "/v1/realtime/client-secret") return createRealtimeSecret(request, env);
    return fail("not_found", 404, "Ruta no encontrada.");
  }
};
