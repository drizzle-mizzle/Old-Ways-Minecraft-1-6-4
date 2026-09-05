package oldways.launcher.ui;

import javax.swing.BorderFactory;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;

/** Поля ввода из макета: полупрозрачные, со скруглением и подсказкой. */
public final class Fields {

    private Fields() {}

    public static TextField text(double scale, String placeholder) {
        return new TextField(scale, placeholder);
    }

    public static PasswordField password(double scale, String placeholder) {
        return new PasswordField(scale, placeholder);
    }

    private static void dress(JTextComponent field, double scale) {
        field.setOpaque(false);
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);
        field.setSelectionColor(new Color(255, 255, 255, 60));
        field.setSelectedTextColor(Color.WHITE);
        field.setFont(Theme.font((float) (14 * scale), false));
        int padY = (int) Math.round(13 * scale);
        int padX = (int) Math.round(10 * scale);
        field.setBorder(BorderFactory.createEmptyBorder(padY, padX, padY, padX));
    }

    private static void paintBox(JTextComponent field, Graphics graphics, double scale) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int radius = (int) Math.round(5 * scale) * 2;
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                0.5f, 0.5f, field.getWidth() - 1f, field.getHeight() - 1f, radius, radius);
        g.setColor(Theme.FIELD_FILL);
        g.fill(shape);
        g.setColor(field.isFocusOwner() ? Theme.FIELD_FOCUS : Theme.FIELD_BORDER);
        g.draw(shape);
        g.dispose();
    }

    private static void paintHint(JTextComponent field, Graphics graphics, String hint,
                                  double scale) {
        if (hint == null || hint.isEmpty() || field.getDocument().getLength() > 0) return;
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        g.setColor(Theme.TEXT_DIM);
        Font font = field.getFont();
        g.setFont(font);
        int x = (int) Math.round(10 * scale) + 1;
        int y = (field.getHeight() + g.getFontMetrics().getAscent()
                - g.getFontMetrics().getDescent()) / 2;
        g.drawString(hint, x, y);
        g.dispose();
    }

    /** Однострочное поле. */
    public static final class TextField extends JTextField {
        private static final long serialVersionUID = 1L;
        private final double scale;
        private final String hint;

        TextField(double scale, String hint) {
            this.scale = scale;
            this.hint = hint;
            dress(this, scale);
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintBox(this, g, scale);
            super.paintComponent(g);
            paintHint(this, g, hint, scale);
        }
    }

    /** Поле пароля. */
    public static final class PasswordField extends JPasswordField {
        private static final long serialVersionUID = 1L;
        private final double scale;
        private final String hint;

        PasswordField(double scale, String hint) {
            this.scale = scale;
            this.hint = hint;
            dress(this, scale);
            setEchoChar('•');
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintBox(this, g, scale);
            super.paintComponent(g);
            paintHint(this, g, hint, scale);
        }
    }
}
