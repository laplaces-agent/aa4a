/* Copyright 2014 Eddy Xiao <bewantbe@gmail.com>
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
 *
 * 2026 laplaces-agent
 * Readouts rebuilt for the new bottom panel; old option popups removed.
 */

package org.woheller69.audio_analyzer_for_android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/**
 * Operate the views in the UI here.
 * Should run on UI thread in general.
 */

class AnalyzerViews {
    final String TAG = "AnalyzerViews";
    private final AnalyzerActivity activity;
    final AnalyzerGraphic graphView;

    private TextView tvPeak, tvCur, tvRMS, tvRes, tvRec;
    private final StringBuilder sb = new StringBuilder();

    private static final long LABEL_INTERVAL_MS = 80;   // readouts refresh at ~12 Hz
    private long lastLabelTime = 0;
    private volatile boolean labelPending = false;

    boolean bWarnOverrun = true;

    AnalyzerViews(AnalyzerActivity _activity) {
        activity = _activity;
        graphView = activity.findViewById(R.id.plot);
        bindViews();
    }

    // Look up the readout views; again after the layout is re-inflated.
    void bindViews() {
        tvPeak = activity.findViewById(R.id.textview_peak);
        tvCur  = activity.findViewById(R.id.textview_cur);
        tvRMS  = activity.findViewById(R.id.textview_RMS);
        tvRes  = activity.findViewById(R.id.textview_res);
        tvRec  = activity.findViewById(R.id.textview_rec);
    }

    // Prepare the spectrum and spectrogram plot (from scratch or full reset)
    // Should be called before samplingThread starts.
    void setupView(AnalyzerParameters analyzerParam) {
        graphView.setupPlot(analyzerParam);
    }

    private final Runnable labelRunnable = new Runnable() {
        @Override
        public void run() {
            labelPending = false;
            lastLabelTime = SystemClock.uptimeMillis();
            refreshPeakLabel(activity.maxAmpFreq, activity.maxAmpDB);
            refreshRMSLabel(activity.dtRMSFromFT);
            refreshCursorLabel();
        }
    };

    private final Runnable invalidateRunnable = new Runnable() {
        @Override
        public void run() {
            graphView.invalidate();
        }
    };

    // Will be called by SamplingLoop (in another thread)
    void update(final double[] spectrumDBcopy) {
        graphView.saveSpectrum(spectrumDBcopy);
        graphView.post(invalidateRunnable);
        if (!labelPending) {
            labelPending = true;
            long wait = Math.max(0, lastLabelTime + LABEL_INTERVAL_MS - SystemClock.uptimeMillis());
            graphView.postDelayed(labelRunnable, wait);
        }
    }

    private double wavSecOld = 0;      // used to reduce frame rate
    void updateRec(double wavSec) {
        if (wavSecOld > wavSec) {
            wavSecOld = wavSec;
        }
        if (wavSec - wavSecOld < 0.1) {
            return;
        }
        wavSecOld = wavSec;
        activity.runOnUiThread(() -> {
            if (activity.samplingThread != null)
                refreshRecTimeLable(activity.samplingThread.wavSec, activity.samplingThread.wavSecRemain);
        });
    }

    void notifyWAVSaved(final String path) {
        String text = "WAV saved to " + path;
        notifyToast(text);
    }

    void notifyToast(final String st) {
        activity.runOnUiThread(() -> {
            Context context = activity.getApplicationContext();
            Toast toast = Toast.makeText(context, st, Toast.LENGTH_SHORT);
            toast.show();
        });
    }

    private long lastTimeNotifyOverrun = 0;
    void notifyOverrun() {
        if (!bWarnOverrun) {
            return;
        }
        long t = SystemClock.uptimeMillis();
        if (t - lastTimeNotifyOverrun > 6000) {
            lastTimeNotifyOverrun = t;
            activity.runOnUiThread(() -> {
                Context context = activity.getApplicationContext();
                String text = "Recorder buffer overrun!\nYour cell phone is too slow.\nTry lower sampling rate or higher average number.";
                Toast toast = Toast.makeText(context, text, Toast.LENGTH_LONG);
                toast.show();
            });
        }
    }

