package wfactory.launcher.ui;

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
import java.awt.geom.RoundRectangle2D;

/**
 * Переключатель из нескольких кнопок — «День / Ночь / Авто» в макете.
 *
 * Сделан одним компонентом, а не тремя кнопками: так проще держать равные
 * доли ширины и одинаковые зазоры при любом масштабе окна.
 */
public class Segmented extends JComponent {

    private static final long serialVersionUID = 1L;

    private final String[] labels;
    private final String[] values;
    private final double scale;
    private int picked;
    private int hovered = -1;
    private ActionListener listener;

    public Segmented(String[] labels, String[] values, double scale) {
        this.labels = labels.clone();
        this.values = values.clone();
        this.scale = scale;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(Theme.font((float) (12 * scale), false));

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int was = hovered;
                hovered = indexAt(e.getX());
                if (was != hovered) repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = -1;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                int index = indexAt(e.getX());
                if (index < 0 || index == picked) return;
                picked = index;
                repaint();
                if (listener != null) {
                    listener.actionPerformed(new ActionEvent(
                            Segmented.this, ActionEvent.ACTION_PERFORMED, values[picked]));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public void onChange(ActionListener listener) {
        this.listener = listener;
    }

    public String selected() {
        return values[picked];
    }

    public void select(String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                picked = i;
                repaint();
                return;
            }
        }
    }

    private int gap() {
        return (int) Math.round(6 * scale);
    }

    private int indexAt(int x) {
        int gap = gap();
        int cell = (getWidth() - gap * (labels.length - 1)) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int left = i * (cell + gap);
            if (x >= left && x < left + cell) return i;
        }
        return -1;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Theme.smooth((Graphics2D) graphics.create());
        int gap = gap();
        int cell = (getWidth() - gap * (labels.length - 1)) / labels.length;
        int radius = (int) Math.round(5 * scale) * 2;
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();

        for (int i = 0; i < labels.length; i++) {
            int left = i * (cell + gap);
            RoundRectangle2D shape = new RoundRectangle2D.Float(
                    left + 0.5f, 0.5f, cell - 1f, getHeight() - 1f, radius, radius);
            boolean on = i == picked;
            g.setColor(on ? Theme.CHOICE_PICKED
                    : (i == hovered ? Theme.BUTTON_FILL : Theme.CHOICE_FILL));
            g.fill(shape);
            g.setColor(on ? Theme.CHOICE_BORDER : Theme.FIELD_BORDER);
            g.draw(shape);

            g.setColor(Color.WHITE);
            int textX = left + (cell - metrics.stringWidth(labels[i])) / 2;
            int textY = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
            g.drawString(labels[i], textX, textY);
        }
        g.dispose();
    }
}
