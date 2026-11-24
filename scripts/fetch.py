#!/usr/bin/env python3
"""Fetch developer events in Jakarta & Jogja from public sources (Luma, Meetup).

Stdlib only. Reads sources.json, writes site/events.json and site/events.ics.
Honest reporting: every source records fetched_at, last_success_at, event count
and error. A source that fails keeps its previously fetched events, marked stale.
"""
import json
import re
import sys
import unicodedata
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
APP_NAME = "kapan"
REPO = "irvan-putra/kapan"
REPO_URL = f"https://github.com/{REPO}"
UA = f"Mozilla/5.0 (compatible; {APP_NAME} events calendar; {REPO_URL})"
WIB = ZoneInfo("Asia/Jakarta")
NOW = datetime.now(timezone.utc)
WINDOW_DAYS = 90
MAX_HTTP = 40  # hard cap on requests per run, keeps Actions usage small

ONLINE_TERMS = ["online", "virtual", "zoom", "google meet", "webinar", "livestream",
                "live stream", "youtube", "discord", "remote", "daring"]
LUMA_URL_RE = re.compile(r"lu(?:ma\.com|\.ma)/([A-Za-z0-9]+)")
TBD_TERMS = ["tbd", "tba", "to be announced", "to be determined", "menyusul"]
OTHER_CITY_RE = re.compile(r"\b(bali|denpasar|bandung|surabaya|semarang|medan|malang|makassar|singapore|kuala lumpur|bangkok|manila|batam|palembang)\b")

_http_count = 0


def http_get(url, accept="*/*"):
    global _http_count
    if _http_count >= MAX_HTTP:
        raise RuntimeError("request budget exhausted")
    _http_count += 1
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def fetch_og_image(url):
    """Best-effort poster: the og:image of the organizer's own event page."""
    try:
        text = http_get(url, "text/html")
    except Exception:
        return None
    m = re.search(r'<meta[^>]+property=["\']og:image["\'][^>]+content=["\']([^"\']+)["\']', text)
    if not m:
        m = re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+property=["\']og:image["\']', text)
    if not m:
        return None
    u = m.group(1).strip().replace("&amp;", "&")
    if u.startswith("https://"):
        return u
    return None


def fetch_luma_page_event(url):
    """Public Luma event page -> facts from its embedded __NEXT_DATA__."""
    try:
        text = http_get(url, "text/html")
    except Exception:
        return None
    m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', text, re.S)
    if not m:
        return None
    try:
        data = json.loads(m.group(1))
        ev = data["props"]["pageProps"]["initialData"]["data"]["event"]
    except Exception:
        return None
    if not isinstance(ev, dict) or not ev.get("start_at"):
        return None
    try:
        start = datetime.fromisoformat(ev["start_at"].replace("Z", "+00:00"))
    except ValueError:
        return None
    end = None
    if ev.get("end_at"):
        try:
            end = datetime.fromisoformat(ev["end_at"].replace("Z", "+00:00"))
        except ValueError:
            pass
    geo = ev.get("geo_address_info") or {}
    loc_text = geo.get("address") or geo.get("city_state") or geo.get("city") or geo.get("full_address") or ""
    online = ev.get("location_type") in ("online", "virtual")
    return {
        "title": ev.get("name") or "",
        "start": iso(start), "end": iso(end) if end else None,
        "online": online, "venue": None if online else (loc_text or None),
        "url": url, "poster": ev.get("cover_url"),
        "uid": f"luma-{ev.get('api_id')}" if ev.get("api_id") else None,
    }


def fetch_luma_event(api_id):
    """Poster + real event page for a Luma ICS event (feeds carry no URL/image)."""
    try:
        d = json.loads(http_get(f"https://api.lu.ma/event/get?event_api_id={api_id}",
                                "application/json"))
        ev = d.get("event", {})
        slug = ev.get("url")
        return ev.get("cover_url"), (f"https://lu.ma/{slug}" if slug else None)
    except Exception:
        return None, None


# ---------------- ICS parsing ----------------

