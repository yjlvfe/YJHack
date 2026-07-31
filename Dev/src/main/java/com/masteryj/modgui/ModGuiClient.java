package com.masteryj.modgui;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.config.RecommendedProfiles;
import com.masteryj.modgui.component.KeybindButton;
import com.masteryj.modgui.component.ThemeSlider;
import com.masteryj.modgui.component.ToggleSwitch;
import com.masteryj.modgui.theme.YjTheme;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;
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
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Professional translucent YJHack control panel with per-module recommended Reset and autosave. */
public final class ModGuiClient implements ClientModInitializer {

    private KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        openGuiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.modgui.open", InputUtil.Type.KEYSYM, 344, "category.modgui"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (!(client.currentScreen instanceof YjScreen)) {
                    client.setScreen(new DashboardScreen(null));
                }
            }
        });
    }

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

    private abstract static class YjScreen extends Screen {
        private static final long AUTO_SAVE_DELAY_NANOS = 300_000_000L;

        protected final Screen parent;
        private String toastMessage;
        private long toastUntilNanos;
        private boolean dirty;
        private long lastEditNanos;

        protected YjScreen(Screen parent, String title) {
            super(Text.literal(title));
            this.parent = parent;
        }

        protected abstract String navId();

        protected void applyLive() {
        }

        protected void commit() {
        }

        protected String headerStatus() {
            return null;
        }

        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
        }

        protected final void markEdited() {
            applyLive();
            dirty = true;
            lastEditNanos = System.nanoTime();
        }

        protected final void saveNow() {
            applyLive();
            commit();
            dirty = false;
        }

        protected final void showToast(String message) {
            toastMessage = message;
            toastUntilNanos = System.nanoTime() + 1_600_000_000L;
        }

        /** Rebuilds this page's widgets in place; the screen is not closed or replaced. */
        protected final void refreshControls() {
            clearChildren();
            init();
        }

        protected final void restoreRecommended(Runnable replaceConfig) {
            replaceConfig.run();
            saveNow();
            refreshControls();
            showToast("Recommended settings restored");
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        protected int winW() {
            return Math.max(360, Math.min(560, width - 20));
        }

        protected int winH() {
            return Math.max(280, Math.min(390, height - 20));
        }

        protected int winX() {
            return (width - winW()) / 2;
        }

        protected int winY() {
            return (height - winH()) / 2;
        }

        protected int headerH() {
            return 32;
        }

        protected int footerH() {
            return 32;
        }

        protected int sidebarW() {
            return Math.min(118, Math.max(96, winW() / 4));
        }

        protected int contentX() {
            return winX() + sidebarW() + 12;
        }

        protected int contentRight() {
            return winX() + winW() - 12;
        }

        protected int contentW() {
            return contentRight() - contentX();
        }

        protected int contentTop() {
            return winY() + headerH() + 12;
        }

        protected int footerY() {
            return winY() + winH() - footerH();
        }

        @Override
        public void tick() {
            super.tick();
            if (dirty && System.nanoTime() - lastEditNanos >= AUTO_SAVE_DELAY_NANOS) {
                saveNow();
            }
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            boolean handled = super.mouseReleased(mouseX, mouseY, button);
            if (dirty) saveNow();
            return handled;
        }

        @Override
        public void close() {
            if (dirty) saveNow();
            if (client != null) client.setScreen(parent);
        }

        @Override
        public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
            // No vanilla blur and no vanilla darkening.
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, width, height, YjTheme.SCREEN_TINT);
            renderChrome(context, mouseX, mouseY);
            renderContent(context, mouseX, mouseY, delta);
            super.render(context, mouseX, mouseY, delta);
            renderToast(context, mouseX, mouseY);
        }

        private void renderChrome(DrawContext context, int mouseX, int mouseY) {
            int x = winX();
            int y = winY();
            int w = winW();
            int h = winH();
            YjTheme.panel(context, x, y, w, h, YjTheme.PANEL, YjTheme.BORDER);

            context.fill(x + 1, y + 1, x + w - 1, y + headerH(), YjTheme.HEADER);
            context.fill(x + 1, y + headerH() - 1, x + w - 1, y + headerH(), YjTheme.ACCENT);
            context.drawText(textRenderer, Text.literal("YJHack").formatted(Formatting.BOLD),
                    x + 12, y + 9, YjTheme.TEXT, false);
            context.drawText(textRenderer, "Client", x + 12 + textRenderer.getWidth("YJHack") + 5,
                    y + 10, YjTheme.ACCENT, false);
            String status = headerStatus();
            if (status != null) {
                context.drawText(textRenderer, status,
                        x + w - 12 - textRenderer.getWidth(status), y + 10,
                        YjTheme.TEXT_DIM, false);
            }

            int sidebarTop = y + headerH();
            context.fill(x + 1, sidebarTop, x + sidebarW(), y + h - 1, YjTheme.SIDEBAR);
            context.fill(x + sidebarW(), sidebarTop, x + sidebarW() + 1, y + h - 1,
                    YjTheme.BORDER_SOFT);
            renderNav(context, mouseX, mouseY);
        }

        private void renderNav(DrawContext context, int mouseX, int mouseY) {
            int x = winX() + 8;
            int w = sidebarW() - 12;
            int y = winY() + headerH() + 9;
            for (NavItem item : NAV) {
                boolean active = item.id().equals(navId());
                boolean hovered = mouseX >= x && mouseX <= x + w
                        && mouseY >= y && mouseY <= y + 23;
                if (active) {
                    context.fill(x, y, x + w, y + 23, 0x3335E0C8);
                    context.fill(x, y, x + 2, y + 23, YjTheme.ACCENT);
                } else if (hovered) {
                    context.fill(x, y, x + w, y + 23, YjTheme.CTRL_HOVER);
                }
                context.drawText(textRenderer, item.label(), x + 8, y + 8,
                        active ? YjTheme.TEXT : YjTheme.TEXT_DIM, false);
                y += 25;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int x = winX() + 8;
                int w = sidebarW() - 12;
                int y = winY() + headerH() + 9;
                for (NavItem item : NAV) {
                    if (mouseX >= x && mouseX <= x + w
                            && mouseY >= y && mouseY <= y + 23) {
                        if (!item.id().equals(navId()) && client != null) {
                            if (dirty) saveNow();
                            Screen dashboard = parent instanceof DashboardScreen
                                    ? parent : new DashboardScreen(null);
                            client.setScreen("dashboard".equals(item.id())
                                    ? new DashboardScreen(null)
                                    : screenFor(item.id(), dashboard));
                        }
                        return true;
                    }
                    y += 25;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        protected final void drawHeading(DrawContext context, String heading, String subtitle) {
            context.drawText(textRenderer, Text.literal(heading).formatted(Formatting.BOLD),
                    contentX(), contentTop(), YjTheme.TEXT, false);
            if (subtitle != null) {
                context.drawText(textRenderer, subtitle, contentX(), contentTop() + 12,
                        YjTheme.TEXT_MUTED, false);
            }
            context.fill(contentX(), contentTop() + 24, contentRight(), contentTop() + 25,
                    YjTheme.DIVIDER);
        }

        protected final void addStatusChip(DrawContext context, int x, int y, boolean on) {
            String label = on ? "ENABLED" : "DISABLED";
            int color = on ? YjTheme.SUCCESS : YjTheme.TEXT_MUTED;
            int w = textRenderer.getWidth(label) + 10;
            YjTheme.pill(context, x, y, w, 12, (color & 0x00FFFFFF) | 0x33000000);
            context.drawText(textRenderer, Text.literal(label).formatted(Formatting.BOLD),
                    x + 5, y + 2, color, false);
        }

        private void renderToast(DrawContext context, int mouseX, int mouseY) {
            if (toastMessage != null && System.nanoTime() < toastUntilNanos) {
                int toastW = textRenderer.getWidth(toastMessage) + 20;
                int toastX = winX() + winW() - toastW - 12;
                int toastY = footerY() - 24;
                YjTheme.panel(context, toastX, toastY, toastW, 18,
                        0xE01B2A22, YjTheme.SUCCESS);
                context.fill(toastX, toastY, toastX + 2, toastY + 18, YjTheme.SUCCESS);
                context.drawText(textRenderer, toastMessage, toastX + 10, toastY + 5,
                        YjTheme.TEXT, false);
            }
        }

        protected final ThemeSlider addSlider(int x, int y, int totalW, String label,
                                               double min, double max, double value,
                                               boolean integer, DoubleConsumer setter) {
            int fieldW = 48;
            int sliderW = Math.max(90, totalW - fieldW - 7);
            boolean[] syncing = {false};

            TextFieldWidget field = new TextFieldWidget(textRenderer,
                    x + sliderW + 7, y, fieldW, 22, Text.literal(label));
            field.setMaxLength(8);
            field.setText(integer ? String.valueOf((int) Math.round(value)) : YjTheme.fmt(value));

            ThemeSlider slider = new ThemeSlider(x, y, sliderW, 22,
                    label, min, max, value, integer, v -> {
                setter.accept(v);
                if (!syncing[0]) {
                    syncing[0] = true;
                    field.setText(integer ? String.valueOf((int) Math.round(v)) : YjTheme.fmt(v));
                    syncing[0] = false;
                }
                markEdited();
            });

            field.setChangedListener(text -> {
                if (syncing[0]) return;
                try {
                    double parsed = MathHelper.clamp(Double.parseDouble(text.trim()), min, max);
                    syncing[0] = true;
                    slider.setDomainQuiet(parsed);
                    syncing[0] = false;
                    setter.accept(parsed);
                    markEdited();
                } catch (NumberFormatException ignored) {
                    // Partial typing keeps the previous valid runtime value.
                }
            });

            addDrawableChild(slider);
            addDrawableChild(field);
            return slider;
        }

        protected final void addActionBar(Runnable recommendedReset) {
            int y = footerY() + 6;
            addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), button -> recommendedReset.run())
                    .dimensions(winX() + winW() - 78, y, 64, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("‹ Back"), button -> close())
                    .dimensions(contentX(), y, 64, 20).build());
        }
    }

    private static final class DashboardScreen extends YjScreen {
        private record Card(int x, int y, int w, int h, String id, String label,
                            String description, Supplier<Boolean> enabled,
                            Supplier<String> summary) {
            boolean contains(double mouseX, double mouseY) {
                return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
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
            int active = 0;
            if (AutoLeftClient.enabled) active++;
            if (AutoRightClient.enabled) active++;
            if (NinjaBridgeClient.enabled) active++;
            if (AimAssistClient.enabled) active++;
            if (TrackerClient.enabled) active++;
            return active + " active";
        }

        @Override
        protected void init() {
            cards.clear();
            int gap = 8;
            int cardW = Math.max(130, (contentW() - gap) / 2);
            int cardH = 58;
            int x = contentX();
            int y = contentTop() + 32;
            addCard(x, y, cardW, cardH, "autoleft", "Auto Left", "Legacy combat attempts",
                    () -> AutoLeftClient.enabled, () -> AutoLeftClient.cps + " CPS");
            addCard(x + cardW + gap, y, cardW, cardH, "autoright", "Auto Right", "Validated block use",
                    () -> AutoRightClient.enabled, () -> AutoRightClient.cps + " CPS");
            addCard(x, y + cardH + gap, cardW, cardH, "ninjabridge", "Ninja Bridge", "Edge sneak + stacks",
                    () -> NinjaBridgeClient.enabled,
                    () -> NinjaBridgeClient.autoSwitch ? "Auto-switch" : "Manual slot");
            addCard(x + cardW + gap, y + cardH + gap, cardW, cardH,
                    "aimassist", "AimAssist", "3.5 max • line of sight",
                    () -> AimAssistClient.enabled,
                    () -> YjTheme.fmt(AimAssistClient.range) + " range");
            addCard(x, y + (cardH + gap) * 2, cardW, cardH,
                    "tracker", "Tracker", "Client-known player HUD",
                    () -> TrackerClient.enabled,
                    () -> YjTheme.fmt(TrackerClient.range) + " range");
        }

        private void addCard(int x, int y, int w, int h, String id, String label,
                             String description, Supplier<Boolean> enabled,
                             Supplier<String> summary) {
            cards.add(new Card(x, y, w, h, id, label, description, enabled, summary));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "Dashboard", "Independent modules • automatic saving");
            for (Card card : cards) {
                boolean hover = card.contains(mouseX, mouseY);
                YjTheme.panel(context, card.x, card.y, card.w, card.h,
                        hover ? YjTheme.CARD_HOVER : YjTheme.CARD, YjTheme.BORDER_SOFT);
                if (card.enabled.get()) context.fill(card.x, card.y, card.x + 2, card.y + card.h, YjTheme.SUCCESS);
                context.drawText(textRenderer, Text.literal(card.label).formatted(Formatting.BOLD),
                        card.x + 9, card.y + 8, YjTheme.TEXT, false);
                context.drawText(textRenderer, card.description, card.x + 9, card.y + 22,
                        YjTheme.TEXT_MUTED, false);
                context.drawText(textRenderer, card.summary.get(), card.x + 9, card.y + 39,
                        YjTheme.TEXT_DIM, false);
                addStatusChip(context, card.x + card.w - 62, card.y + 7, card.enabled.get());
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && client != null) {
                for (Card card : cards) {
                    if (card.contains(mouseX, mouseY)) {
                        client.setScreen(screenFor(card.id, this));
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class AutoLeftScreen extends YjScreen {
        private AutoLeftClient.Config cfg;
        private KeybindButton keybind;

        private AutoLeftScreen(Screen parent) {
            super(parent, "Auto Left");
            cfg = AutoLeftClient.config == null
                    ? RecommendedProfiles.autoLeft()
                    : AutoLeftClient.config.copy();
        }

        @Override
        protected int winH() {
            return Math.max(420, Math.min(530, height - 20));
        }

        @Override protected String navId() { return "autoleft"; }
        @Override protected String headerStatus() { return cfg.enabled ? "ENABLED" : "DISABLED"; }
        @Override protected void applyLive() { AutoLeftClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AutoLeftClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop() + 34;
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 32, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 66, w, "CPS", 1, 40, cfg.cps, true,
                    value -> cfg.cps = (int) Math.round(value));
            addDrawableChild(new ToggleSwitch(x, y + 96, w, 22, "Jitter (Anti-Cheat)", cfg.jitterEnabled, value -> {
                cfg.jitterEnabled = value;
                saveNow();
            }));
            addDrawableChild(new HudPositionEditor(x, y + 126, w, 76,
                    () -> AutoLeftClient.cpsHudX, () -> AutoLeftClient.cpsHudY,
                    (newX, newY) -> {
                        AutoLeftClient.cpsHudX = newX;
                        AutoLeftClient.cpsHudY = newY;
                        markEdited();
                    }));
            addSlider(x, y + 210, w, "HUD X", 4, 1000, AutoLeftClient.cpsHudX, true,
                    value -> AutoLeftClient.cpsHudX = (int) Math.round(value));
            addSlider(x, y + 240, w, "HUD Y", 4, 1000, AutoLeftClient.cpsHudY, true,
                    value -> AutoLeftClient.cpsHudY = (int) Math.round(value));
            addActionBar(() -> restoreRecommended(() -> cfg = RecommendedProfiles.autoLeft()));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "Auto Left", "One owner • no queue • no catch-up • vanilla reach");
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keybind != null && keybind.captureKey(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (keybind != null && keybind.isListening() && keybind.captureMouse(button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class AutoRightScreen extends YjScreen {
        private AutoRightClient.Config cfg;
        private KeybindButton keybind;

        private AutoRightScreen(Screen parent) {
            super(parent, "Auto Right");
            cfg = AutoRightClient.config == null
                    ? RecommendedProfiles.autoRight()
                    : AutoRightClient.config.copy();
        }

        @Override protected String navId() { return "autoright"; }
        @Override protected String headerStatus() { return cfg.enabled ? "ENABLED" : "DISABLED"; }
        @Override protected void applyLive() { AutoRightClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AutoRightClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop() + 34;
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 32, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 66, w, "CPS", 1, 40, cfg.cps, true,
                    value -> cfg.cps = (int) Math.round(value));
            addDrawableChild(new ToggleSwitch(x, y + 96, w, 22, "Jitter (Anti-Cheat)", cfg.jitterEnabled, value -> {
                cfg.jitterEnabled = value;
                saveNow();
            }));
            addActionBar(() -> restoreRecommended(() -> cfg = RecommendedProfiles.autoRight()));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "Auto Right", "Vanilla interactions • placement precheck • no burst");
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keybind != null && keybind.captureKey(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (keybind != null && keybind.isListening() && keybind.captureMouse(button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class NinjaBridgeScreen extends YjScreen {
        private NinjaBridgeClient.Config cfg;
        private KeybindButton keybind;

        private NinjaBridgeScreen(Screen parent) {
            super(parent, "Ninja Bridge");
            cfg = NinjaBridgeClient.config == null
                    ? RecommendedProfiles.ninjaBridge()
                    : NinjaBridgeClient.config.copy();
        }

        @Override protected String navId() { return "ninjabridge"; }
        @Override protected String headerStatus() { return cfg.enabled ? "ENABLED" : "DISABLED"; }
        @Override protected void applyLive() { NinjaBridgeClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { NinjaBridgeClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop() + 34;
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 30, w, 22, "Auto Switch", cfg.autoSwitch, value -> {
                cfg.autoSwitch = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 60, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 94, w, "Slot delay", 50, 500, cfg.switchDelayMs, true,
                    value -> cfg.switchDelayMs = (int) Math.round(value));
            addActionBar(() -> restoreRecommended(() -> cfg = RecommendedProfiles.ninjaBridge()));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "Ninja Bridge", "Edge sneak • stack continuity • clear ownership");
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keybind != null && keybind.captureKey(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (keybind != null && keybind.isListening() && keybind.captureMouse(button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class AimAssistScreen extends YjScreen {
        private AimAssistClient.Config cfg;
        private KeybindButton keybind;

        private AimAssistScreen(Screen parent) {
            super(parent, "AimAssist");
            cfg = AimAssistClient.config == null
                    ? RecommendedProfiles.aimAssist()
                    : AimAssistClient.config.copy();
        }

        @Override protected String navId() { return "aimassist"; }
        @Override protected String headerStatus() { return cfg.enabled ? "ENABLED" : "DISABLED"; }
        @Override protected void applyLive() { AimAssistClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AimAssistClient.saveConfigStatic(cfg); }

        @Override
        protected int winH() {
            return Math.max(340, Math.min(430, height - 20));
        }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop() + 32;
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 28, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 56, w, 22, "Sticky Lock", cfg.stickyLock, value -> {
                cfg.stickyLock = value;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 84, w, 22, "Bed Lock", cfg.bedLock, value -> {
                cfg.bedLock = value;
                saveNow();
            }));
            ToggleSwitch lineOfSight = addDrawableChild(new ToggleSwitch(x, y + 112, w, 22,
                    "Line of Sight (required)", true, value -> { }));
            lineOfSight.active = false;
            addSlider(x, y + 142, w, "Range", 1.0, 3.5, cfg.range, false,
                    value -> cfg.range = value);
            addSlider(x, y + 172, w, "Speed", 0.01, 1.0, cfg.speed, false,
                    value -> cfg.speed = (float) value);
            addSlider(x, y + 202, w, "Smoothness", 0.0, 1.0, cfg.smoothness, false,
                    value -> cfg.smoothness = (float) value);
            addSlider(x, y + 232, w, "FOV", 10, 180, cfg.fov, false,
                    value -> cfg.fov = (float) value);
            addActionBar(() -> restoreRecommended(() -> cfg = RecommendedProfiles.aimAssist()));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "AimAssist", "Deterministic movement • sticky target • bed protection");
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keybind != null && keybind.captureKey(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (keybind != null && keybind.isListening() && keybind.captureMouse(button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class TrackerScreen extends YjScreen {
        private TrackerClient.Config cfg;
        private KeybindButton keybind;

        private TrackerScreen(Screen parent) {
            super(parent, "Tracker");
            cfg = copyTracker();
        }

        private static TrackerClient.Config copyTracker() {
            TrackerClient.Config source = TrackerClient.config;
            if (source == null) return RecommendedProfiles.tracker();
            TrackerClient.Config result = new TrackerClient.Config();
            result.configVersion = source.configVersion;
            result.enabled = source.enabled;
            result.toggleKeyCode = source.toggleKeyCode;
            result.ignoreOwnTeam = source.ignoreOwnTeam;
            result.range = source.range;
            result.hudOffsetX = source.hudOffsetX;
            result.hudY = source.hudY;
            result.normalize();
            return result;
        }

        @Override protected String navId() { return "tracker"; }
        @Override protected String headerStatus() { return cfg.enabled ? "ENABLED" : "DISABLED"; }
        @Override protected void applyLive() { TrackerClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { TrackerClient.saveConfigStatic(cfg); }

        @Override
        protected int winH() {
            return Math.max(340, Math.min(430, height - 20));
        }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop() + 32;
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 28, w, 22, "Ignore Own Team", cfg.ignoreOwnTeam, value -> {
                cfg.ignoreOwnTeam = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 56, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 86, w, "Range", 1, 128, cfg.range, false,
                    value -> cfg.range = value);
            addDrawableChild(new HudPositionEditor(x, y + 118, w, 76,
                    () -> cfg.hudOffsetX, () -> cfg.hudY,
                    (newX, newY) -> {
                        cfg.hudOffsetX = newX;
                        cfg.hudY = newY;
                        markEdited();
                    }));
            addSlider(x, y + 202, w, "HUD X", 4, 1000, cfg.hudOffsetX, true,
                    value -> cfg.hudOffsetX = (int) Math.round(value));
            addSlider(x, y + 232, w, "HUD Y", 4, 1000, cfg.hudY, true,
                    value -> cfg.hudY = (int) Math.round(value));
            addActionBar(() -> restoreRecommended(() -> cfg = RecommendedProfiles.tracker()));
        }

        @Override
        protected void renderContent(DrawContext context, int mouseX, int mouseY, float delta) {
            drawHeading(context, "Tracker", "HUD range • team filter • position editor");
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keybind != null && keybind.captureKey(keyCode)) return true;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (keybind != null && keybind.isListening() && keybind.captureMouse(button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }
    }

    private static final class HudPositionEditor extends ClickableWidget {
        private final IntSupplier hudX;
        private final IntSupplier hudY;
        private final BiConsumer<Integer, Integer> onChange;

        private HudPositionEditor(int x, int y, int width, int height,
                                  IntSupplier hudX, IntSupplier hudY,
                                  BiConsumer<Integer, Integer> onChange) {
            super(x, y, width, height, Text.literal("HUD position editor"));
            this.hudX = hudX;
            this.hudY = hudY;
            this.onChange = onChange;
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            YjTheme.panel(context, getX(), getY(), getWidth(), getHeight(),
                    isMouseOver(mouseX, mouseY) ? YjTheme.CTRL_HOVER : YjTheme.CTRL,
                    YjTheme.BORDER_SOFT);
            TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
            context.drawText(renderer, "HUD POSITION", getX() + 7, getY() + 6,
                    YjTheme.TEXT_MUTED, false);
            int areaX = getX() + 7;
            int areaY = getY() + 18;
            int areaW = getWidth() - 14;
            int areaH = getHeight() - 25;
            context.fill(areaX, areaY, areaX + areaW, areaY + areaH, 0x44101820);
            int markerX = areaX + (int) Math.round(MathHelper.clamp(hudX.getAsInt(), 4, 1000) / 1000.0D * (areaW - 8));
            int markerY = areaY + (int) Math.round(MathHelper.clamp(hudY.getAsInt(), 4, 1000) / 1000.0D * (areaH - 8));
            YjTheme.pill(context, markerX, markerY, 8, 8, YjTheme.ACCENT);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (active && visible && button == 0 && isMouseOver(mouseX, mouseY)) {
                updateFromMouse(mouseX, mouseY);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button,
                                    double deltaX, double deltaY) {
            if (active && visible && button == 0) {
                updateFromMouse(mouseX, mouseY);
                return true;
            }
            return false;
        }

        private void updateFromMouse(double mouseX, double mouseY) {
            int areaX = getX() + 7;
            int areaY = getY() + 18;
            int areaW = Math.max(1, getWidth() - 14);
            int areaH = Math.max(1, getHeight() - 25);
            int x = (int) Math.round(MathHelper.clamp((mouseX - areaX) / areaW, 0.0D, 1.0D) * 996.0D) + 4;
            int y = (int) Math.round(MathHelper.clamp((mouseY - areaY) / areaH, 0.0D, 1.0D) * 996.0D) + 4;
            onChange.accept(x, y);
        }

        @Override
        protected void appendClickableNarrations(NarrationMessageBuilder builder) {
            builder.put(NarrationPart.TITLE, Text.literal(
                    "HUD position X " + hudX.getAsInt() + ", Y " + hudY.getAsInt()));
        }
    }
}
