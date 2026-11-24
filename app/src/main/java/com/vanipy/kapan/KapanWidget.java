package com.vanipy.kapan;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * Home-screen widget: your next countdown (hosted/going events, care films,
 * on-device going marks), falling back to the next developer event and film
 * date when nothing is picked. Redraws from the on-phone cache on the
 * system's 30-minute tick and fetches over the network only when the cache
 * is older than three hours, so the widget costs no polling worth mentioning.
 * At 1x1 it is a small circular countdown (one number); larger sizes list every pick
 * (up to five) with a clock-style countdown.
 * Honest limit: the OS only lets a widget redraw on its ~30-minute tick (plus
 * app opens and refreshes), so the countdowns cannot tick every second like
 * the in-app panel; they are restated at each redraw.
 */
public class KapanWidget extends AppWidgetProvider {

    private static final long FETCH_IF_OLDER_MS = 3L * 3_600_000L;

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        renderAll(ctx, mgr, ids);
        if (System.currentTimeMillis() - KapanData.lastFetched(ctx) > FETCH_IF_OLDER_MS
                || !KapanData.hasCareCache(ctx)) {
            PendingResult pending = goAsync();
            new Thread(() -> {
                try {
                    KapanData.fetch(ctx);
                    AppWidgetManager m = AppWidgetManager.getInstance(ctx);
                    int[] all = m.getAppWidgetIds(new ComponentName(ctx, KapanWidget.class));
                    renderAll(ctx, m, all);
                } finally {
                    pending.finish();
                }
            }).start();
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle newOptions) {
        renderOne(ctx, mgr, id);
    }

    static void updateAll(Context ctx) {
        AppWidgetManager m = AppWidgetManager.getInstance(ctx);
        int[] all = m.getAppWidgetIds(new ComponentName(ctx, KapanWidget.class));
        renderAll(ctx, m, all);
    }

    private static void renderAll(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) renderOne(ctx, mgr, id);
    }

    private static int cells(Bundle options, String key) {
        int dp = options.getInt(key, 0);
        if (dp <= 0) return 2;
        return Math.max(1, (dp + 30) / 70);
    }

    private static void renderOne(Context ctx, AppWidgetManager mgr, int id) {
        Bundle options = mgr.getAppWidgetOptions(id);
        int cols = cells(options, AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int rows = cells(options, AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        long now = System.currentTimeMillis();
        // when any picks exist the widget shows only them, never generic listings
        boolean hasCare = !KapanData.careItems(ctx, now).isEmpty();
        RemoteViews views;
        if (cols <= 1 && rows <= 1) {
            views = new RemoteViews(ctx.getPackageName(), R.layout.kapan_widget_small);
            KapanData.Item next = KapanData.nextCare(ctx, null, now);
            if (next == null && !hasCare) next = KapanData.nextAny(ctx, now);
            if (next != null) {
                views.setTextViewText(R.id.w_big, Format.untilNumber(now, next.start));
                views.setTextViewText(R.id.w_label, Format.untilUnit(now, next.start)
                        + " · " + shortName(next.title));
            } else {
                views.setTextViewText(R.id.w_big, "-");
                views.setTextViewText(R.id.w_label, "kapan");
            }
        } else {
            views = new RemoteViews(ctx.getPackageName(), R.layout.kapan_widget);
            views.removeAllViews(R.id.w_rows);
            java.util.List<KapanData.Item> care = KapanData.careItems(ctx, now);
            if (!care.isEmpty()) {
                // every pick gets a row (up to 5), each with a clock-style countdown
                int n = Math.min(care.size(), 5);
                for (int i = 0; i < n; i++) {
                    KapanData.Item it = care.get(i);
                    addRow(ctx, views, "yours · " + it.title,
                            Format.when(it.start, it.dateOnly) + " · " + Format.clockUntil(now, it.start),
                            "film".equals(it.kind) ? 0xFF7EC3E0 : 0xFF7EE0A3);
                }
            } else {
                KapanData.Item dev = KapanData.next(ctx, KapanData.AUD_DEVELOPERS, now);
                KapanData.Item film = KapanData.next(ctx, KapanData.AUD_MOVIEGOERS, now);
                if (dev != null) {
                    addRow(ctx, views, "dev · " + dev.title,
                            Format.when(dev.start, dev.dateOnly) + " · " + Format.until(now, dev.start),
                            0xFF7EE0A3);
                } else {
                    addRow(ctx, views, "dev · no upcoming event", "", 0xFF7EE0A3);
                }
                if (film != null) {
                    addRow(ctx, views, "film · " + film.title,
                            Format.when(film.start, film.dateOnly) + " · " + Format.until(now, film.start),
                            0xFF7EC3E0);
                } else {
                    addRow(ctx, views, "film · no upcoming date", "", 0xFF7EC3E0);
                }
            }
        }
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        mgr.updateAppWidget(id, views);
    }

    private static void addRow(Context ctx, RemoteViews views, String title, String when, int whenColor) {
        RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.kapan_widget_row);
        row.setTextViewText(R.id.w_row_title, title);
        row.setTextViewText(R.id.w_row_when, when);
        row.setTextColor(R.id.w_row_when, whenColor);
        views.addView(R.id.w_rows, row);
    }

    private static String shortName(String title) {
        if (title == null) return "";
        return title.length() <= 14 ? title : title.substring(0, 13) + "…";
    }
}
