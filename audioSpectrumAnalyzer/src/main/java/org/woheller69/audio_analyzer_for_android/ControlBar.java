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

import android.content.res.Resources;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The row of analysis-option chips on the main screen. Each chip shows the current value;
 * tapping it opens a picker anchored to the chip, where every option states what it costs
 * (frequency/time resolution) so the trade-off is visible before choosing.
 */
class ControlBar {
    private final AnalyzerActivity act;
    private final LayoutInflater inflater;
    private final float dp;

    private TextView fftValue, overlapValue, rangeValue, scaleValue, rateValue, windowValue,
            averageValue, historyValue, colorsValue, weightValue, cursorValue;

    ControlBar(AnalyzerActivity activity) {
        act = activity;
        inflater = LayoutInflater.from(activity);
        dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout row = activity.findViewById(R.id.chip_row);

        fftValue     = addChip(row, R.string.chip_fft,     v -> pickFftLen(v));
        overlapValue = addChip(row, R.string.chip_overlap, v -> pickOverlap(v));
        rangeValue   = addChip(row, R.string.chip_range,   v -> showRangePopup(v));
        scaleValue   = addChip(row, R.string.chip_scale,   v -> pickScale(v));
        rateValue    = addChip(row, R.string.chip_rate,    v -> pickSampleRate(v));
        windowValue  = addChip(row, R.string.chip_window,  v -> pickWindow(v));
        averageValue = addChip(row, R.string.chip_average, v -> pickAverage(v));
        historyValue = addChip(row, R.string.chip_history, v -> pickHistory(v));
        colorsValue  = addChip(row, R.string.chip_colors,  v -> pickColorMap(v));
        weightValue  = addChip(row, R.string.chip_weight,  v -> act.setAWeighting(!act.getAnalyzerParam().isAWeighting));
        cursorValue  = addChip(row, R.string.chip_cursor,  v -> act.showCursorFreqPopup(v));
    }

    private TextView addChip(LinearLayout row, int caption, View.OnClickListener onClick) {
        View chip = inflater.inflate(R.layout.item_chip, row, false);
        ((TextView) chip.findViewById(R.id.chip_caption)).setText(caption);
        chip.setOnClickListener(onClick);
        row.addView(chip);
        return chip.findViewById(R.id.chip_value);
    }

    // ---- value formatting ----

    static String formatHz(double hz) {
        if (hz >= 1000) return trim(hz / 1000, hz >= 10000 ? 1 : 2) + " kHz";
        return trim(hz, hz >= 100 ? 0 : 1) + " Hz";
    }

    static String formatSeconds(double s) {
        if (s < 1) return trim(s * 1000, s < 0.01 ? 1 : 0) + " ms";
        return trim(s, s < 10 ? 2 : 1) + " s";
    }

