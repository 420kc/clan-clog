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

## development

```
./gradlew compileJava   # build
./gradlew run           # boot RuneLite with the plugin loaded (test classpath)
```

If `../kcpdev/build/libs/kill-clog-plugin-1.4.0.jar` is present, `./gradlew run` boots Kill Clog alongside for combined-plugin smoke.

## ecosystem

- [Kill Clog plugin](https://github.com/420kc/kill-clog-plugin), the player-scale sibling
- [killclog.com](https://killclog.com), the unified OSRS data aggregation backend + public profile surface
- [420kc.dev](https://420kc.dev), the dev portfolio

## license

BSD 2-Clause. See [LICENSE](LICENSE).
