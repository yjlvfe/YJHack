package com.masteryj.modgui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
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

public final class ModGuiClient implements ClientModInitializer {
   private static final Logger LOGGER = LoggerFactory.getLogger("YJHack-ModGui");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
   private static final Path AUTO_LEFT_CONFIG = CONFIG_DIR.resolve("autoleft.json");
   private static final Path AUTO_RIGHT_CONFIG = CONFIG_DIR.resolve("autoright.json");
   private static final Path NINJA_BRIDGE_CONFIG = CONFIG_DIR.resolve("ninjabridge.json");
   private static final Path AIM_ASSIST_CONFIG = CONFIG_DIR.resolve("aimassist.json");
   private static final Path TRACKER_CONFIG = CONFIG_DIR.resolve("tracker.json");
   private static final String MOD_ID_AUTO_LEFT = "autoleft";
   private static final String MOD_ID_AUTO_RIGHT = "autoright";
   private static final String MOD_ID_NINJA_BRIDGE = "ninjabridge";
   private static final String MOD_ID_AIM_ASSIST = "aimassist";
   private static final String MOD_ID_TRACKER = "tracker";
   private static final int MOUSE_KEY_OFFSET = 1000;
   private static final String NAV_DASHBOARD = "dashboard";
   private static final List<ModGuiClient.NavItem> NAV_ITEMS = List.of(
      new ModGuiClient.NavItem("dashboard", "Dashboard", null, "Overview and access for all installed modules."),
      new ModGuiClient.NavItem("autoleft", "Auto Left", "autoleft", "Left click speed and weapon mode."),
      new ModGuiClient.NavItem("autoright", "Auto Right", "autoright", "Right click speed and block mode."),
      new ModGuiClient.NavItem("ninjabridge", "Ninja Bridge", "ninjabridge", "Bridge helper and toggle behavior."),
      new ModGuiClient.NavItem("aimassist", "AimAssist", "aimassist", "Aim range and turn strength controls."),
      new ModGuiClient.NavItem("tracker", "Tracker", "tracker", "Tracker range and HUD position editor.")
   );
   private KeyBinding openGuiKey;

   public ModGuiClient() {
   }