    private static String trim(double v, int decimals) {
        String s = String.format(Locale.US, "%." + decimals + "f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String dB(double v) {
        return Long.toString(Math.round(v)).replace('-', '−');  // Math.round also maps -0.0 to 0
    }

    static String windowDisplayName(String name) {
        return name.equals("Hanning") ? "Hann" : name;
    }

    private String scaleName(String mode) {
        switch (mode) {
            case "log":  return act.getString(R.string.scale_log);
            case "note": return act.getString(R.string.scale_note);
            default:     return act.getString(R.string.scale_linear);
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int hopFor(int fftLen, double overlapPercent) {
        return (int) (fftLen * (1 - overlapPercent / 100) + 0.5);
    }

    /** Seconds between spectra for the given settings. */
    private static double stepSeconds(AnalyzerParameters p, int fftLen, double overlap, int nAve) {
        return (double) Math.max(1, hopFor(fftLen, overlap) / p.zeroPadFac) / p.sampleRate * nAve;
    }

    void refresh() {
        AnalyzerParameters p = act.getAnalyzerParam();
        fftValue.setText(String.valueOf(p.fftLen));
        overlapValue.setText(trim(p.overlapPercent, 2) + "%");
        rangeValue.setText(dB(act.getWaterfallDbLow()) + "…" + dB(act.getWaterfallDbHigh()) + " dB");
        scaleValue.setText(scaleName(act.getFreqScale()));
        rateValue.setText(formatHz(p.sampleRate));
        windowValue.setText(windowDisplayName(p.wndFuncName));
        averageValue.setText("×" + p.nFFTAverage);
        historyValue.setText(formatSeconds(p.spectrogramDuration));
        colorsValue.setText(capitalize(act.getColorMapName().replace("_uniform", "")));
        weightValue.setText(p.isAWeighting ? "dBA" : "dB");
        refreshCursor(act.analyzerViews.graphView.getCursorFreq());
    }

    void refreshCursor(double f) {
        cursorValue.setText(f > 0 ? formatHz(f) : "—");
    }

    // ---- pickers ----

    private static class Option {
        final String label, hint;
        final Object value;
        int[] swatch;
        Option(String label, String hint, Object value) {
            this.label = label;
            this.hint = hint;
            this.value = value;
        }
    }

    interface OnPick { void pick(Object value); }

    private void showOptions(View anchor, final List<Option> options, Object current, final OnPick onPick) {
        int selected = -1;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value.equals(current)) selected = i;
        }
        final int sel = selected;
        final ListPopupWindow popup = new ListPopupWindow(act);
        popup.setAnchorView(anchor);
        popup.setModal(true);
        popup.setBackgroundDrawable(ContextCompat.getDrawable(act, R.drawable.bg_popup));
        popup.setContentWidth((int) (260 * dp));
        popup.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return options.size(); }
            @Override public Object getItem(int i) { return options.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = inflater.inflate(R.layout.item_option, parent, false);
                Option o = options.get(i);
                ((TextView) v.findViewById(R.id.option_label)).setText(o.label);
                ((TextView) v.findViewById(R.id.option_hint)).setText(o.hint);
                v.findViewById(R.id.option_check).setVisibility(i == sel ? View.VISIBLE : View.INVISIBLE);
                View sw = v.findViewById(R.id.option_swatch);
                if (o.swatch != null) {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, o.swatch);
                    g.setCornerRadius(3 * dp);
                    sw.setBackgroundDrawable(g);
                    sw.setVisibility(View.VISIBLE);
                } else {
                    sw.setVisibility(View.GONE);
                }
                return v;
            }
        });
        popup.setOnItemClickListener((parent, view, position, id) -> {
            popup.dismiss();
            if (position != sel) onPick.pick(options.get(position).value);
        });
        popup.show();
        if (sel >= 0) popup.setSelection(sel);
    }

    private void pickFftLen(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        for (String item : act.getResources().getStringArray(R.array.fft_len)) {
            String[] kv = item.split("::");
            int n = Integer.parseInt(kv[1]);
            if (n == 0) continue;
            double df = (double) p.sampleRate / n;
            double win = (double) n / p.zeroPadFac / p.sampleRate;
            opts.add(new Option(String.valueOf(n), formatHz(df) + " · " + formatSeconds(win), n));
        }
        showOptions(anchor, opts, p.fftLen, v -> act.setFftLen((Integer) v));
    }

    private void pickOverlap(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        Resources res = act.getResources();
        String[] labels = res.getStringArray(R.array.fft_overlap_percent_describe);
        String[] values = res.getStringArray(R.array.fft_overlap_percent);
        for (int i = 0; i < values.length; i++) {
            double ov = Double.parseDouble(values[i]);
            double step = stepSeconds(p, p.fftLen, ov, 1);
            opts.add(new Option(labels[i], formatSeconds(step) + " / row", ov));
        }
        showOptions(anchor, opts, p.overlapPercent, v -> act.setOverlap((Double) v));
    }

    private void pickSampleRate(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        String[] rates = AnalyzerUtil.validateAudioRates(act.getResources().getStringArray(R.array.sample_rates));
        for (String item : rates) {
            String[] kv = item.split("::");
            int sr = Integer.parseInt(kv[1]);
            if (sr == 0) continue;
            opts.add(new Option(formatHz(sr), "0 – " + formatHz(sr / 2.0), sr));
        }
        showOptions(anchor, opts, p.sampleRate, v -> act.setSampleRate((Integer) v));
    }

    private void pickWindow(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        for (String name : act.getResources().getStringArray(R.array.wnd_func_names)) {
            String hint;
            switch (name) {
                case "Rectangular":     hint = "sharpest, leaky"; break;
                case "Hanning":         hint = "general purpose"; break;
                case "Blackman Harris": hint = "low leakage"; break;
                case "Flat-top":        hint = "accurate levels"; break;
                default:                hint = "";
            }
            opts.add(new Option(windowDisplayName(name), hint, name));
        }
        showOptions(anchor, opts, p.wndFuncName, v -> act.setWindow((String) v));
    }

    private void pickAverage(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        for (String item : act.getResources().getStringArray(R.array.fft_ave_num)) {
            String[] kv = item.split("::");
            int n = Integer.parseInt(kv[1]);
            if (n == 0) continue;
            double step = stepSeconds(p, p.fftLen, p.overlapPercent, n);
            opts.add(new Option("×" + n, "every " + formatSeconds(step), n));
        }
        showOptions(anchor, opts, p.nFFTAverage, v -> act.setAverage((Integer) v));
    }

    private void pickHistory(View anchor) {
        AnalyzerParameters p = act.getAnalyzerParam();
        List<Option> opts = new ArrayList<>();
        for (String item : act.getResources().getStringArray(R.array.spectrogram_duration_array)) {
            double s = Double.parseDouble(item);
            opts.add(new Option(formatSeconds(s), "", s));
        }
        showOptions(anchor, opts, p.spectrogramDuration, v -> act.setDuration((Double) v));
    }

    private void pickScale(View anchor) {
        List<Option> opts = new ArrayList<>();
        opts.add(new Option(scaleName("linear"), "evenly spaced Hz", "linear"));
        opts.add(new Option(scaleName("log"), "octaves evenly spaced", "log"));
        opts.add(new Option(scaleName("note"), "log, labelled by pitch", "note"));
        showOptions(anchor, opts, act.getFreqScale(), v -> act.setFreqScale((String) v));
    }

    private void pickColorMap(View anchor) {
        List<Option> opts = new ArrayList<>();
        Resources res = act.getResources();
        String[] labels = res.getStringArray(R.array.dbColorMapDescribe);
        String[] values = res.getStringArray(R.array.dbColorMap);
        for (int i = 0; i < values.length; i++) {
            Option o = new Option(labels[i], "", values[i]);
            int[] cm = ColorMapArray.selectColorMap(values[i]);
            o.swatch = new int[8];
            for (int k = 0; k < 8; k++) {
                // colormaps are stored loudest first; show quiet -> loud, left -> right
                o.swatch[k] = cm[(cm.length - 1) * (7 - k) / 7] | 0xff000000;
            }
            opts.add(o);
        }
        showOptions(anchor, opts, act.getColorMapName(), v -> act.setColorMap((String) v));
    }

    // ---- waterfall color range ----

    private static final int TOP_MIN = -100, TOP_MAX = 20;
    private static final int FLOOR_MIN = -160, FLOOR_MAX = -10;
    private static final int MIN_SPAN = 10;

    private void showRangePopup(View anchor) {
        View content = inflater.inflate(R.layout.popup_range, null);
        final SeekBar top = content.findViewById(R.id.range_top);
        final SeekBar floor = content.findViewById(R.id.range_floor);
        final TextView topValue = content.findViewById(R.id.range_top_value);
        final TextView floorValue = content.findViewById(R.id.range_floor_value);
        top.setMax(TOP_MAX - TOP_MIN);
        floor.setMax(FLOOR_MAX - FLOOR_MIN);

        final Runnable sync = () -> {
            double lo = act.getWaterfallDbLow(), hi = act.getWaterfallDbHigh();
            top.setProgress((int) Math.round(hi) - TOP_MIN);
            floor.setProgress((int) Math.round(lo) - FLOOR_MIN);
            topValue.setText(dB(hi) + " dB");
            floorValue.setText(dB(lo) + " dB");
            refresh();
        };
        sync.run();

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                double lo = floor.getProgress() + FLOOR_MIN;
                double hi = top.getProgress() + TOP_MIN;
                if (hi - lo < MIN_SPAN) {
                    if (sb == top) lo = hi - MIN_SPAN; else hi = lo + MIN_SPAN;
                }
                act.setWaterfallDbRange(lo, hi);
                sync.run();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        top.setOnSeekBarChangeListener(l);
        floor.setOnSeekBarChangeListener(l);

        content.findViewById(R.id.range_auto).setOnClickListener(v -> {
            double[] r = act.analyzerViews.graphView.waterfallPlot.suggestDbRange();
            if (r == null) return;
            act.setWaterfallDbRange(Math.max(FLOOR_MIN, Math.min(FLOOR_MAX, r[0])),
                                    Math.max(TOP_MIN, Math.min(TOP_MAX, r[1])));
            sync.run();
        });
        content.findViewById(R.id.range_reset).setOnClickListener(v -> {
            act.setWaterfallDbRange(AnalyzerActivity.WF_DB_LOW_DEFAULT, AnalyzerActivity.WF_DB_HIGH_DEFAULT);
            sync.run();
        });

        int screenW = act.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min((int) (320 * dp), screenW - (int) (16 * dp));
        PopupWindow popup = new PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(0));  // needed for outside-touch dismissal
        popup.setOutsideTouchable(true);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        // above the chip, clamped to the screen
        int x = Math.max((int) (8 * dp), Math.min(loc[0], screenW - width - (int) (8 * dp)));
        int y = loc[1] - content.getMeasuredHeight() - (int) (6 * dp);
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y);
    }
}
