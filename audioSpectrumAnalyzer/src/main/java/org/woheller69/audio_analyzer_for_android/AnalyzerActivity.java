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
 * Add spectrogram plot, smooth gesture view control and various settings.
 *
 * 2026 laplaces-agent
 * Main-screen analysis controls, Spectrum / Both / Waterfall view modes,
 * overflow menu in place of the action bar, layout swap on rotation.
 */

package org.woheller69.audio_analyzer_for_android;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GestureDetectorCompat;

/**
 * Audio "FFT" analyzer.
 * @author suhler@google.com (Stephen Uhler)
 */

public class AnalyzerActivity extends AppCompatActivity implements AnalyzerGraphic.Ready
{
    private static final String TAG="AnalyzerActivity:";

    static final double WF_DB_LOW_DEFAULT = -120;
    static final double WF_DB_HIGH_DEFAULT = -20;

    AnalyzerViews analyzerViews;
    ControlBar controlBar;
    SamplingLoop samplingThread = null;
    private RangeViewDialogC rangeViewDialogC;
    private GestureDetectorCompat mDetector;
    private SetCursorFreqDialog setCursorFreqDialog;

    private AnalyzerParameters analyzerParam = null;

    double dtRMS = 0;
    double dtRMSFromFT = 0;
    double maxAmpDB;
    double maxAmpFreq;
    double[] viewRangeArray = null;

    private boolean isLockViewRange = false;
    volatile boolean bSaveWav = false;
    volatile boolean isPaused = false;
    private boolean isFullscreen = false;
    private String freqScale = "log";
    private String colorMapName = "viridis";
    private double wfDbLow = WF_DB_LOW_DEFAULT, wfDbHigh = WF_DB_HIGH_DEFAULT;

    CalibrationLoad calibLoad = new CalibrationLoad();  // data for calibration of spectrum

    @Override
    public void onCreate(Bundle savedInstanceState) {
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        Log.i(TAG, " max runtime mem = " + maxMemory + "k");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Resources res = getResources();
        analyzerParam = new AnalyzerParameters(res);

        // Initialized preferences by default values
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        analyzerViews = new AnalyzerViews(this);
        loadPreferenceForView();
        controlBar = new ControlBar(this);
        setupActionRow();

        rangeViewDialogC = new RangeViewDialogC(this, analyzerViews.graphView);
        setCursorFreqDialog = new SetCursorFreqDialog(this, analyzerViews.graphView);

        mDetector = new GestureDetectorCompat(this, new AnalyzerGestureListener());
    }

    // Rotation is handled here (see configChanges) so the waterfall history survives it:
    // inflate the layout for the new orientation and move the existing graph view into it.
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AnalyzerGraphic graphView = analyzerViews.graphView;
        ((ViewGroup) graphView.getParent()).removeView(graphView);
        setContentView(R.layout.main);
        View placeholder = findViewById(R.id.plot);
        ViewGroup parent = (ViewGroup) placeholder.getParent();
        int index = parent.indexOfChild(placeholder);
        ViewGroup.LayoutParams lp = placeholder.getLayoutParams();
        parent.removeView(placeholder);
        parent.addView(graphView, index, lp);

        analyzerViews.bindViews();
        analyzerViews.enableSaveWavView(bSaveWav);
        analyzerViews.refreshResolutionLabel(analyzerParam);
        controlBar = new ControlBar(this);
        controlBar.refresh();
        setupActionRow();
        applyFullscreen();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();

        LoadPreferences();
        analyzerViews.graphView.setReady(this);
        analyzerViews.enableSaveWavView(bSaveWav);
        controlBar.refresh();

        // Used to prevent extra calling to restartSampling() (e.g. in LoadPreferences())
        bSamplingPreparation = true;

        // Start sampling
        restartSampling(analyzerParam);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause()");
        bSamplingPreparation = false;
        if (samplingThread != null) {
            samplingThread.finish();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putDouble("dtRMS",       dtRMS);
        savedInstanceState.putDouble("dtRMSFromFT", dtRMSFromFT);
        savedInstanceState.putDouble("maxAmpDB",    maxAmpDB);
        savedInstanceState.putDouble("maxAmpFreq",  maxAmpFreq);
        super.onSaveInstanceState(savedInstanceState);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        dtRMS       = savedInstanceState.getDouble("dtRMS");
        dtRMSFromFT = savedInstanceState.getDouble("dtRMSFromFT");
        maxAmpDB    = savedInstanceState.getDouble("maxAmpDB");
        maxAmpFreq  = savedInstanceState.getDouble("maxAmpFreq");
    }

