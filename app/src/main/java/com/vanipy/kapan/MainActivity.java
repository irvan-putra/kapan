package com.vanipy.kapan;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * kapan - developer events and film/festival dates in jakarta & jogja.
 * Two audience tabs over the public listings on vanipy.com/kapan; listings
 * cache on the phone so the app reads offline after the first fetch.
 */
public class MainActivity extends Activity {

    private static final long STALE_MS = 6L * 3_600_000L;

    private LinearLayout content;
    private TextView status;
    private String audience = KapanData.AUD_DEVELOPERS;
    private final java.util.Map<String, Button> tabButtons = new java.util.LinkedHashMap<>();
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<TextView> countdownViews = new ArrayList<>();
    private final List<Long> countdownTargets = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            for (int i = 0; i < countdownViews.size(); i++) {
                countdownViews.get(i).setText(Format.clockUntil(now, countdownTargets.get(i)));
            }
            ticker.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        TextView head = Ui.text(this, 13, Ui.DIM, Typeface.NORMAL);
        head.setText("kapan - what's on in jakarta & jogja");
        Ui.pad(head, this, 16, 14, 16, 4);
        root.addView(head);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Ui.pad(tabs, this, 12, 6, 12, 2);
        addTab(tabs, "developers", KapanData.AUD_DEVELOPERS);
        addTab(tabs, "moviegoers", KapanData.AUD_MOVIEGOERS);
        root.addView(tabs);

