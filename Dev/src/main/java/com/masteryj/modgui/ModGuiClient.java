package com.masteryj.modgui;

import com.masteryj.aimassist.AimAssistClient;
import com.masteryj.autoleft.AutoLeftClient;
import com.masteryj.autoright.AutoRightClient;
import com.masteryj.modgui.component.KeybindButton;
import com.masteryj.modgui.component.ThemeSlider;
import com.masteryj.modgui.component.ToggleSwitch;
import com.masteryj.modgui.theme.YjTheme;
import com.masteryj.ninjabridge.NinjaBridgeClient;
import com.masteryj.tracker.TrackerClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.function.DoubleConsumer;

/** Compact settings UI with automatic persistence and no manual Save buttons. */
public final class ModGuiClient implements ClientModInitializer {

    private KeyBinding openGuiKey;

    @Override
    public void onInitializeClient() {
        openGuiKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.modgui.open", InputUtil.Type.KEYSYM, 344, "category.modgui"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKey.wasPressed()) {
                if (!(client.currentScreen instanceof SimpleScreen)) {
                    client.setScreen(new DashboardScreen(null));
                }
            }
        });
    }

    private static Screen screenFor(String id, Screen parent) {
        return switch (id) {
            case "autoleft" -> new AutoLeftScreen(parent);
            case "autoright" -> new AutoRightScreen(parent);
            case "ninjabridge" -> new NinjaBridgeScreen(parent);
            case "aimassist" -> new AimAssistScreen(parent);
            case "tracker" -> new TrackerScreen(parent);
            default -> new DashboardScreen(parent);
        };
    }

    private abstract static class SimpleScreen extends Screen {
        private static final long AUTO_SAVE_DELAY_NANOS = 180_000_000L;

        protected final Screen parent;
        private boolean dirty;
        private long lastEditNanos;

        protected SimpleScreen(Screen parent, String title) {
            super(Text.literal(title));
            this.parent = parent;
        }

        protected void applyLive() {
        }

        protected void commit() {
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

        @Override
        public boolean shouldPause() {
            return false;
        }

        protected int panelW() {
            return Math.max(300, Math.min(430, width - 20));
        }

        protected int panelH() {
            return Math.max(220, Math.min(340, height - 20));
        }

        protected int panelX() {
            return (width - panelW()) / 2;
        }

        protected int panelY() {
            return (height - panelH()) / 2;
        }

        protected int contentX() {
            return panelX() + 18;
        }

        protected int contentW() {
            return panelW() - 36;
        }

        protected int contentTop() {
            return panelY() + 46;
        }

        protected int footerY() {
            return panelY() + panelH() - 30;
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
            // No vanilla blur or darkening.
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
            ctx.fill(0, 0, width, height, YjTheme.SCREEN_TINT);
            YjTheme.panel(ctx, panelX(), panelY(), panelW(), panelH(), YjTheme.PANEL, YjTheme.BORDER);
            ctx.fill(panelX() + 1, panelY() + 1, panelX() + panelW() - 1,
                    panelY() + 32, YjTheme.HEADER);
            ctx.fill(panelX() + 1, panelY() + 31, panelX() + panelW() - 1,
                    panelY() + 32, YjTheme.ACCENT);
            ctx.drawText(textRenderer, Text.literal(title.getString()).formatted(Formatting.BOLD),
                    panelX() + 14, panelY() + 11, YjTheme.TEXT, false);
            renderLabels(ctx);
            super.render(ctx, mouseX, mouseY, delta);
        }

        protected void renderLabels(DrawContext ctx) {
        }

        protected ThemeSlider addSlider(int x, int y, int totalW, String label,
                                        double min, double max, double value,
                                        boolean integer, DoubleConsumer setter) {
            int fieldW = 48;
            int sliderW = Math.max(80, totalW - fieldW - 7);
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
                    // Allow partial typing; the previous valid value remains active.
                }
            });

            addDrawableChild(slider);
            addDrawableChild(field);
            return slider;
        }

        protected void addFooter(Runnable reset) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Reset"), button -> reset.run())
                    .dimensions(panelX() + panelW() - 78, footerY(), 64, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("‹ Back"), button -> close())
                    .dimensions(panelX() + 14, footerY(), 64, 20).build());
        }
    }

    private static final class DashboardScreen extends SimpleScreen {
        private DashboardScreen(Screen parent) {
            super(parent, "YJHack");
        }

        @Override
        protected int panelH() {
            return Math.max(250, Math.min(300, height - 20));
        }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
            int w = contentW();
            addModuleButton(x, y, w, "Auto Left", "autoleft");
            addModuleButton(x, y + 34, w, "Auto Right", "autoright");
            addModuleButton(x, y + 68, w, "Ninja Bridge", "ninjabridge");
            addModuleButton(x, y + 102, w, "AimAssist", "aimassist");
            addModuleButton(x, y + 136, w, "Tracker", "tracker");
            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                    .dimensions(panelX() + panelW() - 78, footerY(), 64, 20).build());
        }

        private void addModuleButton(int x, int y, int w, String label, String id) {
            addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
                if (client != null) client.setScreen(screenFor(id, this));
            }).dimensions(x, y, w, 26).build());
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "Simple controls • automatic saving",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
        }
    }

    private static final class AutoLeftScreen extends SimpleScreen {
        private final AutoLeftClient.Config cfg = copy();
        private KeybindButton keybind;

        private AutoLeftScreen(Screen parent) {
            super(parent, "Auto Left");
        }

        private static AutoLeftClient.Config copy() {
            AutoLeftClient.Config result = new AutoLeftClient.Config();
            AutoLeftClient.Config source = AutoLeftClient.config;
            if (source != null) {
                result.configVersion = source.configVersion;
                result.enabled = source.enabled;
                result.toggleKeyCode = source.toggleKeyCode;
                result.cps = source.cps;
            }
            return result;
        }

        @Override protected void applyLive() { AutoLeftClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AutoLeftClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 34, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 72, w, "CPS", 1, 40, cfg.cps, true,
                    value -> cfg.cps = (int) Math.round(value));
            addFooter(this::reset);
        }

        private void reset() {
            AutoLeftClient.Config defaults = new AutoLeftClient.Config();
            cfg.enabled = defaults.enabled;
            cfg.toggleKeyCode = defaults.toggleKeyCode;
            cfg.cps = defaults.cps;
            saveNow();
            if (client != null) client.setScreen(new AutoLeftScreen(parent));
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "One fixed rate, direct Minecraft attacks, independent 1–40 CPS.",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
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

    private static final class AutoRightScreen extends SimpleScreen {
        private final AutoRightClient.Config cfg = copy();
        private KeybindButton keybind;

        private AutoRightScreen(Screen parent) {
            super(parent, "Auto Right");
        }

        private static AutoRightClient.Config copy() {
            AutoRightClient.Config result = new AutoRightClient.Config();
            AutoRightClient.Config source = AutoRightClient.config;
            if (source != null) {
                result.configVersion = source.configVersion;
                result.enabled = source.enabled;
                result.toggleKeyCode = source.toggleKeyCode;
                result.cps = source.cps;
            }
            return result;
        }

        @Override protected void applyLive() { AutoRightClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AutoRightClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 34, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 72, w, "CPS", 1, 40, cfg.cps, true,
                    value -> cfg.cps = (int) Math.round(value));
            addFooter(this::reset);
        }

        private void reset() {
            AutoRightClient.Config defaults = new AutoRightClient.Config();
            cfg.enabled = defaults.enabled;
            cfg.toggleKeyCode = defaults.toggleKeyCode;
            cfg.cps = defaults.cps;
            saveNow();
            if (client != null) client.setScreen(new AutoRightScreen(parent));
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "Blocks use one fixed direct rate; hold/charge items stay vanilla.",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
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

    private static final class NinjaBridgeScreen extends SimpleScreen {
        private final NinjaBridgeClient.Config cfg = copy();
        private KeybindButton keybind;

        private NinjaBridgeScreen(Screen parent) {
            super(parent, "Ninja Bridge");
        }

        private static NinjaBridgeClient.Config copy() {
            NinjaBridgeClient.Config result = new NinjaBridgeClient.Config();
            NinjaBridgeClient.Config source = NinjaBridgeClient.config;
            if (source != null) {
                result.configVersion = source.configVersion;
                result.enabled = source.enabled;
                result.toggleKeyCode = source.toggleKeyCode;
                result.autoSwitch = source.autoSwitch;
            }
            return result;
        }

        @Override protected void applyLive() { NinjaBridgeClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { NinjaBridgeClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 34, w, 22, "Auto Switch", cfg.autoSwitch, value -> {
                cfg.autoSwitch = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 68, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addFooter(this::reset);
        }

        private void reset() {
            NinjaBridgeClient.Config defaults = new NinjaBridgeClient.Config();
            cfg.enabled = defaults.enabled;
            cfg.toggleKeyCode = defaults.toggleKeyCode;
            cfg.autoSwitch = defaults.autoSwitch;
            saveNow();
            if (client != null) client.setScreen(new NinjaBridgeScreen(parent));
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "Automatic edge sneak with optional block switching.",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
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

    private static final class AimAssistScreen extends SimpleScreen {
        private final AimAssistClient.Config cfg = copy();
        private KeybindButton keybind;

        private AimAssistScreen(Screen parent) {
            super(parent, "AimAssist");
        }

        private static AimAssistClient.Config copy() {
            AimAssistClient.Config result = new AimAssistClient.Config();
            AimAssistClient.Config source = AimAssistClient.config;
            if (source != null) {
                result.configVersion = source.configVersion;
                result.enabled = source.enabled;
                result.toggleKeyCode = source.toggleKeyCode;
                result.speed = source.speed;
                result.smoothness = source.smoothness;
                result.fov = source.fov;
            }
            return result;
        }

        @Override protected void applyLive() { AimAssistClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { AimAssistClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
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
            addSlider(x, y + 66, w, "Speed", 0.01, 1.0, cfg.speed, false,
                    value -> cfg.speed = (float) value);
            addSlider(x, y + 98, w, "Smoothness", 0.0, 1.0, cfg.smoothness, false,
                    value -> cfg.smoothness = (float) value);
            addSlider(x, y + 130, w, "FOV", 10, 180, cfg.fov, false,
                    value -> cfg.fov = (float) value);
            addFooter(this::reset);
        }

        private void reset() {
            AimAssistClient.Config defaults = new AimAssistClient.Config();
            cfg.enabled = defaults.enabled;
            cfg.toggleKeyCode = defaults.toggleKeyCode;
            cfg.speed = defaults.speed;
            cfg.smoothness = defaults.smoothness;
            cfg.fov = defaults.fov;
            saveNow();
            if (client != null) client.setScreen(new AimAssistScreen(parent));
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "Breaking a bed locks aim to the bed until release or a real player hit.",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
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

    private static final class TrackerScreen extends SimpleScreen {
        private final TrackerClient.Config cfg = copy();
        private KeybindButton keybind;

        private TrackerScreen(Screen parent) {
            super(parent, "Tracker");
        }

        private static TrackerClient.Config copy() {
            TrackerClient.Config result = new TrackerClient.Config();
            TrackerClient.Config source = TrackerClient.config;
            if (source != null) {
                result.configVersion = source.configVersion;
                result.enabled = source.enabled;
                result.toggleKeyCode = source.toggleKeyCode;
                result.ignoreOwnTeam = source.ignoreOwnTeam;
                result.range = source.range;
                result.hudOffsetX = source.hudOffsetX;
                result.hudY = source.hudY;
            }
            return result;
        }

        @Override protected void applyLive() { TrackerClient.applyRuntimeConfig(cfg); }
        @Override protected void commit() { TrackerClient.saveConfigStatic(cfg); }

        @Override
        protected void init() {
            int x = contentX();
            int y = contentTop();
            int w = contentW();
            addDrawableChild(new ToggleSwitch(x, y, w, 22, "Enabled", cfg.enabled, value -> {
                cfg.enabled = value;
                saveNow();
            }));
            addDrawableChild(new ToggleSwitch(x, y + 30, w, 22, "Ignore Team", cfg.ignoreOwnTeam, value -> {
                cfg.ignoreOwnTeam = value;
                saveNow();
            }));
            keybind = addDrawableChild(new KeybindButton(x, y + 60, w, 22,
                    () -> cfg.toggleKeyCode, code -> {
                cfg.toggleKeyCode = code;
                saveNow();
            }));
            addSlider(x, y + 92, w, "Range", 1, 128, cfg.range, false,
                    value -> cfg.range = value);
            addSlider(x, y + 124, w, "HUD X", 4, 1000, cfg.hudOffsetX, true,
                    value -> cfg.hudOffsetX = (int) Math.round(value));
            addSlider(x, y + 156, w, "HUD Y", 4, 1000, cfg.hudY, true,
                    value -> cfg.hudY = (int) Math.round(value));
            addFooter(this::reset);
        }

        private void reset() {
            TrackerClient.Config defaults = new TrackerClient.Config();
            cfg.enabled = defaults.enabled;
            cfg.toggleKeyCode = defaults.toggleKeyCode;
            cfg.ignoreOwnTeam = defaults.ignoreOwnTeam;
            cfg.range = defaults.range;
            cfg.hudOffsetX = defaults.hudOffsetX;
            cfg.hudY = defaults.hudY;
            saveNow();
            if (client != null) client.setScreen(new TrackerScreen(parent));
        }

        @Override
        protected void renderLabels(DrawContext ctx) {
            ctx.drawText(textRenderer, "Range and HUD position are saved automatically.",
                    contentX(), panelY() + 34, YjTheme.TEXT_MUTED, false);
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
}
