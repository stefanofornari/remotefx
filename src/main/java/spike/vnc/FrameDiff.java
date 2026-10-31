package spike.vnc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one client has not been sent yet, tracked per 64x64 tile as the bounding box of the changed pixels.
 * Changes outside the area a client asked for stay marked until it asks for them, and adjacent dirty
 * tiles are merged into as few rectangles as possible. Also finds vertical scrolls, which can be sent as CopyRect.
 *
 * Not thread safe: owned by the client's sender thread.
 */
final class FrameDiff {

    static final int TILE = 64;
    private static final int MAX_SHIFT = 512;        // largest scroll distance looked for, in rows
    private static final int MIN_COPY_ROWS = 32;
    private static final int MIN_COPY_AREA = 4 * TILE * TILE;
    static final int MIN_SCROLL_TILES = 8;           // do not look for scrolls when less than this changed

    private final int width, height, tilesX, tilesY;
    // per tile: x0 | y0<<6 | x1<<12 | y1<<18 (tile relative, inclusive), -1 = clean
    private final int[] dirty;
    private int sx0, sy0, sx1, sy1; // scratch: box found by compare()

    FrameDiff(int width, int height) {
        this.width = width;
        this.height = height;
        this.tilesX = (width + TILE - 1) / TILE;
        this.tilesY = (height + TILE - 1) / TILE;
        this.dirty = new int[tilesX * tilesY];
        Arrays.fill(dirty, -1);
    }

    boolean isClean() {
        for (int d : dirty) {
            if (d >= 0) return false;
        }
        return true;
    }

    void clear() {
        Arrays.fill(dirty, -1);
    }

    /** Marks every tile touching the area (x0,y0)-(x1,y1), exclusive end, as fully changed. */
    void markRegion(int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(width, x1);
        y1 = Math.min(height, y1);
        if (x0 >= x1 || y0 >= y1) return;
        for (int ty = y0 / TILE; ty <= (y1 - 1) / TILE; ty++) {
            for (int tx = x0 / TILE; tx <= (x1 - 1) / TILE; tx++) {
                merge(ty * tilesX + tx, pack(0, 0, tileW(tx) - 1, tileH(ty) - 1));
            }
        }
    }

    /**
     * Marks what differs between what the client shows (prev) and cur. When copy ({x, y, w, h, srcX, srcY})
     * is given, the client is assumed to have performed that CopyRect already.
     */
    void update(int[] prev, int[] cur, int[] copy) {
        for (int ty = 0; ty < tilesY; ty++) {
            int th = tileH(ty);
            for (int tx = 0; tx < tilesX; tx++) {
                int tw = tileW(tx), x = tx * TILE;
                sx0 = tw;
                sy0 = th;
                sx1 = -1;
                sy1 = -1;
                for (int r = 0; r < th; r++) {
                    int row = ty * TILE + r;
                    if (copy != null && row >= copy[1] && row < copy[1] + copy[3]) {
                        int cx0 = copy[0], cx1 = copy[0] + copy[2];
                        int delta = (copy[5] - copy[1]) * width + (copy[4] - copy[0]);
                        compare(cur, prev, row, x, Math.min(x + tw, cx0), 0, ty * TILE, x);
                        compare(cur, prev, row, Math.max(x, cx0), Math.min(x + tw, cx1), delta, ty * TILE, x);
                        compare(cur, prev, row, Math.max(x, cx1), x + tw, 0, ty * TILE, x);
                    } else {
                        compare(cur, prev, row, x, x + tw, 0, ty * TILE, x);
                    }
                }
                if (sx1 >= 0) merge(ty * tilesX + tx, pack(sx0, sy0, sx1, sy1));
            }
        }
    }

    /** Compares cur pixels [xs, xe) of a row with prev pixels shifted by delta, widening the scratch box. */
    private void compare(int[] cur, int[] prev, int row, int xs, int xe, int delta, int tileY, int tileX) {
        if (xs >= xe) return;
        int co = row * width;
        int m = Arrays.mismatch(cur, co + xs, co + xe, prev, co + xs + delta, co + xe + delta);
        if (m < 0) return;
        int first = xs + m, last = xe - 1;
        while (cur[co + last] == prev[co + last + delta]) last--;
        sx0 = Math.min(sx0, first - tileX);
        sx1 = Math.max(sx1, last - tileX);
        sy0 = Math.min(sy0, row - tileY);
        sy1 = Math.max(sy1, row - tileY);
    }