def unfold(text):
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out = []
    for line in lines:
        if line[:1] in (" ", "\t") and out:
            out[-1] += line[1:]
        else:
            out.append(line)
    return out


def parse_dt(value, params):
    tzid = params.get("TZID")
    value = value.strip()
    if params.get("VALUE") == "DATE" or re.fullmatch(r"\d{8}", value):
        d = datetime.strptime(value[:8], "%Y%m%d")
        return d.replace(tzinfo=timezone.utc), True
    fmt = "%Y%m%dT%H%M%S"
    if value.endswith("Z"):
        return datetime.strptime(value, fmt + "Z").replace(tzinfo=timezone.utc), False
    dt = datetime.strptime(value, fmt)
    if tzid:
        try:
            return dt.replace(tzinfo=ZoneInfo(tzid)), False
        except Exception:
            pass
    # floating local time: treat as WIB, sources here are Indonesian
    return dt.replace(tzinfo=WIB), False


def unescape(s):
    return s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",") \
            .replace("\\;", ";").replace("\\\\", "\\")


def parse_ics(text):
    """Return list of raw VEVENT dicts."""
    events, cur = [], None
    for line in unfold(text):
        if line == "BEGIN:VEVENT":
            cur = {}
            continue
        if line == "END:VEVENT":
            if cur is not None:
                events.append(cur)
            cur = None
            continue
        if cur is None or ":" not in line:
            continue
        head, value = line.split(":", 1)
        parts = head.split(";")
        name = parts[0].upper()
        params = {}
        for p in parts[1:]:
            if "=" in p:
                k, v = p.split("=", 1)
                params[k.upper()] = v
        if name in ("DTSTART", "DTEND"):
            try:
                dt, all_day = parse_dt(value, params)
                cur[name.lower()] = dt
                cur[name.lower() + "_allday"] = all_day
            except ValueError:
                pass
        elif name in ("SUMMARY", "LOCATION", "URL", "UID", "DESCRIPTION", "STATUS", "RRULE"):
            cur[name.lower()] = unescape(value)
        elif name == "SEQUENCE":
            cur["sequence"] = value.strip()
    return events


def expand_rrule(start, rrule, window_end):
    """Expand simple DAILY/WEEKLY recurrence within the window. Returns list of starts."""
    if not rrule:
        return [start]
    parts = dict(p.split("=", 1) for p in rrule.split(";") if "=" in p)
    freq = parts.get("FREQ", "").upper()
    step = {"DAILY": timedelta(days=1), "WEEKLY": timedelta(weeks=1)}.get(freq)
    if step is None:
        return [start]  # unsupported freq: show first occurrence only
    interval = int(parts.get("INTERVAL", "1") or 1)
    step = step * interval
    until = None
    if parts.get("UNTIL"):
        try:
            until, _ = parse_dt(parts["UNTIL"], {})
        except ValueError:
            until = None
    count = int(parts.get("COUNT", "200") or 200)
    out, t = [], start
    while t <= window_end and len(out) < count:
        if until and t > until:
            break
        out.append(t)
        t += step
    return out


# ---------------- classification ----------------

def norm(s):
    s = unicodedata.normalize("NFKD", (s or "").lower())
    return re.sub(r"[^a-z0-9]+", "", s)


def classify_city(text, cities):
    t = f" {text or ''} ".lower()
    for cid, c in cities.items():
        for term in c["region_terms"]:
            if term in t:
                return cid
    return None


def is_online(text):
    t = (text or "").lower()
    return any(term in t for term in ONLINE_TERMS)


def is_tbd(text):
    t = (text or "").lower()
    return any(term in t for term in TBD_TERMS) or not t.strip()


NON_DEV_TITLE_RE = re.compile(
    r"\b(hr|human resources|recruit(?:er|ing|ment)?|people ops|marketing|sales|"
    r"finance|fintech|invest(?:or|ing|ment)?|property|real estate|legal|accounting|"
    r"fundraising|venture capital)\b", re.I)


