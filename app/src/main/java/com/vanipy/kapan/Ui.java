package com.vanipy.kapan;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.TextView;

/** Small programmatic-UI helpers shared by the screens. */
public final class Ui {

    static final int BG = 0xFF0E0F13;
    static final int PANEL = 0xFF171A21;
    static final int LINE = 0xFF262B36;
    static final int TEXT = 0xFFE8EAF0;
    static final int DIM = 0xFF9AA3B2;
    static final int ACCENT = 0xFF7EE0A3;
    static final int ACCENT2 = 0xFF7EC3E0;

    private Ui() {}

    static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context ctx, float sp, int color, int style) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    static void pad(View v, Context ctx, int l, int t, int r, int b) {
        v.setPadding(dp(ctx, l), dp(ctx, t), dp(ctx, r), dp(ctx, b));
    }
}
