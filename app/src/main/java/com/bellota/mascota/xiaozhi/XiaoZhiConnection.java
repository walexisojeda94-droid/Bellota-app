package com.bellota.mascota.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Solicita un código de vinculación al servicio OTA oficial de XiaoZhi.
 * El protocolo de activación v1 no requiere una clave privada de hardware:
 * la respuesta de activación se confirma enviando un objeto JSON vacío.
 */
public class XiaoZhiConnection {
    private static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";
    private static final String PREFS = "bellota_xiaozhi";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface ActivationListener {
        void onCodeReceived(String code, String message);
        void onActivated();
        void onError(String message);
    }

    public XiaoZhiConnection(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ensureIdentity();
    }

    public boolean isConfigured() {
        return preferences.getBoolean(KEY_ACTIVATED, false)
                && !preferences.getString(KEY_ACCESS_TOKEN, "").isEmpty();
    }

    public void requestActivationCode(ActivationListener listener) {
        if (isConfigured()) {
            if (listener != null) listener.onActivated();
            return;
        }
        executor.execute(() -> activate(listener));
    }

    private void ensureIdentity() {
        if (!preferences.contains(KEY_CLIENT_ID)) {
            preferences.edit().putString(KEY_CLIENT_ID, UUID.randomUUID().toString()).apply();
        }
        if (!preferences.contains(KEY_DEVICE_ID)) {
            String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null || androidId.trim().isEmpty()) androidId = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_DEVICE_ID, "android-" + androidId).apply();
        }
    }

    private void activate(ActivationListener listener) {
        try {
            JSONObject response = request("GET", OTA_URL, null);
            JSONObject activation = response.optJSONObject("activation");
            if (activation == null) {
                throw new IllegalStateException("XiaoZhi no devolvió una solicitud de vinculación. Revisá la respuesta del servidor.");
            }

            String code = activation.optString("code", "");
            String message = activation.optString("message", "Ingresá este código en la consola de XiaoZhi.");
            if (code.isEmpty()) {
                throw new IllegalStateException("El servidor respondió, pero no entregó un código de vinculación.");
            }
            if (listener != null) listener.onCodeReceived(code, message);

            String challenge = activation.optString("challenge", "");
            if (challenge.isEmpty()) {
                throw new IllegalStateException("XiaoZhi entregó el código, pero no incluyó el desafío de activación.");
            }

            String activateUrl = OTA_URL.endsWith("/") ? OTA_URL + "activate" : OTA_URL + "/activate";
            for (int attempt = 0; attempt < 40; attempt++) {
                HttpResult result = postActivation(activateUrl);
                if (result.status == 200) {
                    String token = result.json.optString("access_token", "");
                    if (!token.isEmpty()) {
                        preferences.edit().putString(KEY_ACCESS_TOKEN, token).putBoolean(KEY_ACTIVATED, true).apply();
                    }
                    if (listener != null) listener.onActivated();
                    return;
                }
                if (result.status != 202) {
                    throw new IllegalStateException("XiaoZhi rechazó la activación (HTTP " + result.status + ").");
                }
                Thread.sleep(3000L);
            }
            throw new IllegalStateException("El código sigue pendiente. Volvé a solicitar uno nuevo e ingresalo en XiaoZhi.");
        } catch (Exception e) {
            if (listener != null) listener.onError(e.getMessage() == null ? "No se pudo conectar con XiaoZhi." : e.getMessage());
        }
    }

    private JSONObject request(String method, String endpoint, String body) throws Exception {
        HttpURLConnection connection = open(endpoint, method);
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        String response = readBody(connection, status);
        connection.disconnect();
        if (status != 200) throw new IllegalStateException("No se pudo solicitar el código a XiaoZhi (HTTP " + status + ").");
        return new JSONObject(response);
    }

    private HttpResult postActivation(String endpoint) throws Exception {
        HttpURLConnection connection = open(endpoint, "POST");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        int status = connection.getResponseCode();
        String response = readBody(connection, status);
        connection.disconnect();
        JSONObject json;
        try { json = response.isEmpty() ? new JSONObject() : new JSONObject(response); }
        catch (Exception ignored) { json = new JSONObject(); }
        return new HttpResult(status, json);
    }

    private HttpURLConnection open(String endpoint, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Activation-Version", "1");
        connection.setRequestProperty("Device-Id", preferences.getString(KEY_DEVICE_ID, ""));
        connection.setRequestProperty("Client-Id", preferences.getString(KEY_CLIENT_ID, ""));
        connection.setRequestProperty("User-Agent", "Bellota-Android/0.1.0");
        connection.setRequestProperty("Accept-Language", "es-AR");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private String readBody(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static class HttpResult {
        final int status;
        final JSONObject json;
        HttpResult(int status, JSONObject json) { this.status = status; this.json = json; }
    }
}
