package com.masteryj.modgui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
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
import com.masteryj.modgui.theme.YjTheme;
import com.masteryj.modgui.component.KeybindButton;
import com.masteryj.modgui.component.ThemeSlider;
import com.masteryj.modgui.component.ToggleSwitch;

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
         return winX() + sidebarW() + YjTheme.PAD;
      }

      protected int contentTop() {
         return winY() + headerH() + 8;
      }

      protected int contentRight() {
         return winX() + winW() - YjTheme.PAD;
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
         ctx.fill(0, 0, this.width, this.height, YjTheme.SCREEN_TINT);   // light tint only
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
         YjTheme.panel(ctx, x, y, w, h, YjTheme.PANEL, YjTheme.BORDER);

         // Header
         ctx.fill(x + 1, y + 1, x + w - 1, y + headerH(), YjTheme.HEADER);
         ctx.fill(x + 1, y + headerH() - 1, x + w - 1, y + headerH(), YjTheme.ACCENT);
         TextRenderer tr = this.textRenderer;
         ctx.drawText(tr, Text.literal("YJHack").formatted(Formatting.BOLD), x + 12, y + 7, YjTheme.TEXT, false);
         ctx.drawText(tr, "Client", x + 12 + tr.getWidth("YJHack") + 5, y + 8, YjTheme.ACCENT, false);
         String status = headerStatus();
         if (status != null) {
            ctx.drawText(tr, status, x + w - 12 - tr.getWidth(status), y + 8, YjTheme.TEXT_DIM, false);
         }

         // Sidebar
         int sbTop = y + headerH();
         int sbBottom = y + h;
         ctx.fill(x + 1, sbTop, x + sidebarW(), sbBottom - 1, YjTheme.SIDEBAR);
         ctx.fill(x + sidebarW(), sbTop, x + sidebarW() + 1, sbBottom - 1, YjTheme.BORDER_SOFT);
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
               ctx.fill(x, y, x + 2, y + 22, YjTheme.ACCENT);
            } else if (hovered) {
               ctx.fill(x, y, x + w, y + 22, YjTheme.CTRL_HOVER);
            }
            ctx.drawText(tr, item.label(), x + 8, y + 7, active ? YjTheme.TEXT : YjTheme.TEXT_DIM, false);
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
            YjTheme.panel(ctx, tx, ty, tw, 18, 0xE01B2A22, YjTheme.SUCCESS);
            ctx.fill(tx, ty, tx + 2, ty + 18, YjTheme.SUCCESS);
            ctx.drawText(tr, this.toastMessage, tx + 10, ty + 5, YjTheme.TEXT, false);
         }
      }

      // ---- shared header/heading helpers ----
      protected void drawHeading(DrawContext ctx, String title, String subtitle) {
         TextRenderer tr = this.textRenderer;
         ctx.drawText(tr, Text.literal(title).formatted(Formatting.BOLD), contentX(), contentTop(), YjTheme.TEXT, false);
         if (subtitle != null) {
            ctx.drawText(tr, subtitle, contentX(), contentTop() + 11, YjTheme.TEXT_MUTED, false);
         }
         ctx.fill(contentX(), contentTop() + 22, contentRight(), contentTop() + 23, YjTheme.DIVIDER);
      }

      protected void addStatusChip(DrawContext ctx, int x, int y, boolean on) {
         String s = on ? "ENABLED" : "DISABLED";
         TextRenderer tr = this.textRenderer;
         int w = tr.getWidth(s) + 10;
         int col = on ? YjTheme.SUCCESS : YjTheme.TEXT_MUTED;
         YjTheme.pill(ctx, x, y, w, 11, (col & 0x00FFFFFF) | 0x33000000);
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
         TextFieldWidget field = new TextFieldWidget(this.textRenderer, x + sliderW + 6, y, fieldW, YjTheme.CTRL_H + 2, Text.literal(label));
         field.setMaxLength(8);
         field.setText(asInt ? String.valueOf((int) Math.round(value)) : YjTheme.fmt(value));
         ThemeSlider slider = new ThemeSlider(x, y, sliderW, YjTheme.CTRL_H + 2, label, min, max, value, asInt, v -> {
            setter.accept(v);
            if (!guard[0]) {
               guard[0] = true;
               field.setText(asInt ? String.valueOf((int) Math.round(v)) : YjTheme.fmt(v));
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
         int y = footerY() + (footerH() - YjTheme.CTRL_H) / 2;
         int right = winX() + winW() - YjTheme.PAD;
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
            this.commit();
            this.dirty = false;
            this.showToast("Settings saved");
         }).dimensions(right - 66, y, 66, YjTheme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> onReset.run())
            .dimensions(right - 66 - 8 - 56, y, 56, YjTheme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("‹ Back"), b -> this.close())
            .dimensions(contentX(), y, 52, YjTheme.CTRL_H).build());
      }
   }

   // =====================================================================
   //  DASHBOARD
   // =====================================================================
   private static final class DashboardScreen extends YjScreen {
      private record Card(int x, int y, int w, int h, String id, String label, String desc,
                          Supplier<Boolean> enabled, Supplier<String> summary) {
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
         return enabledCount() + " active";
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
         this.clearHelps();
         int gap = 8;
         int cols = contentW() >= 300 ? 2 : 1;
         int cardW = (contentW() - gap * (cols - 1)) / cols;
         int cardH = 52;
         int x0 = contentX();
         int y0 = contentTop() + 28;
         int i = 0;
         // Card face stays to three lines (name / short desc / one key value); the
         // remaining detail (toggle key + secondary flag) moves to the hover tooltip.
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "autoleft", "Auto Left", "Left-click automation",
            () -> AutoLeftClient.config != null && AutoLeftClient.config.enabled,
            () -> AutoLeftClient.config != null ? AutoLeftClient.config.toggleKeyCode : -1,
            () -> AutoLeftClient.config == null ? "" : "CPS " + AutoLeftClient.config.minCps + "-" + AutoLeftClient.config.maxCps,
            AutoLeftClient.config != null && AutoLeftClient.config.weaponCheck ? "Weapon mode: on" : "Weapon mode: off");
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "autoright", "Auto Right", "Right-click / blocks",
            () -> AutoRightClient.config != null && AutoRightClient.config.enabled,
            () -> AutoRightClient.config != null ? AutoRightClient.config.toggleKeyCode : -1,
            () -> AutoRightClient.config == null ? "" : "CPS " + AutoRightClient.config.minCps + "-" + AutoRightClient.config.maxCps,
            AutoRightClient.config != null && AutoRightClient.config.blockMode ? "Block mode: on" : "Block mode: off",
            "Fire Charge / pearls: one use per press");
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "ninjabridge", "Ninja Bridge", "Auto-sneak bridging",
            () -> NinjaBridgeClient.config != null && NinjaBridgeClient.config.enabled,
            () -> NinjaBridgeClient.config != null ? NinjaBridgeClient.config.toggleKeyCode : -1,
            () -> NinjaBridgeClient.config == null ? "" : (NinjaBridgeClient.config.autoSwitch ? "Auto-switch on" : "Auto-switch off"));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "aimassist", "AimAssist", "Smooth target tracking",
            () -> AimAssistClient.config != null && AimAssistClient.config.enabled,
            () -> AimAssistClient.config != null ? AimAssistClient.config.toggleKeyCode : -1,
            () -> AimAssistClient.config == null ? "" : "Speed " + YjTheme.fmt(AimAssistClient.config.speed),
            AimAssistClient.config == null ? "" : "FOV " + YjTheme.fmt(AimAssistClient.config.fov));
         i = addCard(i, cols, cardW, cardH, gap, x0, y0, "tracker", "Tracker", "Hidden-enemy HUD + box",
            () -> TrackerClient.config != null && TrackerClient.config.enabled,
            () -> TrackerClient.config != null ? TrackerClient.config.toggleKeyCode : -1,
            () -> TrackerClient.config == null ? "" : "Range " + YjTheme.fmt(TrackerClient.config.range),
            TrackerClient.config != null && TrackerClient.config.ignoreOwnTeam ? "Ignores own team" : "Tracks all teams");
      }

      private int addCard(int i, int cols, int cardW, int cardH, int gap, int x0, int y0,
                          String id, String label, String desc,
                          Supplier<Boolean> enabled, Supplier<Integer> key, Supplier<String> summary,
                          String... extraTip) {
         int col = i % cols;
         int row = i / cols;
         int x = x0 + col * (cardW + gap);
         int y = y0 + row * (cardH + gap);
         this.cards.add(new Card(x, y, cardW, cardH, id, label, desc, enabled, summary));
         // Hover tooltip carries the detail that used to crowd the card face.
         List<String> tip = new ArrayList<>();
         tip.add(label);
         tip.add("Toggle key: " + YjTheme.keyName(key.get()).getString());
         for (String e : extraTip) {
            if (e != null && !e.isEmpty()) tip.add(e);
         }
         tip.add("Click to configure");
         addHelp(x, y, cardW, cardH, tip.toArray(new String[0]));
         return i + 1;
      }

      @Override
      protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
         drawHeading(ctx, "Module Dashboard", "Select a module to configure");
         TextRenderer tr = this.textRenderer;
         for (Card card : this.cards) {
            boolean hovered = card.contains(mouseX, mouseY);
            boolean on = Boolean.TRUE.equals(card.enabled().get());
            YjTheme.panel(ctx, card.x(), card.y(), card.w(), card.h(), hovered ? YjTheme.CARD_HOVER : YjTheme.CARD, YjTheme.BORDER_SOFT);
            ctx.fill(card.x(), card.y(), card.x() + 2, card.y() + card.h(), on ? YjTheme.ACCENT : YjTheme.TEXT_MUTED);
            // Three lines only, with more breathing room; details live in the hover tooltip.
            ctx.drawText(tr, Text.literal(card.label()).formatted(Formatting.BOLD), card.x() + 10, card.y() + 9, YjTheme.TEXT, false);
            int dotX = card.x() + card.w() - 12;
            ctx.fill(dotX, card.y() + 11, dotX + 5, card.y() + 16, on ? YjTheme.SUCCESS : YjTheme.TEXT_MUTED);
            ctx.drawText(tr, card.desc(), card.x() + 10, card.y() + 24, YjTheme.TEXT_MUTED, false);
            String sum = card.summary().get();
            ctx.drawText(tr, sum, card.x() + 10, card.y() + 37, YjTheme.TEXT_DIM, false);
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
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Turn Auto Left on or off.");
         y += YjTheme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Weapon Mode", this.cfg.weaponCheck, v -> {
            this.cfg.weaponCheck = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Only click while holding a sword or axe.");
         y += YjTheme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, YjTheme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += YjTheme.ROW + 4;
         this.addSlider(x, y, w, "Min CPS", 1, 40, this.cfg.minCps, true, v -> this.cfg.minCps = (int) Math.round(v));
         addHelp(x, y, w, YjTheme.CTRL_H, "Minimum clicks per second.");
         y += YjTheme.ROW + 2;
         this.addSlider(x, y, w, "Max CPS", 1, 40, this.cfg.maxCps, true, v -> this.cfg.maxCps = (int) Math.round(v));
         addHelp(x, y, w, YjTheme.CTRL_H, "Maximum clicks per second.");
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
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Turn Auto Right on or off.");
         y += YjTheme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Block Mode", this.cfg.blockMode, v -> {
            this.cfg.blockMode = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Fast placement only while holding a block.",
            "Fire Charge / pearls always fire one use per press.");
         y += YjTheme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, YjTheme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += YjTheme.ROW + 4;
         this.addSlider(x, y, w, "Min CPS", 1, 40, this.cfg.minCps, true, v -> this.cfg.minCps = (int) Math.round(v));
         addHelp(x, y, w, YjTheme.CTRL_H, "Minimum blocks placed per second.");
         y += YjTheme.ROW + 2;
         this.addSlider(x, y, w, "Max CPS", 1, 40, this.cfg.maxCps, true, v -> this.cfg.maxCps = (int) Math.round(v));
         addHelp(x, y, w, YjTheme.CTRL_H, "Maximum blocks placed per second.");
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
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Turn Ninja Bridge on or off.");
         y += YjTheme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Auto Switch", this.cfg.autoSwitch, v -> {
            this.cfg.autoSwitch = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Automatically hold a placeable block while bridging.");
         y += YjTheme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, YjTheme.CTRL_H,
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
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Turn AimAssist on or off.");
         y += YjTheme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, YjTheme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += YjTheme.ROW + 4;
         this.addSlider(x, y, w, "Speed", 0.01, 1.0, this.cfg.speed, false, v -> this.cfg.speed = (float) v);
         addHelp(x, y, w, YjTheme.CTRL_H, "Base turn strength toward the target.");
         y += YjTheme.ROW + 2;
         this.addSlider(x, y, w, "Smoothness", 0.0, 1.0, this.cfg.smoothness, false, v -> this.cfg.smoothness = (float) v);
         addHelp(x, y, w, YjTheme.CTRL_H, "Higher = softer, slower aim.");
         y += YjTheme.ROW + 2;
         this.addSlider(x, y, w, "FOV", 10, 180, this.cfg.fov, false, v -> this.cfg.fov = (float) v);
         addHelp(x, y, w, YjTheme.CTRL_H, "Cone (degrees) in which targets are acquired.");
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
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Enabled", this.cfg.enabled, v -> {
            this.cfg.enabled = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Turn the tracker overlay and box on or off.");
         y += YjTheme.ROW;
         this.addDrawableChild(new ToggleSwitch(x, y, w, YjTheme.CTRL_H, "Ignore Team", this.cfg.ignoreOwnTeam, v -> {
            this.cfg.ignoreOwnTeam = v;
            this.markEdited();
         }));
         addHelp(x, y, w, YjTheme.CTRL_H, "Skip players on your own scoreboard team.");
         y += YjTheme.ROW;
         this.keybind = this.addDrawableChild(new KeybindButton(x, y, w, YjTheme.CTRL_H,
            () -> this.cfg.toggleKeyCode, code -> {
               this.cfg.toggleKeyCode = code;
               this.markEdited();
               this.commit();
               this.dirty = false;
            }));
         y += YjTheme.ROW + 4;
         this.addSlider(x, y, w, "Range", 1, 128, this.cfg.range, false, v -> this.cfg.range = v);
         addHelp(x, y, w, YjTheme.CTRL_H, "Maximum distance (blocks) for alerts and the box.");
         y += YjTheme.ROW + 6;
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD Position"), b -> {
            if (this.dirty) {
               this.commit();
               this.dirty = false;
            }
            if (this.client != null) this.client.setScreen(new TrackerHudEditorScreen(this, this.cfg));
         }).dimensions(x, y, w, YjTheme.CTRL_H).build());
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
            .dimensions(winX() + winW() - YjTheme.PAD - 66, footerY() + (footerH() - YjTheme.CTRL_H) / 2, 66, YjTheme.CTRL_H).build());
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), b -> {
            TrackerClient.Config d = new TrackerClient.Config();
            this.cfg.hudOffsetX = d.hudOffsetX;
            this.cfg.hudY = d.hudY;
            this.markEdited();
            this.commit();
            this.dirty = false;
         }).dimensions(winX() + winW() - YjTheme.PAD - 66 - 8 - 56, footerY() + (footerH() - YjTheme.CTRL_H) / 2, 56, YjTheme.CTRL_H).build());
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
         YjTheme.panel(ctx, px - 5, py - 3, pw + 10, 15, 0x00000000, YjTheme.ACCENT);
         ctx.drawText(tr, preview, px, py, YjTheme.TEXT, false);
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
