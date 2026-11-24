# kapan

developer events & film dates in jakarta & jogja, on one page - and one app.

kapan gathers upcoming developer events (meetups, talks, workshops, hackathons) from public community pages, and hand-tracks films, festivals and streaming dates for moviegoers (the easy-to-miss ones especially). two audience tabs on one calendar: **developers** and **moviegoers**. every entry links back to its source page - registration and tickets always happen there, not here.

live site: https://vanipy.com/kapan/ (tabs: developers · moviegoers; `#moviegoers` opens the film tab directly)
combined developer feed: https://vanipy.com/kapan/events.ics (subscribe by url in any calendar app)
android app (APK + home-screen widget): [latest release](https://github.com/irvan-putra/kapan/releases) - gallery card at https://vanipy.com/kaji/apk/

## how it works

- a github actions job runs monthly (and on demand); github may delay scheduled runs, so events can be up to a month stale unless refreshed manually
- `scripts/fetch.py` (python, standard library only) reads `sources.json`, fetches each source, and rewrites `site/events.json`, `site/films.json`, `site/care.json`, `site/events.ics` and the static pages
- github pages serves `site/` - fully static, no server, no keys, no accounts
- hand-picked countdowns (hosted/going events from `care.events`, films flagged `care`) tick above the list; the next event/film countdown sits above the list too; the tab choice sticks (url hash + saved preference)

## sources

developer events come from public, keyless feeds:

- **luma**: the jakarta city discover page (https://lu.ma/jakarta) plus the public ical feed of every calendar that appears there. luma has no jogja city page, so jogja coverage comes from other sources.
- **meetup**: each group's public ical feed (`https://www.meetup.com/<group>/events/ical/`), for a hand-picked list of jakarta and jogja developer groups in `sources.json`.
- **gdg**: chapter pages on gdg.community.dev.

an event is only listed when its venue or title shows jakarta or jogja, or when it is online. developer relevance is checked against the keyword list in `sources.json`.

moviegoer entries are hand-tracked in `sources.json`: cinema chains (xxi, cgv, cinepolis) block automated fetches, so each film/festival entry names the public page its date came from and when it was last checked (checks are done by instinct, an ai assistant - not a person). entries without a confirmed date stay listed, labeled **date unconfirmed**.

## android app

`app/` builds `com.vanipy.kapan`: the same two tabs over the public listings, cached on the phone for offline reading, plus a personal countdown panel (published care picks + on-device "i'm going" marks, with a "tell instinct" share to add more) and a resizable home-screen widget (1x1 circular countdown, larger sizes show your next event and film). one permission (INTERNET). releases are signed with the kapan key (secrets in this repo's actions secrets; keystore backup in the private cadang repo per the family rule) and gated by patuh.

## honest limits

- best effort, not exhaustive: an event that is not on a listed source does not appear
- posters come from the organizer's own event page (luma cover images, meetup/gdg event images, festival pages), hotlinked, best effort; entries without one just show text
- sources can fail or change; the site shows per-source status and last successful fetch, and keeps older events (marked) when a source is temporarily broken
- recurring events are expanded up to 60 days ahead and tagged "repeats"
- times are shown in western indonesia time (asia/jakarta)

## add a source

edit `sources.json` (add a meetup group slug, a luma calendar id, or a film entry with its source page) and open a pull request.

## license

agpl-3.0. see LICENSE.
