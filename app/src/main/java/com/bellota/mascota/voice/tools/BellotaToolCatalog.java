package com.bellota.mascota.voice.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Catálogo canónico de acciones de Bellota.
 *
 * Los proveedores reciben estas mismas declaraciones; cada adaptador solo
 * traduce el sobre al formato nativo de OpenAI o Gemini.
 */
public final class BellotaToolCatalog {
    private BellotaToolCatalog() { }

    public static JSONArray definitions() throws JSONException {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_bellota_status",
                "Consulta el estado actual de Bellota: ánimo, energía, diversión y si está despierta.", object()));
        tools.put(tool("set_bellota_emotion",
                "Cambia la expresión emocional visible de Bellota. No la despierta si está dormida.",
                schema("emotion", stringEnum("neutral","happy","thinking","surprised","sleepy","calm","curious","angry","bored","offended"))));
        tools.put(tool("speak_as_bellota",
                "Hace que Bellota diga una frase breve en voz alta.",
                schema("text", string("Frase de hasta 240 caracteres."))));
        tools.put(tool("interact_with_bellota",
                "Registra una interacción del usuario para actualizar el vínculo y el estado de Bellota.",
                schema("kind", stringEnum("attention","caress","affectionate","apology"))));
        tools.put(tool("play_with_bellota",
                "Inicia un minijuego visual con Bellota.",
                schema("game", stringEnum("ball","bubbles","fly","stars"))));
        tools.put(tool("get_weather",
                "Consulta el clima actual de la ubicación del dispositivo. Informa temperatura, sensación térmica, humedad y viento.",
                object()));
        tools.put(tool("get_battery_status",
                "Consulta el porcentaje de batería del dispositivo.",
                object()));
        tools.put(tool("get_device_volume",
                "Consulta el volumen multimedia actual del dispositivo como porcentaje.",
                object()));
        tools.put(tool("set_device_volume",
                "Establece el volumen multimedia del dispositivo entre 0 y 100 por ciento.",
                schema("percent", integer(0,100))));
        tools.put(tool("get_current_datetime",
                "Consulta la fecha y hora locales del dispositivo en español.",
                object()));
        return tools;
    }

    private static JSONObject tool(String name, String description, JSONObject parameters) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", parameters);
    }

    private static JSONObject object() throws JSONException {
        return new JSONObject().put("type","object").put("properties",new JSONObject());
    }

    private static JSONObject schema(String name, JSONObject property) throws JSONException {
        return object().put("properties", new JSONObject().put(name,property))
                .put("required",new JSONArray().put(name));
    }

    private static JSONObject string(String description) throws JSONException {
        return new JSONObject().put("type","string").put("description",description);
    }

    private static JSONObject stringEnum(String... values) throws JSONException {
        JSONArray choices = new JSONArray();
        for (String value : values) choices.put(value);
        return new JSONObject().put("type","string").put("enum",choices);
    }

    private static JSONObject integer(int minimum, int maximum) throws JSONException {
        return new JSONObject().put("type","integer").put("minimum",minimum).put("maximum",maximum);
    }
}