    /**
     * Removes and returns the dirty tiles that touch the area (x0,y0)-(x1,y1) (exclusive end) as {x, y, w, h}.
     * Each tile is trimmed to its changed pixels, then horizontal runs and equally wide stacked runs are merged.
     */
    List<int[]> take(int x0, int y0, int x1, int y1) {
        List<int[]> rects = new ArrayList<>(); // {x0, y0, x1, y1}, exclusive end
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(width, x1);
        y1 = Math.min(height, y1);
        if (x0 >= x1 || y0 >= y1) return List.of();
        List<int[]> above = new ArrayList<>();
        for (int ty = y0 / TILE; ty <= (y1 - 1) / TILE; ty++) {
            List<int[]> row = new ArrayList<>();
            int[] run = null;
            for (int tx = x0 / TILE; tx <= (x1 - 1) / TILE; tx++) {
                int i = ty * tilesX + tx;
                int d = dirty[i];
                if (d < 0) {
                    run = null;
                    continue;
                }
                dirty[i] = -1;
                int bx0 = tx * TILE + (d & 63), by0 = ty * TILE + ((d >> 6) & 63);
                int bx1 = tx * TILE + ((d >> 12) & 63) + 1, by1 = ty * TILE + ((d >> 18) & 63) + 1;
                if (run == null) {
                    run = new int[]{bx0, by0, bx1, by1};
                    row.add(run);
                } else {
                    run[0] = Math.min(run[0], bx0);
                    run[1] = Math.min(run[1], by0);
                    run[2] = Math.max(run[2], bx1);
                    run[3] = Math.max(run[3], by1);
                }
            }
            List<int[]> carried = new ArrayList<>();
            for (int[] r : row) {
                int[] up = null;
                for (int[] a : above) {
                    if (a[0] == r[0] && a[2] == r[2] && a[3] == r[1]) {
                        up = a;
                        break;
                    }
                }
                if (up != null) {
                    up[3] = r[3];
                    carried.add(up);
                } else {
                    rects.add(r);
                    carried.add(r);
                }
            }
            above = carried;
        }
        List<int[]> out = new ArrayList<>(rects.size());
        for (int[] r : rects) out.add(new int[]{r[0], r[1], r[2] - r[0], r[3] - r[1]});
        return out;
    }