def is_dev_event(title, description, keywords):
    if NON_DEV_TITLE_RE.search(title or ""):
        return False  # non-developer audience even when the topic is tech
    t = f" {(title or '').lower()} "
    d = f" {(description or '').lower()[:2000]} "
    return any(k in t or k in d for k in keywords)


# ---------------- sources ----------------

def fetch_luma_city(slug, city_id, cfg, source_log):
    """City discover page: events embedded in __NEXT_DATA__, plus calendar ids."""
    page_url = f"https://lu.ma/{slug}"
    log = {"id": f"luma-city-{slug}", "type": "luma-city", "name": f"luma {slug} city page",
           "page_url": page_url, "feed_url": page_url, "fetched_at": iso(NOW)}
    events, calendars = [], {}
    try:
        text = http_get(page_url, "text/html")
        m = re.search(r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>', text, re.S)
        if not m:
            raise RuntimeError("no __NEXT_DATA__ on page (site layout changed)")
        data = json.loads(m.group(1))
        entries = data["props"]["pageProps"]["initialData"]["data"].get("events", [])
        for entry in entries:
            ev = entry.get("event", {})
            cal = entry.get("calendar", {}) or {}
            if cal.get("api_id"):
                calendars[cal["api_id"]] = cal.get("name") or cal["api_id"]
            start = ev.get("start_at")
            if not start:
                continue
            title = ev.get("name") or ""
            desc = ev.get("description") or ""
            if not is_dev_event(title, desc, cfg["dev_keywords"]):
                continue
            geo = ev.get("geo_address_info") or {}
            loc_text = geo.get("address") or geo.get("city_state") or geo.get("city") or geo.get("full_address") or ""
            online = ev.get("location_type") in ("online", "virtual") or is_online(loc_text)
            city = city_id if online else (classify_city(loc_text, cfg["cities"]) or city_id)
            try:
                dt = datetime.fromisoformat(start.replace("Z", "+00:00"))
            except ValueError:
                continue
            end = None
            if ev.get("end_at"):
                try:
                    end = datetime.fromisoformat(ev["end_at"].replace("Z", "+00:00"))
                except ValueError:
                    pass
            events.append({
                "title": title, "start": iso(dt), "end": iso(end) if end else None,
                "city": city, "online": online, "venue": None if online else (loc_text or None),
                "url": f"https://lu.ma/{ev.get('url')}" if ev.get("url") else page_url,
                "source": "luma", "organizer": None if (cal.get("name") or "").lower() in ("personal", "") else cal.get("name"),
                "uid": f"luma-{ev.get('api_id')}", "source_id": f"luma-city-{slug}",
                "poster": ev.get("cover_url"),
            })
        log.update(ok=True, count=len(events), last_success_at=iso(NOW))
        log["calendars_found"] = len(calendars)
    except Exception as e:
        log.update(ok=False, count=0, error=f"{type(e).__name__}: {e}")
    source_log.append(log)
    return events, calendars


def fetch_ics_feed(feed_url, source_id, name, page_url, source_log, prev,
                   dev_filter=None, cities=None):
    log = {"id": source_id, "name": name, "page_url": page_url, "feed_url": feed_url,
           "fetched_at": iso(NOW)}
    events = []
    try:
        text = http_get(feed_url, "text/calendar")
        if "BEGIN:VCALENDAR" not in text:
            raise RuntimeError("response is not a calendar feed")
        window_end = NOW + timedelta(days=WINDOW_DAYS)
        window_start = NOW - timedelta(days=1)
        for raw in parse_ics(text):
            if (raw.get("status") or "").upper() == "CANCELLED":
                continue
            start = raw.get("dtstart")
            if not start:
                continue
            starts = expand_rrule(start, raw.get("rrule"), min(window_end, NOW + timedelta(days=60)))
            for occ in starts:
                if occ < window_start or occ > window_end:
                    continue
                title = raw.get("summary") or ""
                if dev_filter and not is_dev_event(title, raw.get("description"), dev_filter):
                    continue
                loc = raw.get("location") or ""
                online = is_online(loc) or is_online(raw.get("description") or "")
                hay = f"{loc} {title}"
                other_city = OTHER_CITY_RE.search(hay.lower()) if not online else None
                city = None if (online or other_city) else classify_city(hay, cities)
                if other_city:
                    continue  # announced for another city, not jakarta/jogja
                venue = None if online else (loc if not is_tbd(loc) else None)
                events.append({
                    "title": title, "start": iso(occ),
                    "end": iso(raw["dtend"]) if raw.get("dtend") else None,
                    "city": city, "online": online, "venue": venue,
                    "url": raw.get("url") or page_url,
                    "source": source_id.split("-")[0], "organizer": name,
                    "uid": raw.get("uid") or f"{source_id}-{norm(title)}-{iso(occ)[:10]}",
                    "venue_tbd": (venue is None and not online), "source_id": source_id,
                    "recurring": bool(raw.get("rrule")),
                })
        log.update(ok=True, count=len(events), last_success_at=iso(NOW))
    except Exception as e:
        log.update(ok=False, count=0, error=f"{type(e).__name__}: {e}")
        if prev:
            kept = [ev for ev in prev if ev.get("source_id") == source_id]
            for ev in kept:
                ev["stale"] = True
            events = kept
            log["kept_stale"] = len(kept)
            if prev_last.get(source_id):
                log["last_success_at"] = prev_last[source_id]
    source_log.append(log)
    return events


def fetch_gdg_chapter(slug, source_log, prev, cfg):
    """GDG chapter on gdg.community.dev: scrape the chapter page for its numeric
    id, read its public upcoming-events API, then each event page's JSON-LD for
    venue/city evidence."""
    page_url = f"https://gdg.community.dev/{slug}/"
    source_id = f"gdg-{slug}"
    log = {"id": source_id, "type": "gdg", "name": slug.replace("-", " "),
           "page_url": page_url, "feed_url": page_url, "fetched_at": iso(NOW)}
    events = []
    try:
        text = http_get(page_url, "text/html")
        m = re.search(r"event_slim/for_chapter/(\d+)", text)
        if not m:
            raise RuntimeError("chapter id not found on page")
        api = (f"https://gdg.community.dev/api/event_slim/for_chapter/{m.group(1)}/"
               "?page_size=20&status=Live&include_cohosted_events=true&order=start_date"
               "&fields=title,start_date,end_date,event_type_title,url,description_short,picture")
        log["feed_url"] = api
        data = json.loads(http_get(api, "application/json"))
        for ev in data.get("results", []):
            url = ev.get("url") or ""
            if url and url.startswith("/"):
                url = "https://gdg.community.dev" + url
            title = ev.get("title") or ""
            loc_text, start, end = "", None, None
            if url:
                try:
                    page = http_get(url, "text/html")
                    ld = re.search(r'<script type="application/ld\+json">(.*?)</script>', page, re.S)
                    if ld:
                        jd = json.loads(ld.group(1))
                        loc = jd.get("location") or {}
                        if isinstance(loc, dict):
                            loc_text = " ".join(str(loc.get(k) or "") for k in ("name", "address"))
                        start = jd.get("startDate") or start
                        end = jd.get("endDate") or end
                except Exception:
                    pass  # fall back to api fields
            start = start or ev.get("start_date")
            end = end or ev.get("end_date")
            try:
                dt = datetime.fromisoformat(str(start).replace("Z", "+00:00"))
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=WIB)
            except (ValueError, TypeError):
                continue
            try:
                de = datetime.fromisoformat(str(end).replace("Z", "+00:00")) if end else None
                if de and de.tzinfo is None:
                    de = de.replace(tzinfo=WIB)
            except (ValueError, TypeError):
                de = None
            hay = f"{loc_text} {title}"
            online = is_online(hay) or (ev.get("event_type_title") or "").lower() == "online"
            if not online and OTHER_CITY_RE.search(hay.lower()):
                continue
            city = None if online else classify_city(hay, cfg["cities"])
            venue = None if online else (loc_text.strip() or None)
            if dt < NOW - timedelta(days=1) or dt > NOW + timedelta(days=WINDOW_DAYS):
                continue
            events.append({
                "title": title, "start": iso(dt), "end": iso(de) if de else None,
                "city": city, "online": online, "venue": venue,
                "url": url or page_url, "source": "gdg",
                "organizer": slug.replace("-", " "),
                "uid": f"gdg-{norm(title)}-{iso(dt)[:10]}", "source_id": source_id,
                "venue_tbd": (venue is None and not online),
                "poster": ev.get("picture") if ev.get("picture") and "DefaultEventThumbnail" not in ev.get("picture") else None,
            })
        log.update(ok=True, count=len(events), last_success_at=iso(NOW))
    except Exception as e:
        log.update(ok=False, count=0, error=f"{type(e).__name__}: {e}")
        kept = [ev for ev in prev if ev.get("source_id") == source_id]
        for ev in kept:
            ev["stale"] = True
        if kept:
            events = kept
            log["kept_stale"] = len(kept)
        if prev_last.get(source_id):
            log["last_success_at"] = prev_last[source_id]
    source_log.append(log)
    return events




