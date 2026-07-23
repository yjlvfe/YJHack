package com.masteryj.modgui.component;

import java.util.function.DoubleConsumer;

import com.masteryj.modgui.theme.YjTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * Custom-drawn slider built on the vanilla {@link SliderWidget} (which handles the drag
 * maths and keyboard) but rendered in the YJHack theme. Reports the value in its real
 * domain, optionally rounded to an integer.
 */
public final class ThemeSlider extends SliderWidget {
   private final double min;
   private final double max;
   private final boolean asInt;
   private final String label;
   private final DoubleConsumer onValue;
   private boolean syncing;

   public ThemeSlider(int x, int y, int w, int h, String label, double min, double max, double value, boolean asInt, DoubleConsumer onValue) {
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

   public double domainValue() {
      double v = this.min + this.value * (this.max - this.min);
      return this.asInt ? Math.round(v) : v;
   }

   /** Set from the paired text field without firing applyValue. */
   public void setDomainQuiet(double v) {
      this.syncing = true;
      this.value = clamp01((v - this.min) / (this.max - this.min));
      this.updateMessage();
      this.syncing = false;
   }

   @Override
   protected void updateMessage() {
      this.setMessage(Text.literal(this.label + ": " + YjTheme.fmt(domainValue())));
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
      YjTheme.panel(ctx, getX(), getY(), getWidth(), getHeight(), hovered ? YjTheme.CTRL_HOVER : YjTheme.CTRL, YjTheme.BORDER_SOFT);
      int trackY = getY() + getHeight() - 5;
      int trackX0 = getX() + 6;
      int trackX1 = getX() + getWidth() - 6;
      ctx.fill(trackX0, trackY, trackX1, trackY + 2, YjTheme.TRACK_OFF);
      int fillX = trackX0 + (int) ((trackX1 - trackX0) * this.value);
      ctx.fill(trackX0, trackY, fillX, trackY + 2, YjTheme.ACCENT);
      int knobX = MathHelper.clamp(fillX, trackX0, trackX1 - 3);
      ctx.fill(knobX - 2, trackY - 3, knobX + 3, trackY + 4, YjTheme.KNOB);
      TextRenderer tr = MinecraftClient.getInstance().textRenderer;
      ctx.drawText(tr, this.label, getX() + 6, getY() + 4, YjTheme.TEXT_DIM, false);
      String val = YjTheme.fmt(domainValue());
      ctx.drawText(tr, val, getX() + getWidth() - 6 - tr.getWidth(val), getY() + 4, YjTheme.TEXT, false);
   }
}
