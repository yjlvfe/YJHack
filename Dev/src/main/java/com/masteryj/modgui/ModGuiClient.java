package com.masteryj.modgui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;

/**
 * YJHack in-game control panel.
 *
 * <p>Rebuilt 2026-07-23 (v2). Key differences from the prior version:
 * <ul>
 *   <li><b>No vanilla blur / darkening.</b> {@link YjScreen#renderBackground} is
 *       overridden to a single very light tint (~0x22 alpha) — it never calls
 *       {@code super.renderBackground()}, so {@code Screen.render → renderBackground →
 *       applyBlur()/renderDarkening()} never runs. The world and nearby players stay
 *       clearly visible; the previous fullscreen blur was the cause of both the
 *       "too dark" look and the render-thread stall with Iris/Sodium loaded.</li>
 *   <li><b>Real custom controls</b> (toggle switches, sliders with numeric entry,
 *       keybind capture, status chips, hover tooltips) instead of vanilla buttons.</li>
 *   <li><b>Typed config</b> — each settings screen edits the module's OWN
 *       {@code Config} type and pushes it via the module's typed
 *       {@code applyRuntimeConfig()} / {@code saveConfigStatic()} methods. No
 *       reflection, no field-name copying between mismatched objects.</li>
 *   <li><b>No per-frame / per-keystroke saving.</b> Edits apply live in memory;
 *       the file is written on a short debounce, on slider release, on Save, and on
 *       close. Sliders never write the config file while being dragged.</li>
 * </ul>
 */
public final class ModGuiClient implements ClientModInitializer {
   private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-ModGui");
   private static final int MOUSE_KEY_OFFSET = 1000;

   private KeyBinding openGuiKey;

   @Override
   public void onInitializeClient() {
      this.openGuiKey = KeyBindingHelper.registerKeyBinding(
         new KeyBinding("key.modgui.open", InputUtil.Type.KEYSYM, 344, "category.modgui"));
      ClientTickEvents.END_CLIENT_TICK.register(client -> {
         while (this.openGuiKey.wasPressed()) {
            if (!(client.currentScreen instanceof YjScreen)) {
               client.setScreen(new DashboardScreen(null));
            }
         }
      });
   }

   // =====================================================================
   //  THEME
   // =====================================================================
   /** Single source of truth for colours and metrics. All colours are ARGB. */
   static final class Theme {
      // Full-screen tint — the ONLY layer over the whole world. Very light.
      static final int SCREEN_TINT   = 0x22060A0F;   // alpha 0x22 (~13%)

      // Glass panels (translucent so the world shows through).
      static final int PANEL         = 0xB00E1620;   // ~69% main panel
      static final int SIDEBAR       = 0xC00B121B;   // slightly darker, still translucent
      static final int HEADER        = 0xCC0C141E;
      static final int FOOTER        = 0xC00B121B;
      static final int CARD          = 0x9C121C28;
      static final int CARD_HOVER    = 0xBE1A2836;
      static final int CTRL          = 0x8C13202E;
      static final int CTRL_HOVER    = 0xB01D2E40;

      static final int BORDER        = 0x30FFFFFF;   // hairline light border
      static final int BORDER_SOFT   = 0x18FFFFFF;
      static final int DIVIDER       = 0x22FFFFFF;

      static final int ACCENT        = 0xFF35E0C8;   // teal
      static final int ACCENT_DIM    = 0xFF1E9E8E;

      static final int TEXT          = 0xFFF3F6FA;
      static final int TEXT_DIM      = 0xFFA6B6C8;
      static final int TEXT_MUTED    = 0xFF6E7F93;

      static final int SUCCESS       = 0xFF4BD16A;
      static final int WARNING       = 0xFFF5C147;
      static final int ERROR         = 0xFFF06A5A;

      static final int TRACK_OFF     = 0xFF33404E;
      static final int KNOB          = 0xFFF3F6FA;

      static final int PAD    = 10;
      static final int ROW    = 24;
      static final int CTRL_H = 20;

      private Theme() {
      }

      /** Flat panel with a hairline border. */
      static void panel(DrawContext c, int x, int y, int w, int h, int fill, int border) {
         c.fill(x, y, x + w, y + h, fill);
         c.fill(x, y, x + w, y + 1, border);
         c.fill(x, y + h - 1, x + w, y + h, border);
         c.fill(x, y, x + 1, y + h, border);
         c.fill(x + w - 1, y, x + w, y + h, border);
      }

      /** Pill / rounded bar (corner pixels trimmed). */
      static void pill(DrawContext c, int x, int y, int w, int h, int color) {
         if (w <= 0 || h <= 0) return;
         c.fill(x + 1, y, x + w - 1, y + h, color);
         c.fill(x, y + 1, x + w, y + h - 1, color);
      }
   }

   // =====================================================================
   //  SHARED HELPERS
   // =====================================================================
   private static int normalizeKey(int keyCode) {
      if (keyCode >= MOUSE_KEY_OFFSET) return keyCode;
      return keyCode <= 0 ? -1 : keyCode;
   }

   private static int encodeMouse(int button) {
      return MOUSE_KEY_OFFSET + Math.max(0, button);
   }

   static Text keyName(int keyCode) {
      if (keyCode >= MOUSE_KEY_OFFSET) {
         return InputUtil.Type.MOUSE.createFromCode(keyCode - MOUSE_KEY_OFFSET).getLocalizedText();
      }
      if (keyCode > 0) {
         return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText();
      }
      return Text.literal("None");
   }

   static String fmt(double v) {
      return Math.abs(v - Math.rint(v)) < 1.0E-4
         ? String.valueOf((int) Math.rint(v))
         : String.format(Locale.ROOT, "%.2f", v);
   }

   // =====================================================================
   //  WIDGETS
   // =====================================================================
   /** A real sliding on/off switch with an inline label. */
   static final class ToggleSwitch extends ClickableWidget {
      private boolean value;
      private final Consumer<Boolean> onChange;
      private final String label;

