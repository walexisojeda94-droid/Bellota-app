package com.bellota.mascota.voice.provider;

/** Converts signed PCM16 samples to little-endian bytes without allocating per frame. */
final class Pcm16Encoder {
    private Pcm16Encoder() { }

    static void encode(short[] samples, int count, byte[] destination) {
        if (samples == null || destination == null || count < 0
                || count > samples.length || count > destination.length / 2) {
            throw new IllegalArgumentException("Invalid PCM16 buffer or sample count.");
        }
        for (int i = 0; i < count; i++) {
            destination[i * 2] = (byte) (samples[i] & 0xff);
            destination[i * 2 + 1] = (byte) ((samples[i] >>> 8) & 0xff);
        }
    }
}
