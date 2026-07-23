package com.masteryj.modgui.theme;

import java.util.Locale;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

/**
 * The YJHack GUI kit: the single source of truth for colours, metrics and the small
 * shared draw/format/key helpers used by both the widgets ({@code component}) and the
 * screens ({@code ModGuiClient}). All colours are ARGB.
 *
 * <p>Extracted verbatim from {@code ModGuiClient} during the 2026-07-23 cleanup so the
 * visual kit is decoupled from the screen graph. Values are unchanged — the panel stays
 * translucent (no blur, no global darkening) and the world remains visible behind it.
 */
public final class YjTheme {
   // Full-screen tint — the ONLY layer over the whole world. Very light.
   public static final int SCREEN_TINT   = 0x22060A0F;   // alpha 0x22 (~13%)

   // Glass panels (translucent so the world shows through).
   public static final int PANEL         = 0xB00E1620;   // ~69% main panel
   public static final int SIDEBAR       = 0xC00B121B;   // slightly darker, still translucent
   public static final int HEADER        = 0xCC0C141E;
   public static final int CARD          = 0x9C121C28;
   public static final int CARD_HOVER    = 0xBE1A2836;
   public static final int CTRL          = 0x8C13202E;
   public static final int CTRL_HOVER    = 0xB01D2E40;

   public static final int BORDER        = 0x30FFFFFF;   // hairline light border
   public static final int BORDER_SOFT   = 0x18FFFFFF;
   public static final int DIVIDER       = 0x22FFFFFF;

   public static final int ACCENT        = 0xFF35E0C8;   // teal

   public static final int TEXT          = 0xFFF3F6FA;
   public static final int TEXT_DIM      = 0xFFA6B6C8;
   public static final int TEXT_MUTED    = 0xFF6E7F93;

   public static final int SUCCESS       = 0xFF4BD16A;
   public static final int WARNING       = 0xFFF5C147;

   public static final int TRACK_OFF     = 0xFF33404E;
   public static final int KNOB          = 0xFFF3F6FA;

   public static final int PAD    = 10;
   public static final int ROW    = 24;
   public static final int CTRL_H = 20;

   /** Mouse buttons are encoded as key codes offset by this so both share one int. */
   public static final int MOUSE_KEY_OFFSET = 1000;

   private YjTheme() {
   }

   /** Flat panel with a hairline border. */
   public static void panel(DrawContext c, int x, int y, int w, int h, int fill, int border) {
      c.fill(x, y, x + w, y + h, fill);
      c.fill(x, y, x + w, y + 1, border);
      c.fill(x, y + h - 1, x + w, y + h, border);
      c.fill(x, y, x + 1, y + h, border);
      c.fill(x + w - 1, y, x + w, y + h, border);
   }

   /** Pill / rounded bar (corner pixels trimmed). */
   public static void pill(DrawContext c, int x, int y, int w, int h, int color) {
      if (w <= 0 || h <= 0) return;
      c.fill(x + 1, y, x + w - 1, y + h, color);
      c.fill(x, y + 1, x + w, y + h - 1, color);
   }

   /** Encode a mouse button as a key code. */
   public static int encodeMouse(int button) {
      return MOUSE_KEY_OFFSET + Math.max(0, button);
   }

   /** Human-readable name for a key code (mouse or keysym), "None" when unbound. */
   public static Text keyName(int keyCode) {
      if (keyCode >= MOUSE_KEY_OFFSET) {
         return InputUtil.Type.MOUSE.createFromCode(keyCode - MOUSE_KEY_OFFSET).getLocalizedText();
      }
      if (keyCode > 0) {
         return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText();
      }
      return Text.literal("None");
   }

   /** Compact number formatting: integers stay integer, others show two decimals. */
   public static String fmt(double v) {
      return Math.abs(v - Math.rint(v)) < 1.0E-4
         ? String.valueOf((int) Math.rint(v))
         : String.format(Locale.ROOT, "%.2f", v);
   }
}
