/* Copyright 2011 Google Inc.
 *
 *Licensed under the Apache License, Version 2.0 (the "License");
 *you may not use this file except in compliance with the License.
 *You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *Unless required by applicable law or agreed to in writing, software
 *distributed under the License is distributed on an "AS IS" BASIS,
 *WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *See the License for the specific language governing permissions and
 *limitations under the License.
 *
 * @author Stephen Uhler
 *
 * 2014 Eddy Xiao <bewantbe@gmail.com>
 * GUI extensively modified.
 * Add some naive auto refresh rate control logic.
 *
 * 2026 laplaces-agent
 * Spectrum / Both / Waterfall modes sharing one frequency axis,
 * vsync-paced redraw for the scrolling waterfall.
 */

package org.woheller69.audio_analyzer_for_android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

/**
 * Custom view that draws the spectrum, the waterfall, or both stacked (spectrum on top).
 * The frequency axis is horizontal and shared by both plots; vertical gestures only act on
 * the spectrum's dB axis.
 */

public class AnalyzerGraphic extends View {
    private final String TAG = "AnalyzerGraphic:";
    static final double minDB = -144f;    // hard lower bound for dB
    static final double maxDB = 12f;      // hard upper bound for dB
    static final int VIEW_RANGE_DATA_LENGTH = 6;

    enum PlotMode { SPECTRUM, SPLIT, WATERFALL }

    SpectrumPlot  spectrumPlot;
    WaterfallPlot waterfallPlot;
    AnalyzerParameters analyzerParamCache;

    private PlotMode showMode = PlotMode.SPLIT;
    private float splitRatio = 0.4f;          // share of the height given to the spectrum in SPLIT
    private double xZoom = 1, xShift = 0;     // frequency axis, shared
    private double yZoom = 1, yShift = 0;     // spectrum dB axis
    private int canvasWidth, canvasHeight;
    private int spectrumHeight, waterfallTop;
    private boolean yGestureOnSpectrum = true;
    private double freq_lower_bound_for_log = 0;
    private double[] savedDBSpectrum = new double[0];
    private double cursorFreq = 0;
    private volatile boolean paused = false;
    private final float dpRatio;
    private final Paint dividerPaint = new Paint();
    private final Paint gripPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF gripRect = new RectF();

    private long frameTimeNanos = 0;
    private boolean framePosted = false;
    private Choreographer.FrameCallback frameCallback;

