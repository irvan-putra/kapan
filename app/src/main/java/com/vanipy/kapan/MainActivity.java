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
        if (System.currentTimeMillis() - KapanData.lastFetched(this) > STALE_MS) {
            refresh(false);
        }
    }

    private void addTab(LinearLayout tabs, String label, String id) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> { audience = id; render(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tabs.addView(b, lp);
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
        long fetched = KapanData.lastFetched(this);
        if (fetched == 0 && !KapanData.hasCache(this)) {
            status.setText("no data yet - tap refresh");
        } else {
            status.setText("updated " + Format.day(fetched) + " · " + Format.time(fetched)
                    + " wib · tap refresh for the latest");
        }
        content.removeAllViews();

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
                    ? "nothing tracked here yet - the moviegoers list is hand-tracked from public source pages."
                    : "no upcoming events in the checked sources right now.");
            Ui.pad(empty, this, 8, 24, 8, 8);
            content.addView(empty);
            return;
        }
        for (KapanData.Item it : items) {
            content.addView(card(it));
        }
        TextView foot = Ui.text(this, 12, Ui.DIM, Typeface.NORMAL);
        foot.setText("listings from vanipy.com/kapan (public feeds, best effort). "
                + "every card opens its own source page.");
        Ui.pad(foot, this, 8, 20, 8, 8);
        content.addView(foot);
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

        if (!it.url.isEmpty()) {
            card.setOnClickListener(v ->
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(it.url))));
        }
        return card;
    }
}