   public void onInitializeClient() {
      this.openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.modgui.open", InputUtil.Type.KEYSYM, 344, "category.modgui"));
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         while (this.openGuiKey.wasPressed()) {
            if (!(client.currentScreen instanceof ModGuiClient.MainScreen)) {
               client.setScreen(new ModGuiClient.MainScreen());
            }
         }
      });
   }

   private static boolean isLoaded(String modId) {
      return FabricLoader.getInstance().isModLoaded(modId);
   }

   private static <T> T loadConfig(Path path, Class<T> type, Supplier<T> defaults) {
      try {
         if (Files.exists(path)) {
            T loaded = (T)GSON.fromJson(Files.readString(path), type);
            if (loaded != null) {
               normalizeConfigObject(loaded);
               return loaded;
            }
         }
      } catch (Exception e) {
         LOGGER.warn("Config {} unreadable ({}); using defaults", path.getFileName(), e.toString());
      }

      T fresh = defaults.get();
      normalizeConfigObject(fresh);
      saveConfig(path, fresh);
      return fresh;
   }

   private static void saveConfig(Path path, Object config) {
      try {
         normalizeConfigObject(config);
         Files.createDirectories(path.getParent());
         Files.writeString(path, GSON.toJson(config));
         injectRuntimeConfig(config);
      } catch (IOException e) {
         LOGGER.warn("Failed to save config {}: {}", path.getFileName(), e.getMessage());
      }
   }

   private static void injectRuntimeConfig(Object config) {
      if (config instanceof ModGuiClient.AutoLeftConfig) {
         injectToMod(config, "com.masteryj.autoleft.AutoLeftClient");
      } else if (config instanceof ModGuiClient.AutoRightConfig) {
         injectToMod(config, "com.masteryj.autoright.AutoRightClient");
      } else if (config instanceof ModGuiClient.NinjaBridgeConfig) {
         injectToMod(config, "com.masteryj.ninjabridge.NinjaBridgeClient");
      } else if (config instanceof ModGuiClient.AimAssistConfig) {
         injectToMod(config, "com.masteryj.aimassist.AimAssistClient");
      } else if (config instanceof ModGuiClient.TrackerConfig) {
         injectToMod(config, "com.masteryj.tracker.TrackerClient");
      }
   }

   private static void injectToMod(Object src, String targetClassName) {
      try {
         Class<?> targetClass = Class.forName(targetClassName);
         Object targetConfig = targetClass.getField("config").get(null);
         if (targetConfig == null) {
            return;
         }

         // Copy matching fields from the GUI config into the module's live config
         // object. The GUI stores CPS/threshold values as float while the modules
         // store them as int, so every value is coerced to the target field's type.
         // Fields that don't exist on the target (e.g. GUI-only tapThresholdMs) are
         // skipped silently — that is expected, not an error.
         for (Field srcField : src.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(srcField.getModifiers())) {
               continue;
            }

            Field targetField;
            try {
               targetField = targetConfig.getClass().getField(srcField.getName());
            } catch (NoSuchFieldException noSuchField) {
               continue;
            }

            try {
               srcField.setAccessible(true);
               Object coerced = coerceValue(srcField.get(src), targetField.getType());
               if (coerced != null) {
                  targetField.set(targetConfig, coerced);
               }
            } catch (Exception fieldError) {
               LOGGER.warn("Could not sync setting '{}' into {}: {}", srcField.getName(), targetClassName, fieldError.toString());
            }
         }

         // Push the merged config into the module's runtime state through its own
         // contract. This is what makes edits apply instantly instead of waiting
         // for the module's 5-second file reload.
         try {
            targetClass.getMethod("applyRuntimeConfig", targetConfig.getClass()).invoke(null, targetConfig);
         } catch (NoSuchMethodException noApply) {
            LOGGER.debug("{} has no applyRuntimeConfig(); relying on file reload", targetClassName);
         }
      } catch (Exception error) {
         LOGGER.warn("Failed to inject runtime config into {}: {}", targetClassName, error.toString());
      }
   }

   /** Convert a value read from a GUI config field to the target module field's type. */
   private static Object coerceValue(Object value, Class<?> targetType) {
      if (value == null) {
         return null;
      }
      if (targetType.isInstance(value)) {
         return value;
      }
      if (value instanceof Number number) {
         if (targetType == int.class || targetType == Integer.class) {
            return (int)Math.round(number.doubleValue());
         }
         if (targetType == long.class || targetType == Long.class) {
            return Math.round(number.doubleValue());
         }
         if (targetType == float.class || targetType == Float.class) {
            return number.floatValue();
         }
         if (targetType == double.class || targetType == Double.class) {
            return number.doubleValue();
         }
         if (targetType == short.class || targetType == Short.class) {
            return (short)number.intValue();
         }
         if (targetType == byte.class || targetType == Byte.class) {
            return (byte)number.intValue();
         }
      }
      if ((targetType == boolean.class || targetType == Boolean.class) && value instanceof Boolean) {
         return value;
      }
      return null;
   }

   private static void normalizeConfigObject(Object config) {
      if (config instanceof ModGuiClient.AutoLeftConfig autoLeftConfig) {
         autoLeftConfig.normalize();
      } else if (config instanceof ModGuiClient.AutoRightConfig autoRightConfig) {
         autoRightConfig.normalize();
      } else if (config instanceof ModGuiClient.NinjaBridgeConfig ninjaBridgeConfig) {
         ninjaBridgeConfig.normalize();
      } else if (config instanceof ModGuiClient.AimAssistConfig aimAssistConfig) {
         aimAssistConfig.normalize();
      } else if (config instanceof ModGuiClient.TrackerConfig trackerConfig) {
         trackerConfig.normalize();
      }
   }

   private static String onOff(boolean value) {
      return value ? "ON" : "OFF";
   }

   private static int normalizeToggleKeyCode(int keyCode) {
      if (keyCode >= 1000) {
         return keyCode;
      } else {
         return keyCode <= 0 ? -1 : keyCode;
      }
   }

   private static boolean isMouseToggleKeyCode(int keyCode) {
      return keyCode >= 1000;
   }

   private static int encodeMouseToggleButton(int button) {
      return 1000 + Math.max(0, button);
   }

   private static int decodeMouseToggleButton(int keyCode) {
      return Math.max(0, keyCode - 1000);
   }

   private static Text toggleLabel(String label, boolean enabled) {
      return Text.literal(label + ": ")
         .formatted(Formatting.WHITE)
         .append(Text.literal(onOff(enabled)).formatted(enabled ? Formatting.GREEN : Formatting.RED, Formatting.BOLD));
   }

   private static String trimToWidth(String text, int maxWidth) {
      if (text != null && !text.isBlank()) {
         TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
         if (renderer.getWidth(text) <= maxWidth) {
            return text;
         } else {
            String ellipsis = "...";
            StringBuilder builder = new StringBuilder(text.length());

            for (int i = 0; i < text.length(); i++) {
               builder.append(text.charAt(i));
               if (renderer.getWidth(builder.toString() + ellipsis) > maxWidth) {
                  if (!builder.isEmpty()) {
                     builder.setLength(Math.max(0, builder.length() - 1));
                  }

                  return builder + ellipsis;
               }
            }

            return text;
         }
      } else {
         return "";
      }
   }

   private static List<String> wrapToWidth(String text, int maxWidth, int maxLines) {
      List<String> lines = new ArrayList<>();
      if (text != null && !text.isBlank()) {
         TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
         String[] words = text.trim().split("\\s+");
         StringBuilder current = new StringBuilder();

         for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (renderer.getWidth(candidate) <= maxWidth) {
               current.setLength(0);
               current.append(candidate);
            } else {
               if (!current.isEmpty()) {
                  lines.add(current.toString());
                  if (maxLines > 0 && lines.size() >= maxLines) {
                     int lastIndex = lines.size() - 1;
                     lines.set(lastIndex, trimToWidth(lines.get(lastIndex), maxWidth));
                     return lines;
                  }

                  current.setLength(0);
               }

               if (renderer.getWidth(word) <= maxWidth) {
                  current.append(word);
               } else {
                  StringBuilder partial = new StringBuilder();

                  for (int i = 0; i < word.length(); i++) {
                     partial.append(word.charAt(i));
                     if (renderer.getWidth(partial.toString()) > maxWidth) {
                        if (partial.length() > 1) {
                           partial.setLength(partial.length() - 1);
                        }

                        lines.add(partial.toString());
                        if (maxLines > 0 && lines.size() >= maxLines) {
                           int lastIndex = lines.size() - 1;
                           lines.set(lastIndex, trimToWidth(lines.get(lastIndex), maxWidth));
                           return lines;
                        }

                        partial.setLength(0);
                        partial.append(word.charAt(i));
                     }
                  }

                  current.append((CharSequence)partial);
               }
            }
         }

         if (!current.isEmpty() && (maxLines <= 0 || lines.size() < maxLines)) {
            lines.add(current.toString());
         }

         return (List<String>)(maxLines > 0 && lines.size() > maxLines ? new ArrayList<>(lines.subList(0, maxLines)) : lines);
      } else {
         return lines;
      }
   }

   private static Text keyNameText(int keyCode) {
      if (isMouseToggleKeyCode(keyCode)) {
         return InputUtil.Type.MOUSE.createFromCode(decodeMouseToggleButton(keyCode)).getLocalizedText();
      } else {
         return (Text)(keyCode > 0 && keyCode != -1 ? InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText() : Text.literal("Not Set"));
      }
   }

   private static ButtonWidget toggleButton(int x, int y, int width, Supplier<Text> labelSupplier, Runnable onToggle) {
      return ButtonWidget.builder(labelSupplier.get(), button -> {
         onToggle.run();
         button.setMessage(labelSupplier.get());
      }).dimensions(x, y, width, 20).build();
   }

   private static void bindNumberField(TextFieldWidget field, Runnable onChange) {
      field.setChangedListener(value -> onChange.run());
   }

   private static TextFieldWidget numberField(int x, int y, String value) {
      TextFieldWidget field = new TextFieldWidget(MinecraftClient.getInstance().textRenderer, x, y, 116, 18, Text.literal(""));
      field.setMaxLength(32);
      field.setText(value);
      field.setDrawsBackground(true);
      field.setEditableColor(-722949);
      field.setUneditableColor(-7298379);
      return field;
   }

   private static int parseInt(String value, int fallback, int min, int max) {
      try {
         return MathHelper.clamp(Integer.parseInt(value.trim()), min, max);
      } catch (Exception var5) {
         return fallback;
      }
   }

   private static double parseDouble(String value, double fallback, double min, double max) {
      try {
         return MathHelper.clamp(Double.parseDouble(value.trim()), min, max);
      } catch (Exception var8) {
         return fallback;
      }
   }

   private static String formatDouble(double value) {
      return Math.abs(value - Math.rint(value)) < 1.0E-4 ? String.valueOf((int)Math.rint(value)) : String.format(Locale.ROOT, "%.2f", value);
   }

   private static final class AimAssistConfig {
      int configVersion = 6;
      boolean enabled = false;
      int toggleKeyCode = -1;
      int tapThresholdMs = 180;
      float speed = 0.22F;
      float smoothness = 0.4F;
      float fov = 65.0F;

      private AimAssistConfig() {
      }

      void normalize() {
         if (this.configVersion < 5) {
            this.enabled = false;
            this.speed = 0.22F;
            this.smoothness = 0.4F;
            this.fov = 65.0F;
            this.configVersion = 5;
         }

         this.toggleKeyCode = ModGuiClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private static final class AimAssistScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.AimAssistConfig config = ModGuiClient.loadConfig(
         ModGuiClient.AIM_ASSIST_CONFIG, ModGuiClient.AimAssistConfig.class, ModGuiClient.AimAssistConfig::new
      );
      private final ModGuiClient.KeybindEditor keybindEditor = new ModGuiClient.KeybindEditor(() -> this.config.toggleKeyCode, value -> {
         this.config.toggleKeyCode = value;
         ModGuiClient.saveConfig(ModGuiClient.AIM_ASSIST_CONFIG, this.config);
      });
      private TextFieldWidget speedField;
      private TextFieldWidget smoothnessField;
      private TextFieldWidget fovField;

      private AimAssistScreen(Screen parent) {
         super(parent, "AimAssist");
      }

      @Override
      protected String selectedNavId() {
         return "aimassist";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("aimassist")) {
            int left = this.formLabelX();
            int fieldX = this.formFieldX();
            int y = 118;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Enabled", this.config.enabled), () -> {
               this.config.enabled = !this.config.enabled;
               ModGuiClient.saveConfig(ModGuiClient.AIM_ASSIST_CONFIG, this.config);
            }));
            y += 32;
            this.addDrawableChild(this.keybindEditor.createButton(left, y, 220));
            y += 40;
            this.speedField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y, ModGuiClient.formatDouble((double)this.config.speed)));
            this.smoothnessField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 26, ModGuiClient.formatDouble((double)this.config.smoothness)));
            this.fovField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 52, ModGuiClient.formatDouble((double)this.config.fov)));
            ModGuiClient.bindNumberField(this.speedField, this::save);
            ModGuiClient.bindNumberField(this.smoothnessField, this::save);
            ModGuiClient.bindNumberField(this.fovField, this::save);
            this.addSaveAndResetButtons(this::save, this::resetToDefaults);
         }
      }

      private void save() {
         this.config.speed = (float)ModGuiClient.parseDouble(this.speedField.getText(), (double)this.config.speed, 0.01, 1.0);
         this.config.smoothness = (float)ModGuiClient.parseDouble(this.smoothnessField.getText(), (double)this.config.smoothness, 0.0, 1.0);
         this.config.fov = (float)ModGuiClient.parseDouble(this.fovField.getText(), (double)this.config.fov, 10.0, 180.0);
         ModGuiClient.saveConfig(ModGuiClient.AIM_ASSIST_CONFIG, this.config);
      }

      private void resetToDefaults() {
         ModGuiClient.saveConfig(ModGuiClient.AIM_ASSIST_CONFIG, new ModGuiClient.AimAssistConfig());
         this.reopen(new ModGuiClient.AimAssistScreen(this.parent));
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 300);
         this.renderNavigationRail(context, mouseX, mouseY);
         int left = this.formLabelX();
         this.drawPageIntro(context, "Tracking Profile", "Simplified aim controls: Adjust speed, smoothness, and FOV.");
         super.render(context, mouseX, mouseY, deltaTicks);
         this.drawFieldLabel(context, mouseX, mouseY, "Speed", left, 189, new String[]{"Base horizontal turn strength while following a target."});
         this.drawFieldLabel(context, mouseX, mouseY, "Smoothness", left, 215, new String[]{"Gaussian smoothing factor for mouse movement."});
         this.drawFieldLabel(context, mouseX, mouseY, "FOV", left, 241, new String[]{"The field of view in which targets are acquired."});
         this.drawFooterNote(context, "Simplified AimAssist for better stealth", 270);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         return this.keybindEditor.handleKeyPressed(keyCode) ? true : super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean handled = super.mouseClicked(mouseX, mouseY, button);
         return handled ? true : this.keybindEditor.handleMouseClicked(button);
      }
   }

   private static final class AutoLeftConfig {
      int configVersion = 5;
      boolean enabled = true;
      boolean weaponCheck = false;
      int toggleKeyCode = -1;
      int tapThresholdMs = 180;
      float minCps = 8.0F;
      float maxCps = 16.0F;

      private AutoLeftConfig() {
      }

      void normalize() {
         if (this.configVersion < 5) {
            this.minCps = 8.0F;
            this.maxCps = 16.0F;
            this.configVersion = 5;
         }

         this.toggleKeyCode = ModGuiClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private static final class AutoLeftScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.AutoLeftConfig config = ModGuiClient.loadConfig(
         ModGuiClient.AUTO_LEFT_CONFIG, ModGuiClient.AutoLeftConfig.class, ModGuiClient.AutoLeftConfig::new
      );
      private final ModGuiClient.KeybindEditor keybindEditor = new ModGuiClient.KeybindEditor(() -> this.config.toggleKeyCode, value -> {
         this.config.toggleKeyCode = value;
         ModGuiClient.saveConfig(ModGuiClient.AUTO_LEFT_CONFIG, this.config);
      });
      private TextFieldWidget minCpsField;
      private TextFieldWidget maxCpsField;

      private AutoLeftScreen(Screen parent) {
         super(parent, "Auto Left");
      }

      @Override
      protected String selectedNavId() {
         return "autoleft";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("autoleft")) {
            int left = this.formLabelX();
            int fieldX = this.formFieldX();
            int y = 118;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Enabled", this.config.enabled), () -> {
               this.config.enabled = !this.config.enabled;
               ModGuiClient.saveConfig(ModGuiClient.AUTO_LEFT_CONFIG, this.config);
            }));
            y += 26;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Weapon Mode", this.config.weaponCheck), () -> {
               this.config.weaponCheck = !this.config.weaponCheck;
               ModGuiClient.saveConfig(ModGuiClient.AUTO_LEFT_CONFIG, this.config);
            }));
            y += 32;
            this.addDrawableChild(this.keybindEditor.createButton(left, y, 220));
            y += 34;
            this.minCpsField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y, ModGuiClient.formatDouble((double)this.config.minCps)));
            this.maxCpsField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 26, ModGuiClient.formatDouble((double)this.config.maxCps)));
            ModGuiClient.bindNumberField(this.minCpsField, this::save);
            ModGuiClient.bindNumberField(this.maxCpsField, this::save);
            this.addSaveAndResetButtons(this::save, this::resetToDefaults);
         }
      }

      private void save() {
         this.config.minCps = (float)ModGuiClient.parseDouble(this.minCpsField.getText(), (double)this.config.minCps, 1.0, 1000.0);
         this.config.maxCps = (float)ModGuiClient.parseDouble(this.maxCpsField.getText(), (double)this.config.maxCps, 0.0, 250.0);
         ModGuiClient.saveConfig(ModGuiClient.AUTO_LEFT_CONFIG, this.config);
      }

      private void resetToDefaults() {
         ModGuiClient.saveConfig(ModGuiClient.AUTO_LEFT_CONFIG, new ModGuiClient.AutoLeftConfig());
         this.reopen(new ModGuiClient.AutoLeftScreen(this.parent));
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 292);
         this.renderNavigationRail(context, mouseX, mouseY);
         this.drawPageIntro(context, "Click Profile", "Manage left-click speed, weapon mode, and the module toggle key.");
         int left = this.formLabelX();
         super.render(context, mouseX, mouseY, deltaTicks);
         this.drawRowHelp(context, mouseX, mouseY, this.contentRight() - 18, 121, new String[]{"Turns Auto Left on or off without touching other mods."});
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            147,
            new String[]{"If enabled, the mod clicks only while holding a weapon-check compatible item."}
         );
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            179,
            new String[]{"Click this button, then press any key to toggle the module. Backspace clears it."}
         );
         this.drawFieldLabel(context, mouseX, mouseY, "Min CPS", left, 214, new String[]{"Minimum clicks per second."});
         this.drawFieldLabel(context, mouseX, mouseY, "Max CPS", left, 240, new String[]{"Maximum clicks per second."});
         this.drawFooterNote(context, "Saved values stay the same after restart", 266);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         return this.keybindEditor.handleKeyPressed(keyCode) ? true : super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean handled = super.mouseClicked(mouseX, mouseY, button);
         return handled ? true : this.keybindEditor.handleMouseClicked(button);
      }
   }

   private static final class AutoRightConfig {
      int configVersion = 5;
      boolean enabled = true;
      boolean blockMode = true;
      int toggleKeyCode = -1;
      int tapThresholdMs = 180;
      float minCps = 14.0F;
      float maxCps = 28.0F;

      private AutoRightConfig() {
      }

      void normalize() {
         if (this.configVersion < 5) {
            this.minCps = 14.0F;
            this.maxCps = 28.0F;
            this.configVersion = 5;
         }

         this.toggleKeyCode = ModGuiClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private static final class AutoRightScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.AutoRightConfig config = ModGuiClient.loadConfig(
         ModGuiClient.AUTO_RIGHT_CONFIG, ModGuiClient.AutoRightConfig.class, ModGuiClient.AutoRightConfig::new
      );
      private final ModGuiClient.KeybindEditor keybindEditor = new ModGuiClient.KeybindEditor(() -> this.config.toggleKeyCode, value -> {
         this.config.toggleKeyCode = value;
         ModGuiClient.saveConfig(ModGuiClient.AUTO_RIGHT_CONFIG, this.config);
      });
      private TextFieldWidget minCpsField;
      private TextFieldWidget maxCpsField;

      private AutoRightScreen(Screen parent) {
         super(parent, "Auto Right");
      }

      @Override
      protected String selectedNavId() {
         return "autoright";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("autoright")) {
            int left = this.formLabelX();
            int fieldX = this.formFieldX();
            int y = 118;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Enabled", this.config.enabled), () -> {
               this.config.enabled = !this.config.enabled;
               ModGuiClient.saveConfig(ModGuiClient.AUTO_RIGHT_CONFIG, this.config);
            }));
            y += 26;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Block Mode", this.config.blockMode), () -> {
               this.config.blockMode = !this.config.blockMode;
               ModGuiClient.saveConfig(ModGuiClient.AUTO_RIGHT_CONFIG, this.config);
            }));
            y += 32;
            this.addDrawableChild(this.keybindEditor.createButton(left, y, 220));
            y += 34;
            this.minCpsField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y, ModGuiClient.formatDouble((double)this.config.minCps)));
            this.maxCpsField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 26, ModGuiClient.formatDouble((double)this.config.maxCps)));
            ModGuiClient.bindNumberField(this.minCpsField, this::save);
            ModGuiClient.bindNumberField(this.maxCpsField, this::save);
            this.addSaveAndResetButtons(this::save, this::resetToDefaults);
         }
      }

      private void save() {
         this.config.minCps = (float)ModGuiClient.parseDouble(this.minCpsField.getText(), (double)this.config.minCps, 1.0, 1000.0);
         this.config.maxCps = (float)ModGuiClient.parseDouble(this.maxCpsField.getText(), (double)this.config.maxCps, 0.0, 250.0);
         ModGuiClient.saveConfig(ModGuiClient.AUTO_RIGHT_CONFIG, this.config);
      }

      private void resetToDefaults() {
         ModGuiClient.saveConfig(ModGuiClient.AUTO_RIGHT_CONFIG, new ModGuiClient.AutoRightConfig());
         this.reopen(new ModGuiClient.AutoRightScreen(this.parent));
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 292);
         this.renderNavigationRail(context, mouseX, mouseY);
         this.drawPageIntro(context, "Place Profile", "Manage right-click speed, block mode, and the module toggle key.");
         int left = this.formLabelX();
         super.render(context, mouseX, mouseY, deltaTicks);
         this.drawRowHelp(context, mouseX, mouseY, this.contentRight() - 18, 121, new String[]{"Turns Auto Right on or off."});
         this.drawRowHelp(
            context, mouseX, mouseY, this.contentRight() - 18, 147, new String[]{"Block mode keeps normal single-place feel and only speeds up during hold."}
         );
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            179,
            new String[]{"Click this button, then press any key to toggle the module. Backspace clears it."}
         );
         this.drawFieldLabel(context, mouseX, mouseY, "Min CPS", left, 214, new String[]{"Minimum clicks per second."});
         this.drawFieldLabel(context, mouseX, mouseY, "Max CPS", left, 240, new String[]{"Maximum clicks per second."});
         this.drawFooterNote(context, "Saved values stay the same after restart", 266);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         return this.keybindEditor.handleKeyPressed(keyCode) ? true : super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean handled = super.mouseClicked(mouseX, mouseY, button);
         return handled ? true : this.keybindEditor.handleMouseClicked(button);
      }
   }

   private abstract static class BaseScreen extends Screen {
      protected static final int BACKGROUND_TOP = -16182500;
      protected static final int BACKGROUND_BOTTOM = -15720915;
      protected static final int BACKGROUND_STRIP = -15191735;
      protected static final int PANEL_SHADOW = -2146429667;
      protected static final int PANEL_OUTER = -434824669;
      protected static final int PANEL_INNER = 0xCC101C28;
      protected static final int PANEL_HEADER = -14468527;
      protected static final int PANEL_ACCENT = -14229;
      protected static final int CARD_BG = -384162768;
      protected static final int CARD_BORDER = 1715164013;
      protected static final int CARD_HOVER = -266063035;
      protected static final int TEXT_MUTED = -4668205;
      protected static final int TEXT_LABEL = -723206;
      protected static final int TEXT_DIM = -7298379;
      protected final Screen parent;
      protected List<Text> hoveredTooltip = new ArrayList<>();

      protected BaseScreen(Screen parent, String title) {
         super(Text.literal(title));
         this.parent = parent;
      }

      protected String selectedNavId() {
         return "dashboard";
      }

      protected void addSaveAndResetButtons(Runnable onSave, Runnable onReset) {
         this.addDrawableChild(ButtonWidget.builder(Text.literal("Save"), button -> {
            onSave.run();
            this.close();
         }).dimensions(this.contentRight() - 169, this.height - 30, 80, 20).build());
         this.addDrawableChild(
            ButtonWidget.builder(Text.literal("Reset"), button -> onReset.run()).dimensions(this.contentRight() - 80, this.height - 30, 80, 20).build()
         );
      }

      protected void reopen(Screen screen) {
         if (this.client != null) {
            this.client.setScreen(screen);
         }
      }

      protected boolean ensureModuleLoaded(String modId) {
         return true;
      }

      @Override
      public void close() {
         if (this.client != null) {
            this.client.setScreen(this.parent);
         }
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         super.render(context, mouseX, mouseY, deltaTicks);
         context.drawTextWithShadow(this.textRenderer, this.title, this.contentLeft(), 12, -1);
         context.drawTextWithShadow(this.textRenderer, Text.literal("YJHack Client"), this.contentLeft(), 24, -7298379);
      }

      protected int panelLeft() {
         return Math.max(12, this.width / 2 - 340);
      }

      protected int panelRight() {
         return Math.min(this.width - 12, this.width / 2 + 340);
      }

      protected int navLeft() {
         return this.panelLeft() + 14;
      }

      protected int navWidth() {
         return 132;
      }

      protected int navTop() {
         return 84;
      }

      protected int contentLeft() {
         return this.navLeft() + this.navWidth() + 22;
      }

      protected int contentRight() {
         return this.panelRight() - 14;
      }

      protected int contentWidth() {
         return this.contentRight() - this.contentLeft();
      }

      protected int formLabelX() {
         return this.contentLeft() + 14;
      }

      protected int formFieldX() {
         return this.contentRight() - 124;
      }

      protected void beginDecorations() {
         this.hoveredTooltip = null;
      }

      protected void drawChromeBackground(DrawContext drawContext) {
         // Partially transparent backdrop (repair 2026-07-23): the world and other
         // players stay visible behind a soft dark tint instead of a solid fill.
         drawContext.fill(0, 0, this.width, this.height, 0x66060B10);
         drawContext.fill(0, 0, this.width, this.height / 2, 0x2C04090E);
         drawContext.fill(0, 0, this.width, 44, 0x8C0A1622);
         drawContext.fill(0, 44, this.width, 46, 0xB0186A79);
         drawContext.fill(0, this.height - 22, this.width, this.height, 0x8C0A1622);

         for (int bandY = 64; bandY < this.height; bandY += 32) {
            drawContext.fill(0, bandY, this.width, bandY + 1, 0x0F3C7C8A);
         }
      }

      protected void drawScreenFrame(DrawContext drawContext, String subtitle, int top, int bottom) {
         int left = this.panelLeft();
         int right = this.panelRight();
         drawContext.fill(left + 4, top + 5, right + 4, bottom + 5, -2146429667);
         drawContext.fill(left, top, right, bottom, -434824669);
         drawContext.fill(left + 2, top + 2, right - 2, bottom - 2, -266261704);
         drawContext.fill(left + 2, top + 2, right - 2, top + 30, -14468527);
         drawContext.fill(left + 2, top + 30, right - 2, top + 32, -14229);
         drawContext.fill(this.contentLeft() - 10, top + 30, this.contentLeft() - 8, bottom - 12, 861766824);
         drawContext.fill(left + 12, top + 40, right - 12, top + 41, 1148161451);
         if (subtitle != null && !subtitle.isBlank()) {
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(subtitle), this.contentLeft(), top + 42, -4668205);
         }
      }

      protected void drawInsetCard(DrawContext drawContext, int x, int y, int width, int height, String title, String subtitle) {
         drawContext.fill(x + 3, y + 4, x + width + 3, y + height + 4, 1241513984);
         drawContext.fill(x, y, x + width, y + height, -384162768);
         drawContext.fill(x, y, x + width, y + 1, 1715164013);
         drawContext.fill(x, y + height - 1, x + width, y + height, 1715164013);
         drawContext.fill(x, y, x + 1, y + height, 1715164013);
         drawContext.fill(x + width - 1, y, x + width, y + height, 1715164013);
         drawContext.fill(x, y, x + width, y + 3, 1627375723);
         if (title != null && !title.isBlank()) {
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(title), x + 10, y + 8, -1);
         }

         if (subtitle != null && !subtitle.isBlank()) {
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(subtitle), x + 10, y + 20, -7298379);
         }
      }

      protected void drawBadge(DrawContext drawContext, int x, int y, String label, int fillColor, int textColor) {
         int width = this.textRenderer.getWidth(label) + 12;
         drawContext.fill(x, y, x + width, y + 12, fillColor);
         drawContext.drawCenteredTextWithShadow(this.textRenderer, Text.literal(label), x + width / 2, y + 2, textColor);
      }

      protected void drawFooterNote(DrawContext drawContext, String text, int y) {
         drawContext.drawTextWithShadow(this.textRenderer, Text.literal(text), this.contentLeft(), y, -4668205);
      }

      protected void drawPageIntro(DrawContext drawContext, String heading, String description) {
         int x = this.contentLeft();
         int y = 52;
         drawContext.drawTextWithShadow(this.textRenderer, Text.literal(heading), x, y, -1);
         int dividerY = y + 30;
         if (description != null && !description.isBlank()) {
            int consumed = this.drawWrappedText(drawContext, description, x, y + 14, this.contentWidth() - 8, -7298379, 2);
            dividerY = y + 14 + consumed + 6;
         }

         drawContext.fill(x, dividerY, this.contentRight() - 8, dividerY + 1, 1148161451);
      }

      protected void drawFieldLabel(DrawContext drawContext, int mouseX, int mouseY, String label, int x, int y, String... helpLines) {
         Text text = Text.literal(label);
         drawContext.drawTextWithShadow(this.textRenderer, text, x, y, -723206);
         if (helpLines != null && helpLines.length > 0) {
            this.drawHelpMarker(drawContext, mouseX, mouseY, x + this.textRenderer.getWidth(text) + 8, y - 1, helpLines);
         }
      }

      protected void drawRowHelp(DrawContext drawContext, int mouseX, int mouseY, int x, int y, String... helpLines) {
         if (helpLines != null && helpLines.length > 0) {
            this.drawHelpMarker(drawContext, mouseX, mouseY, x, y, helpLines);
         }
      }

      protected void renderHelpTooltip(DrawContext drawContext, int mouseX, int mouseY) {
         if (this.hoveredTooltip != null && !this.hoveredTooltip.isEmpty()) {
            drawContext.drawTooltip(this.textRenderer, this.hoveredTooltip, mouseX, mouseY);
         }
      }

      protected int drawWrappedText(DrawContext drawContext, String text, int x, int y, int maxWidth, int color, int maxLines) {
         List<String> lines = ModGuiClient.wrapToWidth(text, maxWidth, maxLines);
         int lineY = y;

         for (String line : lines) {
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(line), x, lineY, color);
            lineY += 10;
         }

         return Math.max(0, lineY - y);
      }

      private void drawHelpMarker(DrawContext drawContext, int mouseX, int mouseY, int x, int y, String... helpLines) {
         int size = 12;
         boolean hovered = mouseX >= x && mouseX <= x + size && mouseY >= y && mouseY <= y + size;
         int fill = hovered ? -11413 : -863016912;
         int textColor = hovered ? -15394269 : -1;
         drawContext.fill(x, y, x + size, y + size, fill);
         drawContext.drawCenteredTextWithShadow(this.textRenderer, Text.literal("!"), x + size / 2, y + 2, textColor);
         if (hovered) {
            this.hoveredTooltip = new ArrayList<>();

            for (String line : helpLines) {
               this.hoveredTooltip.add(Text.literal(line));
            }
         }
      }

      protected void renderNavigationRail(DrawContext drawContext, int mouseX, int mouseY) {
         int cardHeight = 22 + ModGuiClient.NAV_ITEMS.size() * 24;
         this.drawInsetCard(drawContext, this.navLeft(), 52, this.navWidth(), cardHeight, "Modules", "Quick access");

         for (int index = 0; index < ModGuiClient.NAV_ITEMS.size(); index++) {
            ModGuiClient.NavItem item = ModGuiClient.NAV_ITEMS.get(index);
            int x = this.navLeft() + 6;
            int y = this.navTop() + index * 24;
            int width = this.navWidth() - 12;
            int height = 20;
            boolean hovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
            boolean active = item.id().equals(this.selectedNavId());
            boolean available = item.available();
            int fill = active ? -870102169 : (hovered ? -1440402877 : -2011157961);
            int border = active ? -14229 : (available ? 1431005832 : 1430995265);
            int textColor = available ? -1 : -2638403;
            drawContext.fill(x, y, x + width, y + height, fill);
            drawContext.fill(x, y, x + width, y + 1, border);
            drawContext.fill(x, y + height - 1, x + width, y + height, border);
            drawContext.fill(x, y, x + 1, y + height, border);
            drawContext.fill(x + width - 1, y, x + width, y + height, border);
            drawContext.drawTextWithShadow(this.textRenderer, Text.literal(item.label()), x + 8, y + 6, textColor);
            if (!available) {
               this.drawBadge(drawContext, x + width - 40, y + 4, "LOCK", -1436933341, -1);
            } else if (active) {
               this.drawBadge(drawContext, x + width - 34, y + 4, "ON", -1441317336, -1);
            }

            if (hovered) {
               this.hoveredTooltip = List.of(
                  Text.literal(item.label()).formatted(Formatting.GOLD),
                  Text.literal(item.description()),
                  Text.literal(available ? "Click to open this page" : "This page is locked until the mod is loaded")
               );
            }
         }
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (button == 0) {
            for (int index = 0; index < ModGuiClient.NAV_ITEMS.size(); index++) {
               ModGuiClient.NavItem item = ModGuiClient.NAV_ITEMS.get(index);
               int x = this.navLeft() + 6;
               int y = this.navTop() + index * 24;
               int width = this.navWidth() - 12;
               int height = 20;
               boolean inside = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
               if (inside) {
                  if (!item.id().equals(this.selectedNavId()) && item.available() && this.client != null) {
                     this.client.setScreen(this.screenFor(item.id()));
                  }

                  return true;
               }
            }
         }

         return super.mouseClicked(mouseX, mouseY, button);
      }

      private Screen screenFor(String id) {
         ModGuiClient.NavItem navItem = ModGuiClient.NAV_ITEMS.stream().filter(item -> item.id().equals(id)).findFirst().orElse(null);
         if (navItem != null && !navItem.available()) {
            return new ModGuiClient.MainScreen();
         } else {
            Screen dashboard = (Screen)(this.parent instanceof ModGuiClient.MainScreen ? this.parent : new ModGuiClient.MainScreen());

            return (Screen)(switch (id) {
               case "dashboard" -> new ModGuiClient.MainScreen();
               case "autoleft" -> new ModGuiClient.AutoLeftScreen(dashboard);
               case "autoright" -> new ModGuiClient.AutoRightScreen(dashboard);
               case "ninjabridge" -> new ModGuiClient.NinjaBridgeScreen(dashboard);
               case "aimassist" -> new ModGuiClient.AimAssistScreen(dashboard);
               case "tracker" -> new ModGuiClient.TrackerScreen(dashboard);
               default -> this;
            });
         }
      }
   }

   private record HiddenHudLayout(int x, int y, int width) {
   }

   private static final class KeybindEditor {
      private final Supplier<Integer> getter;
      private final IntConsumer setter;
      private ButtonWidget button;
      private boolean listening;

      private KeybindEditor(Supplier<Integer> getter, IntConsumer setter) {
         this.getter = getter;
         this.setter = setter;
      }

      private ButtonWidget createButton(int x, int y, int width) {
         this.button = ButtonWidget.builder(this.currentMessage(), ignored -> {
            this.listening = true;
            this.refresh();
         }).dimensions(x, y, width, 20).build();
         return this.button;
      }

      private boolean handleKeyPressed(int keyCode) {
         if (!this.listening) {
            return false;
         } else if (keyCode == 256) {
            this.listening = false;
            this.refresh();
            return true;
         } else {
            if (keyCode != 259 && keyCode != 261) {
               this.setter.accept(keyCode);
            } else {
               this.setter.accept(-1);
            }

            this.listening = false;
            this.refresh();
            return true;
         }
      }

      private boolean handleMouseClicked(int button) {
         if (!this.listening) {
            return false;
         } else {
            this.setter.accept(ModGuiClient.encodeMouseToggleButton(button));
            this.listening = false;
            this.refresh();
            return true;
         }
      }

      private Text currentMessage() {
         return this.listening ? Text.literal("Press Key...") : Text.literal("Keybind: ").append(ModGuiClient.keyNameText(this.getter.get()));
      }

      private void refresh() {
         if (this.button != null) {
            this.button.setMessage(this.currentMessage());
         }
      }
   }

   private static final class MainScreen extends ModGuiClient.BaseScreen {
      private final List<ModGuiClient.MainScreen.ModuleCard> moduleCards = new ArrayList<>();

      private MainScreen() {
         super(null, "YJHack Modules");
      }

      @Override
      protected String selectedNavId() {
         return "dashboard";
      }

      @Override
      protected void init() {
         this.buildModuleCards();
      }

      private void buildModuleCards() {
         this.moduleCards.clear();
         int cardWidth = Math.min(190, Math.max(158, (this.contentWidth() - 12) / 2));
         int cardHeight = 72;
         int gap = 10;
         int cardsWidth = cardWidth * 2 + gap;
         int originX = this.contentLeft() + Math.max(0, (this.contentWidth() - cardsWidth) / 2);
         int originY = 104;
         this.addModuleCard(
            originX,
            originY,
            cardWidth,
            cardHeight,
            "Auto Left",
            "autoleft",
            "Left CPS, weapon mode, saved defaults.",
            () -> new ModGuiClient.AutoLeftScreen(this)
         );
         this.addModuleCard(
            originX + cardWidth + gap,
            originY,
            cardWidth,
            cardHeight,
            "Auto Right",
            "autoright",
            "Right CPS, block mode, hold behavior.",
            () -> new ModGuiClient.AutoRightScreen(this)
         );
         this.addModuleCard(
            originX,
            originY + cardHeight + gap,
            cardWidth,
            cardHeight,
            "Ninja Bridge",
            "ninjabridge",
            "Bridge helper and toggle key.",
            () -> new ModGuiClient.NinjaBridgeScreen(this)
         );
         this.addModuleCard(
            originX + cardWidth + gap,
            originY + cardHeight + gap,
            cardWidth,
            cardHeight,
            "AimAssist",
            "aimassist",
            "Target follow and turn strength.",
            () -> new ModGuiClient.AimAssistScreen(this)
         );
         this.addModuleCard(
            originX + (cardWidth + gap) / 2,
            originY + (cardHeight + gap) * 2,
            cardWidth,
            cardHeight,
            "Tracker",
            "tracker",
            "Range control and HUD editor.",
            () -> new ModGuiClient.TrackerScreen(this)
         );
      }

      private void addModuleCard(int x, int y, int width, int height, String label, String modId, String description, Supplier<Screen> screenFactory) {
         this.moduleCards.add(new ModGuiClient.MainScreen.ModuleCard(x, y, width, height, label, modId, description, screenFactory));
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
         } else {
            if (button == 0 && this.client != null) {
               for (ModGuiClient.MainScreen.ModuleCard card : this.moduleCards) {
                  if (card.contains(mouseX, mouseY) && card.loaded()) {
                     this.client.setScreen(card.screenFactory().get());
                     return true;
                  }
               }
            }

            return super.mouseClicked(mouseX, mouseY, button);
         }
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         int panelBottom = Math.max(334, this.height - 40);
         this.drawScreenFrame(context, null, 28, panelBottom);
         this.renderNavigationRail(context, mouseX, mouseY);
         this.drawPageIntro(context, "Module Dashboard", "Click any available card to open its settings.");
         super.render(context, mouseX, mouseY, deltaTicks);

         for (ModGuiClient.MainScreen.ModuleCard card : this.moduleCards) {
            this.renderModuleCard(context, mouseX, mouseY, card);
         }

         this.drawFooterNote(context, "Locked modules stay unavailable until their jar is installed", this.height - 62);
         this.drawFooterNote(context, "Each page saves directly to that mod's config file", this.height - 50);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      private void renderModuleCard(DrawContext drawContext, int mouseX, int mouseY, ModGuiClient.MainScreen.ModuleCard card) {
         boolean hovered = card.contains(mouseX, mouseY);
         int background = hovered ? -266063035 : -384162768;
         int border = card.loaded() ? 1717471656 : 1716795962;
         int accent = card.loaded() ? -11546229 : -3773846;
         int textColor = card.loaded() ? -1 : -1716273;
         int x = card.x();
         int y = card.y();
         int width = card.width();
         int height = card.height();
         drawContext.fill(x + 3, y + 4, x + width + 3, y + height + 4, 1241513984);
         drawContext.fill(x, y, x + width, y + height, background);
         drawContext.fill(x, y, x + width, y + 2, accent);
         drawContext.fill(x, y, x + width, y + 1, border);
         drawContext.fill(x, y + height - 1, x + width, y + height, border);
         drawContext.fill(x, y, x + 1, y + height, border);
         drawContext.fill(x + width - 1, y, x + width, y + height, border);
         drawContext.drawTextWithShadow(this.textRenderer, Text.literal(card.label()), x + 10, y + 9, textColor);
         this.drawBadge(drawContext, x + width - 52, y + 8, "READY", -1441317336, -1);
         this.drawWrappedText(drawContext, card.description(), x + 10, y + 26, width - 20, -4668205, 2);
         drawContext.drawTextWithShadow(this.textRenderer, Text.literal("Open module settings"), x + 10, y + height - 15, hovered ? -14229 : -7298379);
         if (hovered) {
            this.hoveredTooltip = List.of(
               Text.literal(card.label()).formatted(Formatting.GOLD), Text.literal(card.description()), Text.literal("Click to open settings")
            );
         }
      }

      private record ModuleCard(int x, int y, int width, int height, String label, String modId, String description, Supplier<Screen> screenFactory) {
         boolean loaded() {
            return true;
         }

         boolean contains(double mouseX, double mouseY) {
            return mouseX >= this.x && mouseX <= this.x + this.width && mouseY >= this.y && mouseY <= this.y + this.height;
         }
      }
   }

   private record NavItem(String id, String label, String modId, String description) {
      boolean available() {
         return true;
      }
   }

   private static final class NinjaBridgeConfig {
      int configVersion = 7;
      boolean enabled = true;
      int toggleKeyCode = -1;
      int tapThresholdMs = 180;

      private NinjaBridgeConfig() {
      }

      void normalize() {
         if (this.configVersion < 5) {
            this.enabled = true;
            this.tapThresholdMs = 180;
            this.configVersion = 5;
         }

         this.toggleKeyCode = ModGuiClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private static final class NinjaBridgeScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.NinjaBridgeConfig config = ModGuiClient.loadConfig(
         ModGuiClient.NINJA_BRIDGE_CONFIG, ModGuiClient.NinjaBridgeConfig.class, ModGuiClient.NinjaBridgeConfig::new
      );
      private final ModGuiClient.KeybindEditor keybindEditor = new ModGuiClient.KeybindEditor(() -> this.config.toggleKeyCode, value -> {
         this.config.toggleKeyCode = value;
         ModGuiClient.saveConfig(ModGuiClient.NINJA_BRIDGE_CONFIG, this.config);
      });
      private TextFieldWidget tapThresholdField;

      private NinjaBridgeScreen(Screen parent) {
         super(parent, "Ninja Bridge");
      }

      @Override
      protected String selectedNavId() {
         return "ninjabridge";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("ninjabridge")) {
            int left = this.formLabelX();
            int fieldX = this.formFieldX();
            int y = 118;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Enabled", this.config.enabled), () -> {
               this.config.enabled = !this.config.enabled;
               ModGuiClient.saveConfig(ModGuiClient.NINJA_BRIDGE_CONFIG, this.config);
            }));
            y += 32;
            this.addDrawableChild(this.keybindEditor.createButton(left, y, 220));
            y += 40;
            this.tapThresholdField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y, String.valueOf(this.config.tapThresholdMs)));
            ModGuiClient.bindNumberField(this.tapThresholdField, this::save);
            this.addSaveAndResetButtons(this::save, this::resetToDefaults);
         }
      }

      private void save() {
         this.config.tapThresholdMs = ModGuiClient.parseInt(this.tapThresholdField.getText(), this.config.tapThresholdMs, 50, 1000);
         ModGuiClient.saveConfig(ModGuiClient.NINJA_BRIDGE_CONFIG, this.config);
      }

      private void resetToDefaults() {
         ModGuiClient.saveConfig(ModGuiClient.NINJA_BRIDGE_CONFIG, new ModGuiClient.NinjaBridgeConfig());
         this.reopen(new ModGuiClient.NinjaBridgeScreen(this.parent));
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 220);
         this.renderNavigationRail(context, mouseX, mouseY);
         this.drawPageIntro(context, "Bridge Profile", "Sneak instantly at block edges with hybrid key logic.");
         super.render(context, mouseX, mouseY, deltaTicks);
         int left = this.formLabelX();
         this.drawFieldLabel(context, mouseX, mouseY, "Tap Threshold", left, 192, new String[]{"Max hold duration (ms) to be considered a toggle tap."});
         this.drawFooterNote(context, "Tap Sneak to toggle, Hold for Vanilla", 220);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         return this.keybindEditor.handleKeyPressed(keyCode) ? true : super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean handled = super.mouseClicked(mouseX, mouseY, button);
         return handled ? true : this.keybindEditor.handleMouseClicked(button);
      }
   }

   private static final class TrackerConfig {
      int configVersion = 6;
      boolean enabled = true;
      int toggleKeyCode = -1;
      int tapThresholdMs = 180;
      boolean ignoreOwnTeam = true;
      double range = 64.0;
      int hudOffsetX = 0;
      int hudY = 8;

      private TrackerConfig() {
      }

      void normalize() {
         if (this.configVersion < 5) {
            this.enabled = true;
            this.toggleKeyCode = -1;
            this.ignoreOwnTeam = true;
            this.range = 64.0;
            this.configVersion = 5;
         }

         this.toggleKeyCode = ModGuiClient.normalizeToggleKeyCode(this.toggleKeyCode);
      }
   }

   private static final class TrackerHudEditorScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.TrackerConfig config;
      private boolean dragging = false;
      private int dragOffsetX = 0;
      private int dragOffsetY = 0;

      private TrackerHudEditorScreen(Screen parent, ModGuiClient.TrackerConfig config) {
         super(parent, "Tracker HUD Editor");
         this.config = config;
      }

      @Override
      protected String selectedNavId() {
         return "tracker";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("tracker")) {
            this.addSaveAndResetButtons(this::save, this::resetPosition);
         }
      }

      private void save() {
         ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, this.config);
      }

      private void resetPosition() {
         ModGuiClient.TrackerConfig defaults = new ModGuiClient.TrackerConfig();
         this.config.hudOffsetX = defaults.hudOffsetX;
         this.config.hudY = defaults.hudY;
         this.save();
         this.reopen(new ModGuiClient.TrackerHudEditorScreen(this.parent, this.config));
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
         } else {
            ModGuiClient.HiddenHudLayout layout = this.layoutPreview();
            if (mouseX >= layout.x() - 5 && mouseX <= layout.x() + layout.width() + 5 && mouseY >= layout.y() - 3 && mouseY <= layout.y() + 11) {
               this.dragging = true;
               this.dragOffsetX = (int)mouseX - layout.x();
               this.dragOffsetY = (int)mouseY - layout.y();
               this.updateDraggedPosition(mouseX, mouseY);
               return true;
            } else {
               return super.mouseClicked(mouseX, mouseY, button);
            }
         }
      }

      @Override
      public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
         if (this.dragging && button == 0) {
            this.updateDraggedPosition(mouseX, mouseY);
            return true;
         } else {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
         }
      }

      @Override
      public boolean mouseReleased(double mouseX, double mouseY, int button) {
         if (button == 0 && this.dragging) {
            this.dragging = false;
            this.save();
            return true;
         } else {
            return super.mouseReleased(mouseX, mouseY, button);
         }
      }

      private void updateDraggedPosition(double mouseX, double mouseY) {
         int desiredX = (int)mouseX - this.dragOffsetX;
         int desiredY = (int)mouseY - this.dragOffsetY;
         this.config.hudOffsetX = MathHelper.clamp(desiredX, 4, 10000);
         this.config.hudY = MathHelper.clamp(desiredY, 4, 1000);
      }

      private ModGuiClient.HiddenHudLayout layoutPreview() {
         Text previewText = Text.literal("Alert Enemy 6.4m RIGHT");
         int textWidth = MinecraftClient.getInstance().textRenderer.getWidth(previewText);
         int x = Math.max(4, this.config.hudOffsetX);
         int y = Math.max(4, this.config.hudY);
         return new ModGuiClient.HiddenHudLayout(x, y, textWidth);
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 214);
         this.renderNavigationRail(context, mouseX, mouseY);
         this.drawPageIntro(context, "HUD Editor", "Drag the preview block below, then save the new position.");
         super.render(context, mouseX, mouseY, deltaTicks);
         this.drawFooterNote(context, "Drag the HUD with the mouse, then Save", 116);
         ModGuiClient.HiddenHudLayout layout = this.layoutPreview();
         Text previewText = Text.literal("Alert Enemy 6.4m RIGHT");
         context.fill(layout.x() - 4, layout.y() - 2, layout.x() + layout.width() + 4, layout.y() + 10, -1608507360);
         context.fill(layout.x() - 5, layout.y() - 3, layout.x() + layout.width() + 5, layout.y() - 2, -11413);
         context.fill(layout.x() - 5, layout.y() + 10, layout.x() + layout.width() + 5, layout.y() + 11, -11413);
         context.fill(layout.x() - 5, layout.y() - 2, layout.x() - 4, layout.y() + 10, -11413);
         context.fill(layout.x() + layout.width() + 4, layout.y() - 2, layout.x() + layout.width() + 5, layout.y() + 10, -11413);
         context.drawTextWithShadow(this.textRenderer, previewText, layout.x(), layout.y(), -1);
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            layout.x() + layout.width() + 10,
            layout.y() - 1,
            new String[]{"This preview shows the exact tracker HUD area you can move."}
         );
         this.renderHelpTooltip(context, mouseX, mouseY);
      }
   }

   private static final class TrackerScreen extends ModGuiClient.BaseScreen {
      private final ModGuiClient.TrackerConfig config = ModGuiClient.loadConfig(
         ModGuiClient.TRACKER_CONFIG, ModGuiClient.TrackerConfig.class, ModGuiClient.TrackerConfig::new
      );
      private final ModGuiClient.KeybindEditor keybindEditor = new ModGuiClient.KeybindEditor(() -> this.config.toggleKeyCode, value -> {
         this.config.toggleKeyCode = value;
         ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, this.config);
      });
      private TextFieldWidget rangeField;
      private TextFieldWidget hudOffsetXField;
      private TextFieldWidget hudYField;

      private TrackerScreen(Screen parent) {
         super(parent, "Tracker");
      }

      @Override
      protected String selectedNavId() {
         return "tracker";
      }

      @Override
      protected void init() {
         if (this.ensureModuleLoaded("tracker")) {
            int left = this.formLabelX();
            int fieldX = this.formFieldX();
            int y = 118;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Enabled", this.config.enabled), () -> {
               this.config.enabled = !this.config.enabled;
               ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, this.config);
            }));
            y += 32;
            this.addDrawableChild(this.keybindEditor.createButton(left, y, 220));
            y += 34;
            this.addDrawableChild(ModGuiClient.toggleButton(left, y, 220, () -> ModGuiClient.toggleLabel("Ignore Team", this.config.ignoreOwnTeam), () -> {
               this.config.ignoreOwnTeam = !this.config.ignoreOwnTeam;
               ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, this.config);
            }));
            y += 34;
            this.rangeField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y, ModGuiClient.formatDouble(this.config.range)));
            this.hudOffsetXField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 26, String.valueOf(this.config.hudOffsetX)));
            this.hudYField = this.addDrawableChild(ModGuiClient.numberField(fieldX, y + 52, String.valueOf(this.config.hudY)));
            ModGuiClient.bindNumberField(this.rangeField, this::save);
            ModGuiClient.bindNumberField(this.hudOffsetXField, this::save);
            ModGuiClient.bindNumberField(this.hudYField, this::save);
            y += 86;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Edit HUD Position"), button -> {
               if (this.client != null) {
                  this.client.setScreen(new ModGuiClient.TrackerHudEditorScreen(this, this.config));
               }
            }).dimensions(left, y, 220, 20).build());
            this.addSaveAndResetButtons(this::save, this::resetToDefaults);
         }
      }

      private void save() {
         this.config.range = ModGuiClient.parseDouble(this.rangeField.getText(), this.config.range, 1.0, 128.0);
         this.config.hudOffsetX = ModGuiClient.parseInt(this.hudOffsetXField.getText(), this.config.hudOffsetX, -10000, 10000);
         this.config.hudY = ModGuiClient.parseInt(this.hudYField.getText(), this.config.hudY, -10000, 10000);
         ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, this.config);
      }

      private void resetToDefaults() {
         ModGuiClient.saveConfig(ModGuiClient.TRACKER_CONFIG, new ModGuiClient.TrackerConfig());
         this.reopen(new ModGuiClient.TrackerScreen(this.parent));
      }

      @Override
      public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
         this.beginDecorations();
         this.drawChromeBackground(context);
         this.drawScreenFrame(context, null, 28, 372);
         this.renderNavigationRail(context, mouseX, mouseY);
         int left = this.formLabelX();
         this.drawPageIntro(
            context, "Tracker Overlay", "Control every tracker value here, including range, team filtering, HUD coordinates, and the drag editor."
         );
         super.render(context, mouseX, mouseY, deltaTicks);
         this.drawRowHelp(context, mouseX, mouseY, this.contentRight() - 18, 121, new String[]{"Turns the tracker overlay on or off."});
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            153,
            new String[]{"Click this button, then press any key to toggle the module. Backspace clears it."}
         );
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            187,
            new String[]{"If enabled, players on your own scoreboard team are ignored by tracker alerts."}
         );
         this.drawFieldLabel(context, mouseX, mouseY, "Range", left, 222, new String[]{"Maximum distance used for tracker alerts and HUD checks."});
         this.drawFieldLabel(
            context, mouseX, mouseY, "HUD Offset X", left, 248, new String[]{"Moves the tracker text left or right from the centered default position."}
         );
         this.drawFieldLabel(context, mouseX, mouseY, "HUD Y", left, 274, new String[]{"Moves the tracker text higher or lower on the screen."});
         this.drawRowHelp(
            context,
            mouseX,
            mouseY,
            this.contentRight() - 18,
            309,
            new String[]{"Opens the drag editor if you prefer moving the tracker visually instead of typing coordinates."}
         );
         this.drawFooterNote(context, "Reset returns range and HUD coordinates to the default tracker layout", 334);
         this.renderHelpTooltip(context, mouseX, mouseY);
      }

      @Override
      public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
         return this.keybindEditor.handleKeyPressed(keyCode) ? true : super.keyPressed(keyCode, scanCode, modifiers);
      }

      @Override
      public boolean mouseClicked(double mouseX, double mouseY, int button) {
         boolean handled = super.mouseClicked(mouseX, mouseY, button);
         return handled ? true : this.keybindEditor.handleMouseClicked(button);
      }
   }
}
