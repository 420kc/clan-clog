# Clan Clog

Clan Clog is a RuneLite plugin. The clan-scale sibling to [Kill Clog](https://github.com/420kc/kill-clog-plugin).

## product

Search a clan name. See a Kill Clog Profile where the collection log is the union of every clan member's clog. The clan as a super-account whose unique-item set is the combined drop history of its members.

## status

Alpha. Active development on `master`. Public Plugin Hub submission gated on the [killclog.com](https://killclog.com) aggregation backend reaching production capacity.

## current capabilities

- reads the user's in-game clan roster via the modern OSRS Clan API (`ClanSettings`)
- listens for clan chat broadcasts (`joined` / `left` / `kicked`) and parses them in real time
- narrates clan events in the local chatbox as **Clogsworth**, the clan butler (formal, dry, occasionally devastating); narration is local-only, never broadcast to clan chat
- ports the foundational data layer from Kill Clog: hiscore fetch, account-type detection, per-player disk cache

## target v1 capabilities

- sync your clan to killclog.com once via a key-rank-gated handshake; roster stays current passively as members open the clan list in-game
- render a Kill Clog-style profile for the clan with the combined collection log across all members
- surface a clan badge on each member's individual Kill Clog Profile automatically once the clan is synced

## data

Clan Clog reads from and (opt-in) writes to external services:

- **Jagex hiscores** (`secure.runescape.com`), **Temple OSRS**, and **RuneProfile** — read-only lookups of public boss KCs, levels, and collection-log data for clan members.
- **Wise Old Man** (`api.wiseoldman.net`) — read-only roster search for clans you are not in.
- **killclog.com** — only when **Enable Sync** is turned on (off by default) and you hold a key clan rank. Syncing transmits the clan roster (RSNs + ranks), aggregated boss KCs, and the combined collection log so the clan profile renders at `killclog.com/c/<slug>`. Clogsworth's clan-event narration stays local and is never transmitted.

## development

```
./gradlew compileJava   # build
./gradlew run           # boot RuneLite with the plugin loaded (test classpath)
```

If `../kcpdev/build/libs/kill-clog-plugin-1.3.2.jar` is present, `./gradlew run` boots Kill Clog alongside for combined-plugin smoke.

## ecosystem

- [Kill Clog plugin](https://github.com/420kc/kill-clog-plugin), the player-scale sibling
- [killclog.com](https://killclog.com), the unified OSRS data aggregation backend + public profile surface
- [420kc.dev](https://420kc.dev), the dev portfolio

## license

BSD 2-Clause. See [LICENSE](LICENSE).