    public AnalyzerGraphic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        dpRatio = context.getResources().getDisplayMetrics().density;
        setup(context);
    }

    public AnalyzerGraphic(Context context, AttributeSet attrs) {
        super(context, attrs);
        dpRatio = context.getResources().getDisplayMetrics().density;
        setup(context);
    }

    public AnalyzerGraphic(Context context) {
        super(context);
        dpRatio = context.getResources().getDisplayMetrics().density;
        setup(context);
    }

    private void setup(Context context) {
        spectrumPlot  = new SpectrumPlot(context);
        waterfallPlot = new WaterfallPlot(context);
        spectrumPlot.axisY.vLowerBound = Float.parseFloat(context.getString(R.string.max_DB_range));
        dividerPaint.setColor(Color.rgb(40, 40, 40));
        gripPaint.setColor(Color.GRAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            frameCallback = t -> {
                framePosted = false;
                frameTimeNanos = t;
                invalidate();
            };
        }
    }

    // ---- layout of the two plots inside this view ----

    private void layoutPlots() {
        if (canvasWidth <= 0 || canvasHeight <= 0) return;
        int divider = Math.round(12 * dpRatio);
        switch (showMode) {
            case SPECTRUM:
                spectrumHeight = canvasHeight;
                waterfallTop = canvasHeight;
                break;
            case WATERFALL:
                spectrumHeight = 0;
                waterfallTop = 0;
                break;
            default:
                spectrumHeight = Math.round((canvasHeight - divider) * splitRatio);
                waterfallTop = spectrumHeight + divider;
        }
        // The spectrum's frequency axis is the reference for the waterfall, so size it even when hidden.
        spectrumPlot.setCanvas(canvasWidth, spectrumHeight > 0 ? spectrumHeight : canvasHeight, null);
        syncFreqAxis();
    }

    // The spectrum's frequency axis is the reference; the waterfall mirrors it.
    private void syncFreqAxis() {
        ScreenPhysicalMapping a = spectrumPlot.axisX;
        ScreenPhysicalMapping b = waterfallPlot.axisFreq;
        b.nCanvasPixel = a.nCanvasPixel;
        if (b.mapType != a.mapType) b.mapType = a.mapType;
        if (b.vLowerBound != a.vLowerBound || b.vUpperBound != a.vUpperBound) {
            b.vLowerBound = a.vLowerBound;
            b.vUpperBound = a.vUpperBound;
        }
        b.setZoomShift(a.getZoom(), a.getShift());
        xZoom  = a.getZoom();
        xShift = a.getShift();
    }

    void setShowMode(PlotMode mode) {
        showMode = mode;
        layoutPlots();
        invalidate();
    }

    PlotMode getShowMode() { return showMode; }

    void setSplitRatio(float r) {
        splitRatio = Math.max(0.12f, Math.min(0.85f, r));
        layoutPlots();
        invalidate();
    }

    float getSplitRatio() { return splitRatio; }

    /** True if y (view coordinates) is on the handle between spectrum and waterfall. */
    boolean isOnDivider(float y) {
        if (showMode != PlotMode.SPLIT) return false;
        float mid = (spectrumHeight + waterfallTop) / 2f;
        return Math.abs(y - mid) < 20 * dpRatio;
    }

    /** Divider position -> split ratio, for dragging. */
    float splitRatioFromY(float y) {
        int divider = waterfallTop - spectrumHeight;
        return (y - divider / 2f) / Math.max(1, canvasHeight - divider);
    }

    // ---- axes ----

    void setupAxes(AnalyzerParameters analyzerParam) {
        int sampleRate = analyzerParam.sampleRate;
        int fftLen     = analyzerParam.fftLen;

        freq_lower_bound_for_log = (double) sampleRate / fftLen;
        double freq_lower_bound_local = 0;
        if (spectrumPlot.axisX.mapType == ScreenPhysicalMapping.Type.LOG) {
            freq_lower_bound_local = freq_lower_bound_for_log;
        }
        double[] axisBounds = new double[]{freq_lower_bound_local, 0.0, sampleRate / 2.0, spectrumPlot.axisY.vUpperBound};
        spectrumPlot.setCanvas(canvasWidth, spectrumHeight > 0 ? spectrumHeight : canvasHeight, axisBounds);
        syncFreqAxis();
        analyzerParamCache = analyzerParam;
    }

    // Call this when settings changed.
    void setupPlot(AnalyzerParameters analyzerParam) {
        setupAxes(analyzerParam);
        waterfallPlot.setup(analyzerParam);
    }

    void setAxisModeLinear(String mode) {
        ScreenPhysicalMapping.Type mapType;
        GridLabel.Type gridType;
        if (mode.equals("linear")) {
            mapType = ScreenPhysicalMapping.Type.LINEAR;
            gridType = GridLabel.Type.FREQ;
        } else {
            mapType = ScreenPhysicalMapping.Type.LOG;
            gridType = mode.equals("note") ? GridLabel.Type.FREQ_NOTE : GridLabel.Type.FREQ_LOG;
        }
        spectrumPlot.setFreqAxisMode(mapType, freq_lower_bound_for_log, gridType);
        waterfallPlot.setFreqGridType(gridType);
        syncFreqAxis();
        invalidate();
    }

    double[] setViewRange(double[] _ranges, double[] rangesDefault) {
        // See getViewPhysicalRange() for ranges[]
        if (_ranges.length < VIEW_RANGE_DATA_LENGTH) {
            Log.i(TAG, "setViewRange(): invalid input.");
            return null;
        }
        double[] ranges = new double[VIEW_RANGE_DATA_LENGTH];
        System.arraycopy(_ranges, 0, ranges, 0, VIEW_RANGE_DATA_LENGTH);

        if (rangesDefault != null) {
            if (rangesDefault.length != 2 * VIEW_RANGE_DATA_LENGTH) {
                Log.i(TAG, "setViewRange(): invalid input.");
                return null;
            }
            for (int i = 0; i < 6; i += 2) {
                if (ranges[i  ] > ranges[i+1]) {                     // order reversed
                    double t = ranges[i]; ranges[i] = ranges[i+1]; ranges[i+1] = t;
                }
                if (ranges[i  ] < rangesDefault[i+6]) ranges[i  ] = rangesDefault[i+6];  // lower  than lower bound
                if (ranges[i+1] < rangesDefault[i+6]) ranges[i+1] = rangesDefault[i+7];  // all lower  than lower bound?
                if (ranges[i  ] > rangesDefault[i+7]) ranges[i  ] = rangesDefault[i+6];  // all higher than upper bound?
                if (ranges[i+1] > rangesDefault[i+7]) ranges[i+1] = rangesDefault[i+7];  // higher than upper bound
                if (ranges[i    ] == ranges[i + 1] || Double.isNaN(ranges[i]) || Double.isNaN(ranges[i + 1])) {  // invalid input value
                    ranges[i    ] = rangesDefault[i];
                    ranges[i + 1] = rangesDefault[i + 1];
                }
            }
        }

        spectrumPlot.axisX.setViewBounds(ranges[0], ranges[1]);
        spectrumPlot.axisY.setViewBounds(ranges[3], ranges[2]);  // reversed
        yZoom  = spectrumPlot.axisY.getZoom();
        yShift = spectrumPlot.axisY.getShift();
        syncFreqAxis();
        return ranges;
    }

    double[] getViewPhysicalRange() {
        double[] r = new double[12];
        // fL, fU, dBL dBU, time L, time U
        r[0] = spectrumPlot.axisX.vMinInView();
        r[1] = spectrumPlot.axisX.vMaxInView();
        r[2] = spectrumPlot.axisY.vMaxInView(); // reversed
        r[3] = spectrumPlot.axisY.vMinInView();
        // Limits of fL, fU, dBL dBU, time L, time U
        r[6] = spectrumPlot.axisX.vLowerBound;
        r[7] = spectrumPlot.axisX.vUpperBound;
        r[8] = AnalyzerGraphic.minDB;
        r[9] = AnalyzerGraphic.maxDB;
        for (int i = 6; i < r.length; i += 2) {
            if (r[i] > r[i+1]) {
                double t = r[i]; r[i] = r[i+1]; r[i+1] = t;
            }
        }
        return r;
    }

    private void updateAxisZoomShift() {
        spectrumPlot.setZooms(xZoom, xShift, yZoom, yShift);
        waterfallPlot.axisFreq.setZoomShift(xZoom, xShift);
    }

    // ---- appearance settings ----

    void setSmoothRender(boolean b)               { waterfallPlot.setSmooth(b); }
    void setShowTimeAxis(boolean b)               { waterfallPlot.setShowTimeAxis(b); }
    void setColorMap(String colorMapName)         { waterfallPlot.setColorMap(colorMapName); invalidate(); }
    void setWaterfallDbRange(double lo, double hi) { waterfallPlot.setDbRange(lo, hi); invalidate(); }
    void setSpectrumDBLowerBound(double b)        { spectrumPlot.axisY.vUpperBound = b; }
    public void setShowLines(boolean b)           { spectrumPlot.showLines = b; }

    void setPaused(boolean p) {
        paused = p;
        waterfallPlot.setPaused(p);
        if (!p) invalidate();
    }

    // ---- drawing ----

    private void scheduleFrame() {
        if (frameCallback != null) {
            if (!framePosted) {
                framePosted = true;
                Choreographer.getInstance().postFrameCallback(frameCallback);
            }
        } else {
            postInvalidateDelayed(16);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = frameTimeNanos != 0 ? frameTimeNanos : System.nanoTime();
        frameTimeNanos = 0;
        if (analyzerParamCache == null) return;

        if (showMode != PlotMode.WATERFALL && spectrumHeight > 0) {
            c.save();
            c.clipRect(0, 0, canvasWidth, spectrumHeight);
            spectrumPlot.addCalibCurve(analyzerParamCache.micGainDB, null, analyzerParamCache.calibName);
            spectrumPlot.drawSpectrumPlot(c, savedDBSpectrum);
            c.restore();
        }
        if (showMode != PlotMode.SPECTRUM) {
            if (showMode == PlotMode.SPLIT) {
                c.drawRect(0, spectrumHeight, canvasWidth, waterfallTop, dividerPaint);
                float mid = (spectrumHeight + waterfallTop) / 2f;
                float r = 1.5f * dpRatio;
                gripRect.set(canvasWidth / 2f - 16 * dpRatio, mid - r, canvasWidth / 2f + 16 * dpRatio, mid + r);
                c.drawRoundRect(gripRect, r, r, gripPaint);
            }
            waterfallPlot.cursorFreq = cursorFreq;
            waterfallPlot.draw(c, 0, waterfallTop, canvasWidth, canvasHeight - waterfallTop,
                    showMode == PlotMode.WATERFALL, now);
            if (!paused && waterfallPlot.hasData()) {
                scheduleFrame();
            }
        }
    }

    // All FFT data will enter this view through this interface
    // Will be called in another thread (SamplingLoop)
    public void saveSpectrum(double[] db) {
        synchronized (savedDBSpectrum) {
            if (savedDBSpectrum.length != db.length) {
                savedDBSpectrum = new double[db.length];
            }
            System.arraycopy(db, 0, savedDBSpectrum, 0, db.length);
        }
        waterfallPlot.addRow(db);
    }

    // ---- cursor ----

    private double spectrumDbAt(double f) {
        double[] s = savedDBSpectrum;
        if (s.length < 2 || analyzerParamCache == null) return 0;
        int i = (int) Math.round(f / (analyzerParamCache.sampleRate / 2.0) * (s.length - 1));
        if (i < 0 || i >= s.length) return 0;
        return s[i];
    }

    // x, y in screen coordinates; return true if the point is inside this view
    public boolean setCursor(float x, float y) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        x -= loc[0];
        y -= loc[1];
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return false;
        if (y < spectrumHeight) {
            spectrumPlot.setCursor(x, y);
            cursorFreq = spectrumPlot.cursorFreq;
        } else if (y >= waterfallTop) {
            cursorFreq = Math.max(0, waterfallPlot.axisFreq.vFromPixel(x));
            spectrumPlot.setCursorFreq(cursorFreq, spectrumDbAt(cursorFreq));
        }
        return true;
    }

    public void setCursorFreq(double f) {
        cursorFreq = f;
        spectrumPlot.setCursorFreq(f, spectrumDbAt(f));
        invalidate();
    }

    public double getCursorFreq() {
        return canvasWidth == 0 ? 0 : cursorFreq;
    }

    public double getCursorDB() {
        if (cursorFreq == 0) return 0;
        return showMode == PlotMode.WATERFALL ? spectrumDbAt(cursorFreq) : spectrumPlot.getCursorDB();
    }

    public void hideCursor() {
        cursorFreq = 0;
        spectrumPlot.hideCursor();
        invalidate();
    }

    // ---- gestures ----

    /** Call at the start of a gesture; y in view coordinates. */
    void beginGesture(float y) {
        yGestureOnSpectrum = showMode != PlotMode.WATERFALL && y < spectrumHeight;
    }

    public double getXZoom()  { return xZoom; }
    public double getYZoom()  { return yZoom; }
    public double getXShift() { return xShift; }
    public double getYShift() { return yShift; }
    public double getCanvasWidth()  { return canvasWidth; }
    public double getCanvasHeight() { return spectrumHeight > 0 ? spectrumHeight : canvasHeight; }

    private double clamp(double x, double min, double max) {
        return x > max ? max : (x < min ? min : x);
    }

    private double clampXShift(double offset) {
        return clamp(offset, 0f, 1 - 1 / xZoom);
    }

    private double clampYShift(double offset) {
        // limit view to minDB ~ maxDB, assume linear in dB scale
        return clamp(offset, (maxDB - spectrumPlot.axisY.vLowerBound) / spectrumPlot.axisY.diffVBounds(),
                (minDB - spectrumPlot.axisY.vLowerBound) / spectrumPlot.axisY.diffVBounds() - 1 / yZoom);
    }

    public void setXShift(double offset) {
        xShift = clampXShift(offset);
        updateAxisZoomShift();
    }

    public void setYShift(double offset) {
        if (!yGestureOnSpectrum) return;
        yShift = clampYShift(offset);
        updateAxisZoomShift();
    }

    public void resetViewScale() {
        xShift = 0;
        xZoom = 1;
        yShift = 0;
        yZoom = 1;
        updateAxisZoomShift();
    }

    private double xMidOld = 100;
    private double xDiffOld = 100;
    private double xZoomOld = 1;
    private double xShiftOld = 0;
    private double yMidOld = 100;
    private double yDiffOld = 100;
    private double yZoomOld = 1;
    private double yShiftOld = 0;

    // record the coordinate frame state when starting scaling
    public void setShiftScaleBegin(double x1, double y1, double x2, double y2) {
        xMidOld = (x1+x2)/2f;
        xDiffOld = Math.abs(x1-x2);
        xZoomOld  = xZoom;
        xShiftOld = xShift;
        yMidOld = (y1+y2)/2f;
        yDiffOld = Math.abs(y1-y2);
        yZoomOld  = yZoom;
        yShiftOld = yShift;
    }

    // Do the scaling according to the motion event getX() and getY() (getPointerCount()==2)
    // Coordinates are in view space.
    public void setShiftScale(double x1, double y1, double x2, double y2) {
        double limitXZoom = Math.abs(spectrumPlot.axisX.diffVBounds() / 200f);  // limit to 200 Hz a screen
        double limitYZoom = Math.abs(spectrumPlot.axisY.diffVBounds() / 6f);    // limit to 6 dB a screen
        if (canvasWidth*0.13f < xDiffOld) {  // if fingers are not very close in x direction, do scale in x direction
            xZoom  = clamp(xZoomOld * Math.abs(x1-x2)/xDiffOld, 1f, limitXZoom);
        }
        xShift = clampXShift(xShiftOld + (xMidOld/xZoomOld - (x1+x2)/2f/xZoom) / canvasWidth);
        if (yGestureOnSpectrum) {
            double h = getCanvasHeight();
            if (h*0.13f < yDiffOld) {
                yZoom  = clamp(yZoomOld * Math.abs(y1-y2)/yDiffOld, 1f, limitYZoom);
            }
            yShift = clampYShift(yShiftOld + (yMidOld/yZoomOld - (y1+y2)/2f/yZoom) / h);
        }
        updateAxisZoomShift();
    }

    private Ready readyCallback = null;      // callback to caller when rendering is complete

    public void setReady(Ready ready) {
        this.readyCallback = ready;
    }

    interface Ready {
        void ready();
    }

    @Override
    protected void onSizeChanged (int w, int h, int oldw, int oldh) {
        Log.i(TAG, "onSizeChanged(): canvas (" + oldw + "," + oldh + ") -> (" + w + "," + h + ")");
        this.canvasWidth = w;
        this.canvasHeight = h;
        layoutPlots();
        if (h > 0 && readyCallback != null) {
            readyCallback.ready();
        }
    }
}
