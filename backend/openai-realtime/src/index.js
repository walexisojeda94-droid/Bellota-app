const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const OPENAI_MODEL = "gpt-realtime-2.1";
const REALTIME_MAX_OUTPUT_TOKENS = 1024;
const TURN_CREDENTIAL_TTL_SECONDS = 3600;
const GEMINI_LIVE_MODEL = "gemini-3.8-live";
const GEMINI_TOKEN_TTL_SECONDS = 1800;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}
function fail(code, status, message) { return json({ error: code, message }, status); }
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

function bearer(request) {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : "";
}

function constantTimeEqual(left, right) {
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  const length = Math.max(a.length, b.length);
  let difference = a.length ^ b.length;
  for (let i = 0; i < length; i++) difference |= (a[i] || 0) ^ (b[i] || 0);
  return difference === 0;
}

function isAuthorized(request, env) {
  const expected = typeof env.BELLOTA_ACCESS_KEY === "string" ? env.BELLOTA_ACCESS_KEY : "";
  const supplied = bearer(request);
  return expected.length >= 8 && supplied.length >= 8 && constantTimeEqual(supplied, expected);
}

async function healthCheck(env) {
  const accessKey = Boolean(env.BELLOTA_ACCESS_KEY && env.BELLOTA_ACCESS_KEY.length >= 8);
  const openai = Boolean(env.OPENAI_API_KEY);
  const gemini = Boolean(env.GEMINI_API_KEY);
  const configured = accessKey && (openai || gemini);
  return json({
    ok: configured,
    service: "bellota-openai-realtime",
    checks: { access_key: accessKey, openai, gemini }
  }, configured ? 200 : 503);
}

async function pairDevice(request, env) {
  const parsed = await readJson(request, 2048, true);
  if (!parsed.ok) return invalidBody(parsed);
  if (!isAuthorized(request, env))
    return fail("invalid_access_key", 401, "La clave fija de Bellota no es válida.");
  return json({ ok: true, paired: true });
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
  if (!isAuthorized(request, env)) return fail("invalid_access_key", 401, "La clave fija de Bellota no es válida.");
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
  if (!isAuthorized(request, env)) return fail("invalid_access_key", 401, "La clave fija de Bellota no es válida.");
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
    response = await fetch("https://generativelanguage.googleapis.com/v1beta/auth_tokens", {
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
  return json({
    token,
    model: GEMINI_LIVE_MODEL,
    expires_at: Math.floor(now / 1000) + GEMINI_TOKEN_TTL_SECONDS,
    new_session_expires_at: Math.floor(now / 1000) + 60,
    websocket_url: "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
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
      "/v1/pair": () => pairDevice(request, env),
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
