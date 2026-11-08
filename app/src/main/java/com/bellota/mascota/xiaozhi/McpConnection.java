package com.bellota.mascota.xiaozhi;

import android.util.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente MCP WebSocket para descubrir las herramientas publicadas por XiaoZhi.
 * La conexión MCP es independiente de la activación OTA y del transporte de audio.
 */
public final class McpConnection {
    private static final String TAG = "BellotaMCP";
    private final String endpoint;
    private final OkHttpClient client;
    private final AtomicInteger requestIds = new AtomicInteger(1);
    private volatile WebSocket socket;
    private volatile boolean manuallyClosed;
    private volatile boolean initialized;

    public McpConnection(String endpoint) {
        this.endpoint = endpoint;
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public synchronized void connect() {
        if (socket != null) return;
        manuallyClosed = false;
        Request request = new Request.Builder()
                .url(endpoint)
                .header("User-Agent", "Bellota-Android/0.1.0")
                .build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                Log.i(TAG, "WebSocket MCP conectado; iniciando negociación.");
                sendInitialize(webSocket);
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleMessage(webSocket, text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                handleMessage(webSocket, bytes.utf8());
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.w(TAG, "MCP desconectado: " + code + " " + reason);
                socket = null;
                initialized = false;
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "Error de conexión MCP", t);
                socket = null;
                initialized = false;
            }
        });
    }

    private void sendInitialize(WebSocket ws) {
        try {
            JSONObject params = new JSONObject();
            params.put("protocolVersion", "2024-11-05");
            params.put("capabilities", new JSONObject());
            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", "Bellota-Android");
            clientInfo.put("version", "0.1.0");
            params.put("clientInfo", clientInfo);
            sendRequest(ws, "initialize", params);
        } catch (Exception e) {
            Log.e(TAG, "No se pudo iniciar MCP", e);
        }
    }

    private void handleMessage(WebSocket ws, String raw) {
        try {
            JSONObject message = new JSONObject(raw);
            if (message.has("id") && message.has("result")) {
                int id = message.optInt("id", -1);
                JSONObject result = message.optJSONObject("result");
                if (id == 1 && result != null && !initialized) {
                    Log.i(TAG, "MCP inicializado: " + result.optString("protocolVersion"));
                    ws.send(new JSONObject().put("jsonrpc", "2.0")
                            .put("method", "notifications/initialized").toString());
                    initialized = true;
                    sendRequest(ws, "tools/list", new JSONObject());
                } else if (result != null && result.has("tools")) {
                    JSONArray tools = result.optJSONArray("tools");
                    Log.i(TAG, "Herramientas MCP disponibles: " + (tools == null ? 0 : tools.length()));
                    if (tools != null) {
                        for (int i = 0; i < tools.length(); i++) {
                            JSONObject tool = tools.optJSONObject(i);
                            if (tool != null) Log.i(TAG, "Herramienta: " + tool.optString("name"));
                        }
                    }
                }
            } else if (message.has("error")) {
                Log.e(TAG, "Error JSON-RPC MCP: " + message.optJSONObject("error"));
            } else if (message.has("method")) {
                Log.i(TAG, "Notificación MCP: " + message.optString("method"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Mensaje MCP no reconocido: " + raw, e);
        }
    }

    private void sendRequest(WebSocket ws, String method, JSONObject params) throws Exception {
        int id = requestIds.getAndIncrement();
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        ws.send(message.toString());
    }

    public synchronized void disconnect() {
        manuallyClosed = true;
        if (socket != null) {
            socket.close(1000, "Bellota cerrada");
            socket = null;
        }
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
