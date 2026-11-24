package dev.floons.client;

import net.minecraft.client.gui.DrawContext;

/** Logo des Floons Clients: der Spielerkopf (8x8 Pixel), pixelgenau gezeichnet. */
public final class FloonsLogo {
    private FloonsLogo() {}

    private static final int[] PX = {
        0x3675D9, 0x3675D9, 0x3675D9, 0x326CC3, 0x3675D9, 0x3675D9, 0x3675D9, 0x3675D9,
        0x326CC3, 0x3675D9, 0x3675D9, 0x3675D9, 0x326CC3, 0x3675D9, 0x3675D9, 0x326CC3,
        0x326CC3, 0x3675D9, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0x3675D9, 0x3675D9,
        0x3675D9, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0x3675D9,
        0xB2C8DD, 0x6186C9, 0x6186C9, 0xB2C8DD, 0xB2C8DD, 0x6186C9, 0x6186C9, 0xB2C8DD,
        0xB2C8DD, 0x6186C9, 0x264C9F, 0xB2C8DD, 0xB2C8DD, 0x264C9F, 0x6186C9, 0xB2C8DD,
        0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD,
        0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD, 0x264C9F, 0xB2C8DD, 0xB2C8DD, 0xB2C8DD,
    };

    /** Zeichnet den Kopf mit Kantenlaenge size (GUI-Pixel) an x/y. */
    public static void draw(DrawContext ctx, int x, int y, int size) {
        for (int j = 0; j < 8; j++) {
            int y0 = y + j * size / 8, y1 = y + (j + 1) * size / 8;
            for (int i = 0; i < 8; i++) {
                int x0 = x + i * size / 8, x1 = x + (i + 1) * size / 8;
                ctx.fill(x0, y0, x1, y1, 0xFF000000 | PX[j * 8 + i]);
            }
        }
    }
}
