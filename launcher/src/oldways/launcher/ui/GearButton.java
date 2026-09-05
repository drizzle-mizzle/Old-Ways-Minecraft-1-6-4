package oldways.launcher.ui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Кнопка настроек в углу: квадрат с шестерёнкой и подпись под ним. */
public class GearButton extends JComponent {

    private static final long serialVersionUID = 1L;

    private final double scale;
    private final String caption;
    private final Path2D gear;
    private boolean hot;
    private ActionListener listener;

    public GearButton(String caption, double scale) {
        this.caption = caption;
        this.scale = scale;
        this.gear = Icons.scaled(Icons.GEAR, 24 * scale);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.font((float) (10 * scale), false));

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hot = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hot = false;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (listener != null) {
                    listener.actionPerformed(
                            new ActionEvent(GearButton.this, ActionEvent.ACTION_PERFORMED, "gear"));
                }
            }
        });
    }

    public void onClick(ActionListener listener) {
        this.listener = listener;
    }

    public int buttonSize() {
        return (int) Math.round(44 * scale);
    }

    /**
     * Запас по краям: в него должны влезть и прирост под курсором, и тень.
     * Не хватит — обрежется либо увеличенный квадрат, либо тень, и она
     * превращается в тёмную полосу с ровным краем.
     */
    private int pad() {
        return (int) Math.ceil(Math.max(buttonSize() * (Theme.HOVER - 1) / 2, 9 * scale));
    }

    /** Ширина блока: подпись обычно шире самой кнопки. */
    public int fullWidth() {
        return Math.max(buttonSize() + pad() * 2,
                getFontMetrics(getFont()).stringWidth(caption) + (int) Math.round(8 * scale));
    }

    /** Высота блока: квадрат с запасом, зазор и строка подписи. */
    public int fullHeight() {
        return pad() + buttonSize() + (int) Math.round(8 * scale)
                + getFontMetrics(getFont()).getHeight();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int size = buttonSize();
        int pad = pad();
        int radius = (int) Math.round(7 * scale) * 2;
        int left = (getWidth() - size) / 2;

        Graphics2D box = (Graphics2D) g.create();
        if (hot) {
            box.translate(left + size / 2.0, pad + size / 2.0);
            box.scale(Theme.HOVER, Theme.HOVER);
            box.translate(-size / 2.0, -size / 2.0);
        } else {
            box.translate(left, pad);
        }
        // Тень мельче макетной: под кнопкой в сорок точек снос в двенадцать
        // читается отдельной полосой снизу, а не тенью самой кнопки.
        Theme.shadow(box, 0, 0, size, size, radius, scale, 3, 10);
        RoundRectangle2D shape = new RoundRectangle2D.Float(
                0.5f, 0.5f, size - 1f, size - 1f, radius, radius);
        box.setColor(Theme.GEAR_FILL);
        box.fill(shape);
        if (hot) {
            Graphics2D light = (Graphics2D) box.create();
            light.clip(shape);
            light.setPaint(Theme.sheen(size, size));
            light.fill(shape);
            light.dispose();
        }
        box.setColor(Theme.INNER_LIGHT);
        box.drawLine(radius / 3, 1, size - radius / 3, 1);
        box.setColor(Theme.BORDER);
        box.draw(shape);

        box.setColor(Color.WHITE);
        double icon = 24 * scale;
        box.translate((size - icon) / 2, (size - icon) / 2);
        box.fill(gear);
        box.dispose();

        g.setFont(getFont());
        g.setColor(Color.WHITE);
        FontMetrics metrics = g.getFontMetrics();
        int textY = pad + size + (int) Math.round(8 * scale) + metrics.getAscent();
        g.drawString(caption, (getWidth() - metrics.stringWidth(caption)) / 2, textY);
        g.dispose();
    }
}
