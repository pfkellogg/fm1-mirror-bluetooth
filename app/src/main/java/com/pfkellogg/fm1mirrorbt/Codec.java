package com.pfkellogg.fm1mirrorbt;

import java.io.ByteArrayOutputStream;

/**
 * The FM-1's screen messages (the same as FM-1 Mirror's). The app says hello (F0 7D 'F' 'S' 00 F7,
 * or 01 to have the whole screen sent again) about once a second; while it does, the FM-1 sends
 * every row it paints as F0 7D 'F' 'S' 01, the row and first column (two 7-bit bytes each), then
 * runs of four bytes: a count, and the RGB565 colour in 7, 7 and 2 bits. The Big Screen firmware
 * answers on the connection that asked, so over Bluetooth MIDI too.
 */
final class Codec {
    private Codec() {}

    static final int SIZE = 240;
    static final byte[] HELLO = {(byte) 0xf0, 0x7d, 'F', 'S', 0, (byte) 0xf7};
    /** Hello, and send the whole screen again. */
    static final byte[] HELLO_ALL = {(byte) 0xf0, 0x7d, 'F', 'S', 1, (byte) 0xf7};

    // ---- the screen

    /** Paints one row message into `pixels` (RGB565, row by row); false if it isn't one. */
    static boolean apply(int[] pixels, byte[] m) {
        int n = m.length;
        if (n < 10 || m[0] != (byte) 0xf0 || m[1] != 0x7d || m[2] != 'F' || m[3] != 'S' || m[4] != 1 || m[n - 1] != (byte) 0xf7)
            return false;
        int y = (m[5] & 127) | (m[6] & 127) << 7, x = (m[7] & 127) | (m[8] & 127) << 7;
        if (y >= SIZE) return false;
        for (int i = 9; i + 4 <= n - 1; i += 4) {
            int c = (m[i + 1] & 127) | (m[i + 2] & 127) << 7 | (m[i + 3] & 127) << 14;
            for (int k = 0; k < (m[i] & 127); k++, x++)
                if (x < SIZE) pixels[y * SIZE + x] = c & 0xffff;
        }
        return true;
    }

    /** RGB565 to 8-bit red, green, blue. */
    static int[] rgb(int c) {
        int r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
        return new int[] {r << 3 | r >> 2, g << 2 | g >> 4, b << 3 | b >> 2};
    }

    /** Ways to show the screen, easier to read for some eyes. */
    enum Look {
        COLOR("Color"), BRIGHT("Bright color"), YELLOW("Yellow on black"), WHITE("White on black"), INVERTED("Black on white");

        final String label;

        Look(String label) { this.label = label; }

        /** An RGB565 pixel as ARGB. */
        int paint(int c) {
            int[] p = rgb(c);
            float luma = (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 255f;
            // The FM-1 draws light text on black: lift anything that isn't background to full strength.
            int lit = (int) (Math.min(1f, luma * 1.6f) * 255f);
            switch (this) {
                case BRIGHT: return lift(bright(p[0]), bright(p[1]), bright(p[2]));
                case YELLOW: return argb(lit, lit, 0);
                case WHITE: return argb(lit, lit, lit);
                case INVERTED: return argb(255 - lit, 255 - lit, 255 - lit);
                default: return argb(p[0], p[1], p[2]);
            }
        }

        private static int bright(int v) { return (int) Math.min(255f, v * 1.6f); }

        /**
         * Brings any colour that isn't the black background up to at least 80% brightness, keeping
         * its hue: a dark purple on black becomes a light lilac that can be read.
         */
        private static int lift(int r, int g, int b) {
            float l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f, want = 0.8f;
            if (l > 0.03f && l < want) {
                float t = (want - l) / (1f - l);
                r += (int) ((255 - r) * t);
                g += (int) ((255 - g) * t);
                b += (int) ((255 - b) * t);
            }
            return argb(r, g, b);
        }

        private static int argb(int r, int g, int b) { return 0xff000000 | r << 16 | g << 8 | b; }
    }

    /** SysEx out of a byte stream that may arrive in pieces (Android hands MIDI over a few bytes at a time). */
    static final class Sysex {
        interface Sink { void message(byte[] m); }

        private final Sink sink;
        private final ByteArrayOutputStream part = new ByteArrayOutputStream();
        private boolean inside;

        Sysex(Sink sink) { this.sink = sink; }

        void feed(byte[] data, int offset, int count) {
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 255;
                if (b == 0xf0) {
                    part.reset();
                    part.write(b);
                    inside = true;
                } else if (b >= 0xf8) {
                    // clock and other real-time bytes may come between any two others
                } else if (inside && b == 0xf7) {
                    part.write(b);
                    inside = false;
                    sink.message(part.toByteArray());
                } else if (inside && b < 0x80) {
                    part.write(b);
                } else {
                    inside = false; // a note or other message: not SysEx
                }
            }
        }
    }
}
