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

/**
 * Punto de acceso MCP de XiaoZhi.
 * IMPORTANTE: acá Bellota es el servidor MCP. XiaoZhi manda initialize / tools/list /
 * tools/call y Bellota responde. (Antes estaba al revés: Bellota mandaba initialize
 * y nunca contestaba lo que pedía XiaoZhi.)
 */
public final class McpConnection {
    private static final String TAG = "BellotaMCP";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private volatile WebSocket socket;
    private volatile Listener listener;
    private volatile JSONArray tools = new JSONArray();
    private volatile ToolHandler handler;

    public interface Listener { void onStatus(String status); }

    /** Ejecuta una herramienta de la mascota y devuelve el texto de resultado. */
    public interface ToolHandler { String call(String name, JSONObject arguments) throws Exception; }

    /** Ejemplo de definición: {"name":"set_emotion","description":"...","inputSchema":{...}} */
    public synchronized void connect(String endpoint, JSONArray toolDefs, ToolHandler toolHandler, Listener callback) {
        disconnect();
        listener = callback;
        tools = toolDefs == null ? new JSONArray() : toolDefs;
        handler = toolHandler;
        if (endpoint == null || !endpoint.startsWith("wss://")) {
            notifyStatus("La dirección MCP debe comenzar con wss://");
            return;
        }
        Request request = new Request.Builder().url(endpoint)
                .header("User-Agent", "Bellota-Android/0.4.4").build();
        notifyStatus("Conectando con XiaoZhi…");
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) { notifyStatus("MCP conectado. Esperando a XiaoZhi…"); }
            @Override public void onMessage(WebSocket ws, String text) { handleMessage(ws, text); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) {
                notifyStatus("Conexión MCP interrumpida: " + (r == null ? String.valueOf(t.getMessage()) : "HTTP " + r.code()));
                synchronized (McpConnection.this) { socket = null; }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                notifyStatus("Conexión MCP cerrada.");
                synchronized (McpConnection.this) { socket = null; }
            }
        });
    }

    private void handleMessage(WebSocket ws, String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String method = msg.optString("method", "");
            if (method.isEmpty()) return;          // respuestas ajenas: ignorar
            if (!msg.has("id")) return;            // notificaciones (notifications/initialized, etc.)
            Object id = msg.get("id");
            JSONObject params = msg.optJSONObject("params");
            switch (method) {
                case "initialize": {
                    JSONObject result = new JSONObject()
                            .put("protocolVersion", "2024-11-05")
                            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                            .put("serverInfo", new JSONObject().put("name", "Bellota").put("version", "0.4.4"));
                    reply(ws, id, result);
                    notifyStatus("XiaoZhi inicializó MCP.");
                    break;
                }
                case "tools/list":
                    reply(ws, id, new JSONObject().put("tools", tools));
                    notifyStatus("Herramientas enviadas a XiaoZhi: " + tools.length());
                    break;
                case "tools/call": {
                    String name = params == null ? "" : params.optString("name", "");
                    JSONObject args = params == null ? new JSONObject() : params.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    boolean isError = false;
                    String text;
                    try {
                        ToolHandler h = handler;
                        text = h == null ? "Sin manejador" : h.call(name, args);
                    } catch (Exception e) { isError = true; text = String.valueOf(e.getMessage()); }
                    JSONObject result = new JSONObject()
                            .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)))
                            .put("isError", isError);
                    reply(ws, id, result);
                    break;
                }
                case "ping": reply(ws, id, new JSONObject()); break;
                default: {
                    JSONObject err = new JSONObject().put("code", -32601).put("message", "Method not found: " + method);
                    ws.send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", err).toString());
                }
            }
        } catch (Exception e) { Log.w(TAG, "No se pudo interpretar un mensaje MCP", e); }
    }

    private void reply(WebSocket ws, Object id, JSONObject result) throws Exception {
        ws.send(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString());
    }

    private void notifyStatus(String status) {
        Listener l = listener;
        if (l != null) l.onStatus(status);
        Log.i(TAG, status);
    }

    public synchronized void disconnect() {
        if (socket != null) { socket.close(1000, "Bellota pausa"); socket = null; }
    }
}
