package com.bellota.mascota.voice.provider;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceProviderManagerTest {
    @Test public void switchesToSecondaryAfterPrimaryFailure() {
        FakeProvider primary = new FakeProvider("openai");
        FakeProvider secondary = new FakeProvider("gemini");
        RecordingListener listener = new RecordingListener();
        VoiceProviderManager manager = new VoiceProviderManager(primary, secondary, listener);
        manager.connect(config());
        primary.fail(VoiceProvider.Failure.NETWORK);
        assertEquals(1, primary.connectCount);
        assertEquals(1, primary.disconnectCount);
        assertEquals(1, secondary.connectCount);
        assertEquals("gemini", manager.getActiveProviderId());
        assertEquals(0, listener.localFallbackCount);
    }

    @Test public void requestsLocalFallbackOnlyAfterBothProvidersFail() {
        FakeProvider primary = new FakeProvider("openai");
        FakeProvider secondary = new FakeProvider("gemini");
        RecordingListener listener = new RecordingListener();
        VoiceProviderManager manager = new VoiceProviderManager(primary, secondary, listener);
        manager.connect(config());
        primary.fail(VoiceProvider.Failure.QUOTA);
        secondary.fail(VoiceProvider.Failure.AUTHENTICATION);
        assertNull(manager.getActiveProviderId());
        assertEquals(1, listener.localFallbackCount);
        assertEquals(VoiceProvider.Failure.AUTHENTICATION, listener.lastFailure);
        primary.fail(VoiceProvider.Failure.NETWORK);
        assertEquals(1, listener.localFallbackCount);
    }

    @Test public void disconnectIgnoresLateProviderEvents() {
        FakeProvider primary = new FakeProvider("openai");
        RecordingListener listener = new RecordingListener();
        VoiceProviderManager manager = new VoiceProviderManager(primary, null, listener);
        manager.connect(config());
        manager.disconnect();
        primary.fail(VoiceProvider.Failure.NETWORK);
        assertEquals(0, listener.localFallbackCount);
        assertFalse(manager.isConnected());
    }

    @Test public void invalidPcmRangeIsRejectedBeforeSending() {
        FakeProvider primary = new FakeProvider("openai");
        VoiceProviderManager manager = new VoiceProviderManager(primary, null, new RecordingListener());
        manager.connect(config());
        try {
            manager.sendAudio(new byte[4], 3, 2);
            fail("El rango inválido debe rechazarse.");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, primary.audioSendCount);
        }
    }

    @Test public void disconnectExceptionDoesNotBlockSecondaryFallback() {
        FakeProvider primary = new FakeProvider("openai");
        FakeProvider secondary = new FakeProvider("gemini");
        primary.throwOnDisconnect = true;
        RecordingListener listener = new RecordingListener();
        VoiceProviderManager manager = new VoiceProviderManager(primary, secondary, listener);
        manager.connect(config());
        primary.fail(VoiceProvider.Failure.NETWORK);
        assertEquals("gemini", manager.getActiveProviderId());
        assertEquals(0, listener.localFallbackCount);
    }

    @Test public void throwingApplicationCallbackDoesNotBreakProviderFailover() {
        FakeProvider primary = new FakeProvider("openai");
        FakeProvider secondary = new FakeProvider("gemini");
        RecordingListener listener = new RecordingListener() {
            @Override public void onStateChanged(VoiceProvider.State state, String providerId, String detail) {
                throw new IllegalStateException("UI callback failure");
            }
        };
        VoiceProviderManager manager = new VoiceProviderManager(primary, secondary, listener);
        manager.connect(config());
        primary.fail(VoiceProvider.Failure.NETWORK);
        assertEquals("gemini", manager.getActiveProviderId());
    }

    private static VoiceProvider.SessionConfig config() {
        return new VoiceProvider.SessionConfig("es-AR", "", "Bellota", "[]", 24000);
    }

    private static final class RecordingListener implements VoiceProviderManager.Listener {
        int localFallbackCount;
        VoiceProvider.Failure lastFailure;
        @Override public void onStateChanged(VoiceProvider.State state, String providerId, String detail) { }
        @Override public void onUserTranscript(String text, boolean isFinal) { }
        @Override public void onAssistantTranscript(String text, boolean isFinal) { }
        @Override public void onAudioOutput(byte[] pcm16, int sampleRateHz, int channels) { }
        @Override public void onAssistantSpeaking(boolean speaking) { }
        @Override public void onToolCall(String callId, String name, String argumentsJson) { }
        @Override public void onLocalFallbackRequested(VoiceProvider.Failure reason, String detail) {
            localFallbackCount++;
            lastFailure = reason;
        }
    }

    private static final class FakeProvider implements VoiceProvider {
        final String id;
        Listener listener;
        int connectCount;
        int disconnectCount;
        int audioSendCount;
        boolean connected;
        boolean throwOnDisconnect;
        FakeProvider(String id) { this.id = id; }
        @Override public String getProviderId() { return id; }
        @Override public void setListener(Listener value) { listener = value; }
        @Override public void connect(SessionConfig config) {
            connectCount++;
            connected = true;
            if (listener != null) listener.onStateChanged(State.CONNECTED, "ok");
        }
        void fail(Failure failure) {
            connected = false;
            if (listener != null) listener.onError(failure, true, "simulated");
        }
        @Override public void sendAudio(byte[] pcm16, int offset, int length) { audioSendCount++; }
        @Override public void finishAudioInput() { }
        @Override public void sendText(String text) { }
        @Override public void sendToolResult(String callId, String result, boolean isError) { }
        @Override public void interrupt() { }
        @Override public void disconnect() {
            disconnectCount++;
            connected = false;
            if (throwOnDisconnect) throw new IllegalStateException("simulated disconnect failure");
        }
        @Override public boolean isConnected() { return connected; }
    }
}
