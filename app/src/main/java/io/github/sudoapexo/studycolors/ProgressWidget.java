package io.github.sudoapexo.studycolors;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Home-screen widget: the overview bar and the first subjects with their colors. */
public class ProgressWidget extends AppWidgetProvider {

    private static final String PREFS = "study_colors";
    private static final String DATA = "widget_data";
    private static final int[] ROWS = {R.id.widget_row0, R.id.widget_row1, R.id.widget_row2,
            R.id.widget_row3, R.id.widget_row4};
    private static final Map<String, Integer> COLORS = new HashMap<>();

    static {
        String[][] c = {{"red", "D32F2F"}, {"orange", "F57C00"}, {"amber", "FFB300"},
                {"yellow", "FDD835"}, {"lime", "C0CA33"}, {"lightgreen", "8BC34A"},
                {"green", "2E7D32"}, {"teal", "00796B"}, {"cyan", "00ACC1"},
                {"blue", "1976D2"}, {"indigo", "3949AB"}, {"purple", "8E24AA"},
                {"pink", "D81B60"}, {"brown", "6D4C41"}, {"gray", "9E9E9E"},
                {"charcoal", "37474F"}};
        for (String[] x : c) {
            COLORS.put(x[0], Color.parseColor("#" + x[1]));
        }
    }

    /** Called by the page whenever the subjects change. */
    static void save(Context context, String json) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, json).apply();
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ProgressWidget.class));
        if (ids.length > 0) {
            update(context, manager, ids);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        update(context, manager, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
                                          android.os.Bundle options) {
        update(context, manager, new int[]{id});
    }

    private static void update(Context context, AppWidgetManager manager, int[] ids) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, "[]");
        JSONArray subjects;
        try {
            subjects = new JSONArray(raw);
        } catch (Exception e) {
            subjects = new JSONArray();
        }
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        for (int id : ids) {
            int heightDp = manager.getAppWidgetOptions(id)
                    .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
            // A title and bar take about 70dp; each row about 22dp.
            int rows = Math.max(0, Math.min(ROWS.length, (heightDp - 70) / 22));
            RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_progress);
            v.setOnClickPendingIntent(R.id.widget_root, pending);
            fill(context, v, subjects, rows);
            manager.updateAppWidget(id, v);
        }
    }

    private static void fill(Context context, RemoteViews v, JSONArray subjects, int rowLimit) {
        int total = subjects.length();
        v.setTextViewText(R.id.widget_count, total == 1 ? "1 subject" : total + " subjects");

        // Bar: one segment per color, biggest group first, uncolored subjects as gray outline.
        Map<String, Integer> counts = new LinkedHashMap<>();
        int none = 0;
        for (int i = 0; i < total; i++) {
            JSONArray s = subjects.optJSONArray(i);
            String key = s == null ? "" : s.optString(1, "");
            if (COLORS.containsKey(key)) {
                counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
            } else {
                none++;
            }
        }
        float density = context.getResources().getDisplayMetrics().density;
        int w = (int) (320 * density), h = (int) (12 * density);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float radius = h / 2f;
        p.setColor(Color.argb(60, 128, 128, 128));
        canvas.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        if (total > 0) {
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            float x = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                float seg = (float) w * e.getValue() / total;
                p.setColor(COLORS.get(e.getKey()));
                canvas.drawRect(x, 0, x + seg, h, p);
                x += seg;
            }
        }
        v.setImageViewBitmap(R.id.widget_bar, bmp);

        int shown = Math.min(rowLimit, total);
        for (int r = 0; r < ROWS.length; r++) {
            if (r < shown) {
                JSONArray s = subjects.optJSONArray(r);
                String name = s == null ? "" : s.optString(0, "");
                String key = s == null ? "" : s.optString(1, "");
                Integer color = COLORS.get(key);
                SpannableStringBuilder sb = new SpannableStringBuilder("●  " + name);
                sb.setSpan(new ForegroundColorSpan(color != null ? color : Color.GRAY),
                        0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                v.setTextViewText(ROWS[r], sb);
                v.setViewVisibility(ROWS[r], android.view.View.VISIBLE);
            } else {
                v.setViewVisibility(ROWS[r], android.view.View.GONE);
            }
        }
        if (total == 0) {
            v.setTextViewText(R.id.widget_more, context.getString(R.string.widget_empty));
            v.setViewVisibility(R.id.widget_more, android.view.View.VISIBLE);
        } else if (total > shown) {
            v.setTextViewText(R.id.widget_more, "+" + (total - shown) + " more");
            v.setViewVisibility(R.id.widget_more, android.view.View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.widget_more, android.view.View.GONE);
        }
    }
}
