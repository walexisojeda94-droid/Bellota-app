package com.bellota.mascota.voice.provider;

import org.junit.Test;
import static org.junit.Assert.*;

public class Pcm16EncoderTest {
    @Test public void encodesSignedSamplesAsLittleEndianPcm16() {
        short[] samples = new short[]{0x1234, -2, Short.MIN_VALUE};
        byte[] output = new byte[8];
        Pcm16Encoder.encode(samples, 3, output);
        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xfe, (byte) 0xff, 0, (byte) 0x80, 0, 0}, output);
    }

    @Test public void onlyWritesTheRequestedSamples() {
        short[] samples = new short[]{1, 2, 3};
        byte[] output = new byte[]{9, 9, 9, 9, 9, 9};
        Pcm16Encoder.encode(samples, 1, output);
        assertArrayEquals(new byte[]{1, 0, 9, 9, 9, 9}, output);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDestinationTooSmall() {
        Pcm16Encoder.encode(new short[]{1, 2}, 2, new byte[2]);
    }
}
