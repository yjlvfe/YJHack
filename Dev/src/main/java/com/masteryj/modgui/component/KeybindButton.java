package com.masteryj.modgui.component;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import com.masteryj.modgui.theme.YjTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Click, then press any key or mouse button to bind; Backspace/Delete clears. */
public final class KeybindButton extends ClickableWidget {
   private final IntSupplier getter;
   private final IntConsumer setter;
   private boolean listening;

   public KeybindButton(int x, int y, int w, int h, IntSupplier getter, IntConsumer setter) {
      super(x, y, w, h, Text.literal("Keybind"));
      this.getter = getter;
      this.setter = setter;
   }

   public boolean isListening() {
      return this.listening;
   }

   @Override
   protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
      boolean hovered = this.isMouseOver(mouseX, mouseY);
      YjTheme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? YjTheme.CTRL_HOVER : YjTheme.CTRL, YjTheme.BORDER_SOFT);
      TextRenderer tr = MinecraftClient.getInstance().textRenderer;
      ctx.drawText(tr, "Toggle Key", getX() + 8, getY() + (getHeight() - 8) / 2, YjTheme.TEXT, false);
      Text right = this.listening
         ? Text.literal("Press a key…").formatted(Formatting.YELLOW)
         : YjTheme.keyName(this.getter.getAsInt());
      int rw = tr.getWidth(right);
      ctx.drawText(tr, right, getX() + getWidth() - 8 - rw, getY() + (getHeight() - 8) / 2, this.listening ? YjTheme.WARNING : YjTheme.ACCENT, false);
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
   public boolean captureMouse(int button) {
      if (!this.listening) return false;
      this.setter.accept(YjTheme.encodeMouse(button));
      this.listening = false;
      return true;
   }

   public boolean captureKey(int keyCode) {
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
      builder.put(NarrationPart.TITLE, Text.literal("Toggle key: ").append(YjTheme.keyName(this.getter.getAsInt())));
   }
}