    // ---- action row: run/pause, view mode, record, overflow menu ----

    private void setupActionRow() {
        findViewById(R.id.btn_run).setOnClickListener(v -> setPaused(!isPaused));
        findViewById(R.id.mode_spectrum).setOnClickListener(v -> setViewMode(AnalyzerGraphic.PlotMode.SPECTRUM));
        findViewById(R.id.mode_split).setOnClickListener(v -> setViewMode(AnalyzerGraphic.PlotMode.SPLIT));
        findViewById(R.id.mode_waterfall).setOnClickListener(v -> setViewMode(AnalyzerGraphic.PlotMode.WATERFALL));
        findViewById(R.id.btn_rec).setOnClickListener(v -> setRecording(!bSaveWav));
        findViewById(R.id.btn_menu).setOnClickListener(this::showOverflowMenu);
        refreshActionRow();
    }

    private void refreshActionRow() {
        AnalyzerGraphic.PlotMode m = analyzerViews.graphView.getShowMode();
        findViewById(R.id.mode_spectrum).setSelected(m == AnalyzerGraphic.PlotMode.SPECTRUM);
        findViewById(R.id.mode_split).setSelected(m == AnalyzerGraphic.PlotMode.SPLIT);
        findViewById(R.id.mode_waterfall).setSelected(m == AnalyzerGraphic.PlotMode.WATERFALL);
        ((ImageButton) findViewById(R.id.btn_run)).setImageResource(isPaused ? R.drawable.ic_play : R.drawable.ic_pause);
        findViewById(R.id.btn_rec).setSelected(bSaveWav);
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        popup.getMenuInflater().inflate(R.menu.info, menu);
        menu.findItem(R.id.fullscreen).setTitle(isFullscreen ? R.string.menu_fullscreen_exit : R.string.menu_fullscreen);
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    private void applyFullscreen() {
        int vis = isFullscreen ? View.GONE : View.VISIBLE;
        findViewById(R.id.data_bar).setVisibility(vis);
        findViewById(R.id.chip_scroll).setVisibility(vis);
    }

    static final int REQUEST_AUDIO_GET = 1;
    static final int REQUEST_CALIB_LOAD = 2;

    public void selectFile(int requestType) {
        // https://developer.android.com/guide/components/intents-common.html#Storage
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        if (requestType == REQUEST_AUDIO_GET) {
            intent.setType("audio/*");
        } else {
            intent.setType("*/*");
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, requestType);
        } else {
            Log.e(TAG, "No file chooser found!.");

            // Potentially direct the user to the Market with a Dialog
            Toast.makeText(this, "Please install a File Manager.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    public static String getMimeType(String url) {
        String type = null;
        String extension = MimeTypeMap.getFileExtensionFromUrl(url);
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type;
    }

    void fillFftCalibration(AnalyzerParameters _analyzerParam, CalibrationLoad _calibLoad) {
        if (_calibLoad.freq == null || _calibLoad.freq.length == 0 || _analyzerParam == null) {
            return;
        }
        double[] freqTick = new double[_analyzerParam.fftLen/2 + 1];
        for (int i = 0; i < freqTick.length; i++) {
            freqTick[i] = (double)i / _analyzerParam.fftLen * _analyzerParam.sampleRate;
        }
        _analyzerParam.micGainDB = AnalyzerUtil.interpLinear(_calibLoad.freq, _calibLoad.gain, freqTick);
        _analyzerParam.calibName = _calibLoad.name;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CALIB_LOAD && resultCode == RESULT_OK) {
            final Uri uri = data.getData();
            calibLoad.loadFile(uri, this);
            Log.w(TAG, "mime:" + getContentResolver().getType(uri));
            fillFftCalibration(analyzerParam, calibLoad);
        } else if (requestCode == REQUEST_AUDIO_GET) {
            Log.w(TAG, "requestCode == REQUEST_AUDIO_GET");
        }
    }

    // for pass audioSourceIDs and audioSourceNames to MyPreferences
    public final static String MYPREFERENCES_MSG_SOURCE_ID = "AnalyzerActivity.SOURCE_ID";
    public final static String MYPREFERENCES_MSG_SOURCE_NAME = "AnalyzerActivity.SOURCE_NAME";

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.i(TAG, "onOptionsItemSelected(): " + item.toString());
        int itemId = item.getItemId();
        if (itemId == R.id.fullscreen){
            isFullscreen = !isFullscreen;
            applyFullscreen();
            return true;
        } else if (itemId == R.id.screenshot){
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(AnalyzerActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(AnalyzerActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
            }
            ScreenCapture.ScreenCapture(this);
            return true;
        } else if (itemId == R.id.menu_manual){
            analyzerViews.showInstructions();
            return true;
        } else if (itemId == R.id.menu_settings) {
            Intent settings = new Intent(getBaseContext(), MyPreferences.class);
            settings.putExtra(MYPREFERENCES_MSG_SOURCE_ID, analyzerParam.audioSourceIDs);
            settings.putExtra(MYPREFERENCES_MSG_SOURCE_NAME, analyzerParam.audioSourceNames);
            startActivity(settings);
            return true;
        } else if (itemId == R.id.menu_test_recorder) {
            Intent int_info_rec = new Intent(this, InfoRecActivity.class);
            startActivity(int_info_rec);
            return true;
        } else if (itemId == R.id.menu_view_range) {
            rangeViewDialogC.ShowRangeViewDialog();
            return true;
        } else if (itemId == R.id.menu_github) {
            analyzerViews.showAbout();
            return true;
        } else if (itemId == R.id.menu_calibration) {
            selectFile(REQUEST_CALIB_LOAD);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    // ---- settings changed from the main screen ----

    AnalyzerParameters getAnalyzerParam() { return analyzerParam; }
    String getFreqScale()       { return freqScale; }
    String getColorMapName()    { return colorMapName; }
    double getWaterfallDbLow()  { return wfDbLow; }
    double getWaterfallDbHigh() { return wfDbHigh; }

    private SharedPreferences.Editor editPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(this).edit();
    }

    private void updateHop() {
        analyzerParam.hopLen = (int)(analyzerParam.fftLen*(1 - analyzerParam.overlapPercent/100) + 0.5);
    }

    private void settingsChanged(boolean restart) {
        if (restart) restartSampling(analyzerParam);
        analyzerViews.refreshResolutionLabel(analyzerParam);
        controlBar.refresh();
    }

    void setSampleRate(int sr) {
        analyzerParam.sampleRate = sr;
        updateHop();
        editPrefs().putInt("button_sample_rate", sr).apply();
        fillFftCalibration(analyzerParam, calibLoad);
        settingsChanged(true);
    }

    void setFftLen(int n) {
        analyzerParam.fftLen = n;
        updateHop();
        editPrefs().putInt("button_fftlen", n).apply();
        fillFftCalibration(analyzerParam, calibLoad);
        settingsChanged(true);
    }

    void setOverlap(double percent) {
        analyzerParam.overlapPercent = percent;
        updateHop();
        editPrefs().putString("fft_overlap_percent", Double.toString(percent)).apply();
        settingsChanged(true);
    }

    void setWindow(String name) {
        analyzerParam.wndFuncName = name;
        editPrefs().putString("windowFunction", name).apply();
        settingsChanged(true);
    }

    void setAverage(int n) {
        analyzerParam.nFFTAverage = n;
        editPrefs().putInt("button_average", n).apply();
        settingsChanged(true);
    }

    void setDuration(double seconds) {
        analyzerParam.spectrogramDuration = seconds;
        editPrefs().putString("spectrogramDuration", Double.toString(seconds)).apply();
        settingsChanged(true);
    }

    void setFreqScale(String mode) {
        freqScale = mode;
        analyzerViews.graphView.setAxisModeLinear(mode);
        editPrefs().putString("freq_scaling_mode", mode).apply();
        settingsChanged(false);
    }

    void setColorMap(String name) {
        colorMapName = name;
        analyzerViews.graphView.setColorMap(name);
        editPrefs().putString("spectrogramColorMap", name).apply();
        settingsChanged(false);
    }

    void setWaterfallDbRange(double lo, double hi) {
        wfDbLow = lo;
        wfDbHigh = hi;
        analyzerViews.graphView.setWaterfallDbRange(lo, hi);
        editPrefs().putFloat("waterfall_db_low", (float) lo).putFloat("waterfall_db_high", (float) hi).apply();
    }

    void setAWeighting(boolean b) {
        analyzerParam.isAWeighting = b;
        if (samplingThread != null) {
            samplingThread.setAWeighting(b);
        }
        editPrefs().putBoolean("dbA", b).apply();
        settingsChanged(false);
    }

    void setViewMode(AnalyzerGraphic.PlotMode mode) {
        analyzerViews.graphView.setShowMode(mode);
        editPrefs().putString("view_mode", mode.name()).apply();
        refreshActionRow();
    }

    void setPaused(boolean pause) {
        isPaused = pause;
        if (samplingThread != null) {
            samplingThread.setPause(pause);
        }
        analyzerViews.graphView.setPaused(pause);
        refreshActionRow();
    }

    private void setRecording(boolean rec) {
        bSaveWav = rec;
        analyzerViews.enableSaveWavView(bSaveWav);
        refreshActionRow();
        restartSampling(analyzerParam);
    }

    // Load preferences for Views
    // When this function is called, the SamplingLoop must not running in the meanwhile.
    private void loadPreferenceForView() {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        analyzerParam.sampleRate   = sharedPref.getInt("button_sample_rate", 48000);
        analyzerParam.fftLen       = sharedPref.getInt("button_fftlen",      4096);
        analyzerParam.nFFTAverage  = sharedPref.getInt("button_average",        1);
        analyzerParam.isAWeighting = sharedPref.getBoolean("dbA", false);

        AnalyzerGraphic.PlotMode mode = AnalyzerGraphic.PlotMode.SPLIT;
        try {
            mode = AnalyzerGraphic.PlotMode.valueOf(sharedPref.getString("view_mode", mode.name()));
        } catch (IllegalArgumentException ignored) {
        }
        AnalyzerGraphic graphView = analyzerViews.graphView;
        graphView.setSplitRatio(sharedPref.getFloat("split_ratio", 0.4f));
        graphView.setShowMode(mode);

        Log.i(TAG, "loadPreferenceForView():"+
                "\n  sampleRate  = " + analyzerParam.sampleRate +
                "\n  fftLen      = " + analyzerParam.fftLen +
                "\n  nFFTAverage = " + analyzerParam.nFFTAverage);
    }

    private void LoadPreferences() {
        // Load preferences for recorder and views, beside loadPreferenceForView()
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        boolean keepScreenOn = sharedPref.getBoolean("keepScreenOn", true);
        if (keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        analyzerParam.audioSourceId = Integer.parseInt(sharedPref.getString("audioSource", Integer.toString(analyzerParam.RECORDER_AGC_OFF)));
        analyzerParam.wndFuncName = sharedPref.getString("windowFunction", getString(R.string.wnd_func_default));
        analyzerParam.spectrogramDuration = Double.parseDouble(sharedPref.getString("spectrogramDuration",
                getString(R.string.spectrogram_duration_default)));
        analyzerParam.overlapPercent = Double.parseDouble(sharedPref.getString("fft_overlap_percent",
                getString(R.string.fft_overlap_percent_default)));
        analyzerParam.zeroPadFac = Integer.parseInt(sharedPref.getString("zeroPadding",getString(R.string.zeropadding_default))); //Zero padding factor
        updateHop();

        // Settings of graph view
        AnalyzerGraphic graphView = analyzerViews.graphView;
        graphView.setShowLines(sharedPref.getBoolean("showLines", false));
        graphView.setSpectrumDBLowerBound(
                Float.parseFloat(sharedPref.getString("spectrumRange", Double.toString(AnalyzerGraphic.minDB))));

        graphView.setShowTimeAxis (sharedPref.getBoolean("spectrogramTimeAxis", true));
        graphView.setSmoothRender (sharedPref.getBoolean("spectrogramSmoothRender", true));
        colorMapName = sharedPref.getString("spectrogramColorMap", getString(R.string.dbColorMap_default));
        graphView.setColorMap(colorMapName);
        wfDbLow  = sharedPref.getFloat("waterfall_db_low",  (float) WF_DB_LOW_DEFAULT);
        wfDbHigh = sharedPref.getFloat("waterfall_db_high", (float) WF_DB_HIGH_DEFAULT);
        graphView.setWaterfallDbRange(wfDbLow, wfDbHigh);

        freqScale = sharedPref.getString("freq_scaling_mode", "log");
        graphView.setAxisModeLinear(freqScale);
        graphView.setPaused(isPaused);

        analyzerViews.bWarnOverrun = sharedPref.getBoolean("warnOverrun", false);
        analyzerViews.refreshResolutionLabel(analyzerParam);

        // Get view range setting
        boolean isLock = sharedPref.getBoolean("view_range_lock", false);
        if (isLock) {
            Log.i(TAG, "LoadPreferences(): isLocked");
            // Set view range and stick to measure mode
            double[] rr = new double[AnalyzerGraphic.VIEW_RANGE_DATA_LENGTH];
            for (int i = 0; i < rr.length; i++) {
                rr[i] = AnalyzerUtil.getDouble(sharedPref, "view_range_rr_" + i, 0.0/0.0);
                if (Double.isNaN(rr[i])) {  // not properly initialized
                    Log.w(TAG, "LoadPreferences(): rr is not properly initialized");
                    rr = null;
                    break;
                }
            }
            if (rr != null) {
                viewRangeArray = rr;
            }
            stickToMeasureMode();
        } else {
            stickToMeasureModeCancel();
        }
    }

    // With the view range locked there is nothing to pan, so a one-finger drag anywhere moves the cursor.
    void stickToMeasureMode() {
        isLockViewRange = true;
    }

    void stickToMeasureModeCancel() {
        isLockViewRange = false;
    }

    private boolean isInGraphView(float x, float y) {
        analyzerViews.graphView.getLocationInWindow(windowLocation);
        return x >= windowLocation[0] && y >= windowLocation[1] &&
                x < windowLocation[0] + analyzerViews.graphView.getWidth() &&
                y < windowLocation[1] + analyzerViews.graphView.getHeight();
    }

    public void showCursorFreqPopup(View view) {
        setCursorFreqDialog.ShowSetCursorFreqDialog();
    }


    public void openPrivacyPolicy(MenuItem item) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/laplaces-agent/aa4a#privacy")));
    }

    /**
     * Gesture Listener for graphView (and possibly other views)
     * How to attach these events to the graphView?
     * @author xyy
     */
    private class AnalyzerGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {  // enter here when down action happen
            flyingMoveHandler.removeCallbacks(flyingMoveRunnable);
            return true;
        }

        // Long press toggles the cursor. A newly placed cursor stays grabbed, so the same
        // finger can keep going and drag it without lifting.
        @Override
        public void onLongPress(MotionEvent event) {
            if (isDraggingDivider || !isInGraphView(event.getX(0), event.getY(0))) return;
            AnalyzerGraphic graphView = analyzerViews.graphView;
            if (graphView.hasCursor()) {
                graphView.hideCursor();
                isDraggingCursor = false;
            } else {
                graphView.setCursor(event.getX(0), event.getY(0));
                cursorGrabDx = 0;
                isDraggingCursor = true;
            }
            graphView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            analyzerViews.invalidateGraphView();
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            if (!isDraggingCursor) {
                scaleEvent(event);            // ends scale mode
                analyzerViews.graphView.resetViewScale();
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                               float velocityX, float velocityY) {
            if (isDraggingCursor) {
                return true;
            }
            // Fly the canvas in graphView when in scale mode
            shiftingVelocity = Math.sqrt(velocityX*velocityX + velocityY*velocityY);
            shiftingComponentX = velocityX / shiftingVelocity;
            shiftingComponentY = velocityY / shiftingVelocity;
            float DPRatio = getResources().getDisplayMetrics().density;
            flyAcceleration = 1200 * DPRatio;
            timeFlingStart = SystemClock.uptimeMillis();
            flyingMoveHandler.postDelayed(flyingMoveRunnable, 0);
            return true;
        }

        Handler flyingMoveHandler = new Handler();
        long timeFlingStart;                     // Prevent from running forever
        double flyDt = 1/20.;                     // delta t of refresh
        double shiftingVelocity;                  // fling velocity
        double shiftingComponentX;                // fling direction x
        double shiftingComponentY;                // fling direction y
        double flyAcceleration = 1200.;           // damping acceleration of fling, pixels/second^2

        Runnable flyingMoveRunnable = new Runnable() {
            @Override
            public void run() {
                double shiftingVelocityNew = shiftingVelocity - flyAcceleration*flyDt;
                if (shiftingVelocityNew < 0) shiftingVelocityNew = 0;
                // Number of pixels that should move in this time step
                double shiftingPixel = (shiftingVelocityNew + shiftingVelocity)/2 * flyDt;
                shiftingVelocity = shiftingVelocityNew;
                if (shiftingVelocity > 0f
                        && SystemClock.uptimeMillis() - timeFlingStart < 10000) {
                    AnalyzerGraphic graphView = analyzerViews.graphView;
                    graphView.setXShift(graphView.getXShift() - shiftingComponentX*shiftingPixel / graphView.getCanvasWidth() / graphView.getXZoom());
                    graphView.setYShift(graphView.getYShift() - shiftingComponentY*shiftingPixel / graphView.getCanvasHeight() / graphView.getYZoom());
                    analyzerViews.invalidateGraphView();
                    flyingMoveHandler.postDelayed(flyingMoveRunnable, (int)(1000*flyDt));
                }
            }
        };
    }

    private boolean isDraggingDivider = false;
    private boolean isDraggingCursor = false;
    private float cursorGrabDx = 0;   // cursor line x minus finger x, so a grabbed cursor doesn't jump

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        AnalyzerGraphic graphView = analyzerViews.graphView;
        boolean inGraph = isInGraphView(event.getX(0), event.getY(0));  // also updates windowLocation
        float viewX = event.getX(0) - windowLocation[0];
        float viewY = event.getY(0) - windowLocation[1];
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            isDraggingDivider = inGraph && graphView.isOnDivider(viewY);
            isDraggingCursor = !isDraggingDivider && inGraph && graphView.hasCursor()
                    && (isLockViewRange || graphView.isNearCursor(viewX));
            cursorGrabDx = isDraggingCursor && !isLockViewRange ? graphView.cursorPixelX() - viewX : 0;
            graphView.beginGesture(viewY);
        }
        if (isDraggingDivider) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                graphView.setSplitRatio(graphView.splitRatioFromY(viewY));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                isDraggingDivider = false;
                editPrefs().putFloat("split_ratio", graphView.getSplitRatio()).apply();
            }
            return true;
        }
        if (inGraph) {
            this.mDetector.onTouchEvent(event);
            // A second finger turns a cursor drag into a pinch, unless the view range is locked.
            if (isDraggingCursor && event.getPointerCount() > 1 && !isLockViewRange) {
                isDraggingCursor = false;
            }
            if (isDraggingCursor) {
                if (event.getPointerCount() == 1) {
                    graphView.setCursor(event.getX(0) + cursorGrabDx, event.getY(0));
                }
            } else if (!isLockViewRange) {
                scaleEvent(event);
            }
            analyzerViews.invalidateGraphView();
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isDraggingCursor = false;
        }
        return super.onTouchEvent(event);
    }

    /**
     *  Manage scroll and zoom
     */
    final private static double INIT = Double.MIN_VALUE;
    private boolean isPinching = false;
    private double xShift0 = INIT, yShift0 = INIT;
    private double x0, y0;
    private int[] windowLocation = new int[2];

    private void scaleEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_MOVE) {
            xShift0 = INIT;
            yShift0 = INIT;
            isPinching = false;
            return;
        }
        AnalyzerGraphic graphView = analyzerViews.graphView;
        graphView.getLocationInWindow(windowLocation);
        switch (event.getPointerCount()) {
            case 2 :
                float x1 = event.getX(0) - windowLocation[0], y1 = event.getY(0) - windowLocation[1];
                float x2 = event.getX(1) - windowLocation[0], y2 = event.getY(1) - windowLocation[1];
                if (isPinching)  {
                    graphView.setShiftScale(x1, y1, x2, y2);
                } else {
                    graphView.setShiftScaleBegin(x1, y1, x2, y2);
                }
                isPinching = true;
                break;
            case 1:
                float x = event.getX(0);
                float y = event.getY(0);
                if (isPinching || xShift0 == INIT) {
                    xShift0 = graphView.getXShift();
                    x0 = x;
                    yShift0 = graphView.getYShift();
                    y0 = y;
                } else {
                    // when close to the axis, scroll that axis only
                    if (x0 < windowLocation[0] + 50) {
                        graphView.setYShift(yShift0 + (y0 - y) / graphView.getCanvasHeight() / graphView.getYZoom());
                    } else if (y0 < windowLocation[1] + 50) {
                        graphView.setXShift(xShift0 + (x0 - x) / graphView.getCanvasWidth() / graphView.getXZoom());
                    } else {
                        graphView.setXShift(xShift0 + (x0 - x) / graphView.getCanvasWidth() / graphView.getXZoom());
                        graphView.setYShift(yShift0 + (y0 - y) / graphView.getCanvasHeight() / graphView.getYZoom());
                    }
                }
                isPinching = false;
                break;
            default:
                Log.i(TAG, "Invalid touch count");
                break;
        }
    }

    private final int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;  // just a number
    private final int MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE = 2;
    Thread graphInit;
    private boolean bSamplingPreparation = false;

    private void restartSampling(final AnalyzerParameters _analyzerParam) {
        // Stop previous sampler if any.
        if (samplingThread != null) {
            samplingThread.finish();
            try {
                samplingThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            samplingThread = null;
        }

        if (viewRangeArray != null) {
            analyzerViews.graphView.setupAxes(analyzerParam);
            double[] rangeDefault = analyzerViews.graphView.getViewPhysicalRange();
            Log.i(TAG, "restartSampling(): setViewRange: " + viewRangeArray[0] + " ~ " + viewRangeArray[1]);
            analyzerViews.graphView.setViewRange(viewRangeArray, rangeDefault);
            if (! isLockViewRange) viewRangeArray = null;  // do not conserve
        }

        // Set the view for incoming data
        graphInit = new Thread(new Runnable() {
            public void run() {
                analyzerViews.setupView(_analyzerParam);
            }
        });
        graphInit.start();

        // Check and request permissions
        if (! checkAndRequestPermissions())
            return;

        if (! bSamplingPreparation)
            return;

        // Start sampling
        samplingThread = new SamplingLoop(this, _analyzerParam);
        samplingThread.start();
    }

    // For preventing infinity loop: onResume() -> requestPermissions() -> onRequestPermissionsResult() -> onResume()
    private int count_permission_request = 0;

    // Test and try to gain permissions.
    // Return true if it is OK to proceed.
    // Ref.
    //   https://developer.android.com/training/permissions/requesting.html
    //   https://developer.android.com/guide/topics/permissions/requesting.html
    private boolean checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(AnalyzerActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permission RECORD_AUDIO denied. Trying  to request...");
            if (count_permission_request < 3) {
                ActivityCompat.requestPermissions(AnalyzerActivity.this,
                        new String[]{Manifest.permission.RECORD_AUDIO},
                        MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
                count_permission_request++;
            }
            return false;
        }
        if (bSaveWav) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(AnalyzerActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                Log.w(TAG, "Permission WRITE_EXTERNAL_STORAGE denied. Trying  to request...");
                bSaveWav = false;
                analyzerViews.enableSaveWavView(bSaveWav);
                refreshActionRow();
                ActivityCompat.requestPermissions(AnalyzerActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE);
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "RECORD_AUDIO Permission granted by user.");
                    count_permission_request = 0; //once permission is granted reset counter. Just in case Android removes the permission
                } else {
                    Log.w(TAG, "RECORD_AUDIO Permission denied by user.");
                    this.runOnUiThread(() -> {
                        Context context = getApplicationContext();
                        String text = getString(R.string.permission_microphone_denied);
                        Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
                        toast.show();
                    });
                }
                break;
            }
            case MY_PERMISSIONS_REQUEST_WRITE_EXTERNAL_STORAGE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "WRITE_EXTERNAL_STORAGE Permission granted by user.");
                    if (! bSaveWav) {
                        runOnUiThread(() -> {
                            bSaveWav = true;
                            analyzerViews.enableSaveWavView(bSaveWav);
                            refreshActionRow();
                        });
                    }
                } else {
                    Log.w(TAG, "WRITE_EXTERNAL_STORAGE Permission denied by user.");
                    this.runOnUiThread(() -> {
                        Context context = getApplicationContext();
                        String text = getString(R.string.permission_storage_denied);
                        Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
                        toast.show();
                    });
                }
                break;
            }
        }
        // Then onResume() will be called.
    }

    /**
     * The graph view size has been determined - update the labels accordingly.
     */
    @Override
    public void ready() {
        analyzerViews.invalidateGraphView();
    }
}
