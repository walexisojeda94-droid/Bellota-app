const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const ACCESS_TTL_SECONDS = 900;
const REFRESH_TTL_DAYS = 90;
const PAIRING_TTL_SECONDS = 600;
const OPENAI_MODEL = "gpt-realtime-2.1";
const REALTIME_MAX_OUTPUT_TOKENS = 1024;
const TURN_CREDENTIAL_TTL_SECONDS = 3600;
const GEMINI_LIVE_MODEL = "gemini-3.8-live";
const GEMINI_TOKEN_TTL_SECONDS = 1800;

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
const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 símbolos, sin 0/O/1/I
function randomPairingCode(length = 8) {
  let code = "";
  const data = new Uint8Array(1);
  while (code.length < length) {
    crypto.getRandomValues(data);
    if (data[0] < 256 - (256 % PAIRING_ALPHABET.length)) code += PAIRING_ALPHABET[data[0] % PAIRING_ALPHABET.length];
  }
  return code;
}
async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(x => x.toString(16).padStart(2, "0")).join("");
}
async function hmacKey(secret) {
  return crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}
async function deriveRefreshToken(deviceId, oldHash, secret) {
  const key = await hmacKey(secret);
  const signature = await crypto.subtle.sign("HMAC", key,
    new TextEncoder().encode("bellota-refresh-rotation:" + deviceId + ":" + oldHash));
  return b64url(new Uint8Array(signature));
}
async function issueAccessToken(deviceId, secret) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(new TextEncoder().encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const payload = b64url(new TextEncoder().encode(JSON.stringify({ sub: deviceId, iat: now, exp: now + ACCESS_TTL_SECONDS, iss: "bellota-backend", aud: "bellota-app" })));
  const input = header + "." + payload;
  const signature = await crypto.subtle.sign("HMAC", await hmacKey(secret), new TextEncoder().encode(input));
  return input + "." + b64url(new Uint8Array(signature));
}
function b64urlDecode(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  return atob(padded);
}
async function verifyAccessToken(token, secret) {
  try {
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const input = parts[0] + "." + parts[1];
    const signature = Uint8Array.from(b64urlDecode(parts[2]), c => c.charCodeAt(0));
    const valid = await crypto.subtle.verify("HMAC", await hmacKey(secret), signature, new TextEncoder().encode(input));
    if (!valid) return null;
    const payload = JSON.parse(b64urlDecode(parts[1]));
    if (payload.iss !== "bellota-backend" || payload.aud !== "bellota-app" || payload.exp <= Math.floor(Date.now() / 1000)) return null;
    return payload.sub;
  } catch { return null; }
}
async function readJson(request, maxBytes = 16384, optional = false) {
  const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  if (!contentType && optional && !request.body) return { ok: true, value: {} };
  if (contentType !== "application/json") return { ok: false, status: 415, code: "unsupported_media_type", message: "El cuerpo debe enviarse como JSON." };
  const declaredLength = Number(request.headers.get("content-length") || 0);
  if (declaredLength > maxBytes) return { ok: false, status: 413, code: "request_too_large", message: "La solicitud supera el tamaño permitido." };
  if (!request.body) return optional
    ? { ok: true, value: {} }
    : { ok: false, status: 400, code: "invalid_json", message: "Falta el cuerpo JSON." };
  try {
    const reader = request.body.getReader();
    const chunks = [];
    let total = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return { ok: false, status: 413, code: "request_too_large", message: "La solicitud supera el tamaño permitido." };
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    const parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
      return { ok: false, status: 400, code: "invalid_json", message: "El JSON debe contener un objeto." };
    return { ok: true, value: parsed };
  } catch {
    return { ok: false, status: 400, code: "invalid_json", message: "El cuerpo JSON no es válido." };
  }
}
function invalidBody(parsed) {
  return fail(parsed.code, parsed.status, parsed.message);
}
async function cleanupExpiredRecords(env, now) {
  // Pairing codes are short-lived; retain a seven-day audit window.
  await env.DB.prepare(
    "DELETE FROM pairing_codes WHERE expires_at < ? AND (consumed_at IS NULL OR consumed_at < ?)"
  ).bind(now - 7 * 86400, now - 7 * 86400).run();
  // Keep revoked/expired device records for 180 days before physical cleanup.
  await env.DB.prepare(
    "DELETE FROM devices WHERE (revoked_at IS NOT NULL AND revoked_at < ?) OR " +
    "(revoked_at IS NULL AND refresh_expires_at < ?)"
  ).bind(now - 180 * 86400, now - 180 * 86400).run();
}
async function healthCheck(env) {
  const configured = Boolean(env.DB && env.ADMIN_TOKEN && env.JWT_SECRET && env.OPENAI_API_KEY);
  let database = false;
  if (env.DB) {
    try {
      await env.DB.prepare(
        "SELECT id, device_name, refresh_token_hash, previous_refresh_token_hash, " +
        "previous_refresh_valid_until, created_at, refresh_expires_at, last_seen_at, revoked_at " +
        "FROM devices LIMIT 0"
      ).first();
      await env.DB.prepare(
        "SELECT code_hash, created_at, expires_at, consumed_at, device_id FROM pairing_codes LIMIT 0"
      ).first();
      database = true;
    } catch { database = false; }
  }
  const ok = configured && database;
  return json({
    ok,
    service: "bellota-openai-realtime",
    checks: { database, configuration: configured, gemini: Boolean(env.GEMINI_API_KEY) }
  }, ok ? 200 : 503);
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
  const now = Math.floor(Date.now() / 1000);
  await cleanupExpiredRecords(env, now);
  const code = randomPairingCode();
  const codeHash = await sha256(code);
  const expiresAt = now + PAIRING_TTL_SECONDS;
  await env.DB.prepare("INSERT INTO pairing_codes (code_hash, created_at, expires_at) VALUES (?, ?, ?)")
    .bind(codeHash, Math.floor(Date.now() / 1000), expiresAt).run();
  return json({ code, expires_at: expiresAt, expires_in_seconds: PAIRING_TTL_SECONDS });
}