    /** Pixel bounds (tile aligned, exclusive end) of the dirty tiles and their count: {x0, y0, x1, y1, count}. */
    int[] dirtyBounds() {
        int x0 = tilesX, y0 = tilesY, x1 = -1, y1 = -1, n = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                if (dirty[ty * tilesX + tx] < 0) continue;
                n++;
                x0 = Math.min(x0, tx);
                y0 = Math.min(y0, ty);
                x1 = Math.max(x1, tx);
                y1 = Math.max(y1, ty);
            }
        }
        if (n == 0) return new int[]{0, 0, 0, 0, 0};
        return new int[]{x0 * TILE, y0 * TILE, Math.min(width, (x1 + 1) * TILE), Math.min(height, (y1 + 1) * TILE), n};
    }

    /**
     * Looks for a vertical scroll inside bounds ({x0, y0, x1, y1, ...} from {@link #dirtyBounds()}): a block
     * of cur that is a block of prev moved up or down. Returns the best one as {x, y, w, h, srcX, srcY}, or null.
     * Every tile column votes for its own shift, so a scrollbar or a static sidebar does not hide the scroll.
     */
    int[] findScroll(int[] prev, int[] cur, int[] bounds) {
        int by0 = bounds[1], by1 = bounds[3];
        int p0 = Math.max(0, by0 - MAX_SHIFT), p1 = Math.min(height, by1 + MAX_SHIFT);
        int firstCol = bounds[0] / TILE, lastCol = (bounds[2] - 1) / TILE;
        int[][] found = new int[lastCol + 1][]; // per column: {dy, from, to}
        for (int tc = firstCol; tc <= lastCol; tc++) {
            found[tc] = scrollOfColumn(prev, cur, tc * TILE, Math.min(width, (tc + 1) * TILE), by0, by1, p0, p1);
        }
        int[] best = null;
        long bestArea = 0;
        for (int c = firstCol; c <= lastCol; c++) {
            if (found[c] == null) continue;
            int dy = found[c][0], lo = found[c][1], hi = found[c][2], d = c + 1;
            while (d <= lastCol && found[d] != null && found[d][0] == dy
                    && Math.min(hi, found[d][2]) - Math.max(lo, found[d][1]) >= MIN_COPY_ROWS) {
                lo = Math.max(lo, found[d][1]);
                hi = Math.min(hi, found[d][2]);
                d++;
            }
            int x = c * TILE, w = Math.min(width, d * TILE) - x;
            long area = (long) w * (hi - lo);
            if (hi - lo >= MIN_COPY_ROWS && area > bestArea) {
                bestArea = area;
                best = new int[]{x, lo, w, hi - lo, x, lo + dy};
            }
            c = d - 1;
        }
        if (best == null || bestArea < MIN_COPY_AREA) return null;
        for (int y = best[1]; y < best[1] + best[3]; y++) { // hashes can collide: confirm pixel by pixel
            int co = y * width + best[0], po = (y + best[5] - best[1]) * width + best[4];
            if (!Arrays.equals(cur, co, co + best[2], prev, po, po + best[2])) return null;
        }
        return best;
    }

    private int[] scrollOfColumn(int[] prev, int[] cur, int xs, int xe, int by0, int by1, int p0, int p1) {
        int rows = by1 - by0;
        long[] hc = new long[rows], hp = new long[p1 - p0];
        boolean[] plainC = new boolean[rows], plainP = new boolean[p1 - p0];
        for (int i = 0; i < rows; i++) {
            hc[i] = hash(cur, (by0 + i) * width + xs, xe - xs);
            plainC[i] = plain(cur, (by0 + i) * width + xs, xe - xs);
        }
        Map<Long, Integer> unique = new HashMap<>(); // row of prev by hash, -1 if the hash occurs more than once
        for (int i = 0; i < hp.length; i++) {
            hp[i] = hash(prev, (p0 + i) * width + xs, xe - xs);
            plainP[i] = plain(prev, (p0 + i) * width + xs, xe - xs);
            if (!plainP[i]) unique.merge(hp[i], p0 + i, (a, b) -> -1);
        }
        int[] votes = new int[2 * MAX_SHIFT + 1];
        for (int i = 0; i < rows; i++) {
            if (plainC[i]) continue;
            Integer yp = unique.get(hc[i]);
            if (yp != null && yp >= 0 && yp != by0 + i) votes[yp - (by0 + i) + MAX_SHIFT]++;
        }
        int bestIdx = 0;
        for (int i = 1; i < votes.length; i++) {
            if (votes[i] > votes[bestIdx]) bestIdx = i;
        }
        if (votes[bestIdx] < MIN_COPY_ROWS / 2) return null;
        int dy = bestIdx - MAX_SHIFT;
        int bestFrom = 0, bestLen = 0, from = -1;
        for (int i = 0; i <= rows; i++) {
            int src = by0 + i + dy;
            boolean match = i < rows && src >= p0 && src < p1 && hc[i] == hp[src - p0];
            if (match && from < 0) from = i;
            if (!match && from >= 0) {
                if (i - from > bestLen) {
                    bestLen = i - from;
                    bestFrom = from;
                }
                from = -1;
            }
        }
        return bestLen < MIN_COPY_ROWS ? null : new int[]{dy, by0 + bestFrom, by0 + bestFrom + bestLen};
    }

    private static long hash(int[] a, int off, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = off; i < off + len; i++) {
            h = (h ^ a[i]) * 0x100000001b3L;
        }
        return h;
    }

    private static boolean plain(int[] a, int off, int len) {
        for (int i = off + 1; i < off + len; i++) {
            if (a[i] != a[off]) return false;
        }
        return true;
    }

    private int tileW(int tx) {
        return Math.min(TILE, width - tx * TILE);
    }

    private int tileH(int ty) {
        return Math.min(TILE, height - ty * TILE);
    }

    private static int pack(int x0, int y0, int x1, int y1) {
        return x0 | y0 << 6 | x1 << 12 | y1 << 18;
    }

    private void merge(int i, int box) {
        int o = dirty[i];
        if (o < 0) {
            dirty[i] = box;
            return;
        }
        dirty[i] = pack(Math.min(o & 63, box & 63), Math.min((o >> 6) & 63, (box >> 6) & 63),
                Math.max((o >> 12) & 63, (box >> 12) & 63), Math.max((o >> 18) & 63, (box >> 18) & 63));
    }
}
