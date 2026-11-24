package com.bellota.mascota.voice.tools;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BellotaToolCatalogTest {
    @Test public void canonicalCatalogContainsAllSharedCapabilities() throws Exception {
        JSONArray tools = BellotaToolCatalog.definitions();
        String[] expected = {
                "get_bellota_status", "set_bellota_emotion", "speak_as_bellota",
                "interact_with_bellota", "play_with_bellota", "get_weather",
                "get_battery_status", "get_device_volume", "set_device_volume",
                "get_current_datetime"
        };
        assertEquals(expected.length, tools.length());
        for (String name : expected) assertNotNull(find(tools, name));
    }

    @Test public void volumeSetterHasBoundedRequiredPercent() throws Exception {
        JSONObject tool = find(BellotaToolCatalog.definitions(), "set_device_volume");
        JSONObject schema = tool.getJSONObject("inputSchema");
        assertEquals("percent", schema.getJSONArray("required").getString(0));
        JSONObject percent = schema.getJSONObject("properties").getJSONObject("percent");
        assertEquals("integer", percent.getString("type"));
        assertEquals(0, percent.getInt("minimum"));
        assertEquals(100, percent.getInt("maximum"));
    }

    private static JSONObject find(JSONArray tools, String name) {
        for (int i = 0; i < tools.length(); i++) {
            JSONObject item = tools.optJSONObject(i);
            if (item != null && name.equals(item.optString("name"))) return item;
        }
        return null;
    }
}
