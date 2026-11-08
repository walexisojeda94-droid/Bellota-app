package com.bellota.mascota.xiaozhi;

import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Cliente MCP WebSocket. La URL y su token se reciben en tiempo de ejecución. */
public final class McpConnection {
    private static final String TAG = "BellotaMCP";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private final AtomicInteger ids = new AtomicInteger(1);
    private volatile WebSocket socket;
    private volatile Listener listener;
    private volatile boolean initialized;

    public interface Listener {
        void onStatus(String status);
        void onTools(JSONArray tools);
    }

    public synchronized void connect(String endpoint, Listener callback) {
        disconnect();
        listener = callback;
        initialized = false;
        try {
            if (endpoint == null || !endpoint.startsWith("wss://")) {
                notifyStatus("La dirección MCP debe comenzar con wss://");
                return;
            }
            Request request = new Request.Builder().url(endpoint)
                    .header("User-Agent", "Bellota-Android/0.1.0").build();
            notifyStatus("Conectando con XiaoZhi…");
            socket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    try {
                        JSONObject params = new JSONObject();
                        params.put("protocolVersion", "2024-11-05");
                        params.put("capabilities", new JSONObject());
                        JSONObject info = new JSONObject();
                        info.put("name", "Bellota-Android");
                        info.put("version", "0.1.0");
                        params.put("clientInfo", info);
                        sendRequest("initialize", params);
                    } catch (Exception e) { notifyStatus("No pude iniciar el protocolo MCP."); }
                }
                @Override public void onMessage(WebSocket webSocket, String text) { handleMessage(text); }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    notifyStatus("Conexión MCP interrumpida: " + (response == null ? "sin respuesta del servidor" : "HTTP " + response.code()));
                    synchronized (McpConnection.this) { socket = null; initialized = false; }
                }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    notifyStatus("Conexión MCP cerrada.");
                    synchronized (McpConnection.this) { socket = null; initialized = false; }
                }
            });
        } catch (Exception e) { notifyStatus("No se pudo abrir la conexión MCP. Revisá la dirección."); }
    }

    private synchronized void handleMessage(String raw) {
        try {
            JSONObject message = new JSONObject(raw);
            if (!message.has("id")) return;
            int id = message.optInt("id", -1);
            if (id == 1 && message.has("result") && !initialized) {
                initialized = true;
                JSONObject notification = new JSONObject();
                notification.put("jsonrpc", "2.0");
                notification.put("method", "notifications/initialized");
                if (socket != null) socket.send(notification.toString());
                sendRequest("tools/list", new JSONObject());
                notifyStatus("MCP inicializado. Buscando herramientas…");
                return;
            }
            if (message.has("result")) {
                JSONObject result = message.optJSONObject("result");
                if (result != null && result.has("tools")) {
                    JSONArray tools = result.optJSONArray("tools");
                    if (tools == null) tools = new JSONArray();
                    if (listener != null) listener.onTools(tools);
                    notifyStatus("MCP conectado. Herramientas encontradas: " + tools.length());
                }
            } else if (message.has("error")) {
                notifyStatus("XiaoZhi devolvió un error del protocolo MCP.");
                Log.w(TAG, "Respuesta JSON-RPC con error.");
            }
        } catch (Exception e) { Log.w(TAG, "No se pudo interpretar una respuesta MCP."); }
    }

    private void sendRequest(String method, JSONObject params) throws Exception {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", ids.getAndIncrement());
        message.put("method", method);
        message.put("params", params);
        WebSocket current = socket;
        if (current != null) current.send(message.toString());
    }

    private void notifyStatus(String status) {
        Listener current = listener;
        if (current != null) current.onStatus(status);
        Log.i(TAG, status);
    }

    public synchronized void disconnect() {
        if (socket != null) { socket.close(1000, "Bellota pausa"); socket = null; }
        initialized = false;
    }
}