# ---------------- films (moviegoers tab) ----------------

def parse_day(s):
    """YYYY-MM-DD -> datetime at WIB day start, or None."""
    if not s:
        return None
    try:
        return datetime.strptime(s, "%Y-%m-%d").replace(tzinfo=WIB)
    except ValueError:
        return None


def load_films(cfg, prev_films):
    """Hand-tracked films/festivals from sources.json.

    Cinema chain sites block automated fetches, so entries are curated: each
    names the public page its date came from and when it was checked. Entries
    without a confirmed date stay listed, marked unverified. A cinema release
    stays listed for a typical 20-day run when no end is given; festivals and
    series keep their configured end (or stay until edited). Posters reuse the
    previous run's, then best-effort og:image from the source page.
    """
    films = []
    cutoff = NOW - timedelta(days=1)
    for f in cfg.get("films", []):
        start = parse_day(f.get("start"))
        end = parse_day(f.get("end"))
        if start and not end and f.get("kind") == "cinema":
            end = start + timedelta(days=20)  # typical indonesian run, varies by cinema
        horizon = end or start
        if horizon and horizon < cutoff:
            continue
        films.append({
            "title": f.get("title") or "",
            "kind": f.get("kind") or "cinema",
            "cities": f.get("cities") or [],
            "start": iso(start) if start else None,
            "date_only": True,
            "end": iso(end) if end else None,
            "url": f.get("url") or "",
            "source": f.get("source") or "",
            "verified": bool(f.get("verified")),
            "checked": f.get("checked"),
            "note": f.get("note") or "",
            "care": bool(f.get("care")),
            "uid": "film-" + norm(f.get("title") or ""),
        })
    prev_posters = {pf["uid"]: pf["poster"] for pf in prev_films
                    if pf.get("uid") and pf.get("poster")}
    for f in films:
        if f["uid"] in prev_posters:
            f["poster"] = prev_posters[f["uid"]]
    dated = sorted((f for f in films if f["start"]), key=lambda f: f["start"])
    undated = [f for f in films if not f["start"]]
    return dated + undated


