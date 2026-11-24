package com.vanipy.kapan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The two public listings behind vanipy.com/kapan, cached on the phone.
 * Fetching is manual or on a stale cache; the widget redraws from cache.
 */
public final class KapanData {

    public static final String AUD_DEVELOPERS = "developers";
    public static final String AUD_MOVIEGOERS = "moviegoers";

    private static final String EVENTS_URL = "https://vanipy.com/kapan/events.json";
    private static final String FILMS_URL = "https://vanipy.com/kapan/films.json";
    private static final String CARE_URL = "https://vanipy.com/kapan/care.json";
    private static final String PREFS = "kapan";
    private static final String KEY_FETCHED = "fetched_at";
    private static final String KEY_MARKED = "marked_uids";

    public static final class Item {
        public String uid = "";
        public String title = "";
        public long start;           // millis; 0 when undated
        public long end;             // millis; 0 when unknown
        public boolean dateOnly;
        public String when = "";     // preformatted "wed, 25 nov · 12:00 wib"
        public String where = "";
        public String url = "";
        public String tags = "";     // preformatted "luma · online" style
        public String kind = "";     // film kind: cinema / streaming / festival / series
        public String role = "";     // care role: hosting / going / watching
        public String note = "";
    }

    private KapanData() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static long lastFetched(Context ctx) {
        return prefs(ctx).getLong(KEY_FETCHED, 0L);
    }

    /** Cached items for one audience, upcoming-first; undated entries last. */
    public static List<Item> items(Context ctx, String audience) {
        List<Item> out = new ArrayList<>();
        try {
            if (AUD_DEVELOPERS.equals(audience)) {
                parseEvents(readCache(ctx, "events.json"), out);
            } else {
                parseFilms(readCache(ctx, "films.json"), out);
            }
        } catch (Exception ignored) {
            // a broken cache shows as "no data yet", never a crash
        }
        return out;
    }

    public static boolean hasCache(Context ctx) {
        return new File(ctx.getFilesDir(), "events.json").exists()
                || new File(ctx.getFilesDir(), "films.json").exists();
    }

    /** False until the care feed has been downloaded at least once. */
    public static boolean hasCareCache(Context ctx) {
        return new File(ctx.getFilesDir(), "care.json").exists();
    }

    /** Next dated item still ahead of now, or null. */
    public static Item next(Context ctx, String audience, long nowMillis) {
        Item best = null;
        for (Item it : items(ctx, audience)) {
            if (it.start <= nowMillis) continue;
            if (best == null || it.start < best.start) best = it;
        }
        return best;
    }

    /** Next dated item across both audiences, or null. */
    public static Item nextAny(Context ctx, long nowMillis) {
        Item a = next(ctx, AUD_DEVELOPERS, nowMillis);
        Item b = next(ctx, AUD_MOVIEGOERS, nowMillis);
        if (a == null) return b;
        if (b == null) return a;
        return a.start <= b.start ? a : b;
    }

    /** On-device "i'm going" marks, kept on this phone only. */
    public static java.util.Set<String> marked(Context ctx) {
        return new java.util.HashSet<>(prefs(ctx).getStringSet(KEY_MARKED,
                new java.util.HashSet<String>()));
    }

    public static boolean isMarked(Context ctx, String uid) {
        return !uid.isEmpty() && marked(ctx).contains(uid);
    }

    public static void toggleMark(Context ctx, String uid) {
        if (uid.isEmpty()) return;
        java.util.Set<String> m = marked(ctx);
        if (!m.remove(uid)) m.add(uid);
        prefs(ctx).edit().putStringSet(KEY_MARKED, m).apply();
    }

