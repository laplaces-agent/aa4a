/* Copyright 2026 laplaces-agent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.woheller69.audio_analyzer_for_android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Arrays;

/**
 * Scrolling waterfall (spectrogram) with frequency along X and newest row on top.
 *
 * Rows arrive on the sampling thread and are stored as dB values in a ring buffer.
 * On the UI thread they are colorized into a ring of fixed-height bitmap tiles, so a new row
 * only dirties one small tile (cheap GPU upload) and scrolling is just drawing the tiles at a
 * new offset. A display clock that runs at the nominal row rate and slowly locks onto the
 * actual arrival of rows gives sub-row, per-frame smooth scrolling.
 *
 * Tiles are rendered in screen space for the frequency mapping that was current when they were
 * rendered. While the user zooms/pans they are stretched with an affine transform (exact for both
 * linear and log axes) and re-rendered once the view has been still for a moment.
 */
class WaterfallPlot {
    private static final int TILE_ROWS = 64;
    private static final int MAX_ROWS = 2048;           // rows kept for the visible history
    private static final long REBUILD_IDLE_NANOS = 150_000_000L;
    private static final short DB_FLOOR = -32767;       // stored value for -inf / NaN (0.01 dB units)

    final ScreenPhysicalMapping axisFreq = new ScreenPhysicalMapping(0, 0, 0, ScreenPhysicalMapping.Type.LINEAR);
    private final ScreenPhysicalMapping axisTime = new ScreenPhysicalMapping(0, 0, 1, ScreenPhysicalMapping.Type.LINEAR);
    private final GridLabel fqGridLabel;
    private final GridLabel tmGridLabel;
    private final float dpRatio;
    private final float gridDensity = 1 / 85f;

    // History, shared with the sampling thread. Guarded by lock.
    private final Object lock = new Object();
    private short[] store = new short[0];   // cap rows of (nFreq + 1) values, dB * 100
    private int stride = 0;
    private int cap = 0;
    private int decim = 1;                  // spectra merged (max) into one row
    private double[] accum = new double[0];
    private int accumCount = 0;
    private volatile long rowsIn = 0;
    private boolean geometryChanged = true;

    private int nFreq = 0;
    private double binHz = 1;
    private int nTime = 1;                  // rows spanning the full height
    private double rowRate = 1;             // rows per second
    private double duration = 30;

    // Tiles, UI thread only.
    private Bitmap[] tiles = new Bitmap[0];
    private int bmpW = 0;
    private long rowsRendered = 0;
    private ScreenPhysicalMapping bmpAxis = null;
    private boolean needRebuild = true;
    private int[] pixLo = new int[0];
    private int[] pixHi = new int[0];
    private float[] pixT = new float[0];
    private int[] tileBuf = new int[0];

    private int[] cmap = ColorMapArray.viridis;
    private double dbLow = -120, dbHigh = 0;
    private boolean smooth = true;
    private boolean showTimeAxis = true;

    // Display clock, in rows. UI thread only.
    private double pos = 0;
    private long lastFrameNanos = 0;
    private volatile boolean paused = false;
    private double lastViewMin = Double.NaN, lastViewMax = Double.NaN;
    private long lastViewChangeNanos = 0;

    double cursorFreq = 0;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint backgroundPaint = new Paint();
    private final Paint labelPaint;
    private final Paint gridPaint;
    private final Paint rulerBrightPaint;
    private final Paint cursorPaint;
    private final RectF dst = new RectF();

    WaterfallPlot(Context context) {
        dpRatio = context.getResources().getDisplayMetrics().density;
        fqGridLabel = new GridLabel(GridLabel.Type.FREQ, 1);
        tmGridLabel = new GridLabel(GridLabel.Type.TIME, 1);

        backgroundPaint.setColor(Color.BLACK);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.LTGRAY);
        labelPaint.setTextSize(12f * dpRatio);
        labelPaint.setTypeface(Typeface.MONOSPACE);
        labelPaint.setShadowLayer(2f * dpRatio, 0, 0, Color.BLACK);

        gridPaint = new Paint();
        gridPaint.setColor(Color.DKGRAY);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(0.6f * dpRatio);

        rulerBrightPaint = new Paint(gridPaint);
        rulerBrightPaint.setColor(Color.GRAY);
        rulerBrightPaint.setStrokeWidth(1);

