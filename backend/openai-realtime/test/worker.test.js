import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";

function envWithDatabase(database = { prepare: () => ({ first: async () => ({ ok: 1 }) }) }) {
  return {
    DB: database,
    JWT_SECRET: "test-jwt-secret",
    OPENAI_API_KEY: "test-openai-key"
  };
}

test("health reports ready only when configuration and D1 are available", async () => {
  const response = await worker.fetch(new Request("https://worker.test/health"), envWithDatabase());
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.deepEqual(body.checks, { database: true, configuration: true });
});

test("health returns 503 when D1 cannot be reached without leaking details", async () => {
  const response = await worker.fetch(
    new Request("https://worker.test/health"),
    envWithDatabase({ prepare: () => ({ first: async () => { throw new Error("private database detail"); } }) })
  );
  assert.equal(response.status, 503);
  const body = await response.json();
  assert.equal(body.ok, false);
  assert.deepEqual(body.checks, { database: false, configuration: true });
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
