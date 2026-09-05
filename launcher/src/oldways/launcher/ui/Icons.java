package oldways.launcher.ui;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Значки берутся прямо из макета — теми же путями SVG.
 *
 * Разбор пути короче, чем перерисовывать шестерёнку вручную, и главное —
 * значок остаётся ровно тем, который нарисован в HTML: поправят макет,
 * поправится и здесь.
 */
public final class Icons {

    /** Шестерёнка настроек, viewBox 24x24 — путь из ui_template.html. */
    public static final String GEAR =
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94"
            + "l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29"
            + " -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24"
            + " -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35"
            + "C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22"
            + "L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12"
            + "s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32"
            + "c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54"
            + "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
            + "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
            + "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6"
            + "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";

    private Icons() {}

    /** Путь SVG, вписанный в квадрат size на size (исходный viewBox 24x24). */
    public static Path2D scaled(String path, double size) {
        Path2D shape = parse(path);
        shape.transform(AffineTransform.getScaleInstance(size / 24.0, size / 24.0));
        return shape;
    }

    /**
     * Разбирает подмножество синтаксиса пути: M, L, H, V, C, S, Q, T, Z
     * в обоих регистрах. Дуг (A) в значках интерфейса не встречается.
     */
    public static Path2D parse(String path) {
        Path2D.Double shape = new Path2D.Double();
        Point2D.Double at = new Point2D.Double();
        Point2D.Double start = new Point2D.Double();
        Point2D.Double lastControl = new Point2D.Double();
        char previous = ' ';
        int index = 0;
        char command = ' ';

        while (index < path.length()) {
            char c = path.charAt(index);
            if (Character.isLetter(c)) {
                command = c;
                index++;
            } else if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                index++;
                continue;
            }
            boolean relative = Character.isLowerCase(command);
            char upper = Character.toUpperCase(command);

            if (upper == 'Z') {
                shape.closePath();
                at.setLocation(start);
                previous = upper;
                continue;
            }

            int needed = numbersFor(upper);
            List<Double> numbers = new ArrayList<Double>(needed);
            for (int i = 0; i < needed; i++) {
                index = skip(path, index);
                int end = index;
                if (end < path.length() && (path.charAt(end) == '-' || path.charAt(end) == '+')) {
                    end++;
                }
                while (end < path.length()
                        && (Character.isDigit(path.charAt(end)) || path.charAt(end) == '.')) {
                    end++;
                }
                if (end == index) {
                    throw new IllegalArgumentException("не понял путь SVG на позиции " + index);
                }
                numbers.add(Double.valueOf(path.substring(index, end)));
                index = end;
            }

            double dx = relative ? at.x : 0;
            double dy = relative ? at.y : 0;
            switch (upper) {
                case 'M':
                    at.setLocation(numbers.get(0) + dx, numbers.get(1) + dy);
                    start.setLocation(at);
                    shape.moveTo(at.x, at.y);
                    command = relative ? 'l' : 'L';   // следующие пары — линии
                    break;
                case 'L':
                    at.setLocation(numbers.get(0) + dx, numbers.get(1) + dy);
                    shape.lineTo(at.x, at.y);
                    break;
                case 'H':
                    at.setLocation(numbers.get(0) + dx, at.y);
                    shape.lineTo(at.x, at.y);
                    break;
                case 'V':
                    at.setLocation(at.x, numbers.get(0) + dy);
                    shape.lineTo(at.x, at.y);
                    break;
                case 'C': {
                    double x1 = numbers.get(0) + dx, y1 = numbers.get(1) + dy;
                    double x2 = numbers.get(2) + dx, y2 = numbers.get(3) + dy;
                    at.setLocation(numbers.get(4) + dx, numbers.get(5) + dy);
                    shape.curveTo(x1, y1, x2, y2, at.x, at.y);
                    lastControl.setLocation(x2, y2);
                    break;
                }
                case 'S': {
                    boolean smooth = previous == 'C' || previous == 'S';
                    double x1 = smooth ? 2 * at.x - lastControl.x : at.x;
                    double y1 = smooth ? 2 * at.y - lastControl.y : at.y;
                    double x2 = numbers.get(0) + dx, y2 = numbers.get(1) + dy;
                    at.setLocation(numbers.get(2) + dx, numbers.get(3) + dy);
                    shape.curveTo(x1, y1, x2, y2, at.x, at.y);
                    lastControl.setLocation(x2, y2);
                    break;
                }
                case 'Q': {
                    double x1 = numbers.get(0) + dx, y1 = numbers.get(1) + dy;
                    at.setLocation(numbers.get(2) + dx, numbers.get(3) + dy);
                    shape.quadTo(x1, y1, at.x, at.y);
                    lastControl.setLocation(x1, y1);
                    break;
                }
                case 'T': {
                    boolean smooth = previous == 'Q' || previous == 'T';
                    double x1 = smooth ? 2 * at.x - lastControl.x : at.x;
                    double y1 = smooth ? 2 * at.y - lastControl.y : at.y;
                    at.setLocation(numbers.get(0) + dx, numbers.get(1) + dy);
                    shape.quadTo(x1, y1, at.x, at.y);
                    lastControl.setLocation(x1, y1);
                    break;
                }
                default:
                    throw new IllegalArgumentException("незнакомая команда пути: " + command);
            }
            previous = upper;
        }
        return shape;
    }

    private static int skip(String path, int index) {
        while (index < path.length()) {
            char c = path.charAt(index);
            if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') index++;
            else break;
        }
        return index;
    }

    private static int numbersFor(char command) {
        switch (command) {
            case 'H': case 'V': return 1;
            case 'M': case 'L': case 'T': return 2;
            case 'S': case 'Q': return 4;
            case 'C': return 6;
            default: return 0;
        }
    }
}