async function pairDevice(request, env) {
  if (!env.JWT_SECRET) return fail("server_not_configured", 503, "El backend no está configurado.");
  const parsed = await readJson(request);
  if (!parsed.ok) return invalidBody(parsed);
  const body = parsed.value;
  if (typeof body.code !== "string") return fail("invalid_request", 400, "Falta el código de vinculación.");
  if (body.device_name !== undefined && (typeof body.device_name !== "string" || body.device_name.length > 80))
    return fail("invalid_request", 400, "El nombre del dispositivo debe ser texto de hasta 80 caracteres.");
  const code = body.code.trim().toUpperCase();
  if (!/^[A-Z0-9_-]{6,32}$/.test(code)) return fail("invalid_code", 400, "El código no es válido.");
  const now = Math.floor(Date.now() / 1000);
  const codeHash = await sha256(code);
  const deviceId = crypto.randomUUID();
  const refreshToken = randomToken(32);
  const refreshHash = await sha256(refreshToken);
  const label = typeof body.device_name === "string" ? body.device_name.trim().slice(0, 80) : "Bellota Android";
  // D1 batch is transactional: code consumption and device creation commit or roll back together.
  const results = await env.DB.batch([
    env.DB.prepare(
      "UPDATE pairing_codes SET consumed_at = ?, device_id = ? WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > ?"
    ).bind(now, deviceId, codeHash, now),
    env.DB.prepare(
      "INSERT INTO devices (id, device_name, refresh_token_hash, created_at, refresh_expires_at) " +
      "SELECT ?, ?, ?, ?, ? WHERE EXISTS " +
      "(SELECT 1 FROM pairing_codes WHERE code_hash = ? AND consumed_at = ? AND device_id = ?)"
    ).bind(deviceId, label || "Bellota Android", refreshHash, now,
      now + REFRESH_TTL_DAYS * 86400, codeHash, now, deviceId)
  ]);
  if (!results[0]?.meta?.changes || !results[1]?.meta?.changes)
    return fail("pairing_code_invalid", 401, "El código venció o ya fue utilizado.");
  return json({ device_id: deviceId, refresh_token: refreshToken, access_token: await issueAccessToken(deviceId, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}

async function refreshDevice(request, env) {
  if (!env.JWT_SECRET) return fail("server_not_configured", 503, "El backend no está configurado.");
  const parsed = await readJson(request);
  if (!parsed.ok) return invalidBody(parsed);
  const body = parsed.value;
  if (typeof body.refresh_token !== "string" || body.refresh_token.length < 32 || body.refresh_token.length > 4096)
    return fail("invalid_request", 400, "Token de renovación inválido.");
  const oldHash = await sha256(body.refresh_token);
  const now = Math.floor(Date.now() / 1000);
  const device = await env.DB.prepare(
    "SELECT id, refresh_token_hash, previous_refresh_token_hash, previous_refresh_valid_until " +
    "FROM devices WHERE (refresh_token_hash = ? OR " +
    "(previous_refresh_token_hash = ? AND previous_refresh_valid_until > ?)) " +
    "AND revoked_at IS NULL AND refresh_expires_at > ?"
  ).bind(oldHash, oldHash, now, now).first();
  if (!device) return fail("refresh_invalid", 401, "La vinculación venció o fue revocada. Es necesario volver a vincular Bellota.");

  // Deterministic rotation lets a client retry the previous token after a lost HTTP response.
  const nextRefresh = await deriveRefreshToken(device.id, oldHash, env.JWT_SECRET);
  const nextHash = await sha256(nextRefresh);
  if (device.refresh_token_hash === nextHash) {
    return json({ refresh_token: nextRefresh, access_token: await issueAccessToken(device.id, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
  }
  if (device.refresh_token_hash !== oldHash)
    return fail("refresh_replayed", 401, "El token anterior ya no puede recuperarse. Volvé a vincular el dispositivo.");

  const updated = await env.DB.prepare(
    "UPDATE devices SET previous_refresh_token_hash = refresh_token_hash, " +
    "previous_refresh_valid_until = ?, refresh_token_hash = ?, refresh_expires_at = ? " +
    "WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL RETURNING id"
  ).bind(now + 120, nextHash, now + REFRESH_TTL_DAYS * 86400, device.id, oldHash).first();
  if (!updated) {
    const latest = await env.DB.prepare(
      "SELECT refresh_token_hash FROM devices WHERE id = ? AND revoked_at IS NULL"
    ).bind(device.id).first();
    if (!latest || latest.refresh_token_hash !== nextHash)
      return fail("refresh_replayed", 401, "El token ya fue renovado. Volvé a vincular el dispositivo si el problema continúa.");
  }
  return json({ refresh_token: nextRefresh, access_token: await issueAccessToken(device.id, env.JWT_SECRET), access_expires_in: ACCESS_TTL_SECONDS });
}

async function revokeDevice(request, env) {
  const parsed = await readJson(request);
  if (!parsed.ok) return invalidBody(parsed);
  const body = parsed.value;
  if (typeof body.refresh_token !== "string" || body.refresh_token.length < 32 || body.refresh_token.length > 4096)
    return fail("invalid_request", 400, "Falta la credencial del dispositivo.");
  const tokenHash = await sha256(body.refresh_token);
  const now = Math.floor(Date.now() / 1000);
  // Keep this endpoint idempotent so a lost response can safely be retried.
  const device = await env.DB.prepare(
    "SELECT id FROM devices WHERE refresh_token_hash = ? OR " +
    "(previous_refresh_token_hash = ? AND previous_refresh_valid_until > ?)"
  ).bind(tokenHash, tokenHash, now).first();
  if (!device) return fail("device_unauthorized", 401, "La credencial del dispositivo no es válida.");
  await env.DB.prepare("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?) WHERE id = ?")
    .bind(now, device.id).run();
  return json({ ok: true, revoked: true });
}

async function createTurnIceServers(env) {
  // TURN keys remain server-side. Only short-lived credentials reach the Android client.
  if (!env.TURN_KEY_ID && !env.TURN_KEY_API_TOKEN) return [];
  if (!env.TURN_KEY_ID || !env.TURN_KEY_API_TOKEN) throw new Error("TURN está configurado de forma incompleta.");
  const endpoint = "https://rtc.live.cloudflare.com/v1/turn/keys/" +
    encodeURIComponent(env.TURN_KEY_ID) + "/credentials/generate-ice-servers";
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      authorization: "Bearer " + env.TURN_KEY_API_TOKEN,
      "content-type": "application/json"
    },
    body: JSON.stringify({ ttl: TURN_CREDENTIAL_TTL_SECONDS })
  });
  if (!response.ok) throw new Error("Cloudflare TURN HTTP " + response.status);
  const data = await response.json();
  if (!Array.isArray(data.iceServers) || data.iceServers.length === 0)
    throw new Error("Cloudflare TURN devolvió una lista ICE vacía.");
  // With non-trickle SDP, discard port 53 candidates because they can stall ICE gathering.
  const filtered = data.iceServers.map((server) => {
    if (!server || !server.urls) return null;
    const sourceUrls = Array.isArray(server.urls) ? server.urls : [server.urls];
    const urls = sourceUrls.filter((url) => typeof url === "string" && !/:53(?:\?|\/|$)/.test(url));
    if (urls.length === 0) return null;
    const result = { urls };
    if (typeof server.username === "string" && server.username) result.username = server.username;
    if (typeof server.credential === "string" && server.credential) result.credential = server.credential;
    const hasTurn = urls.some((url) => url.startsWith("turn:") || url.startsWith("turns:"));
    if (hasTurn && (!result.username || !result.credential))
      throw new Error("Cloudflare TURN devolvió servidores sin credenciales.");
    return result;
  }).filter(Boolean);
  if (filtered.length === 0) throw new Error("Cloudflare TURN no devolvió servidores ICE utilizables.");
  return filtered;
}
async function createRealtimeSecret(request, env) {
  if (!env.OPENAI_API_KEY) return fail("provider_not_configured", 503, "OpenAI todavía no está configurado en el backend.");
  const deviceId = await authenticatedDevice(request, env);
  if (!deviceId) return fail("device_unauthorized", 401, "La vinculación del dispositivo no es válida.");
  const parsed = await readJson(request, 16384, true);
  if (!parsed.ok) return invalidBody(parsed);
  const body = parsed.value;
  if (body.instructions !== undefined && (typeof body.instructions !== "string" || body.instructions.length > 12000))
    return fail("invalid_request", 400, "Las instrucciones deben ser texto de hasta 12.000 caracteres.");
  if (body.voice !== undefined && (typeof body.voice !== "string" || !/^[a-z0-9_-]{2,32}$/i.test(body.voice)))
    return fail("invalid_request", 400, "La voz solicitada no es válida.");
  const instructions = typeof body.instructions === "string" ? body.instructions.trim() : "";
  const voice = body.voice || "marin";
  const session = {
    type: "realtime",
    model: OPENAI_MODEL,
    expires_at: Math.floor(Date.now() / 1000) + 2700,
    output_modalities: ["audio"],
    instructions: instructions || "Sos Bellota, una compañera virtual conversacional. Hablá en español rioplatense natural.",
    audio: {
      input: { turn_detection: { type: "server_vad" } },
      output: { voice }
    },
    max_output_tokens: REALTIME_MAX_OUTPUT_TOKENS,
    truncation: "auto"
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
  let iceServers = [];
  try {
    iceServers = await createTurnIceServers(env);
  } catch (error) {
    return fail("turn_unavailable", 503, "No se pudieron preparar los servidores TURN. Reintentá en unos segundos.");
  }
  await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(Math.floor(Date.now() / 1000), deviceId).run();
  return json({
    value: data.value,
    expires_at: data.expires_at,
    model: OPENAI_MODEL,
    max_output_tokens: REALTIME_MAX_OUTPUT_TOKENS,
    session_max_duration_seconds: 2700,
    session_expires_at: session.expires_at,
    ice_servers: iceServers
  });
}


function normalizeGeminiSchema(schema) {
  if (Array.isArray(schema)) return schema.map(normalizeGeminiSchema);
  if (!schema || typeof schema !== "object") return schema;
  const result = {};
  for (const [key, value] of Object.entries(schema)) {
    result[key] = key === "type" && typeof value === "string"
      ? value.toUpperCase()
      : normalizeGeminiSchema(value);
  }
  return result;
}

async function createGeminiLiveToken(request, env) {
  if (!env.GEMINI_API_KEY)
    return fail("provider_not_configured", 503, "Gemini Live todavía no está configurado en el backend.");
  const deviceId = await authenticatedDevice(request, env);
  if (!deviceId) return fail("device_unauthorized", 401, "La vinculación del dispositivo no es válida.");
  const parsed = await readJson(request, 16384, true);
  if (!parsed.ok) return invalidBody(parsed);
  const body = parsed.value;
  if (body.instructions !== undefined && (typeof body.instructions !== "string" || body.instructions.length > 12000))
    return fail("invalid_request", 400, "Las instrucciones deben ser texto de hasta 12.000 caracteres.");
  if (body.voice !== undefined && (typeof body.voice !== "string" || body.voice.length > 32))
    return fail("invalid_request", 400, "La voz solicitada no es válida.");
  if (body.tools_json !== undefined && (typeof body.tools_json !== "string" || body.tools_json.length > 12000))
    return fail("invalid_request", 400, "Las herramientas superan el tamaño permitido.");

  let sourceTools = [];
  try {
    sourceTools = body.tools_json ? JSON.parse(body.tools_json) : [];
    if (!Array.isArray(sourceTools)) throw new Error("tools must be an array");
  } catch {
    return fail("invalid_request", 400, "La definición de herramientas no es JSON válido.");
  }
  const functionDeclarations = [];
  for (const item of sourceTools.slice(0, 32)) {
    if (!item || typeof item !== "object") continue;
    const name = typeof item.name === "string" ? item.name.trim() : "";
    if (!/^[a-zA-Z0-9_]{1,64}$/.test(name)) continue;
    const parameters = item.parameters && typeof item.parameters === "object"
      ? item.parameters
      : (item.inputSchema && typeof item.inputSchema === "object"
        ? item.inputSchema : { type: "OBJECT", properties: {} });
    functionDeclarations.push({
      name,
      description: typeof item.description === "string" ? item.description.slice(0, 1024) : "",
      parameters: normalizeGeminiSchema(parameters)
    });
  }

  const now = Date.now();
  const instructions = typeof body.instructions === "string" && body.instructions.trim()
    ? body.instructions.trim()
    : "Sos Bellota, una compañera virtual conversacional. Hablá en español rioplatense natural. No cambies de idioma por la configuración del dispositivo.";
  const voice = "Puck";
  const config = {
    responseModalities: ["AUDIO"],
    maxOutputTokens: 1024,
    speechConfig: {
      languageCode: "es-AR",
      voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } }
    },
    inputAudioTranscription: {},
    outputAudioTranscription: {},
    sessionResumption: {},
    systemInstruction: { parts: [{ text: instructions }] }
  };
  if (functionDeclarations.length) config.tools = [{ functionDeclarations }];

  const tokenRequest = {
    uses: 1,
    expireTime: new Date(now + GEMINI_TOKEN_TTL_SECONDS * 1000).toISOString(),
    newSessionExpireTime: new Date(now + 60 * 1000).toISOString(),
    liveConnectConstraints: {
      model: "models/" + GEMINI_LIVE_MODEL,
      config
    }
  };
  let response;
  try {
    response = await fetch("https://generativelanguage.googleapis.com/v1alpha/auth_tokens", {
      method: "POST",
      headers: {
        "x-goog-api-key": env.GEMINI_API_KEY,
        "content-type": "application/json"
      },
      body: JSON.stringify(tokenRequest)
    });
  } catch {
    return fail("provider_network", 502, "No se pudo contactar con el servicio de credenciales de Gemini.");
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 || response.status === 403)
      return fail("provider_authentication", 502, "Gemini rechazó la autenticación del backend.");
    if (response.status === 429)
      return fail("provider_rate_limit", 429, "Gemini limitó temporalmente las solicitudes.");
    if (response.status === 402)
      return fail("provider_quota", 402, "La cuenta de Gemini no tiene cuota disponible.");
    return fail("provider_error", 502, "Gemini no pudo emitir la credencial temporal.");
  }
  const token = typeof data.name === "string" ? data.name : "";
  if (!/^auth_?Tokens?\/[A-Za-z0-9_-]+$/i.test(token))
    return fail("invalid_response", 502, "Gemini devolvió una credencial temporal incompleta.");
  await env.DB.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?")
    .bind(Math.floor(Date.now() / 1000), deviceId).run();
  return json({
    token,
    model: GEMINI_LIVE_MODEL,
    expires_at: Math.floor(now / 1000) + GEMINI_TOKEN_TTL_SECONDS,
    new_session_expires_at: Math.floor(now / 1000) + 60,
    websocket_url: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      if (request.method !== "GET") return new Response(null, { status: 405, headers: { ...JSON_HEADERS, allow: "GET" } });
      return healthCheck(env);
    }
    const routes = {
      "/admin/pairing-code": () => createPairingCode(request, env),
      "/v1/pair": () => pairDevice(request, env),
      "/v1/auth/refresh": () => refreshDevice(request, env),
      "/v1/device/revoke": () => revokeDevice(request, env),
      "/v1/realtime/client-secret": () => createRealtimeSecret(request, env),
      "/v1/gemini/live-token": () => createGeminiLiveToken(request, env)
    };
    if (routes[url.pathname]) {
      if (request.method !== "POST") return new Response(null, { status: 405, headers: { ...JSON_HEADERS, allow: "POST" } });
      return routes[url.pathname]();
    }
    return fail("not_found", 404, "Ruta no encontrada.");
  }
};