# ---------------- main ----------------

def parse_care_dt(s):
    """'YYYY-MM-DD' or 'YYYY-MM-DD HH:MM' (WIB) -> datetime, or None."""
    if not s:
        return None
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(s, fmt).replace(tzinfo=WIB)
        except ValueError:
            pass
    return None


def load_care(cfg, final_events, films):
    """Hand-picked countdown list: events the maintainer hosts or is going to
    (care.events in sources.json, public titles/dates/links only), plus films
    flagged care. Enriched from the fetched listings when the event is already
    tracked; otherwise read from its public Luma page."""
    items = []
    cutoff = NOW - timedelta(days=1)
    by_slug = {}
    for ev in final_events:
        mslug = LUMA_URL_RE.search(ev.get("url") or "")
        if mslug:
            by_slug[mslug.group(1)] = ev
    for c in (cfg.get("care") or {}).get("events", []):
        url = c.get("url") or ""
        role = c.get("role") or "going"
        slug_m = LUMA_URL_RE.search(url)
        ev = by_slug.get(slug_m.group(1)) if slug_m else None
        if ev is None and c.get("title"):
            nt = norm(c["title"])
            ev = next((cand for cand in final_events if norm(cand["title"]) == nt), None)
        if ev is None and slug_m and _http_count < MAX_HTTP:
            ev = fetch_luma_page_event(url)
        if ev is None:
            start = parse_care_dt(c.get("start"))
            if not start:
                continue  # nothing verifiable to count down to
            end_cfg = parse_care_dt(c.get("end"))
            ev = {"title": c.get("title") or "", "start": iso(start),
                  "end": iso(end_cfg) if end_cfg else None,
                  "url": url, "venue": c.get("where"), "online": False,
                  "poster": None, "uid": None}
        if not ev.get("start"):
            continue
        try:
            start_dt = datetime.fromisoformat(ev["start"].replace("Z", "+00:00"))
        except ValueError:
            continue
        end_dt = None
        if ev.get("end"):
            try:
                end_dt = datetime.fromisoformat(ev["end"].replace("Z", "+00:00"))
            except ValueError:
                pass
        if (end_dt or start_dt) < cutoff:
            continue
        items.append({
            "uid": ev.get("uid") or f"care-{norm(c.get('title') or ev['title'])}-{ev['start'][:10]}",
            "title": ev["title"], "start": ev["start"], "end": ev.get("end"),
            "kind": "event", "role": role, "url": ev.get("url") or url,
            "where": "online" if ev.get("online") else (ev.get("venue") or ""),
            "poster": ev.get("poster"), "date_only": False,
        })
    for f in films:
        if not f.get("care") or not f.get("start"):
            continue
        start_dt = datetime.fromisoformat(f["start"].replace("Z", "+00:00"))
        end_dt = None
        if f.get("end"):
            try:
                end_dt = datetime.fromisoformat(f["end"].replace("Z", "+00:00"))
            except ValueError:
                pass
        if (end_dt or start_dt) < cutoff:
            continue
        if f["kind"] == "cinema":
            where = "cinemas across indonesia"
        elif f["kind"] == "streaming":
            where = "streaming"
        else:
            where = " · ".join(f.get("cities") or []) or "indonesia"
        items.append({
            "uid": f["uid"], "title": f["title"], "start": f["start"], "end": f.get("end"),
            "kind": "film", "role": "watching", "url": f.get("url") or "",
            "where": where, "poster": f.get("poster"), "date_only": True,
        })
    items.sort(key=lambda i: i["start"])
    return items