    /**
     * Personal countdown list: the published care picks (hosted/going events,
     * care films) merged with on-device marks, upcoming first.
     */
    public static List<Item> careItems(Context ctx, long nowMillis) {
        List<Item> out = new ArrayList<>();
        try {
            parseCare(readCache(ctx, "care.json"), out);
        } catch (Exception ignored) {
            // no or broken care cache: on-device marks still apply
        }
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Item it : out) if (!it.uid.isEmpty()) have.add(it.uid);
        java.util.Set<String> marks = marked(ctx);
        if (!marks.isEmpty()) {
            for (String aud : new String[]{AUD_DEVELOPERS, AUD_MOVIEGOERS}) {
                for (Item it : items(ctx, aud)) {
                    if (it.uid.isEmpty() || have.contains(it.uid) || !marks.contains(it.uid)) continue;
                    it.role = "going (this phone)";
                    it.kind = AUD_MOVIEGOERS.equals(aud) ? "film" : "event";
                    out.add(it);
                    have.add(it.uid);
                }
            }
        }
        List<Item> upcoming = new ArrayList<>();
        for (Item it : out) {
            if (it.start <= 0) continue;
            long horizon = it.end > 0 ? it.end
                    : it.start + (it.dateOnly ? 20L * 3_600_000L : 3L * 3_600_000L);
            if (horizon > nowMillis) upcoming.add(it);
        }
        upcoming.sort((a, b) -> Long.compare(a.start, b.start));
        return upcoming;
    }

    /** Next care item still ahead of now; kind null means any kind. */
    public static Item nextCare(Context ctx, String kind, long nowMillis) {
        Item best = null;
        for (Item it : careItems(ctx, nowMillis)) {
            if (it.start <= nowMillis) continue;
            if (kind != null && !kind.equals(it.kind)) continue;
            if (best == null || it.start < best.start) best = it;
        }
        return best;
    }

    /** Download both listings into the cache. Returns an error text or null. */
    public static String fetch(Context ctx) {
        String err = null;
        err = download(EVENTS_URL, new File(ctx.getFilesDir(), "events.json"));
        String err2 = download(FILMS_URL, new File(ctx.getFilesDir(), "films.json"));
        if (err == null) err = err2;
        // care picks are optional: a failed download keeps the previous cache
        download(CARE_URL, new File(ctx.getFilesDir(), "care.json"));
        if (err == null) {
            prefs(ctx).edit().putLong(KEY_FETCHED, System.currentTimeMillis()).apply();
        }
        return err;
    }

    private static String download(String url, File dest) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "kapan-android/0.1 (" + url + ")");
            if (c.getResponseCode() != 200) return "http " + c.getResponseCode();
            try (InputStream in = c.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return null;
        } catch (IOException e) {
            return e.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readCache(Context ctx, String name) throws IOException {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static long parseIso(String s) {
        if (s == null || s.isEmpty()) return 0L;
        String[] fmts = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String fm : fmts) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fm, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static void parseEvents(String json, List<Item> out) throws Exception {
        if (json.isEmpty()) return;
        JSONArray events = new JSONObject(json).getJSONArray("events");
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            Item it = new Item();
            it.uid = e.optString("uid");
            it.title = e.optString("title");
            it.start = parseIso(e.optString("start"));
            it.end = parseIso(e.optString("end"));
            it.kind = "event";
            boolean online = e.optBoolean("online");
            String venue = e.optString("venue");
            it.where = online ? "online" : (venue.isEmpty() ? "venue to be announced" : venue);
            it.url = e.optString("url");
            List<String> tags = new ArrayList<>();
            if (online) tags.add("online");
            if (e.optBoolean("recurring")) tags.add("repeats");
            String org = e.optString("organizer");
            if (!org.isEmpty() && !"null".equals(org)) tags.add(org);
            String src = e.optString("source");
            if (!src.isEmpty()) tags.add(src);
            it.tags = String.join(" · ", tags);
            it.when = Format.when(it.start, false);
            out.add(it);
        }
    }

    private static void parseFilms(String json, List<Item> out) throws Exception {
        if (json.isEmpty()) return;
        JSONArray films = new JSONObject(json).getJSONArray("films");
        for (int i = 0; i < films.length(); i++) {
            JSONObject f = films.getJSONObject(i);
            Item it = new Item();
            it.uid = f.optString("uid");
            it.title = f.optString("title");
            it.start = parseIso(f.optString("start"));
            it.end = parseIso(f.optString("end"));
            it.dateOnly = true;
            String kind = f.optString("kind");
            it.kind = kind;
            JSONArray cities = f.optJSONArray("cities");
            List<String> cs = new ArrayList<>();
            if (cities != null) for (int j = 0; j < cities.length(); j++) cs.add(cities.getString(j));
            if ("cinema".equals(kind)) it.where = "cinemas across indonesia";
            else if ("streaming".equals(kind)) it.where = "streaming";
            else it.where = cs.isEmpty() ? "indonesia" : String.join(" · ", cs);
            it.url = f.optString("url");
            List<String> tags = new ArrayList<>();
            if (!kind.isEmpty()) tags.add(kind);
            if (!f.optBoolean("verified", true)) tags.add("date unconfirmed");
            String src = f.optString("source");
            if (!src.isEmpty()) tags.add(src);
            it.tags = String.join(" · ", tags);
            it.note = f.optString("note");
            it.when = it.start > 0 ? Format.day(it.start) : "date unconfirmed";
            out.add(it);
        }
    }

    private static void parseCare(String json, List<Item> out) throws Exception {
        if (json.isEmpty()) return;
        JSONArray items = new JSONObject(json).getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject c = items.getJSONObject(i);
            Item it = new Item();
            it.uid = c.optString("uid");
            it.title = c.optString("title");
            it.start = parseIso(c.optString("start"));
            it.end = parseIso(c.optString("end"));
            it.dateOnly = c.optBoolean("date_only");
            it.url = c.optString("url");
            it.kind = c.optString("kind");
            it.role = c.optString("role");
            it.where = c.optString("where");
            it.when = it.start > 0 ? Format.when(it.start, it.dateOnly) : "";
            out.add(it);
        }
    }
}
