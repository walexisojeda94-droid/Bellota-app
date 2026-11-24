package com.bellota.mascota.voice.auth;

import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synchronous HTTPS client for Bellota's device-auth Worker.
 * Call every method from a worker thread. The backend URL is injected at runtime;
 * no production hostname or permanent credential is embedded in the APK.
 */
public final class BellotaBackendClient {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_RESPONSE_BYTES = 65536;

    private final String baseUrl;
    private final SecureTokenStore tokenStore;
    private final Object authLock = new Object();

    private String accessToken;
    private long accessTokenExpiresAtMs;

    public BellotaBackendClient(String backendBaseUrl, SecureTokenStore tokenStore) {
        if (backendBaseUrl == null || backendBaseUrl.trim().isEmpty())
            throw new IllegalArgumentException("Falta configurar la URL del backend de Bellota.");
        String normalized = backendBaseUrl.trim().replaceAll("/+$", "");
        if (!normalized.startsWith("https://"))
            throw new IllegalArgumentException("El backend debe utilizar HTTPS.");
        if (tokenStore == null) throw new IllegalArgumentException("SecureTokenStore obligatorio.");
        this.baseUrl = normalized;
        this.tokenStore = tokenStore;
    }

    /** Exchanges the administrator's one-time code for a device refresh token. */
    public void pair(String oneTimeCode, String deviceName) throws IOException, BackendException {
        requireWorkerThread();
        if (oneTimeCode == null || !oneTimeCode.trim().matches("[A-Za-z0-9_-]{6,32}"))
            throw new IllegalArgumentException("Ingresá un código de vinculación válido.");
        JSONObject body = new JSONObject();
        try {
            body.put("code", oneTimeCode.trim());
            body.put("device_name", deviceName == null ? "Bellota Android" : deviceName.trim());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        JSONObject response = request("POST", "/v1/pair", body, null);
        String refresh = response.optString("refresh_token", "");
        String access = response.optString("access_token", "");
        int ttl = response.optInt("access_expires_in", 0);
        if (refresh.length() < 32 || access.isEmpty() || ttl <= 0)
            throw new BackendException("invalid_response", 502, "El backend devolvió una vinculación incompleta.");
        try {
            tokenStore.saveRefreshToken(refresh);
        } catch (Exception error) {
            // Do not leave an unmanageable device registered if secure storage failed.
            try {
                JSONObject revokeBody = new JSONObject();
                revokeBody.put("refresh_token", refresh);
                request("POST", "/v1/device/revoke", revokeBody, null);
            } catch (Exception ignored) { }
            throw new BackendException("secure_storage_error", 500,
                    "No se pudo guardar la vinculación segura. El servidor intentó revocar el dispositivo.");
        }
        synchronized (authLock) {
            accessToken = access;
            accessTokenExpiresAtMs = System.currentTimeMillis() + ttl * 1000L;
        }
    }

    /** Renews and rotates the stored refresh token. Access tokens remain memory-only. */
    public void refresh() throws IOException, BackendException {
        requireWorkerThread();
        synchronized (authLock) {
            String refresh;
            try {
                refresh = tokenStore.readRefreshToken();
            } catch (Exception error) {
                throw new BackendException("secure_storage_error", 500,
                        "La credencial segura no se pudo descifrar. Volvé a vincular el dispositivo.");
            }
            if (refresh == null || refresh.isEmpty())
                throw new BackendException("not_paired", 401, "Bellota todavía no está vinculada.");
            JSONObject body = new JSONObject();
            try { body.put("refresh_token", refresh); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            JSONObject response = request("POST", "/v1/auth/refresh", body, null);
            String nextRefresh = response.optString("refresh_token", "");
            String nextAccess = response.optString("access_token", "");
            int ttl = response.optInt("access_expires_in", 0);
            if (nextRefresh.length() < 32 || nextAccess.isEmpty() || ttl <= 0)
                throw new BackendException("invalid_response", 502, "El backend devolvió una renovación incompleta.");
            try {
                tokenStore.saveRefreshToken(nextRefresh);
            } catch (Exception error) {
                throw new BackendException("secure_storage_error", 500,
                        "No se pudo guardar la renovación segura. Bellota puede reintentar la misma rotación durante 120 segundos.");
            }
            accessToken = nextAccess;
            accessTokenExpiresAtMs = System.currentTimeMillis() + ttl * 1000L;
        }
    }

    /** Returns a valid in-memory access token, refreshing the device credential if needed. */
    public String getAccessToken() throws IOException, BackendException {
        requireWorkerThread();
        synchronized (authLock) {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAtMs - 30000L)
                return accessToken;
        }
        refresh();
        synchronized (authLock) { return accessToken; }
    }

    /** Requests a short-lived OpenAI Realtime client secret from the authenticated Worker. */
    public RealtimeCredential createRealtimeClientSecret(String instructions, String voice)
            throws IOException, BackendException {
        requireWorkerThread();
        JSONObject body = new JSONObject();
        try {
            if (instructions != null && !instructions.trim().isEmpty())
                body.put("instructions", instructions.trim());
            if (voice != null && !voice.trim().isEmpty()) body.put("voice", voice.trim());
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }

        String token = getAccessToken();
        JSONObject response;
        try {
            response = request("POST", "/v1/realtime/client-secret", body, token);
        } catch (BackendException unauthorized) {
            if (unauthorized.httpStatus != 401) throw unauthorized;
            refresh();
            synchronized (authLock) { token = accessToken; }
            response = request("POST", "/v1/realtime/client-secret", body, token);
        }
        String value = response.optString("value", "");
        long expiresAt = response.optLong("expires_at", 0L);
        if (value.isEmpty() || expiresAt <= 0L)
            throw new BackendException("invalid_response", 502,
                    "El backend devolvió una credencial temporal incompleta.");
        return new RealtimeCredential(value, expiresAt, response.optString("model", ""),
                parseIceServers(response.optJSONArray("ice_servers")),
                response.optInt("max_output_tokens", 1024),
                response.optLong("session_max_duration_seconds", 2700L),
                response.optLong("session_expires_at", 0L));
    }


    /** Requests a model-constrained, single-use Gemini Live ephemeral token. */
    public GeminiCredential createGeminiLiveToken(String instructions, String voice, String toolsJson)
            throws IOException, BackendException {
        requireWorkerThread();
        JSONObject body = new JSONObject();
        try {
            if (instructions != null && !instructions.trim().isEmpty())
                body.put("instructions", instructions.trim());
            if (voice != null && !voice.trim().isEmpty()) body.put("voice", voice.trim());
            if (toolsJson != null && !toolsJson.trim().isEmpty())
                body.put("tools_json", toolsJson);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        String token = getAccessToken();
        JSONObject response;
        try {
            response = request("POST", "/v1/gemini/live-token", body, token);
        } catch (BackendException unauthorized) {
            if (unauthorized.httpStatus != 401) throw unauthorized;
            refresh();
            synchronized (authLock) { token = accessToken; }
            response = request("POST", "/v1/gemini/live-token", body, token);
        }
        String value = response.optString("token", "");
        String model = response.optString("model", "");
        String websocketUrl = response.optString("websocket_url", "");
        long expiresAt = response.optLong("expires_at", 0L);
        if (!value.startsWith("authTokens/") || model.isEmpty()
                || !websocketUrl.startsWith("wss://") || expiresAt <= 0L)
            throw new BackendException("invalid_response", 502,
                    "El backend devolvió una credencial Gemini incompleta.");
        return new GeminiCredential(value, model, websocketUrl, expiresAt);
    }

    public static final class GeminiCredential {
        public final String value;
        public final String model;
        public final String websocketUrl;
        public final long expiresAtEpochSeconds;
        GeminiCredential(String value, String model, String websocketUrl, long expiresAtEpochSeconds) {
            this.value = value;
            this.model = model;
            this.websocketUrl = websocketUrl;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        }
    }

    static List<IceServerConfig> parseIceServers(JSONArray servers) {
        if (servers == null || servers.length() == 0) return Collections.emptyList();
        List<IceServerConfig> parsed = new ArrayList<>();
        for (int i = 0; i < servers.length(); i++) {
            org.json.JSONObject item = servers.optJSONObject(i);
            if (item == null) continue;
            JSONArray urlsJson = item.optJSONArray("urls");
            List<String> urls = new ArrayList<>();
            if (urlsJson != null) {
                for (int j = 0; j < urlsJson.length(); j++) {
                    String url = urlsJson.optString(j, "").trim();
                    if (url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:")) urls.add(url);
                }
            } else {
                String url = item.optString("urls", "").trim();
                if (url.startsWith("stun:") || url.startsWith("turn:") || url.startsWith("turns:")) urls.add(url);
            }
            if (!urls.isEmpty()) parsed.add(new IceServerConfig(urls,
                    item.optString("username", ""), item.optString("credential", "")));
        }
        return Collections.unmodifiableList(parsed);
    }

    public static final class IceServerConfig {
        public final List<String> urls;
        public final String username;
        public final String credential;
        IceServerConfig(List<String> urls, String username, String credential) {
            this.urls = Collections.unmodifiableList(new ArrayList<>(urls));
            this.username = username;
            this.credential = credential;
        }
    }

    public static final class RealtimeCredential {
        public final String value;
        public final long expiresAtEpochSeconds;
        public final String model;
        public final List<IceServerConfig> iceServers;
        public final int maxOutputTokens;
        public final long sessionMaxDurationSeconds;
        public final long sessionExpiresAtEpochSeconds;
        RealtimeCredential(String value, long expiresAtEpochSeconds, String model,
                           List<IceServerConfig> iceServers, int maxOutputTokens, long sessionMaxDurationSeconds,
                           long sessionExpiresAtEpochSeconds) {
            this.value = value;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.model = model;
            this.iceServers = iceServers;
            this.maxOutputTokens = maxOutputTokens;
            this.sessionMaxDurationSeconds = sessionMaxDurationSeconds;
            this.sessionExpiresAtEpochSeconds = sessionExpiresAtEpochSeconds;
        }
    }

    /** Revokes the device remotely first. The refresh credential makes revocation safely retryable. */
    public void unpair() throws IOException, BackendException {
        requireWorkerThread();
        final String refresh;
        try {
            refresh = tokenStore.readRefreshToken();
        } catch (Exception error) {
            throw new BackendException("secure_storage_error", 500,
                    "La clave segura se invalidó; este dispositivo necesita vincularse otra vez.");
        }
        if (refresh == null || refresh.isEmpty())
            throw new BackendException("not_paired", 401, "Este dispositivo no tiene una vinculación local.");
        JSONObject body = new JSONObject();
        try { body.put("refresh_token", refresh); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        request("POST", "/v1/device/revoke", body, null);
        if (!tokenStore.clear())
            throw new BackendException("secure_storage_error", 500,
                    "El servidor revocó el dispositivo, pero no se pudo borrar la credencial local. Podés reintentar.");
        synchronized (authLock) {
            accessToken = null;
            accessTokenExpiresAtMs = 0L;
        }
    }

    public boolean isPaired() {
        return tokenStore.hasRefreshToken();
    }

    private JSONObject request(String method, String path, JSONObject body, String bearer)
            throws IOException, BackendException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }
            if (bearer != null && !bearer.isEmpty())
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String responseText = stream == null ? "" : readLimited(stream);
            JSONObject response;
            try { response = responseText.isEmpty() ? new JSONObject() : new JSONObject(responseText); }
            catch (JSONException invalid) {
                throw new BackendException("invalid_response", status,
                        "El backend devolvió una respuesta que no es JSON válido.");
            }
            if (status < 200 || status >= 300) {
                throw new BackendException(response.optString("error", "backend_error"), status,
                        response.optString("message", "El backend rechazó la solicitud."));
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("La respuesta del backend es demasiado grande.");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void requireWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("La comunicación con el backend debe ejecutarse fuera del hilo principal.");
    }

    public static final class BackendException extends Exception {
        public final String code;
        public final int httpStatus;

        BackendException(String code, int httpStatus, String message) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }
    }
}
