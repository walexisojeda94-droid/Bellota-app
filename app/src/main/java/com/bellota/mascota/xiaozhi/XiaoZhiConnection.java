package com.bellota.mascota.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import com.bellota.mascota.core.DebugLog;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
    private static final String TAG = "BellotaXiaoZhi";
    private static final String PREFS = "bellota_xiaozhi";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_ACTIVATED = "activated";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_WEBSOCKET_CONFIG = "websocket_config";
    private static final String KEY_MQTT_CONFIG = "mqtt_config";
    private final Context context;
    private final SharedPreferences preferences;
    private final XiaoZhiTokenStore tokenStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface ActivationListener {
        void onCodeReceived(String code, String message);
        void onActivated();
        void onError(String message);
    }

    public XiaoZhiConnection(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        tokenStore = new XiaoZhiTokenStore(this.context);
        ensureIdentity();
        migrateLegacyAccessToken();
    }

    public boolean isConfigured() {
        return preferences.getBoolean(KEY_ACTIVATED, false);
    }

    public Context getContext() { return context; }

    public String getAccessToken() {
        try {
            String encrypted = tokenStore.read();
            if (encrypted != null) return encrypted;
            String legacy = preferences.getString(KEY_ACCESS_TOKEN, "");
            if (!legacy.isEmpty()) {
                tokenStore.save(legacy);
                preferences.edit().remove(KEY_ACCESS_TOKEN).commit();
            }
            return legacy;
        } catch (Exception ignored) {
            // Si el Keystore no está disponible, permitir la migración heredada
            // solo mientras el token antiguo siga presente.
            return preferences.getString(KEY_ACCESS_TOKEN, "");
        }
    }

    private void migrateLegacyAccessToken() {
        String legacy = preferences.getString(KEY_ACCESS_TOKEN, "");
        if (legacy.isEmpty()) return;
        try {
            tokenStore.save(legacy);
            if (!preferences.edit().remove(KEY_ACCESS_TOKEN).commit())
                throw new IllegalStateException("No se pudo eliminar el token heredado.");
        } catch (Exception e) {
            DebugLog.w(TAG, "No se pudo migrar el token al almacenamiento seguro.");
        }
    }

    private void saveAccessToken(String token) throws Exception {
        if (token == null || token.isEmpty()) return;
        tokenStore.save(token);
        if (!preferences.edit().remove(KEY_ACCESS_TOKEN).commit())
            throw new IllegalStateException("No se pudo retirar el token heredado.");
    }

    private void clearAccessToken() {
        try { tokenStore.clear(); } catch (Exception ignored) { }
        preferences.edit().remove(KEY_ACCESS_TOKEN).apply();
    }
    public String getDeviceId() { return preferences.getString(KEY_DEVICE_ID, ""); }
    public String getClientId() { return preferences.getString(KEY_CLIENT_ID, ""); }

    /** Configuración de transporte entregada por el OTA oficial. */
    public JSONObject getWebSocketConfig() {
        try { String raw = preferences.getString(KEY_WEBSOCKET_CONFIG, null); return raw == null ? null : new JSONObject(raw); }
        catch (Exception ignored) { return null; }
    }
    public JSONObject getMqttConfig() {
        try { String raw = preferences.getString(KEY_MQTT_CONFIG, null); return raw == null ? null : new JSONObject(raw); }
        catch (Exception ignored) { return null; }
    }
    public String getTransport() {
        JSONObject ws = getWebSocketConfig();
        if (ws != null && !ws.optString("url", "").trim().isEmpty()
                && (!ws.optString("token", "").trim().isEmpty()
                    || !getAccessToken().trim().isEmpty())) return "websocket";
        JSONObject mqtt = getMqttConfig();
        if (mqtt != null && !mqtt.optString("endpoint", "").trim().isEmpty()
                && !mqtt.optString("username", "").trim().isEmpty()
                && !mqtt.optString("password", "").trim().isEmpty()
                && !mqtt.optString("publish_topic", "").trim().isEmpty()
                && !mqtt.optString("subscribe_topic", "").trim().isEmpty()
                && !"null".equalsIgnoreCase(mqtt.optString("subscribe_topic", "").trim())) return "mqtt";
        return null;
    }
    public boolean hasVoiceConfiguration() {
        return getTransport() != null;
    }
    private void replaceServerConfiguration(JSONObject response) {
        // Una respuesta OTA que no incluya transporte no debe borrar la última
        // configuración válida. Si incluye websocket o mqtt, sí reemplaza el
        // conjunto anterior para respetar cambios explícitos del servidor.
        saveServerConfiguration(response);
    }

    private void saveServerConfiguration(JSONObject response) {
        if (response == null) return;
        JSONObject ws = response.optJSONObject("websocket");
        JSONObject mqtt = response.optJSONObject("mqtt");
        SharedPreferences.Editor editor = preferences.edit();

        // Cuando OTA entrega una nueva configuración de transporte, reemplazar la
        // anterior completa. No conservar un WebSocket/MQTT obsoleto si el servidor
        // cambió de transporte. Las respuestas de /activate que solo traen token
        // no contienen estas claves y por eso no borran la configuración recién recibida.
        if (response.has("websocket") || response.has("mqtt")) {
            editor.remove(KEY_WEBSOCKET_CONFIG);
            editor.remove(KEY_MQTT_CONFIG);
            if (ws != null) editor.putString(KEY_WEBSOCKET_CONFIG, ws.toString());
            if (mqtt != null) editor.putString(KEY_MQTT_CONFIG, mqtt.toString());
        }
        editor.apply();
    }


    /** Resumen sin secretos de una respuesta OTA: qué bloques trae y de dónde. */
    private static String summarize(JSONObject r) {
        if (r == null) return "(vacía)";
        StringBuilder sb = new StringBuilder("claves=");
        java.util.Iterator<String> keys = r.keys();
        boolean first = true;
        while (keys.hasNext()) { if (!first) sb.append(','); sb.append(keys.next()); first = false; }
        JSONObject ws = r.optJSONObject("websocket");
        if (ws != null) sb.append(" | ws url=").append(ws.optString("url", "-").split("\\?")[0])
                .append(" token=").append(ws.optString("token", "").isEmpty() ? "no" : "si")
                .append(" version=").append(ws.has("version") ? ws.optInt("version") : -1);
        JSONObject mqtt = r.optJSONObject("mqtt");
        if (mqtt != null) sb.append(" | mqtt endpoint=").append(mqtt.optString("endpoint", "-"))
                .append(" pub=").append(mqtt.optString("publish_topic", "").isEmpty() ? "no" : "si")
                .append(" sub=").append(mqtt.optString("subscribe_topic", "").isEmpty() ? "no" : "si");
        JSONObject act = r.optJSONObject("activation");
        if (act != null) sb.append(" | activation code=").append(act.optString("code", "-"))
                .append(" challenge=").append(act.has("challenge") ? "SI" : "no");
        return sb.toString();
    }

    /** Estado de la vinculación para mostrar en pantalla (sin tokens). */
    public String describeState() {
        StringBuilder sb = new StringBuilder();
        sb.append("vinculada=").append(isConfigured())
          .append(" transporte=").append(getTransport() == null ? "ninguno" : getTransport()).append('\n');
        JSONObject ws = getWebSocketConfig();
        sb.append("ws: ").append(ws == null ? "sin config" : summarize(wrap("websocket", ws))).append('\n');
        JSONObject mqtt = getMqttConfig();
        sb.append("mqtt: ").append(mqtt == null ? "sin config" : summarize(wrap("mqtt", mqtt))).append('\n');
        sb.append("token propio=").append(getAccessToken().isEmpty() ? "no" : "si")
          .append(" device=").append(getDeviceId()).append('\n');
        return sb.toString();
    }

    private static JSONObject wrap(String key, JSONObject value) {
        JSONObject o = new JSONObject();
        try { o.put(key, value); } catch (Exception ignored) { }
        return o;
    }

    /** Libera el hilo de trabajo cuando la Activity termina definitivamente. */
    public void shutdown() {
        executor.shutdownNow();
    }

    public void requestActivationCode(ActivationListener listener) {
        if (isConfigured()) {
            // La vinculación persiste, pero la configuración de transporte puede cambiar.
            // Consultar OTA en cada inicio evita depender de una URL/token antiguos o ausentes.
            executor.execute(() -> refreshConfiguration(listener));
            return;
        }
        executor.execute(() -> activate(listener));
    }

    private void ensureIdentity() {
        // ANDROID_ID permanece estable para la misma app/dispositivo entre actualizaciones
        // y reinstalaciones normales. No generar un Client-Id nuevo en cada instalación.
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            androidId = "bellota-device-bellota";
        }
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(KEY_CLIENT_ID)) {
            // XiaoZhi espera un UUID de cliente; se genera una sola vez y se
            // conserva en preferencias para mantener la identidad entre sesiones.
            String stableClientId = UUID.randomUUID().toString();
            editor.putString(KEY_CLIENT_ID, stableClientId);
        }
        if (!preferences.contains(KEY_DEVICE_ID)) {
            editor.putString(KEY_DEVICE_ID, createStableDeviceId(androidId));
        }
        editor.apply();
    }

    private String createStableDeviceId(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            hash[0] = (byte) ((hash[0] & 0xFE) | 0x02);
            StringBuilder mac = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) mac.append(":");
                mac.append(String.format(java.util.Locale.US, "%02X", hash[i] & 0xFF));
            }
            return mac.toString();
        } catch (Exception e) {
            return "02:00:00:00:00:01";
        }
    }

    private void refreshConfiguration(ActivationListener listener) {
        try {
            JSONObject response = request("POST", OTA_URL, buildDeviceReport());
            DebugLog.i(TAG, "OTA (refresco): " + summarize(response));
            if (response.optJSONObject("activation") != null) {
                // El servidor volvió a pedir vinculación (dispositivo desvinculado o token
                // revocado): descartar el estado local y repetir el flujo de activación.
                clearAccessToken();
                preferences.edit().putBoolean(KEY_ACTIVATED, false).apply();
                handleActivationResponse(response, listener);
                return;
            }
            replaceServerConfiguration(response);
            if (listener != null) listener.onActivated();
        } catch (Exception e) {
            if (listener != null) listener.onError("No pude actualizar la conexión con XiaoZhi: "
                    + (e.getMessage() == null ? "error de red" : e.getMessage()));
        }
    }

    private void activate(ActivationListener listener) {
        try {
            JSONObject first = request("POST", OTA_URL, buildDeviceReport());
            DebugLog.i(TAG, "OTA (alta): " + summarize(first));
            handleActivationResponse(first, listener);
        } catch (Exception e) {
            if (listener != null) listener.onError(e.getMessage() == null ? "No se pudo conectar con XiaoZhi." : e.getMessage());
        }
    }

    private void handleActivationResponse(JSONObject response, ActivationListener listener) {
        try {
            replaceServerConfiguration(response);
            JSONObject activation = response.optJSONObject("activation");
            if (activation == null) {
                // El protocolo oficial considera que un dispositivo sin campo
                // "activation" no necesita vincularse nuevamente.
                String token = response.optString("access_token", "");
                saveAccessToken(token);
                preferences.edit().putBoolean(KEY_ACTIVATED, true).apply();
                if (listener != null) listener.onActivated();
                return;
            }

            String code = activation.optString("code", "");
            String message = activation.optString("message", "Ingresá este código en la consola de XiaoZhi.");
            if (code.isEmpty()) {
                throw new IllegalStateException("El servidor respondió, pero no entregó un código de vinculación.");
            }
            if (listener != null) listener.onCodeReceived(code, message);

            // Activation-Version 1 confirma el alta de seis dígitos con un objeto vacío.

            String activateUrl = OTA_URL.endsWith("/") ? OTA_URL + "activate" : OTA_URL + "/activate";
            for (int attempt = 0; attempt < 40; attempt++) {
                HttpResult result = postActivation(activateUrl);
                DebugLog.i(TAG, "activate intento " + (attempt + 1) + ": HTTP " + result.status
                        + (result.status == 200 ? " " + summarize(result.json) : ""));
                if (result.status == 200) {
                    String token = result.json.optString("access_token", "");
                    saveServerConfiguration(result.json);
                    saveAccessToken(token);
                    preferences.edit().putBoolean(KEY_ACTIVATED, true).apply();
                    // Tras vincular, el firmware oficial vuelve a consultar el OTA para obtener la
                    // configuración de voz definitiva; la de antes de vincular puede no servir.
                    try {
                        JSONObject fresh = request("POST", OTA_URL, buildDeviceReport());
                        DebugLog.i(TAG, "OTA tras vincular: " + summarize(fresh));
                        if (fresh.optJSONObject("activation") == null) saveServerConfiguration(fresh);
                    } catch (Exception e) {
                        DebugLog.w(TAG, "No pude releer el OTA tras vincular", e);
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

    private String buildDeviceReport() throws Exception {
        String deviceId = preferences.getString(KEY_DEVICE_ID, "");
        String clientId = preferences.getString(KEY_CLIENT_ID, "");
        JSONObject report = new JSONObject();
        report.put("version", 2);
        report.put("language", "es-ES");
        report.put("mac_address", deviceId);
        report.put("uuid", clientId);
        report.put("chip_model_name", "Android");
        JSONObject application = new JSONObject();
        application.put("name", "Bellota");
        application.put("version", "0.4.4");
        application.put("idf_version", "Android");
        application.put("elf_sha256", "");
        report.put("application", application);
        JSONObject board = new JSONObject();
        board.put("type", "android");
        board.put("name", "Bellota");
        report.put("board", board);
        return report.toString();
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
        if (status != 200) throw new IllegalStateException("No se pudo solicitar el código a XiaoZhi (HTTP " + status + "): " + response);
        if (response == null || response.trim().isEmpty()) throw new IllegalStateException("XiaoZhi respondió sin contenido.");
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
        connection.setRequestProperty("User-Agent", "Bellota-Android/0.4.4");
        connection.setRequestProperty("Accept-Language", "es-ES");
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
