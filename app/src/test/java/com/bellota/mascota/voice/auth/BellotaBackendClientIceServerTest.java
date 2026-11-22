package com.bellota.mascota.voice.auth;

import org.json.JSONArray;
import org.json.JSONException;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BellotaBackendClientIceServerTest {
    @Test public void parsesCloudflareStunAndAuthenticatedTurnUrls() throws JSONException {
        JSONArray json = new JSONArray("[{\"urls\":[\"stun:stun.cloudflare.com:3478\"]},{\"urls\":[\"turn:turn.cloudflare.com:3478?transport=udp\",\"turns:turn.cloudflare.com:443?transport=tcp\"],\"username\":\"short-user\",\"credential\":\"short-password\"}]");
        List<BellotaBackendClient.IceServerConfig> servers = BellotaBackendClient.parseIceServers(json);
        assertEquals(2, servers.size());
        assertEquals("stun:stun.cloudflare.com:3478", servers.get(0).urls.get(0));
        assertEquals(2, servers.get(1).urls.size());
        assertEquals("short-user", servers.get(1).username);
        assertEquals("short-password", servers.get(1).credential);
    }

    @Test public void rejectsUnsupportedAndMalformedUrls() throws JSONException {
        JSONArray json = new JSONArray("[{\"urls\":[\"http://invalid\",\"file:///tmp/x\"]},{\"urls\":\"turn:turn.cloudflare.com:443?transport=udp\"}]");
        List<BellotaBackendClient.IceServerConfig> servers = BellotaBackendClient.parseIceServers(json);
        assertEquals(1, servers.size());
        assertEquals("turn:turn.cloudflare.com:443?transport=udp", servers.get(0).urls.get(0));
    }

    @Test public void missingTurnConfigurationProducesEmptyList() {
        assertTrue(BellotaBackendClient.parseIceServers(null).isEmpty());
        assertTrue(BellotaBackendClient.parseIceServers(new JSONArray()).isEmpty());
    }
}
