package oldways.launcher.ui;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/** Кнопки макета: полупрозрачная заливка, скругление 5, подсветка под курсором. */
public class Buttons extends JButton {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private final float fontSize;
    private final boolean heavy;
    private Color accentBorder;
    private Color accentText;
    private Color accentFill;

    public Buttons(String text, double scale, float fontSize, boolean heavy) {
        super(text);
        this.scale = scale;
        this.fontSize = fontSize;
        this.heavy = heavy;
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setForeground(Color.WHITE);
        setFont(Theme.font((float) (fontSize * scale), heavy));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    /** Главная кнопка входа: та же отделка, но крупнее и жирнее. */
    public static Buttons primary(String text, double scale) {
        return new Buttons(text, scale, 15f, true);
    }

    /** Второстепенная кнопка: мельче, как подписи в меню настроек. */
    public static Buttons small(String text, double scale) {
        return new Buttons(text, scale, 12f, false);
    }

    /** Красит рамку и надпись: так отмечен выход из учётной записи. */
    public Buttons accent(Color border, Color text) {
        this.accentBorder = border;
        this.accentText = text;
        repaint();
        return this;
    }

    /** Красит кнопку целиком в один цвет: заливка, рамка и надпись. */
    public Buttons tint(Color base) {
        this.accentFill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 56);
        this.accentBorder = new Color(base.getRed(), base.getGreen(), base.getBlue(), 170);
        this.accentText = base;
        repaint();
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int padY = (int) Math.round((heavy ? 9 : 7) * scale);
        int padX = (int) Math.round((heavy ? 7 : 12) * scale);
        return new Dimension(metrics.stringWidth(getText()) + padX * 2,
                metrics.getHeight() + padY * 2);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        boolean hot = isEnabled() && (getModel().isRollover() || getModel().isPressed());

        // По краям оставлен запас ровно на прирост под курсором: без него
        // подросшая кнопка обрезалась бы о собственные границы.
        int insetX = (int) Math.ceil(getWidth() * (Theme.HOVER - 1) / 2);
        int insetY = (int) Math.ceil(getHeight() * (Theme.HOVER - 1) / 2);
        int width = getWidth() - insetX * 2;
        int height = getHeight() - insetY * 2;
        if (hot) {
            g.translate(getWidth() / 2.0, getHeight() / 2.0);
            g.scale(Theme.HOVER, Theme.HOVER);
            g.translate(-getWidth() / 2.0, -getHeight() / 2.0);
        }

        int radius = (int) Math.round(5 * scale) * 2;
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                insetX + 0.5f, insetY + 0.5f, width - 1f, height - 1f, radius, radius);

        Color fill = accentFill != null ? accentFill : Theme.BUTTON_FILL;
        g.setColor(!isEnabled() ? Theme.CHOICE_FILL : fill);
        g.fill(shape);
        if (hot) {
            Graphics2D light = (Graphics2D) g.create();
            light.clip(shape);
            light.setPaint(Theme.sheen(getWidth(), getHeight()));
            light.fill(shape);
            light.dispose();
        }

        Color border = accentBorder != null ? accentBorder : Theme.BUTTON_BORDER;
        g.setColor(isEnabled() ? border : Theme.FIELD_BORDER);
        g.draw(shape);

        g.setFont(getFont());
        Color label = accentText != null ? accentText : Color.WHITE;
        g.setColor(isEnabled() ? label : Theme.TEXT_DIM);
        FontMetrics metrics = g.getFontMetrics();
        int x = insetX + (width - metrics.stringWidth(getText())) / 2;
        int y = insetY + (height + metrics.getAscent() - metrics.getDescent()) / 2;
        g.drawString(getText(), x, y);
        g.dispose();
    }
}
