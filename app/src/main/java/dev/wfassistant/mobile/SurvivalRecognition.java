package dev.wfassistant.mobile;

import android.graphics.Bitmap;
import android.util.Base64;
import java.util.BitSet;

final class SurvivalRecognition {
    private static final Anchor EQUIP = new Anchor(750, 596, 117, 20, false,
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYQAGAACAYQACAAAAGAAkDMAAGGAwDGD+wfABA4D1H/4HMwyGAcY/+B/wP4D+42FgjuH9YQCAyYADAxgGdgeMMb4/ZAA4GPg4wMMAfIBhBsMGDAAHA/ADTP/w/zFAYMjA/PNgwP8HCAAPPAYM3BkcGN7/PPgAGMD/wYDhe+MDA/sfMAD+//g/mDE8fmhgYDDg/wFYAGMGPwfGDwwMDAb8P+DZ4f/g8cGMgYGBwQAMBh8OjBked5ihMDAwGMBAAL+H/4NwDBscBgb2Pw4M4MHxfwAGcYPD8MD+5/ABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    // 未选中遗物时的提示「选择一个遗物来查看其奖励」，模板不含两侧的图标。
    private static final Anchor SELECT_RELIC_HINT = new Anchor(702, 361, 204, 19, false,
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMAAABAAAADiACAAYABACAwGGAEAAAADB8w/wAAAAww/9EYABgAGMD/B4PBxoB/A8D5D/MPAADgAfcfjwH+f/7/+Af8/2z/+TcAuP8w7AAAAD/g//H55//n/8//x//P/48BAwDNwc8HAAC8B/Af/3+YOeAP/H8wGGjn+P8BgBwwOAAA4PGB//P/h5mB2+H/D/+Bx4f/HwD8H/MeAIDvvP+/bXuYHZ55/v/wH3482LAB3P+xx/3/M457/8H05//n/wcHAP9B/oF9G8Bhg4/B/z/gAPYfvG3/f/gf8D/wH2AD2LcBGDN8/wAAAA5gs/Hf9v+H/4E/A7MBMIB9G4A5G/MPAADgALYbz3bwB/gf/j/8//z/2Ls52PExGAAAAA5g/sFkh/+A5+Ef4//P/4+/2YcLD/MfAADgAPc4DDOcPfgf8D9gHMAPeJtN/AAwOAAAAA74B8M485jv7wF/g88Dz8P32cT8j4MBAADggPk/zD2GYf5/8D8++Hz4/O95BP4YGAAAAA4A/sPAARjgAAAHwwDGAYzaDgM=");
    private static final Anchor YES = new Anchor(492, 388, 28, 27, false,
            "AAAAAAAAAAAAAAD//wPw/z8AB4AD8P8/AP//A3AAOAAPgAPw/z8A/v8DAAAAwP//H/z//wEADwDAcAAAHAcA4PB/AB7/B/BxAAA/BwD4fwDA8/8fHPz/gQD8HwAAAAA=");
    private static final Anchor NO = new Anchor(760, 388, 29, 27, false,
            "AAAAAAAAAAAAAAD8//+B//8/8P//AwB8AADABwAA/AwA4N8HAP/zA/xz+MAfDjzwwAEDADgAAAAAAMD/fwD4/w8AB+AB4AA4ABwAB4AD4ABwAB4A/v8DwP9/ADgADgAAAAA=");
    // 固定句首「确定要装备」。遗物名长度改变时，允许整行水平移动。
    private static final Anchor PROMPT = new Anchor(359, 288, 119, 26, true,
            "AAAAAAAAAAAAAAAAAAAAAGAAAGAAAAAAAGDAAAAG/zAAAHAA8P//AzBgAACDf/gHADAAAB4DwBkwAMAPBgYD/v8/AIYBgO3/B/AHgcEAAwAYAMMAAPb/Ax7AwDCAAQAI/P8fAAMGgBtg8P/BAAAExzAMgAEDYDgw/P9gAACCYRgE8IABAHgIHkOA/z/AMAwCb8AAADx+jCGA/x9gGIbBMf8fwAd/xhAAMADw//8AmP8P/sAx/w8AGAAAHgAADACAH+CYMQYYDAAABgAAwAAA/nPMGAIMBgCAAQAA4AAA/yFmDAEG/4H//z/8//+BwRAzhgCD/wA44AAANgDAYIj5f4DBAAAMMADAMRhgMMSMMeBhAAAHHAA4MAfwH2bDEPAxAIAPB4Af+AAYDL9hCMwZAAD/AeAMeAAMhmEwBMYPAAD+AwCGcQAGQzAYg4H/H+DHDwD/8AD/AQjgYQD/j38AHsAH4ID/AAAAAAAAAAAABAAAAEAAAAAAAAAAAAAAAAAAAAAA");

    boolean relicSelection(Bitmap screenshot) {
        // 手柄模式的提示图标会把按钮文字向右推约 12 像素。
        return EQUIP.matches(screenshot, 16, 2, 0.75);
    }

    boolean relicSelectionHint(Bitmap screenshot) {
        return SELECT_RELIC_HINT.matches(screenshot, 3, 2, 0.75);
    }

    boolean relicConfirmation(Bitmap screenshot) {
        return YES.matches(screenshot, 2, 2, 0.78)
                && NO.matches(screenshot, 2, 2, 0.78)
                && PROMPT.matches(screenshot, 56, 5, 0.75);
    }

    private record Anchor(int x, int y, int width, int height, boolean white, BitSet mask) {
        Anchor(int x, int y, int width, int height, boolean white, String encoded) {
            this(x, y, width, height, white, BitSet.valueOf(Base64.decode(encoded, Base64.DEFAULT)));
        }

        boolean matches(Bitmap frame, int radiusX, int radiusY, double threshold) {
            int stride = width + 2 * radiusX;
            int rows = height + 2 * radiusY;
            int[] colors = new int[stride * rows];
            frame.getPixels(colors, 0, stride, x - radiusX, y - radiusY, stride, rows);
            boolean[] foreground = new boolean[colors.length];
            for (int i = 0; i < colors.length; i++) {
                int r = (colors[i] >> 16) & 255;
                int g = (colors[i] >> 8) & 255;
                int b = colors[i] & 255;
                foreground[i] = white ? Math.min(r, Math.min(g, b)) >= 170
                        && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 45
                        : r >= 128 && g >= 104 && r - g >= -4 && r - g <= 48 && g - b >= 17;
            }
            int expected = mask.cardinality();
            for (int dy = 0; dy <= 2 * radiusY; dy++) {
                for (int dx = 0; dx <= 2 * radiusX; dx++) {
                    int hits = 0;
                    int observed = 0;
                    for (int row = 0; row < height; row++) {
                        int offset = (dy + row) * stride + dx;
                        for (int col = 0; col < width; col++) {
                            if (!foreground[offset + col]) continue;
                            observed++;
                            if (mask.get(row * width + col)) hits++;
                        }
                    }
                    if (2 * hits >= threshold * (expected + observed)) return true;
                }
            }
            return false;
        }
    }
}
