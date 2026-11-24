package com.vanipy.kapan;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Pure date/countdown formatting, shared by the app screen and the widget. */
public final class Format {

    public static final TimeZone WIB = TimeZone.getTimeZone("Asia/Jakarta");

    private Format() {}

    /** "wed, 25 nov" style day label in WIB. */
    public static String day(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, d MMM", Locale.US);
        f.setTimeZone(WIB);
        return f.format(new Date(millis)).toLowerCase(Locale.US);
    }

    /** "19:00" in WIB. */
    public static String time(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(WIB);
        return f.format(new Date(millis));
    }

    /** Date with time when it is not date-only, e.g. "wed, 25 nov · 12:00 wib". */
    public static String when(long millis, boolean dateOnly) {
        if (dateOnly) return day(millis);
        return day(millis) + " · " + time(millis) + " wib";
    }

    /** Short countdown: "in 3d 4h", "in 5h 12m", "in 40m", "today". */
    public static String until(long nowMillis, long targetMillis) {
        long ms = targetMillis - nowMillis;
        if (ms <= 0) return "now";
        long mins = (ms + 30_000L) / 60_000L;
        if (mins < 1) mins = 1;
        long days = mins / (24 * 60);
        long hours = (mins / 60) % 24;
        long m = mins % 60;
        if (days > 0) return "in " + days + "d " + hours + "h";
        if (hours > 0) return "in " + hours + "h " + m + "m";
        return "in " + m + "m";
    }

    /** Live clock countdown: "3d 04:12:33", "04:12:33". */
    public static String clockUntil(long nowMillis, long targetMillis) {
        long ms = Math.max(0L, targetMillis - nowMillis);
        long s = ms / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        String clock = String.format(Locale.US, "%02d:%02d:%02d", h, m, sec);
        return d > 0 ? d + "d " + clock : clock;
    }

    /** One number for the 1x1 widget: hours when under two days, else days. */
    public static String untilNumber(long nowMillis, long targetMillis) {
        long ms = Math.max(0L, targetMillis - nowMillis);
        long hours = (ms + 1_800_000L) / 3_600_000L;
        if (hours < 48) return String.valueOf(Math.max(hours, 1));
        return String.valueOf((ms + 43_200_000L) / 86_400_000L);
    }

    /** Unit for untilNumber: "h" under two days, else "d". */
    public static String untilUnit(long nowMillis, long targetMillis) {
        long ms = Math.max(0L, targetMillis - nowMillis);
        long hours = (ms + 1_800_000L) / 3_600_000L;
        return hours < 48 ? "h" : "d";
    }
}