        status = Ui.text(this, 12, Ui.DIM, Typeface.NORMAL);
        Ui.pad(status, this, 16, 2, 16, 2);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        Ui.pad(content, this, 12, 4, 12, 24);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        render();
        if (System.currentTimeMillis() - KapanData.lastFetched(this) > STALE_MS
                || !KapanData.hasCareCache(this)) {
            refresh(false);
        }
    }

    private void addTab(LinearLayout tabs, String label, String id) {
        Button b = new Button(this);
        b.setText(label.toLowerCase());
        b.setAllCaps(false);
        b.setOnClickListener(v -> { audience = id; render(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tabs.addView(b, lp);
        tabButtons.put(id, b);
    }

    private void styleTabs() {
        for (java.util.Map.Entry<String, Button> e : tabButtons.entrySet()) {
            Button b = e.getValue();
            boolean on = e.getKey().equals(audience);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 20));
            if (on) {
                bg.setColor(Ui.ACCENT2);
                b.setTextColor(0xFF0C0D10);
                b.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                bg.setColor(Ui.PANEL);
                bg.setStroke(Ui.dp(this, 1), Ui.LINE);
                b.setTextColor(Ui.DIM);
                b.setTypeface(Typeface.DEFAULT);
            }
            b.setBackground(bg);
        }
    }

    private void refresh(boolean manual) {
        status.setText("refreshing…");
        new Thread(() -> {
            String err = KapanData.fetch(this);
            runOnUiThread(() -> {
                if (err != null && manual) {
                    Toast.makeText(this, "refresh failed (" + err + ") - showing saved data",
                            Toast.LENGTH_LONG).show();
                }
                render();
                KapanWidget.updateAll(this);
            });
        }).start();
    }

    private void render() {
        styleTabs();
        long fetched = KapanData.lastFetched(this);
        if (fetched == 0 && !KapanData.hasCache(this)) {
            status.setText("no data yet - tap refresh");
        } else {
            status.setText("updated " + Format.day(fetched) + " · " + Format.time(fetched)
                    + " wib · tap refresh for the latest");
        }
        content.removeAllViews();

        addCarePanel();

        Button refresh = new Button(this);
        refresh.setText("refresh");
        refresh.setOnClickListener(v -> refresh(true));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8));
        content.addView(refresh, rlp);

        List<KapanData.Item> items = KapanData.items(this, audience);
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, 14, Ui.DIM, Typeface.NORMAL);
            empty.setText(audience.equals(KapanData.AUD_MOVIEGOERS)
                    ? "nothing tracked here yet - the moviegoers list is hand-tracked from public source pages, dates checked by instinct (an ai assistant)."
                    : "no upcoming events in the checked sources right now.");
            Ui.pad(empty, this, 8, 24, 8, 8);
            content.addView(empty);
            return;
        }
        if (audience.equals(KapanData.AUD_MOVIEGOERS)) {
            List<KapanData.Item> dated = new ArrayList<>();
            List<KapanData.Item> undated = new ArrayList<>();
            List<KapanData.Item> fests = new ArrayList<>();
            for (KapanData.Item it : items) {
                boolean fest = "festival".equals(it.kind) || "series".equals(it.kind);
                if (fest) fests.add(it);
                else if (it.start > 0) dated.add(it);
                else undated.add(it);
            }
            addFilmSection(content, "big releases", dated, true);
            addFilmSection(content, "coming, date unconfirmed", undated, false);
            addFilmSection(content, "festivals & regular screenings - easy to miss", fests, false);
        } else {
            for (KapanData.Item it : items) {
                content.addView(card(it));
            }
        }
        TextView foot = Ui.text(this, 12, Ui.DIM, Typeface.NORMAL);
        foot.setText("listings from vanipy.com/kapan (public feeds, best effort). "
                + "every card opens its own source page.");
        Ui.pad(foot, this, 8, 20, 8, 8);
        content.addView(foot);
    }

    /** Personal countdowns: published care picks plus on-device going marks. */
    private void addCarePanel() {
        long now = System.currentTimeMillis();
        countdownViews.clear();
        countdownTargets.clear();
        List<KapanData.Item> care = KapanData.careItems(this, now);
        if (care.isEmpty()) return;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL);
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setStroke(Ui.dp(this, 1), Ui.ACCENT2);
        panel.setBackground(bg);
        Ui.pad(panel, this, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 8));
        panel.setLayoutParams(lp);

        TextView h = Ui.text(this, 12, Ui.ACCENT2, Typeface.BOLD);
        h.setText("YOUR COUNTDOWNS");
        h.setLetterSpacing(0.12f);
        Ui.pad(h, this, 0, 0, 0, 6);
        panel.addView(h);

        int shown = 0;
        for (KapanData.Item it : care) {
            if (shown >= 5) break;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            Ui.pad(row, this, 0, 6, 0, 6);

            TextView t = Ui.text(this, 14, Ui.TEXT, Typeface.BOLD);
            t.setText(it.title);
            row.addView(t);

            StringBuilder meta = new StringBuilder(it.when);
            if (!it.where.isEmpty()) meta.append(" · ").append(it.where);
            if (!it.role.isEmpty()) meta.append(" · ").append(it.role);
            TextView m = Ui.text(this, 11, Ui.DIM, Typeface.NORMAL);
            m.setText(meta.toString());
            row.addView(m);

            TextView cd = Ui.text(this, 18, Ui.ACCENT, Typeface.BOLD);
            cd.setText(Format.clockUntil(now, it.start));
            row.addView(cd);
            countdownViews.add(cd);
            countdownTargets.add(it.start);

            if (!it.url.isEmpty()) {
                row.setOnClickListener(v ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.url))));
            }
            panel.addView(row);
        }

        Button tell = new Button(this);
        tell.setText("tell instinct about an event");
        tell.setAllCaps(false);
        tell.setTextSize(12f);
        tell.setOnClickListener(v -> tellInstinct());
        panel.addView(tell);

        content.addView(panel);
    }

    /** Nudge path: hands him a share sheet so he can send the event to Instinct. */
    private void tellInstinct() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "kapan: please count this event for me - ");
        startActivity(Intent.createChooser(send, "tell instinct"));
    }

    private void addFilmSection(LinearLayout content, String title, List<KapanData.Item> items,
                                boolean showDayHeaders) {
        if (items.isEmpty()) return;
        TextView h = Ui.text(this, 12, Ui.ACCENT2, Typeface.BOLD);
        h.setText(title.toUpperCase());
        h.setLetterSpacing(0.12f);
        Ui.pad(h, this, 8, 18, 8, 2);
        content.addView(h);
        String lastDay = "";
        for (KapanData.Item it : items) {
            if (showDayHeaders) {
                String day = Format.day(it.start);
                if (!day.equals(lastDay)) {
                    TextView d = Ui.text(this, 12, Ui.DIM, Typeface.NORMAL);
                    d.setText(day);
                    Ui.pad(d, this, 8, 10, 8, 0);
                    content.addView(d);
                    lastDay = day;
                }
            }
            content.addView(card(it));
        }
    }

    private View card(KapanData.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL);
        bg.setCornerRadius(Ui.dp(this, 12));
        bg.setStroke(Ui.dp(this, 1), Ui.LINE);
        card.setBackground(bg);
        Ui.pad(card, this, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        card.setLayoutParams(lp);

        TextView title = Ui.text(this, 15, Ui.TEXT, Typeface.BOLD);
        title.setText(it.title);
        card.addView(title);

        StringBuilder meta = new StringBuilder(it.when);
        if (!it.where.isEmpty()) meta.append(" · ").append(it.where);
        TextView m = Ui.text(this, 12, Ui.ACCENT, Typeface.NORMAL);
        m.setText(meta.toString());
        card.addView(m);

        if (!it.tags.isEmpty()) {
            TextView t = Ui.text(this, 11, Ui.DIM, Typeface.NORMAL);
            t.setText(it.tags);
            card.addView(t);
        }
        if (!it.note.isEmpty()) {
            TextView n = Ui.text(this, 11, Ui.DIM, Typeface.NORMAL);
            n.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
            n.setText(it.note);
            card.addView(n);
        }

        if (!it.uid.isEmpty() && it.start > 0) {
            Button mark = new Button(this);
            mark.setAllCaps(false);
            mark.setTextSize(12f);
            mark.setText(KapanData.isMarked(this, it.uid) ? "\u2605 going" : "\u2606 i'm going");
            mark.setOnClickListener(v -> {
                KapanData.toggleMark(this, it.uid);
                if (KapanData.isMarked(this, it.uid)) {
                    Toast.makeText(this,
                            "counted on this phone - 'tell instinct' above adds it everywhere",
                            Toast.LENGTH_LONG).show();
                }
                render();
                KapanWidget.updateAll(this);
            });
            card.addView(mark);
        }

        if (!it.url.isEmpty()) {
            card.setOnClickListener(v ->
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.url))));
        }
        return card;
    }
}