def main():
    global prev_last
    cfg = json.loads((ROOT / "sources.json").read_text())
    old = {}
    prev_last = {}
    old_events_path = SITE / "events.json"
    if old_events_path.exists():
        try:
            old = json.loads(old_events_path.read_text())
            for s in old.get("sources", []):
                if s.get("last_success_at"):
                    prev_last[s["id"]] = s["last_success_at"]
        except Exception:
            old = {}
    prev_events = old.get("events", [])
    prev_films = []
    old_films_path = SITE / "films.json"
    if old_films_path.exists():
        try:
            prev_films = json.loads(old_films_path.read_text()).get("films", [])
        except Exception:
            prev_films = []

    source_log, all_events = [], []

    # 1. Luma city pages (dev-keyword filtered) + calendars discovered there
    luma_calendars = {c["id"]: c["name"] for c in cfg.get("luma_calendars", [])}
    for cid, c in cfg["cities"].items():
        if c.get("luma_city_slug"):
            evs, cals = fetch_luma_city(c["luma_city_slug"], cid, cfg, source_log)
            all_events.extend(evs)
            luma_calendars.update(cals)
    # 2. Luma calendar feeds (keyword filtered, city from venue text)
    cal_all = {c["id"] for c in cfg.get("luma_calendars", []) if c.get("all")}
    for cal_id, cal_name in sorted(luma_calendars.items()):
        label = cal_name if cal_name and cal_name.lower() != "personal" else f"luma community calendar {cal_id[-6:]}"
        cal_filter = None if cal_id in cal_all else cfg["dev_keywords"]
        feed = f"https://api.lu.ma/ics/get?entity=calendar&id={cal_id}"
        all_events.extend(fetch_ics_feed(
            feed, f"luma-cal-{cal_id}", label, f"https://lu.ma/calendar/{cal_id}",
            source_log, prev_events,
            dev_filter=cal_filter, cities=cfg["cities"]))
    # 3. Meetup group feeds (groups are hand-picked dev groups; city from venue text)
    for g in cfg["meetup_groups"]:
        slug = g["slug"]
        feed = f"https://www.meetup.com/{slug}/events/ical/"
        all_events.extend(fetch_ics_feed(
            feed, f"meetup-{slug}", slug.replace("-", " "),
            f"https://www.meetup.com/{slug}/", source_log, prev_events,
            dev_filter=None, cities=cfg["cities"]))

    # 4. GDG chapters on gdg.community.dev
    for ch in cfg.get("gdg_chapters", []):
        all_events.extend(fetch_gdg_chapter(ch["slug"], source_log, prev_events, cfg))

    # 5. Moviegoers tab: curated film/festival entries
    films = load_films(cfg, prev_films)

    # dedupe: same normalized title + same day = same event, keep first (richest wins below)
    seen, deduped = {}, []
    for ev in sorted(all_events, key=lambda e: e["start"]):
        key = (norm(ev["title"]), ev["start"][:10])
        if key in seen:
            other = seen[key]
            # merge: prefer the one with a venue; record the duplicate's url
            keep, drop = (ev, other) if ev.get("venue") and not other.get("venue") else (other, ev)
            keep.setdefault("also_on", [])
            if drop["url"] != keep["url"] and drop["url"] not in keep["also_on"]:
                keep["also_on"].append(drop["url"])
            if not keep.get("poster") and drop.get("poster"):
                keep["poster"] = drop["poster"]
            seen[key] = keep
            if keep not in deduped:
                deduped.append(keep)
            continue
        seen[key] = ev
        deduped.append(ev)

    # keep only events in our cities or online, within window
    final = [ev for ev in deduped if ev.get("online") or ev.get("city") in cfg["cities"]]

    # posters: keep ones we already found, then best-effort og:image from the
    # organizer's own event page for the rest (earliest events first)
    prev_posters = {ev["uid"]: ev["poster"] for ev in prev_events if ev.get("uid") and ev.get("poster")}
    for ev in final:
        if not ev.get("poster") and ev["uid"] in prev_posters:
            ev["poster"] = prev_posters[ev["uid"]]
    enrich_left = min(12, MAX_HTTP - _http_count)
    for ev in final:
        if enrich_left <= 0:
            break
        if ev.get("poster"):
            continue
        m = re.fullmatch(r"(evt-[^@]+)@events\.lu\.ma", ev.get("uid") or "")
        if m:
            img, real_url = fetch_luma_event(m.group(1))
            # feed linked only the calendar page: point at the event page instead
            if real_url and ev["url"].startswith("https://lu.ma/calendar/"):
                ev["url"] = real_url
        else:
            img = fetch_og_image(ev["url"])
        enrich_left -= 1
        if img:
            ev["poster"] = img
    posters = sum(1 for ev in final if ev.get("poster"))

    enrich_left = MAX_HTTP - _http_count
    for f in films:
        if enrich_left <= 0:
            break
        if f.get("poster") or not f.get("url"):
            continue
        img = fetch_og_image(f["url"])
        enrich_left -= 1
        if img:
            f["poster"] = img

    films_out = {"app": APP_NAME, "generated_at": iso(NOW),
                 "timezone": "Asia/Jakarta", "films": films}
    old_films_path.write_text(json.dumps(films_out, indent=1, ensure_ascii=False))

    care_items = load_care(cfg, final, films)
    care_out = {"app": APP_NAME, "generated_at": iso(NOW),
                "timezone": "Asia/Jakarta", "items": care_items}
    (SITE / "care.json").write_text(json.dumps(care_out, indent=1, ensure_ascii=False))

    out = {
        "app": APP_NAME, "repo": REPO, "generated_at": iso(NOW),
        "timezone": "Asia/Jakarta", "window_days": WINDOW_DAYS,
        "cities": list(cfg["cities"].keys()),
        "sources": source_log,
        "events": final,
    }
    SITE.mkdir(exist_ok=True)
    old_events_path.write_text(json.dumps(out, indent=1, ensure_ascii=False))

    # combined ICS
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", f"PRODID:-//{APP_NAME}//events//EN",
             "CALSCALE:GREGORIAN", "METHOD:PUBLISH", f"X-WR-CALNAME:{APP_NAME} dev events",
             "X-WR-TIMEZONE:Asia/Jakarta"]
    def esc(s):
        return (s or "").replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    def fold(line):
        while len(line.encode()) > 74:
            cut = 73
            while len(line[:cut].encode()) > 73:
                cut -= 1
            yield line[:cut]
            line = " " + line[cut:]
        yield line
    for ev in final:
        dtstart = datetime.fromisoformat(ev["start"].replace("Z", "+00:00")).strftime("%Y%m%dT%H%M%SZ")
        lines += ["BEGIN:VEVENT", f"UID:{ev['uid']}@{APP_NAME}",
                  f"DTSTAMP:{NOW.strftime('%Y%m%dT%H%M%SZ')}", f"DTSTART:{dtstart}"]
        if ev.get("end"):
            lines.append("DTEND:" + datetime.fromisoformat(ev["end"].replace("Z", "+00:00")).strftime("%Y%m%dT%H%M%SZ"))
        lines.append(f"SUMMARY:{esc(ev['title'])}")
        if ev.get("venue"):
            lines.append(f"LOCATION:{esc(ev['venue'])}")
        elif ev.get("online"):
            lines.append("LOCATION:online")
        lines.append(f"URL:{ev['url']}")
        lines.append(f"DESCRIPTION:{esc(ev['url'])}")
        lines.append("END:VEVENT")
    lines.append("END:VCALENDAR")
    ics = []
    for l in lines:
        ics.extend(fold(l))
    (SITE / "events.ics").write_text("\r\n".join(ics) + "\r\n")

    # render static page with embedded data
    tpl = (ROOT / "scripts" / "template.html").read_text()
    data_json = json.dumps(out, ensure_ascii=False).replace("</", "<\\/")
    films_json = json.dumps(films_out, ensure_ascii=False).replace("</", "<\\/")
    care_json = json.dumps(care_out, ensure_ascii=False).replace("</", "<\\/")
    audiences_json = json.dumps(cfg.get("audiences") or
        {"developers": {"label": "developers"}, "moviegoers": {"label": "moviegoers"}},
        ensure_ascii=False)
    for sub, city in (("", "all"), ("jakarta", "jakarta"), ("jogja", "jogja")):
        page = tpl.replace("__APPNAME__", APP_NAME).replace("__REPO__", REPO) \
                  .replace("__DATAJSON__", data_json).replace("__CITY__", city) \
                  .replace("__FILMSJSON__", films_json) \
                  .replace("__CAREJSON__", care_json) \
                  .replace("__AUDIENCESJSON__", audiences_json) \
                  .replace("__BASE__", "/" + APP_NAME + ("/" + sub if sub else ""))
        d = SITE / sub
        d.mkdir(parents=True, exist_ok=True)
        (d / "index.html").write_text(page)

    ok = sum(1 for s in source_log if s.get("ok"))
    print(f"{len(final)} events ({posters} with posters), {len(films)} films, {len(care_items)} care items, {ok}/{len(source_log)} sources ok, {_http_count} requests")
    for s in source_log:
        flag = "ok" if s.get("ok") else f"FAIL {s.get('error')}"
        print(f"  {s['id']}: {flag}, {s.get('count')} events" + (f" (kept {s['kept_stale']} stale)" if s.get("kept_stale") else ""))


prev_last = {}
if __name__ == "__main__":
    main()