    void showInstructions() {
        TextView tv = new TextView(activity);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setText(fromHtml(activity.getString(R.string.instructions_text)));
        PackageInfo pInfo = null;
        String version = "\n" + activity.getString(R.string.app_name) + "  Version: ";
        try {
            pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            version += pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version += "(Unknown)";
        }
        tv.append(version);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.instructions_title)
                .setView(tv)
                .setNegativeButton(R.string.dismiss, null)
                .create().show();
    }

    static final String UPSTREAM_URL = "https://github.com/woheller69/audio-analyzer-for-android";

    void showAbout() {
        String version;
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "";
        }
        TextView tv = new TextView(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setText(fromHtml(activity.getString(R.string.about_text, activity.getString(R.string.app_name), version)));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(tv);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.about)
                .setView(scroll)
                .setNeutralButton(R.string.about_upstream_button, (d, w) -> activity.startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM_URL))))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // Thanks http://stackoverflow.com/questions/37904739/html-fromhtml-deprecated-in-android-n
    @SuppressWarnings("deprecation")
    public static android.text.Spanned fromHtml(String source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY); // or Html.FROM_HTML_MODE_COMPACT
        } else {
            return Html.fromHtml(source);
        }
    }

    void enableSaveWavView(boolean bSaveWav) {
        tvRec.setVisibility(bSaveWav ? View.VISIBLE : View.GONE);
    }

    /** Frequency and time resolution of the current settings. */
    void refreshResolutionLabel(AnalyzerParameters p) {
        double df = (double) p.sampleRate / p.fftLen;
        double dt = (double) Math.max(1, p.hopLen / p.zeroPadFac) / p.sampleRate * p.nFFTAverage;
        tvRes.setText(ControlBar.formatHz(df) + " · " + ControlBar.formatSeconds(dt));
    }

    private void appendFreqNote(StringBuilder s, double f) {
        SBNumFormat.fillInNumFixedWidthPositive(s, f, 5, 1);
        s.append("Hz ");
        AnalyzerUtil.freq2Cent(s, f, " ");
        s.append(' ');
    }

    private void refreshCursorLabel() {
        double f1 = graphView.getCursorFreq();
        if (activity.controlBar != null) activity.controlBar.refreshCursor(f1);
        if (f1 <= 0) {
            tvCur.setVisibility(View.GONE);
            return;
        }
        sb.setLength(0);
        sb.append(activity.getString(R.string.text_cur));
        appendFreqNote(sb, f1);
        SBNumFormat.fillInNumFixedWidth(sb, graphView.getCursorDB(), 3, 1);
        sb.append("dB");
        tvCur.setText(sb);
        tvCur.setVisibility(View.VISIBLE);
    }

    private void refreshRMSLabel(double dtRMSFromFT) {
        sb.setLength(0);
        sb.append("RMS ");
        SBNumFormat.fillInNumFixedWidth(sb, 20*Math.log10(dtRMSFromFT), 3, 1);
        sb.append("dB");
        tvRMS.setText(sb);
    }

    private void refreshPeakLabel(double maxAmpFreq, double maxAmpDB) {
        sb.setLength(0);
        sb.append(activity.getString(R.string.text_peak));
        appendFreqNote(sb, maxAmpFreq);
        SBNumFormat.fillInNumFixedWidth(sb, maxAmpDB, 3, 1);
        sb.append("dB");
        tvPeak.setText(sb);
    }

    private void refreshRecTimeLable(double wavSec, double wavSecRemain) {
        sb.setLength(0);
        sb.append(activity.getString(R.string.text_rec));
        SBNumFormat.fillTime(sb, wavSec, 1);
        sb.append(activity.getString(R.string.text_remain));
        SBNumFormat.fillTime(sb, wavSecRemain, 0);
        tvRec.setText(sb);
    }

    // Redraw the plot and the cursor readout now (e.g. after a touch)
    void invalidateGraphView() {
        graphView.invalidate();
        refreshCursorLabel();
    }
}