      ToggleSwitch(int x, int y, int w, int h, String label, boolean value, Consumer<Boolean> onChange) {
         super(x, y, w, h, Text.literal(label));
         this.label = label;
         this.value = value;
         this.onChange = onChange;
      }

      @Override
      protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
         boolean hovered = this.isMouseOver(mouseX, mouseY);
         Theme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? Theme.CTRL_HOVER : Theme.CTRL, Theme.BORDER_SOFT);
         TextRenderer tr = MinecraftClient.getInstance().textRenderer;
         ctx.drawText(tr, this.label, getX() + 8, getY() + (getHeight() - 8) / 2, Theme.TEXT, false);

         int trackW = 26;
         int trackH = 12;
         int tx = getX() + getWidth() - trackW - 8;
         int ty = getY() + (getHeight() - trackH) / 2;
         Theme.pill(ctx, tx, ty, trackW, trackH, this.value ? Theme.ACCENT : Theme.TRACK_OFF);
         int knobX = this.value ? tx + trackW - 11 : tx + 1;
         Theme.pill(ctx, knobX, ty + 1, 10, trackH - 2, Theme.KNOB);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.active && this.visible && button == 0 && this.isMouseOver(mouseX, mouseY)) {
            this.value = !this.value;
            this.onChange.accept(this.value);
            return true;
         }
         return false;
      }

      @Override
      protected void appendClickableNarrations(NarrationMessageBuilder builder) {
         builder.put(NarrationPart.TITLE, Text.literal(this.label + ": " + (this.value ? "on" : "off")));
      }
   }

   /**
    * Custom-drawn slider built on the vanilla {@link SliderWidget} (which handles
    * the drag maths and keyboard) but rendered in the YJHack theme. Reports the
    * value in its real domain, optionally rounded to an integer.
    */
   static final class ThemeSlider extends SliderWidget {
      private final double min;
      private final double max;
      private final boolean asInt;
      private final String label;
      private final DoubleConsumer onValue;
      private boolean syncing;

      ThemeSlider(int x, int y, int w, int h, String label, double min, double max, double value, boolean asInt, DoubleConsumer onValue) {
         super(x, y, w, h, Text.empty(), clamp01((value - min) / (max - min)));
         this.min = min;
         this.max = max;
         this.asInt = asInt;
         this.label = label;
         this.onValue = onValue;
         this.updateMessage();
      }

      private static double clamp01(double v) {
         return MathHelper.clamp(v, 0.0, 1.0);
      }

      double domainValue() {
         double v = this.min + this.value * (this.max - this.min);
         return this.asInt ? Math.round(v) : v;
      }

      /** Set from the paired text field without firing applyValue. */
      void setDomainQuiet(double v) {
         this.syncing = true;
         this.value = clamp01((v - this.min) / (this.max - this.min));
         this.updateMessage();
         this.syncing = false;
      }

      @Override
      protected void updateMessage() {
         this.setMessage(Text.literal(this.label + ": " + fmt(domainValue())));
      }

      @Override
      protected void applyValue() {
         if (!this.syncing) {
            this.onValue.accept(domainValue());
         }
      }

      @Override
      public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
         boolean hovered = this.isMouseOver(mouseX, mouseY);
         Theme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? Theme.CTRL_HOVER : Theme.CTRL, Theme.BORDER_SOFT);
         int trackY = getY() + getHeight() - 5;
         int trackX0 = getX() + 6;
         int trackX1 = getX() + getWidth() - 6;
         ctx.fill(trackX0, trackY, trackX1, trackY + 2, Theme.TRACK_OFF);
         int fillX = trackX0 + (int) ((trackX1 - trackX0) * this.value);
         ctx.fill(trackX0, trackY, fillX, trackY + 2, Theme.ACCENT);
         int knobX = MathHelper.clamp(fillX, trackX0, trackX1 - 3);
         ctx.fill(knobX - 2, trackY - 3, knobX + 3, trackY + 4, Theme.KNOB);
         TextRenderer tr = MinecraftClient.getInstance().textRenderer;
         ctx.drawText(tr, this.label, getX() + 6, getY() + 4, Theme.TEXT_DIM, false);
         String val = fmt(domainValue());
         ctx.drawText(tr, val, getX() + getWidth() - 6 - tr.getWidth(val), getY() + 4, Theme.TEXT, false);
      }
   }

   /** Click, then press any key or mouse button to bind; Backspace/Delete clears. */
   static final class KeybindButton extends ClickableWidget {
      private final IntSupplier getter;
      private final IntConsumer setter;
      private boolean listening;

      KeybindButton(int x, int y, int w, int h, IntSupplier getter, IntConsumer setter) {
         super(x, y, w, h, Text.literal("Keybind"));
         this.getter = getter;
         this.setter = setter;
      }

      boolean isListening() {
         return this.listening;
      }

      @Override
      protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
         boolean hovered = this.isMouseOver(mouseX, mouseY);
         Theme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? Theme.CTRL_HOVER : Theme.CTRL, Theme.BORDER_SOFT);
         TextRenderer tr = MinecraftClient.getInstance().textRenderer;
         ctx.drawText(tr, "Toggle Key", getX() + 8, getY() + (getHeight() - 8) / 2, Theme.TEXT, false);
         Text right = this.listening
            ? Text.literal("Press a key…").formatted(Formatting.YELLOW)
            : keyName(this.getter.getAsInt());
         int rw = tr.getWidth(right);
         ctx.drawText(tr, right, getX() + getWidth() - 8 - rw, getY() + (getHeight() - 8) / 2, this.listening ? Theme.WARNING : Theme.ACCENT, false);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.active && this.visible && button == 0 && this.isMouseOver(mouseX, mouseY)) {
            this.listening = true;
            return true;
         }
         return false;
      }

      /** Called by the screen while listening; returns true if it consumed the input. */
      boolean captureMouse(int button) {
         if (!this.listening) return false;
         this.setter.accept(encodeMouse(button));
         this.listening = false;
         return true;
      }

      boolean captureKey(int keyCode) {
         if (!this.listening) return false;
         if (keyCode == 256) {                       // Escape cancels
            this.listening = false;
            return true;
         }
         if (keyCode == 259 || keyCode == 261) {     // Backspace / Delete clears
            this.setter.accept(-1);
         } else {
            this.setter.accept(keyCode);
         }
         this.listening = false;
         return true;
      }

      @Override
      protected void appendClickableNarrations(NarrationMessageBuilder builder) {
         builder.put(NarrationPart.TITLE, Text.literal("Toggle key: ").append(keyName(this.getter.getAsInt())));
      }
   }

   // =====================================================================
   //  NAV MODEL
   // =====================================================================
   private record NavItem(String id, String label) {
   }

   private static final List<NavItem> NAV = List.of(
      new NavItem("dashboard", "Dashboard"),
      new NavItem("autoleft", "Auto Left"),
      new NavItem("autoright", "Auto Right"),
      new NavItem("ninjabridge", "Ninja Bridge"),
      new NavItem("aimassist", "AimAssist"),
      new NavItem("tracker", "Tracker")
   );

   private static Screen screenFor(String id, Screen dashboard) {
      return switch (id) {
         case "autoleft" -> new AutoLeftScreen(dashboard);
         case "autoright" -> new AutoRightScreen(dashboard);
         case "ninjabridge" -> new NinjaBridgeScreen(dashboard);
         case "aimassist" -> new AimAssistScreen(dashboard);
         case "tracker" -> new TrackerScreen(dashboard);
         default -> new DashboardScreen(null);
      };
   }

   // =====================================================================
   //  BASE SCREEN — layout, chrome, no-blur background, debounced saving
   // =====================================================================
   private abstract static class YjScreen extends Screen {
      private record Help(int x, int y, int w, int h, List<Text> lines) {
      }

      protected final Screen parent;
      private final List<Help> helps = new ArrayList<>();
      private List<Text> pendingTooltip;
      private String toastMessage;
      private long toastUntilMs;
      protected boolean dirty;
      private long lastEditMs;

      protected YjScreen(Screen parent, String title) {
         super(Text.literal(title));
         this.parent = parent;
      }

      protected abstract String navId();

      /** Push the edited config into the module's live runtime state (no file write). */
      protected void applyLive() {
      }

      /** Write the config to disk through the module's typed static save. */
      protected void commit() {
      }

      @Override
      public boolean shouldPause() {
         return false;   // keep the world ticking / visible behind the panel
      }

      // ---- responsive layout ----
      protected int winW() {
         return Math.max(300, Math.min(452, this.width - 20));
      }

      protected int winH() {
         return Math.max(210, Math.min(300, this.height - 20));
      }

      protected int winX() {
         return (this.width - winW()) / 2;
      }

      protected int winY() {
         return (this.height - winH()) / 2;
      }

      protected int headerH() {
         return 30;
      }

      protected int footerH() {
         return 28;
      }

      protected int sidebarW() {
         return Math.min(110, Math.max(84, winW() / 3));
      }

      protected int contentX() {
         return winX() + sidebarW() + Theme.PAD;
      }

      protected int contentTop() {
         return winY() + headerH() + 8;
      }

      protected int contentRight() {
         return winX() + winW() - Theme.PAD;
      }

      protected int contentW() {
         return contentRight() - contentX();
      }

      protected int footerY() {
         return winY() + winH() - footerH();
      }

      // ---- edit lifecycle ----
      protected void markEdited() {
         this.applyLive();
         this.dirty = true;
         this.lastEditMs = System.currentTimeMillis();
      }

      protected void showToast(String message) {
         this.toastMessage = message;
         this.toastUntilMs = System.currentTimeMillis() + 1600L;
      }

      @Override
      public void tick() {
         super.tick();
         // Debounced save: commit shortly after the last edit, never every frame.
         if (this.dirty && System.currentTimeMillis() - this.lastEditMs > 350L) {
            this.commit();
            this.dirty = false;
         }
      }

      @Override
      public void close() {
         if (this.dirty) {
            this.commit();
            this.dirty = false;
         }
         if (this.client != null) {
            this.client.setScreen(this.parent);
         }
      }

      // ---- NO vanilla blur / darkening ----
      @Override
      public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
         // Intentionally empty: the light tint is drawn in render(). Not calling
         // super.renderBackground() means applyBlur()/renderDarkening() never run,
         // so the world and nearby players stay clearly visible.
      }

      @Override
      public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
         this.pendingTooltip = null;
         ctx.fill(0, 0, this.width, this.height, Theme.SCREEN_TINT);   // light tint only
         this.renderChrome(ctx, mouseX, mouseY);
         this.renderContent(ctx, mouseX, mouseY, delta);
         super.render(ctx, mouseX, mouseY, delta);                    // widgets on top
         this.renderHelpTooltips(mouseX, mouseY);
         this.renderTooltipAndToast(ctx, mouseX, mouseY);
      }

      /** Page body drawn under the widgets (headings, descriptions). */
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
      }

      protected void renderChrome(DrawContext ctx, int mouseX, int mouseY) {
         int x = winX();
         int y = winY();
         int w = winW();
         int h = winH();
         Theme.panel(ctx, x, y, w, h, Theme.PANEL, Theme.BORDER);

         // Header
         ctx.fill(x + 1, y + 1, x + w - 1, y + headerH(), Theme.HEADER);
         ctx.fill(x + 1, y + headerH() - 1, x + w - 1, y + headerH(), Theme.ACCENT);
         TextRenderer tr = this.textRenderer;
         ctx.drawText(tr, Text.literal("YJHack").formatted(Formatting.BOLD), x + 12, y + 7, Theme.TEXT, false);
         ctx.drawText(tr, "Client", x + 12 + tr.getWidth("YJHack") + 5, y + 8, Theme.ACCENT, false);
         String status = headerStatus();
         if (status != null) {
            ctx.drawText(tr, status, x + w - 12 - tr.getWidth(status), y + 8, Theme.TEXT_DIM, false);
         }

         // Sidebar
         int sbTop = y + headerH();
         int sbBottom = y + h;
         ctx.fill(x + 1, sbTop, x + sidebarW(), sbBottom - 1, Theme.SIDEBAR);
         ctx.fill(x + sidebarW(), sbTop, x + sidebarW() + 1, sbBottom - 1, Theme.BORDER_SOFT);
         renderNav(ctx, mouseX, mouseY);
      }

      protected String headerStatus() {
         return null;
      }

      private void renderNav(DrawContext ctx, int mouseX, int mouseY) {
         TextRenderer tr = this.textRenderer;
         int x = winX() + 8;
         int w = sidebarW() - 12;
         int y = winY() + headerH() + 8;
         for (NavItem item : NAV) {
            boolean active = item.id().equals(navId());
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 22;
            if (active) {
               ctx.fill(x, y, x + w, y + 22, 0x3335E0C8);
               ctx.fill(x, y, x + 2, y + 22, Theme.ACCENT);
            } else if (hovered) {
               ctx.fill(x, y, x + w, y + 22, Theme.CTRL_HOVER);
            }
            ctx.drawText(tr, item.label(), x + 8, y + 7, active ? Theme.TEXT : Theme.TEXT_DIM, false);
            y += 24;
         }
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (button == 0) {
            int x = winX() + 8;
            int w = sidebarW() - 12;
            int y = winY() + headerH() + 8;
            for (NavItem item : NAV) {
               if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 22) {
                  if (!item.id().equals(navId()) && this.client != null) {
                     if (this.dirty) {
                        this.commit();
                        this.dirty = false;
                     }
                     Screen dash = (this.parent instanceof DashboardScreen) ? this.parent : new DashboardScreen(null);
                     this.client.setScreen("dashboard".equals(item.id()) ? new DashboardScreen(null) : screenFor(item.id(), dash));
                  }
                  return true;
               }
               y += 24;
            }
         }
         return super.mouseClicked(mouseX, mouseY, button);
      }

      // ---- help / tooltip registration ----
      protected void addHelp(int x, int y, int w, int h, String... lines) {
         List<Text> t = new ArrayList<>();
         for (String l : lines) {
            t.add(Text.literal(l));
         }
         this.helps.add(new Help(x, y, w, h, t));
      }

      protected void clearHelps() {
         this.helps.clear();
      }

      private void renderHelpTooltips(int mouseX, int mouseY) {
         for (Help help : this.helps) {
            if (mouseX >= help.x() && mouseX <= help.x() + help.w() && mouseY >= help.y() && mouseY <= help.y() + help.h()) {
               this.pendingTooltip = help.lines();
               return;
            }
         }
      }

      private void renderTooltipAndToast(DrawContext ctx, int mouseX, int mouseY) {
         if (this.pendingTooltip != null && !this.pendingTooltip.isEmpty()) {
            ctx.drawTooltip(this.textRenderer, this.pendingTooltip, mouseX, mouseY);
         }
         if (this.toastMessage != null && System.currentTimeMillis() < this.toastUntilMs) {
            TextRenderer tr = this.textRenderer;
            int tw = tr.getWidth(this.toastMessage) + 20;
            int tx = winX() + winW() - tw - 12;
            int ty = footerY() - 24;
            Theme.panel(ctx, tx, ty, tw, 18, 0xE01B2A22, Theme.SUCCESS);
            ctx.fill(tx, ty, tx + 2, ty + 18, Theme.SUCCESS);
            ctx.drawText(tr, this.toastMessage, tx + 10, ty + 5, Theme.TEXT, false);
         }
      }

      // ---- shared header/heading helpers ----
      protected void drawHeading(DrawContext ctx, String title, String subtitle) {
         TextRenderer tr = this.textRenderer;
         ctx.drawText(tr, Text.literal(title).formatted(Formatting.BOLD), contentX(), contentTop(), Theme.TEXT, false);
         if (subtitle != null) {
            ctx.drawText(tr, subtitle, contentX(), contentTop() + 11, Theme.TEXT_MUTED, false);
         }
         ctx.fill(contentX(), contentTop() + 22, contentRight(), contentTop() + 23, Theme.DIVIDER);
      }

      protected void addStatusChip(DrawContext ctx, int x, int y, boolean on) {
         String s = on ? "ENABLED" : "DISABLED";
         TextRenderer tr = this.textRenderer;
         int w = tr.getWidth(s) + 10;
         int col = on ? Theme.SUCCESS : Theme.TEXT_MUTED;
         Theme.pill(ctx, x, y, w, 11, (col & 0x00FFFFFF) | 0x33000000);
         ctx.drawText(tr, Text.literal(s).formatted(Formatting.BOLD), x + 5, y + 2, col, false);
      }

      /**
       * Adds a themed slider with a synced numeric entry box to its right.
       * The setter writes the value into the screen's config; live-apply + debounced
       * save are handled here. Dragging never writes the file.
       */
      protected ThemeSlider addSlider(int x, int y, int totalW, String label,
                                      double min, double max, double value, boolean asInt, DoubleConsumer setter) {
         int fieldW = 46;
         int sliderW = Math.max(60, totalW - fieldW - 6);
         boolean[] guard = {false};
         TextFieldWidget field = new TextFieldWidget(this.textRenderer, x + sliderW + 6, y, fieldW, Theme.CTRL_H + 2, Text.literal(label));
         field.setMaxLength(8);
         field.setText(asInt ? String.valueOf((int) Math.round(value)) : fmt(value));
         ThemeSlider slider = new ThemeSlider(x, y, sliderW, Theme.CTRL_H + 2, label, min, max, value, asInt, v -> {
            setter.accept(v);
            if (!guard[0]) {
               guard[0] = true;
               field.setText(asInt ? String.valueOf((int) Math.round(v)) : fmt(v));
               guard[0] = false;
            }
            this.markEdited();
         });
         field.setChangedListener(s -> {
            if (guard[0]) return;
            try {
               double v = MathHelper.clamp(Double.parseDouble(s.trim()), min, max);
               guard[0] = true;
               slider.setDomainQuiet(v);
               guard[0] = false;
               setter.accept(v);
               this.markEdited();
            } catch (NumberFormatException ignored) {
               // Partial / invalid entry: keep the slider where it is until valid.
            }
         });
         this.addDrawableChild(slider);
         this.addDrawableChild(field);
         return slider;
      }

      /** Save + Reset + Back bar pinned to the panel footer. */
      protected void addActionBar(Runnable onReset) {
         int y = footerY() + (footerH() - Theme.CTRL_H) / 2;
         int right = winX() + winW() - Theme.PAD;
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
            this.commit();
            this.dirty = false;
            this.showToast("Settings saved");
         }).dimensions(right - 66, y, 66, Theme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> onReset.run())
            .dimensions(right - 66 - 8 - 56, y, 56, Theme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("‹ Back"), b -> this.close())
            .dimensions(contentX(), y, 52, Theme.CTRL_H).build());
      }
   }

   // =====================================================================
   //  DASHBOARD
   // =====================================================================
   private static final class DashboardScreen extends YjScreen {
      private record Card(int x, int y, int w, int h, String id, String label, String desc,
                          Supplier<Boolean> enabled, Supplier<Integer> key, Supplier<String> summary) {
         boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
         }
      }

      private final List<Card> cards = new ArrayList<>();

      private DashboardScreen(Screen parent) {
         super(parent, "Dashboard");
      }

      @Override
      protected String navId() {
         return "dashboard";
      }

      @Override
      protected String headerStatus() {
         return enabledCount() + "/5 modules on";
      }

      private static int enabledCount() {
         int n = 0;
         if (AutoLeftClient.config != null && AutoLeftClient.config.enabled) n++;
         if (AutoRightClient.config != null && AutoRightClient.config.enabled) n++;
         if (NinjaBridgeClient.config != null && NinjaBridgeClient.config.enabled) n++;
         if (AimAssistClient.config != null && AimAssistClient.config.enabled) n++;
         if (TrackerClient.config != null && TrackerClient.config.enabled) n++;
         return n;
      }

      @Override
      protected void init() {
         this.cards.clear();
         int gap = 8;
         int cols = contentW() >= 300 ? 2 : 1;
         int cardW = (contentW() - gap * (cols - 1)) / cols;
         int cardH = 52;
         int x0 = contentX();
         int y0 = contentTop() + 28;
         int i = 0;
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "autoleft", "Auto Left", "Left-click automation",
            () -> AutoLeftClient.config != null && AutoLeftClient.config.enabled,
            () -> AutoLeftClient.config != null ? AutoLeftClient.config.toggleKeyCode : -1,
            () -> AutoLeftClient.config == null ? "" : "CPS " + AutoLeftClient.config.minCps + "-" + AutoLeftClient.config.maxCps
               + (AutoLeftClient.config.weaponCheck ? "  •  Weapon" : ""));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "autoright", "Auto Right", "Right-click / block placement",
            () -> AutoRightClient.config != null && AutoRightClient.config.enabled,
            () -> AutoRightClient.config != null ? AutoRightClient.config.toggleKeyCode : -1,
            () -> AutoRightClient.config == null ? "" : "CPS " + AutoRightClient.config.minCps + "-" + AutoRightClient.config.maxCps
               + (AutoRightClient.config.blockMode ? "  •  Block" : ""));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "ninjabridge", "Ninja Bridge", "Auto-sneak bridging helper",
            () -> NinjaBridgeClient.config != null && NinjaBridgeClient.config.enabled,
            () -> NinjaBridgeClient.config != null ? NinjaBridgeClient.config.toggleKeyCode : -1,
            () -> NinjaBridgeClient.config == null ? "" : (NinjaBridgeClient.config.autoSwitch ? "Auto-switch on" : "Auto-switch off"));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "aimassist", "AimAssist", "Close-range aim smoothing",
            () -> AimAssistClient.config != null && AimAssistClient.config.enabled,
            () -> AimAssistClient.config != null ? AimAssistClient.config.toggleKeyCode : -1,
            () -> AimAssistClient.config == null ? "" : "Speed " + fmt(AimAssistClient.config.speed) + "  •  FOV " + fmt(AimAssistClient.config.fov));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "tracker", "Tracker", "Hidden-enemy HUD + box",
            () -> TrackerClient.config != null && TrackerClient.config.enabled,
            () -> TrackerClient.config != null ? TrackerClient.config.toggleKeyCode : -1,
            () -> TrackerClient.config == null ? "" : "Range " + fmt(TrackerClient.config.range));
      }

      private int addCard(int i, int cols, int cardW, int cardH, int gap, int x0, int y0,
                          String id, String label, String desc,
                          Supplier<Boolean> enabled, Supplier<Integer> key, Supplier<String> summary) {
         int col = i % cols;
         int row = i / cols;
         int x = x0 + col * (cardW + gap);
         int y = y0 + row * (cardH + gap);
         this.cards.add(new Card(x, y, cardW, cardH, id, label, desc, enabled, key, summary));
         return i + 1;
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Module Dashboard", "Click a card to configure. Everything below is live.");
         TextRenderer tr = this.textRenderer;
         for (Card card : this.cards) {
            boolean hovered = card.contains(mouseX, mouseY);
            boolean on = Boolean.TRUE.equals(card.enabled().get());
            Theme.panel(ctx, card.x(), card.y(), card.w(), card.h(), hovered ? Theme.CARD_HOVER : Theme.CARD, Theme.BORDER_SOFT);
            ctx.fill(card.x(), card.y(), card.x() + 2, card.y() + card.h(), on ? Theme.ACCENT : Theme.TEXT_MUTED);
            ctx.drawText(tr, Text.literal(card.label()).formatted(Formatting.BOLD), card.x() + 9, card.y() + 7, Theme.TEXT, false);
            // status dot
            int dotX = card.x() + card.w() - 12;
            ctx.fill(dotX, card.y() + 9, dotX + 5, card.y() + 14, on ? Theme.SUCCESS : Theme.TEXT_MUTED);
            ctx.drawText(tr, card.desc(), card.x() + 9, card.y() + 19, Theme.TEXT_MUTED, false);
            String sum = card.summary().get();
            ctx.drawText(tr, sum, card.x() + 9, card.y() + 31, Theme.TEXT_DIM, false);
            Text keyLine = Text.literal("Key: ").append(keyName(card.key().get()));
            ctx.drawText(tr, keyLine, card.x() + 9, card.y() + 41, Theme.TEXT_MUTED, false);
         }
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
         }
         if (button == 0 && this.client != null) {
            for (Card card : this.cards) {
               if (card.contains(mouseX, mouseY)) {
                  this.client.setScreen(screenFor(card.id(), this));
                  return true;
               }
            }
         }
         return false;
      }
   }

   // =====================================================================
   //  SETTINGS SCREENS
   // =====================================================================
   private static final class AutoLeftScreen extends YjScreen {
      private final AutoLeftClient.Config cfg = copy();
      private KeybindButton keybind;

      private AutoLeftScreen(Screen parent) {
         super(parent, "Auto Left");
      }

      private static AutoLeftClient.Config copy() {
         AutoLeftClient.Config c = new AutoLeftClient.Config();
         AutoLeftClient.Config s = AutoLeftClient.config;
         if (s != null) {
            c.configVersion = s.configVersion;
            c.enabled = s.enabled;
            c.weaponCheck = s.weaponCheck;
            c.toggleKeyCode = s.toggleKeyCode;
            c.minCps = s.minCps;
            c.maxCps = s.maxCps;
         }
         return c;
      }

      @Override
      protected String navId() {
         return "autoleft";
      }

      @Override
      protected String headerStatus() {
         return this.cfg.enabled ? "Auto Left: on" : "Auto Left: off";
      }

      @Override
      protected void applyLive() {
         AutoLeftClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         AutoLeftClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.clearHelps();
         int x = contentX();
         int w = contentW();
         int y = contentTop() + 26;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Turn Auto Left on or off.");
         y += Theme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Weapon Mode", this.cfg.weaponCheck, v -> {
            this.cfg.weaponCheck = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Only click while holding a sword or axe.");
         y += Theme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, Theme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += Theme.ROW + 4;
         this.addSlider(x, y, w, "Min CPS", 1, 40, this.cfg.minCps, true, v -> this.cfg.minCps = (int) Math.round(v));
         addHelp(x, y, w, Theme.CTRL_H, "Minimum clicks per second.");
         y += Theme.ROW + 2;
         this.addSlider(x, y, w, "Max CPS", 1, 40, this.cfg.maxCps, true, v -> this.cfg.maxCps = (int) Math.round(v));
         addHelp(x, y, w, Theme.CTRL_H, "Maximum clicks per second.");
         addActionBar(this::reset);
      }

      private void reset() {
         AutoLeftClient.Config d = new AutoLeftClient.Config();
         this.cfg.enabled = d.enabled;
         this.cfg.weaponCheck = d.weaponCheck;
         this.cfg.toggleKeyCode = d.toggleKeyCode;
         this.cfg.minCps = d.minCps;
         this.cfg.maxCps = d.maxCps;
         this.applyLive();
         this.commit();
         this.dirty = false;
         if (this.client != null) this.client.setScreen(new AutoLeftScreen(this.parent));
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Auto Left", "Left-click speed and weapon gating.");
         addStatusChip(ctx, contentRight() - 60, contentTop(), this.cfg.enabled);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         if (this.keybind != null && this.keybind.captureKey(keyCode)) return true;
         return super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.keybind != null && this.keybind.isListening() && this.keybind.captureMouse(button)) return true;
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   private static final class AutoRightScreen extends YjScreen {
      private final AutoRightClient.Config cfg = copy();
      private KeybindButton keybind;

      private AutoRightScreen(Screen parent) {
         super(parent, "Auto Right");
      }

      private static AutoRightClient.Config copy() {
         AutoRightClient.Config c = new AutoRightClient.Config();
         AutoRightClient.Config s = AutoRightClient.config;
         if (s != null) {
            c.configVersion = s.configVersion;
            c.enabled = s.enabled;
            c.blockMode = s.blockMode;
            c.toggleKeyCode = s.toggleKeyCode;
            c.minCps = s.minCps;
            c.maxCps = s.maxCps;
         }
         return c;
      }

      @Override
      protected String navId() {
         return "autoright";
      }

      @Override
      protected String headerStatus() {
         return this.cfg.enabled ? "Auto Right: on" : "Auto Right: off";
      }

      @Override
      protected void applyLive() {
         AutoRightClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         AutoRightClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.clearHelps();
         int x = contentX();
         int w = contentW();
         int y = contentTop() + 26;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Turn Auto Right on or off.");
         y += Theme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Block Mode", this.cfg.blockMode, v -> {
            this.cfg.blockMode = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Fast placement only while holding a block.",
            "Fire Charge / pearls always fire one use per press.");
         y += Theme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, Theme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += Theme.ROW + 4;
         this.addSlider(x, y, w, "Min CPS", 1, 40, this.cfg.minCps, true, v -> this.cfg.minCps = (int) Math.round(v));
         addHelp(x, y, w, Theme.CTRL_H, "Minimum blocks placed per second.");
         y += Theme.ROW + 2;
         this.addSlider(x, y, w, "Max CPS", 1, 40, this.cfg.maxCps, true, v -> this.cfg.maxCps = (int) Math.round(v));
         addHelp(x, y, w, Theme.CTRL_H, "Maximum blocks placed per second.");
         addActionBar(this::reset);
      }

      private void reset() {
         AutoRightClient.Config d = new AutoRightClient.Config();
         this.cfg.enabled = d.enabled;
         this.cfg.blockMode = d.blockMode;
         this.cfg.toggleKeyCode = d.toggleKeyCode;
         this.cfg.minCps = d.minCps;
         this.cfg.maxCps = d.maxCps;
         this.applyLive();
         this.commit();
         this.dirty = false;
         if (this.client != null) this.client.setScreen(new AutoRightScreen(this.parent));
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Auto Right", "Right-click speed and block placement.");
         addStatusChip(ctx, contentRight() - 60, contentTop(), this.cfg.enabled);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         if (this.keybind != null && this.keybind.captureKey(keyCode)) return true;
         return super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.keybind != null && this.keybind.isListening() && this.keybind.captureMouse(button)) return true;
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   private static final class NinjaBridgeScreen extends YjScreen {
      private final NinjaBridgeClient.Config cfg = copy();
      private KeybindButton keybind;

      private NinjaBridgeScreen(Screen parent) {
         super(parent, "Ninja Bridge");
      }

      private static NinjaBridgeClient.Config copy() {
         NinjaBridgeClient.Config c = new NinjaBridgeClient.Config();
         NinjaBridgeClient.Config s = NinjaBridgeClient.config;
         if (s != null) {
            c.configVersion = s.configVersion;
            c.enabled = s.enabled;
            c.toggleKeyCode = s.toggleKeyCode;
            c.autoSwitch = s.autoSwitch;
         }
         return c;
      }

      @Override
      protected String navId() {
         return "ninjabridge";
      }

      @Override
      protected String headerStatus() {
         return this.cfg.enabled ? "Ninja Bridge: on" : "Ninja Bridge: off";
      }

      @Override
      protected void applyLive() {
         NinjaBridgeClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         NinjaBridgeClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.clearHelps();
         int x = contentX();
         int w = contentW();
         int y = contentTop() + 26;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Turn Ninja Bridge on or off.");
         y += Theme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Auto Switch", this.cfg.autoSwitch, v -> {
            this.cfg.autoSwitch = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Automatically hold a placeable block while bridging.");
         y += Theme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, Theme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         addActionBar(this::reset);
      }

      private void reset() {
         NinjaBridgeClient.Config d = new NinjaBridgeClient.Config();
         this.cfg.enabled = d.enabled;
         this.cfg.toggleKeyCode = d.toggleKeyCode;
         this.cfg.autoSwitch = d.autoSwitch;
         this.applyLive();
         this.commit();
         this.dirty = false;
         if (this.client != null) this.client.setScreen(new NinjaBridgeScreen(this.parent));
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Ninja Bridge", "Tap the key to toggle auto-sneak bridging.");
         addStatusChip(ctx, contentRight() - 60, contentTop(), this.cfg.enabled);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         if (this.keybind != null && this.keybind.captureKey(keyCode)) return true;
         return super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.keybind != null && this.keybind.isListening() && this.keybind.captureMouse(button)) return true;
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   private static final class AimAssistScreen extends YjScreen {
      private final AimAssistClient.Config cfg = copy();
      private KeybindButton keybind;

      private AimAssistScreen(Screen parent) {
         super(parent, "AimAssist");
      }

      private static AimAssistClient.Config copy() {
         AimAssistClient.Config c = new AimAssistClient.Config();
         AimAssistClient.Config s = AimAssistClient.config;
         if (s != null) {
            c.configVersion = s.configVersion;
            c.enabled = s.enabled;
            c.toggleKeyCode = s.toggleKeyCode;
            c.speed = s.speed;
            c.smoothness = s.smoothness;
            c.fov = s.fov;
         }
         return c;
      }

      @Override
      protected String navId() {
         return "aimassist";
      }

      @Override
      protected String headerStatus() {
         return this.cfg.enabled ? "AimAssist: on" : "AimAssist: off";
      }

      @Override
      protected void applyLive() {
         AimAssistClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         AimAssistClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.clearHelps();
         int x = contentX();
         int w = contentW();
         int y = contentTop() + 26;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Turn AimAssist on or off.");
         y += Theme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, Theme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += Theme.ROW + 4;
         this.addSlider(x, y, w, "Speed", 0.01, 1.0, this.cfg.speed, false, v -> this.cfg.speed = (float) v);
         addHelp(x, y, w, Theme.CTRL_H, "Base turn strength toward the target.");
         y += Theme.ROW + 2;
         this.addSlider(x, y, w, "Smoothness", 0.0, 1.0, this.cfg.smoothness, false, v -> this.cfg.smoothness = (float) v);
         addHelp(x, y, w, Theme.CTRL_H, "Higher = softer, slower aim.");
         y += Theme.ROW + 2;
         this.addSlider(x, y, w, "FOV", 10, 180, this.cfg.fov, false, v -> this.cfg.fov = (float) v);
         addHelp(x, y, w, Theme.CTRL_H, "Cone (degrees) in which targets are acquired.");
         addActionBar(this::reset);
      }

      private void reset() {
         AimAssistClient.Config d = new AimAssistClient.Config();
         this.cfg.enabled = d.enabled;
         this.cfg.toggleKeyCode = d.toggleKeyCode;
         this.cfg.speed = d.speed;
         this.cfg.smoothness = d.smoothness;
         this.cfg.fov = d.fov;
         this.applyLive();
         this.commit();
         this.dirty = false;
         if (this.client != null) this.client.setScreen(new AimAssistScreen(this.parent));
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "AimAssist", "Close-range aim smoothing.");
         addStatusChip(ctx, contentRight() - 60, contentTop(), this.cfg.enabled);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         if (this.keybind != null && this.keybind.captureKey(keyCode)) return true;
         return super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.keybind != null && this.keybind.isListening() && this.keybind.captureMouse(button)) return true;
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   private static final class TrackerScreen extends YjScreen {
      private final TrackerClient.Config cfg = copy();
      private KeybindButton keybind;

      private TrackerScreen(Screen parent) {
         super(parent, "Tracker");
      }

      private static TrackerClient.Config copy() {
         TrackerClient.Config c = new TrackerClient.Config();
         TrackerClient.Config s = TrackerClient.config;
         if (s != null) {
            c.configVersion = s.configVersion;
            c.enabled = s.enabled;
            c.toggleKeyCode = s.toggleKeyCode;
            c.ignoreOwnTeam = s.ignoreOwnTeam;
            c.range = s.range;
            c.hudOffsetX = s.hudOffsetX;
            c.hudY = s.hudY;
         }
         return c;
      }

      @Override
      protected String navId() {
         return "tracker";
      }

      @Override
      protected String headerStatus() {
         return this.cfg.enabled ? "Tracker: on" : "Tracker: off";
      }

      @Override
      protected void applyLive() {
         TrackerClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         TrackerClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.clearHelps();
         int x = contentX();
         int w = contentW();
         int y = contentTop() + 26;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Turn the tracker overlay and box on or off.");
         y += Theme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, Theme.CTRL_H, "Ignore Team", this.cfg.ignoreOwnTeam, v -> {
            this.cfg.ignoreOwnTeam = v;
            this.markEdited();
         }));
         addHelp(x, y, w, Theme.CTRL_H, "Skip players on your own scoreboard team.");
         y += Theme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, Theme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += Theme.ROW + 4;
         this.addSlider(x, y, w, "Range", 1, 128, this.cfg.range, false, v -> this.cfg.range = v);
         addHelp(x, y, w, Theme.CTRL_H, "Maximum distance (blocks) for alerts and the box.");
         y += Theme.ROW + 6;
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD Position"), b -> {
            if (this.dirty) {
               this.commit();
               this.dirty = false;
            }
            if (this.client != null) this.client.setScreen(new TrackerHudEditorScreen(this, this.cfg));
         }).dimensions(x, y, w, Theme.CTRL_H).build());
         addActionBar(this::reset);
      }

      private void reset() {
         TrackerClient.Config d = new TrackerClient.Config();
         this.cfg.enabled = d.enabled;
         this.cfg.toggleKeyCode = d.toggleKeyCode;
         this.cfg.ignoreOwnTeam = d.ignoreOwnTeam;
         this.cfg.range = d.range;
         this.cfg.hudOffsetX = d.hudOffsetX;
         this.cfg.hudY = d.hudY;
         this.applyLive();
         this.commit();
         this.dirty = false;
         if (this.client != null) this.client.setScreen(new TrackerScreen(this.parent));
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Tracker", "Range, team filter and HUD placement.");
         addStatusChip(ctx, contentRight() - 60, contentTop(), this.cfg.enabled);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         if (this.keybind != null && this.keybind.captureKey(keyCode)) return true;
         return super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (this.keybind != null && this.keybind.isListening() && this.keybind.captureMouse(button)) return true;
         return super.mouseClicked(mouseX, mouseY, button);
      }
   }

   /** Drag-to-place editor for the tracker HUD text. */
   private static final class TrackerHudEditorScreen extends YjScreen {
      private final TrackerClient.Config cfg;
      private boolean dragging;
      private int grabX;
      private int grabY;

      private TrackerHudEditorScreen(Screen parent, TrackerClient.Config cfg) {
         super(parent, "Tracker HUD");
         this.cfg = cfg;
      }

      @Override
      protected String navId() {
         return "tracker";
      }

      @Override
      protected void applyLive() {
         TrackerClient.applyRuntimeConfig(this.cfg);
      }

      @Override
      protected void commit() {
         TrackerClient.saveConfigStatic(this.cfg);
      }

      @Override
      protected void init() {
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
            .dimensions(winX() + winW() - Theme.PAD - 66, footerY() + (footerH() - Theme.CTRL_H) / 2, 66, Theme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
            TrackerClient.Config d = new TrackerClient.Config();
            this.cfg.hudOffsetX = d.hudOffsetX;
            this.cfg.hudY = d.hudY;
            this.markEdited();
            this.commit();
            this.dirty = false;
         }).dimensions(winX() + winW() - Theme.PAD - 66 - 8 - 56, footerY() + (footerH() - Theme.CTRL_H) / 2, 56, Theme.CTRL_H).build());
      }

      private int previewX() {
         return Math.max(4, this.cfg.hudOffsetX);
      }

      private int previewY() {
         return Math.max(4, this.cfg.hudY);
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "HUD Editor", "Drag the preview to reposition, then Done.");
         TextRenderer tr = this.textRenderer;
         Text preview = Text.literal("Alert Enemy 6.4m RIGHT");
         int pw = tr.getWidth(preview);
         int px = previewX();
         int py = previewY();
         ctx.fill(px - 4, py - 2, px + pw + 4, py + 10, 0xB0000000);
         Theme.panel(ctx, px - 5, py - 3, pw + 10, 15, 0x00000000, Theme.ACCENT);
         ctx.drawText(tr, preview, px, py, Theme.TEXT, false);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (button == 0) {
            TextRenderer tr = this.textRenderer;
            int pw = tr.getWidth("Alert Enemy 6.4m RIGHT");
            int px = previewX();
            int py = previewY();
            if (mouseX >= px - 6 && mouseX <= px + pw + 6 && mouseY >= py - 4 && mouseY <= py + 12) {
               this.dragging = true;
               this.grabX = (int) mouseX - px;
               this.grabY = (int) mouseY - py;
               return true;
            }
         }
         return super.mouseClicked(mouseX, mouseY, button);
      }

      @Override
      public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
         if (this.dragging && button == 0) {
            this.cfg.hudOffsetX = MathHelper.clamp((int) mouseX - this.grabX, 4, 10000);
            this.cfg.hudY = MathHelper.clamp((int) mouseY - this.grabY, 4, 10000);
            this.markEdited();
            return true;
         }
         return super.mouseDragged(mouseX, mouseY, button, dx, dy);
      }

      @Override
      public boolean mouseReleased(double mouseX, double mouseY, int button) {
         if (this.dragging && button == 0) {
            this.dragging = false;
            this.commit();
            this.dirty = false;
            return true;
         }
         return super.mouseReleased(mouseX, mouseY, button);
      }
   }
}
