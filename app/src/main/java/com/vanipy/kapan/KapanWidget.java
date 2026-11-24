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
 * At 1x1 it is a small circular countdown (one number); larger sizes list
 * both rows.
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
            KapanData.Item dev = KapanData.nextCare(ctx, "event", now);
            boolean devCare = dev != null;
            if (dev == null && !hasCare) dev = KapanData.next(ctx, KapanData.AUD_DEVELOPERS, now);
            KapanData.Item film = KapanData.nextCare(ctx, "film", now);
            boolean filmCare = film != null;
            if (film == null && !hasCare) film = KapanData.next(ctx, KapanData.AUD_MOVIEGOERS, now);
            if (dev != null) {
                views.setTextViewText(R.id.w_dev_title, (devCare ? "yours · " : "dev · ") + dev.title);
                views.setTextViewText(R.id.w_dev_when,
                        Format.when(dev.start, dev.dateOnly) + " · " + Format.until(now, dev.start));
            } else {
                views.setTextViewText(R.id.w_dev_title, hasCare ? "yours · no event pick ahead" : "dev · no upcoming event");
                views.setTextViewText(R.id.w_dev_when, "");
            }
            if (film != null) {
                views.setTextViewText(R.id.w_film_title, (filmCare ? "yours · " : "film · ") + film.title);
                views.setTextViewText(R.id.w_film_when,
                        Format.when(film.start, film.dateOnly) + " · " + Format.until(now, film.start));
            } else {
                views.setTextViewText(R.id.w_film_title, hasCare ? "yours · no film pick ahead" : "film · no upcoming date");
                views.setTextViewText(R.id.w_film_when, "");
            }
        }
        Intent open = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        mgr.updateAppWidget(id, views);
    }

    private static String shortName(String title) {
        if (title == null) return "";
        return title.length() <= 14 ? title : title.substring(0, 13) + "…";
    }
}
