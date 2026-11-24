package com.vanipy.kapan;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/**
 * Scrollable row source for the large widget: every pick (no cap), or the
 * next developer event and film when nothing is picked. Pick rows carry no
 * prefix - they are all his - while the fallback rows keep their "dev"/"film"
 * tags because that is the only place two kinds can mix. The list scrolls, so
 * any number of picks fits; rows beyond the visible area are one swipe away.
 * Rows restate their countdown when the widget redraws (about every 30
 * minutes on the OS tick, plus app opens and refreshes), the same limit as
 * the rest of the widget.
 */
public class KapanWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static final class Row {
        String title, when;
        int color;
    }

    static class Factory implements RemoteViewsService.RemoteViewsFactory {
        private final Context ctx;
        private final List<Row> rows = new ArrayList<>();

        Factory(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onCreate() {}

        @Override
        public void onDataSetChanged() {
            long now = System.currentTimeMillis();
            rows.clear();
            List<KapanData.Item> care = KapanData.careItems(ctx, now);
            if (!care.isEmpty()) {
                for (KapanData.Item it : care) {
                    rows.add(make(it.title, it, now,
                            "film".equals(it.kind) ? 0xFF7EC3E0 : 0xFF7EE0A3));
                }
                return;
            }
            KapanData.Item dev = KapanData.next(ctx, KapanData.AUD_DEVELOPERS, now);
            KapanData.Item film = KapanData.next(ctx, KapanData.AUD_MOVIEGOERS, now);
            if (dev != null) rows.add(make("dev · " + dev.title, dev, now, 0xFF7EE0A3));
            if (film != null) rows.add(make("film · " + film.title, film, now, 0xFF7EC3E0));
        }

        private static Row make(String title, KapanData.Item it, long now, int color) {
            Row r = new Row();
            r.title = title;
            r.when = Format.when(it.start, it.dateOnly) + " · " + Format.untilShort(now, it.start);
            r.color = color;
            return r;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            Row r = rows.get(position);
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.kapan_widget_row);
            rv.setTextViewText(R.id.w_row_title, r.title);
            rv.setTextViewText(R.id.w_row_when, r.when);
            rv.setTextColor(R.id.w_row_when, r.color);
            rv.setOnClickFillInIntent(R.id.w_row_root, new Intent());
            return rv;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public void onDestroy() {}
    }
}
