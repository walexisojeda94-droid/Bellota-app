import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { createHmac } from "node:crypto";

function envWithDatabase(database = { prepare: () => ({ first: async () => ({ table_count: 2 }) }) }) {
  return {
    DB: database,
    ADMIN_TOKEN: "test-admin-token",
    JWT_SECRET: "test-jwt-secret",
    OPENAI_API_KEY: "test-openai-key"
  };
}

test("health reports ready only when configuration and D1 are available", async () => {
  const response = await worker.fetch(new Request("https://worker.test/health"), envWithDatabase());
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.deepEqual(body.checks, { database: true, configuration: true, gemini: false });
});

test("health reports Gemini configured when its secret is present", async () => {
  const env = { ...envWithDatabase(), GEMINI_API_KEY: "test-gemini-key" };
  const response = await worker.fetch(new Request("https://worker.test/health"), env);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.deepEqual(body.checks, { database: true, configuration: true, gemini: true });
});

test("health returns 503 when D1 cannot be reached without leaking details", async () => {
  const response = await worker.fetch(
    new Request("https://worker.test/health"),
    envWithDatabase({ prepare: () => ({ first: async () => { throw new Error("private database detail"); } }) })
  );
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.ok, false);
  assert.deepEqual(body.checks, { database: false, configuration: true, gemini: false });
  assert.equal(JSON.stringify(body).includes("private database detail"), false);
});

test("known routes reject unsupported HTTP methods with 405", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/pair"), envWithDatabase());
  assert.equal(response.status, 405);
  assert.equal(response.headers.get("allow"), "POST");
});

test("JSON endpoints reject unsupported content types", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/pair", {
    method: "POST",
    headers: { "content-type": "text/plain" },
    body: "code=12345678"
  }), envWithDatabase());
  assert.equal(response.status, 415);
  assert.equal((await response.json()).error, "unsupported_media_type");
});

test("JSON endpoints reject declared oversized bodies before parsing", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/pair", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code: "x".repeat(17000) })
  }), envWithDatabase());
  assert.equal(response.status, 413);
  assert.equal((await response.json()).error, "request_too_large");
});

test("malformed JSON is rejected instead of silently using defaults", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/pair", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{"
  }), envWithDatabase());
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error, "invalid_json");
});

test("Gemini token endpoint reports a missing server secret without exposing details", async () => {
  const response = await worker.fetch(new Request("https://worker.test/v1/gemini/live-token", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({})
  }), envWithDatabase());
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.error, "provider_not_configured");
  assert.equal(JSON.stringify(body).includes("test-gemini-key"), false);
});


test("Gemini ephemeral credentials use the v1alpha constrained Live API", async () => {
  const secret = "test-jwt-secret";
  const now = Math.floor(Date.now() / 1000);
  const encode = value => Buffer.from(JSON.stringify(value)).toString("base64url");
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({ sub: "device-test", iat: now, exp: now + 300, iss: "bellota-backend", aud: "bellota-app" });
  const unsigned = header + "." + payload;
  const token = unsigned + "." + createHmac("sha256", secret).update(unsigned).digest("base64url");
  const calls = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    calls.push({ url: String(url), body: JSON.parse(options.body) });
    return new Response(JSON.stringify({ name: "auth_tokens/test-ephemeral-token" }), {
      status: 200, headers: { "content-type": "application/json" }
    });
  };
  const db = {
    prepare(sql) {
      return {
        bind(...values) {
          return {
            first: async () => sql.includes("SELECT id FROM devices") ? { id: values[0] } : null,
            run: async () => ({ success: true })
          };
        }
      };
    }
  };
  try {
    const response = await worker.fetch(new Request("https://worker.test/v1/gemini/live-token", {
      method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer " + token },
      body: JSON.stringify({ instructions: "Sos Bellota." })
    }), { ...envWithDatabase(db), GEMINI_API_KEY: "test-gemini-key" });
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "https://generativelanguage.googleapis.com/v1beta/auth_tokens");
    assert.equal(calls[0].body.liveConnectConstraints.model, "models/gemini-3.8-live");
    assert.deepEqual(calls[0].body.liveConnectConstraints.config.sessionResumption, {});
    assert.equal(body.websocket_url,
      "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained");
  } finally {
    globalThis.fetch = originalFetch;
  }
});