        cursorPaint = new Paint();
        cursorPaint.setColor(Color.parseColor("#00CD00"));
        cursorPaint.setStrokeWidth(2);
    }

    // ---- configuration ----

    /** (Re)size the history for the given analysis parameters. Keeps history if the geometry is unchanged. */
    void setup(AnalyzerParameters p) {
        int nF = p.fftLen / 2;
        int hopSamples = Math.max(1, p.hopLen / p.zeroPadFac);
        double rawRate = (double) p.sampleRate / hopSamples / Math.max(1, p.nFFTAverage);
        double dur = p.spectrogramDuration;
        int d = Math.max(1, (int) Math.ceil(dur * rawRate / MAX_ROWS));
        double rate = rawRate / d;
        int nT = Math.max(2, (int) Math.round(dur * rate));
        int c = ((nT + TILE_ROWS - 1) / TILE_ROWS + 2) * TILE_ROWS;
        double hz = p.sampleRate / 2.0 / nF;
        synchronized (lock) {
            boolean same = nF == nFreq && c == cap && d == decim && hz == binHz && nT == nTime;
            nFreq = nF;
            stride = nF + 1;
            binHz = hz;
            decim = d;
            rowRate = rate;
            nTime = nT;
            duration = dur;
            if (!same) {
                cap = c;
                store = new short[cap * stride];
                Arrays.fill(store, DB_FLOOR);
                accum = new double[stride];
                accumCount = 0;
                rowsIn = 0;
                geometryChanged = true;
            }
        }
    }

    void setDbRange(double low, double high) {
        if (low == dbLow && high == dbHigh) return;
        dbLow = low;
        dbHigh = high;
        needRebuild = true;
    }

    double getDbLow()  { return dbLow; }
    double getDbHigh() { return dbHigh; }
    double getDuration() { return duration; }
    double getRowInterval() { return 1.0 / rowRate; }

    void setColorMap(String name) {
        cmap = ColorMapArray.selectColorMap(name);
        needRebuild = true;
    }

    void setSmooth(boolean b) {
        if (smooth == b) return;
        smooth = b;
        tilePaint.setFilterBitmap(b);
        bmpAxis = null;  // pixel map depends on it
    }

    void setShowTimeAxis(boolean b) { showTimeAxis = b; }

    void setPaused(boolean p) { paused = p; }

    boolean hasData() { return rowsIn > 0; }

    // ---- data in (sampling thread) ----

    void addRow(double[] db) {
        synchronized (lock) {
            if (db.length != stride || cap == 0) return;
            double[] src = db;
            if (decim > 1) {
                if (accumCount == 0) {
                    System.arraycopy(db, 0, accum, 0, stride);
                } else {
                    for (int i = 0; i < stride; i++) {
                        if (db[i] > accum[i]) accum[i] = db[i];
                    }
                }
                if (++accumCount < decim) return;
                accumCount = 0;
                src = accum;
            }
            int o = (int) (rowsIn % cap) * stride;
            for (int i = 0; i < stride; i++) {
                double v = src[i];
                short s;
                if (v > 327.67) s = Short.MAX_VALUE;
                else if (v > -327.67) s = (short) Math.round(v * 100);
                else s = DB_FLOOR;  // also catches NaN and -inf
                store[o + i] = s;
            }
            rowsIn++;
        }
    }

    /** Level at frequency f in the newest row, or NaN. */
    double dbAt(double f) {
        synchronized (lock) {
            if (rowsIn == 0 || stride == 0) return Double.NaN;
            int i = (int) Math.round(f / binHz);
            if (i < 0 || i >= stride) return Double.NaN;
            return store[(int) ((rowsIn - 1) % cap) * stride + i] / 100.0;
        }
    }

    /** Suggest a color range from the last ~1.5 s: floor just under the noise, ceiling at the peaks. */
    double[] suggestDbRange() {
        synchronized (lock) {
            long n = Math.min(rowsIn, Math.max(1, (long) (rowRate * 1.5)));
            if (n == 0) return null;
            int[] hist = new int[400];   // 1 dB buckets, -300 .. +100 dB
            long total = 0;
            for (long r = rowsIn - n; r < rowsIn; r++) {
                int o = (int) (r % cap) * stride;
                for (int i = 1; i < stride; i++) {
                    int b = store[o + i] / 100 + 300;
                    if (b < 0) b = 0;
                    if (b >= hist.length) b = hist.length - 1;
                    hist[b]++;
                    total++;
                }
            }
            double low = percentile(hist, total, 0.10) - 300;
            double high = percentile(hist, total, 0.9995) - 300;
            low = Math.floor((low - 5) / 5) * 5;
            high = Math.ceil((high + 5) / 5) * 5;
            if (high - low < 30) high = low + 30;
            return new double[]{low, high};
        }
    }

    private static int percentile(int[] hist, long total, double q) {
        long target = (long) (q * total);
        long acc = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc > target) return i;
        }
        return hist.length - 1;
    }

    // ---- rendering (UI thread) ----

    private boolean sameView(ScreenPhysicalMapping a, ScreenPhysicalMapping b) {
        return a.vMinInView() == b.vMinInView() && a.vMaxInView() == b.vMaxInView();
    }

    private boolean sameFrame(ScreenPhysicalMapping a, ScreenPhysicalMapping b) {
        return a.mapType == b.mapType && a.vLowerBound == b.vLowerBound
                && a.vUpperBound == b.vUpperBound && a.nCanvasPixel == b.nCanvasPixel;
    }

    private void ensureTiles(int w) {
        int nTiles = cap / TILE_ROWS;
        if (w == bmpW && tiles.length == nTiles && !geometryChanged) return;
        // Old tiles are left to the GC: a display list on the render thread may still reference them.
        tiles = new Bitmap[nTiles];
        for (int i = 0; i < nTiles; i++) {
            tiles[i] = Bitmap.createBitmap(w, TILE_ROWS, Bitmap.Config.ARGB_8888);
            tiles[i].eraseColor(Color.BLACK);
        }
        bmpW = w;
        tileBuf = new int[w * TILE_ROWS];
        geometryChanged = false;
        bmpAxis = null;
        rowsRendered = 0;
        pos = 0;
        lastFrameNanos = 0;
    }

    private void buildPixelMap() {
        bmpAxis = new ScreenPhysicalMapping(axisFreq);
        if (pixLo.length != bmpW) {
            pixLo = new int[bmpW];
            pixHi = new int[bmpW];
            pixT = new float[bmpW];
        }
        for (int x = 0; x < bmpW; x++) {
            double fL = bmpAxis.vFromPixel(x);
            double fR = bmpAxis.vFromPixel(x + 1);
            if (fL > fR) { double t = fL; fL = fR; fR = t; }
            // bins whose centers fall inside this pixel
            int lo = Math.max(1, (int) Math.ceil(fL / binHz));
            int hi = Math.min(nFreq, (int) Math.ceil(fR / binHz) - 1);
            if (hi >= lo) {
                pixLo[x] = lo;
                pixHi[x] = hi;
            } else {
                // pixel narrower than a bin: interpolate between neighbouring bins
                double b = Math.max(1, Math.min(nFreq, (fL + fR) / 2 / binHz));
                if (!smooth) b = Math.round(b);
                int i0 = Math.min((int) Math.floor(b), nFreq - 1);
                pixLo[x] = i0;
                pixHi[x] = i0 - 1;
                pixT[x] = (float) (b - i0);
            }
        }
    }

    private void renderRow(long r, int[] out, int outOff) {
        final short[] s = store;
        final int o = (int) (r % cap) * stride;
        final int n = cmap.length;
        final double hiS = dbHigh * 100;
        final double k = n / ((dbHigh - dbLow) * 100);
        for (int x = 0; x < bmpW; x++) {
            int lo = pixLo[x], hi = pixHi[x];
            double v;
            if (hi >= lo) {
                int m = s[o + lo];
                for (int i = lo + 1; i <= hi; i++) {
                    if (s[o + i] > m) m = s[o + i];
                }
                v = m;
            } else {
                int a = s[o + lo];
                v = a + (s[o + lo + 1] - a) * pixT[x];
            }
            int lev = (int) ((hiS - v) * k);
            if (lev < 0) lev = 0;
            else if (lev >= n) lev = n - 1;
            out[outOff + x] = cmap[lev] | 0xff000000;
        }
    }

    private int tileOf(long r) { return (int) ((r % cap) / TILE_ROWS); }
    private int yInTile(long r) { return TILE_ROWS - 1 - (int) (r % TILE_ROWS); }

    /** Colorize rows [from, to) into the tiles, one setPixels per tile. Caller holds lock. */
    private void renderRows(long from, long to) {
        long r = from;
        while (r < to) {
            long tileEnd = Math.min(to, (r / TILE_ROWS + 1) * TILE_ROWS);
            int yTop = yInTile(tileEnd - 1);
            for (long q = r; q < tileEnd; q++) {
                renderRow(q, tileBuf, yInTile(q) * bmpW);
            }
            tiles[tileOf(r)].setPixels(tileBuf, yTop * bmpW, bmpW, 0, yTop, bmpW, (int) (tileEnd - r));
            r = tileEnd;
        }
        rowsRendered = to;
    }

    private void advanceClock(long now, long avail) {
        double latency = Math.max(1.5, rowRate * 0.05);
        double target = avail - latency;
        if (lastFrameNanos == 0) {
            pos = Math.max(0, target);
        } else if (!paused) {
            double dt = Math.min(0.1, (now - lastFrameNanos) / 1e9);
            pos += dt * rowRate;
            double e = target - pos;
            if (Math.abs(e) > Math.max(8, rowRate * 0.5)) {
                pos = target;
            } else {
                pos += e * (1 - Math.exp(-dt / 0.3));
            }
        }
        if (pos > avail) pos = avail;
        if (pos < 0) pos = 0;
        lastFrameNanos = now;
    }

    /**
     * Draw into the rectangle (left, top, width, height) of c.
     * If freqLabels, a strip with frequency labels is reserved at the top.
     */
    void draw(Canvas c, float left, float top, int width, float height, boolean freqLabels, long frameNanos) {
        if (width <= 0 || height <= 0) return;
        float textHeight = labelPaint.getFontMetrics(null);
        float strip = freqLabels ? 1.4f * textHeight : 0;
        float plotTop = top + strip;
        float plotH = height - strip;

        c.drawRect(left, top, left + width, top + height, backgroundPaint);

        long avail;
        double historySeconds;
        // Held for the whole tile pass: setup() may resize the ring from another thread.
        synchronized (lock) {
            if (cap == 0) return;
            ensureTiles(width);
            avail = rowsIn;

            // Re-render everything when the colorization or the frequency frame changed,
            // or when a zoom/pan has come to rest.
            if (lastViewMin != axisFreq.vMinInView() || lastViewMax != axisFreq.vMaxInView()) {
                lastViewMin = axisFreq.vMinInView();
                lastViewMax = axisFreq.vMaxInView();
                lastViewChangeNanos = frameNanos;
            }
            boolean rebuild = needRebuild || bmpAxis == null || !sameFrame(bmpAxis, axisFreq)
                    || (!sameView(bmpAxis, axisFreq) && frameNanos - lastViewChangeNanos > REBUILD_IDLE_NANOS)
                    || avail - rowsRendered > nTime + TILE_ROWS;
            if (rebuild) {
                buildPixelMap();
                needRebuild = false;
                long keep = Math.min(avail, (long) nTime + TILE_ROWS);
                renderRows(avail - keep, avail);
            } else if (rowsRendered < avail) {
                renderRows(rowsRendered, avail);
            }
            advanceClock(frameNanos, avail);

            // x transform from the tiles' frequency mapping to the current one (affine in lin and log)
            double x0 = axisFreq.pixelFromV(bmpAxis.vFromPixel(0));
            double x1 = axisFreq.pixelFromV(bmpAxis.vFromPixel(bmpW));
            float sx = (float) ((x1 - x0) / bmpW);

            c.save();
            c.clipRect(left, plotTop, left + width, plotTop + plotH);
            c.translate(left + (float) x0, plotTop);
            c.scale(sx, 1);
            float rowH = plotH / nTime;
            long oldest = Math.max(0, Math.max(avail - cap + TILE_ROWS, (long) Math.floor(pos) - nTime - 1));
            long tileStart = ((long) Math.ceil(pos) - 1) / TILE_ROWS * TILE_ROWS;
            for (long R = tileStart; R + TILE_ROWS > oldest && R >= 0; R -= TILE_ROWS) {
                // row r occupies y in [(pos - r - 1) * rowH, (pos - r) * rowH]; tile bitmap row 0 is r = R + TILE_ROWS - 1
                float yTop = (float) ((pos - R - TILE_ROWS) * rowH);
                dst.set(0, yTop, bmpW, yTop + TILE_ROWS * rowH);
                c.drawBitmap(tiles[tileOf(R)], null, dst, tilePaint);
            }
            c.restore();
            historySeconds = nTime / rowRate;
        }

        if (cursorFreq > 0) {
            float cx = left + (float) axisFreq.pixelFromV(cursorFreq);
            c.drawLine(cx, plotTop, cx, plotTop + plotH, cursorPaint);
        }

        if (showTimeAxis) {
            axisTime.setNCanvasPixel(plotH);
            axisTime.setBounds(0, historySeconds);
            tmGridLabel.setDensity(plotH * gridDensity / dpRatio);
            tmGridLabel.updateGridLabels(0, historySeconds);
            AxisTickLabels.draw(c, axisTime, tmGridLabel, left, plotTop, 1, 1,
                    labelPaint, gridPaint, rulerBrightPaint);
        }
        if (freqLabels) {
            c.drawRect(left, top, left + width, plotTop, backgroundPaint);
            fqGridLabel.setDensity(axisFreq.nCanvasPixel * gridDensity / dpRatio);
            fqGridLabel.updateGridLabels(axisFreq.vMinInView(), axisFreq.vMaxInView());
            // labels sit just above the plot, ticks point into the plot
            AxisTickLabels.draw(c, axisFreq, fqGridLabel, left, plotTop, 0, -1,
                    labelPaint, gridPaint, rulerBrightPaint);
        }
    }

    void setFreqGridType(GridLabel.Type t) { fqGridLabel.setGridType(t); }
}
