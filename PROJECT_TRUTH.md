# Clan Clog Project Truth

This is the living truth doc for the Clan Clog RuneLite plugin. If README,
historical research, porting logs, source comments, or handoff docs disagree
with this file, update the stale file or treat it as legacy until reconciled.

Runtime truth still comes from the current source and the real dev client.
Product and architecture decisions live here.

## Current State

- Repository: `420kc/clan-clog`
- Plugin version: `0.1.0`
- Branch: `master`
- Status: alpha, active development.
- Plugin Hub submission is gated on the killclog.com clan aggregation backend
  reaching production capacity and real-client smoke.

## Product Shape

Clan Clog is the clan-scale sibling of Kill Clog. It is a clan identity surface:
search a clan name, see a shared PvM trophy case, and understand the clan flex
without reading a report.

It is not a leaderboard. The profile should feel OSRS/RuneLite-native,
compact, proof-backed, and screenshot-worthy.

## Data Ownership

The current target architecture is backend-authoritative:

- The plugin reads public clan profiles from `https://killclog.com/api/clan/*`.
- `GET /api/clan/<slug>` is the primary Clan Profile contract.
- `GET /api/clan/<slug>/clog` is a legacy aggregate fallback while the API
  contract settles.
- `POST /api/clan/<slug>/sync` sends an owner/deputy-verified roster snapshot.
- After a roster sync, the plugin refreshes from the backend. It does not own
  the durable aggregate profile.
- `POST /api/clan/<slug>/events` is passive roster-event evidence. It is not a
  primary read path.

Roster-scale TempleOSRS/RuneProfile fanout belongs to the killclog.com web API,
not the RuneLite plugin. The API should take roster sources such as WOM or a
verified in-game roster, fan out to providers, store the build, and serve the
result back to both the web page and plugin.

## Transitional Drift

The plugin still contains a manual own-clan `clanalyze` path that can fan out
per-member hiscore/clog lookups from a verified in-game roster. Treat this as a
legacy/bootstrap path, not as the product direction. Do not expand it without an
explicit product decision.

The next architecture cleanup should either retire the in-client provider
fanout or narrow it to local/cache-only preview behavior that cannot be confused
with the backend source of truth.

## Sync Rules

- Sync is off by default in config.
- Sync requires the user to be in the clan roster and have OWNER or DEPUTY_OWNER
  rank.
- Sync sends roster RSNs and ranks to killclog.com.
- Sync must be manual. It must not fire automatically from startup, search, or
  passive roster refresh.
- The visible profile after sync should come back from the backend, because the
  backend owns provider fanout and aggregate truth.
- Clogsworth narration is local-only and must not be posted to clan chat.

## Provider Model

Current provider model:

- Jagex hiscores: public KC, activity, skill, and account data.
- TempleOSRS: collection-log provider and useful account metadata.
- RuneProfile: collection-log provider and newer/fresher clog payloads when
  available.
- WOM: public roster discovery/bootstrap source.
- In-game ClanSettings: highest-trust source for the user's own roster and
  sync authority evidence.

Provider truth should be merged carefully. Fresher collection-log data can come
from RuneProfile while Temple-only metadata may still need to be preserved.

## UI Canon

- Keep the RuneLite/Kill Clog visual language: black, Courier, compact rows,
  sprites, K4 red-orange identity accents, K2 green proof/success numbers, and
  K3 amber chrome/dividers.
- No SaaS dashboard look, large rounded cards, glow spam, or webby cursor
  behavior.
- Clan name hover underline opens Clan Summary.
- Clog total hover underline opens Clog Summary.
- Member count hover/click opens Member Summary.
- Clan Clog summary progress uses OSRS-native stoplight coloring, not the Kill
  Clog K convention progression colors.
- Popups should close when the RuneLite window becomes inactive.

## Release Gate

Before calling Clan Clog ready:

```powershell
.\gradlew.bat compileJava checkstyleMain checkstyleTest test
```

For visual readiness, smoke through the active dev-client launcher. Prove which
jar it built and which classes the dev client loaded before claiming the client
is current.

## Legacy Docs

- `PORTING-LOG.md` is a historical porting record, not current architecture.
- `RESEARCH-ROSTER-SOURCE.md` is historical source research. Use it as evidence
  only after checking this truth doc and current source.
