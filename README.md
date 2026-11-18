# kapan

developer events in jakarta & jogja, on one page.

kapan gathers upcoming developer events (meetups, talks, workshops, hackathons) from public community pages and shows them on a single calendar, so it is easier to see how much is happening. every event links back to its organizer page - registration always happens there, not here.

live site: https://vanipy.com/kapan/
combined feed: https://vanipy.com/kapan/events.ics (subscribe by url in any calendar app)

## how it works

- a github actions job runs every few hours (and on demand)
- `scripts/fetch.py` (python, standard library only) reads `sources.json`, fetches each source, and rewrites `site/events.json`, `site/events.ics` and `site/index.html`
- github pages serves `site/` - fully static, no server, no keys, no accounts

## sources

all sources are public, keyless feeds:

- **luma**: the jakarta city discover page (https://lu.ma/jakarta) plus the public ical feed of every calendar that appears there. luma has no jogja city page, so jogja coverage comes from other sources.
- **meetup**: each group's public ical feed (`https://www.meetup.com/<group>/events/ical/`), for a hand-picked list of jakarta and jogja developer groups in `sources.json`.

an event is only listed when its venue or title shows jakarta or jogja, or when it is online. events announced for other cities are excluded. developer relevance is checked against the keyword list in `sources.json`.

## honest limits

- best effort, not exhaustive: an event that is not on a listed source does not appear
- posters come from the organizer's own event page (luma cover images, meetup/gdg event images), hotlinked, best effort; events without one just show text
- sources can fail or change; the site shows per-source status and last successful fetch, and keeps older events (marked) when a source is temporarily broken
- recurring events are expanded up to 60 days ahead and tagged "repeats"
- times are shown in western indonesia time (asia/jakarta)

## add a source

edit `sources.json` (add a meetup group slug or a luma calendar id) and open a pull request.

## license

agpl-3.0. see LICENSE.
