package com.masteryj.modgui.component;

import java.util.function.Consumer;

import com.masteryj.modgui.theme.YjTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** A real sliding on/off switch with an inline label. */
public final class ToggleSwitch extends ClickableWidget {
   private boolean value;
   private final Consumer<Boolean> onChange;
   private final String label;

   public ToggleSwitch(int x, int y, int w, int h, String label, boolean value, Consumer<Boolean> onChange) {
      super(x, y, w, h, Text.literal(label));
      this.label = label;
      this.value = value;
      this.onChange = onChange;
   }

   @Override
   protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
      boolean hovered = this.isMouseOver(mouseX, mouseY);
      YjTheme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? YjTheme.CTRL_HOVER : YjTheme.CTRL, YjTheme.BORDER_SOFT);
      TextRenderer tr = MinecraftClient.getInstance().textRenderer;
      ctx.drawText(tr, this.label, getX() + 8, getY() + (getHeight() - 8) / 2, YjTheme.TEXT, false);

      int trackW = 26;
      int trackH = 12;
      int tx = getX() + getWidth() - trackW - 8;
      int ty = getY() + (getHeight() - trackH) / 2;
      YjTheme.pill(ctx, tx, ty, trackW, trackH, this.value ? YjTheme.ACCENT : YjTheme.TRACK_OFF);
      int knobX = this.value ? tx + trackW - 11 : tx + 1;
      YjTheme.pill(ctx, knobX, ty + 1, 10, trackH - 2, YjTheme.KNOB);
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
