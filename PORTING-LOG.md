# clan clog: porting log

per-component record of what was ported from kill-clog-plugin, why, and what (if anything) was adapted for clan-level use. read this alongside `RESEARCH-ROSTER-SOURCE.md`.

source of truth for the ports: `~/plugins/kcpdev/` (the kill-clog-plugin dev tree). all line counts + sha refs are kcpdev-side.

---

## 2026-05-13 cycle 3: data models triplet

**ported:** `AccountType.java`, `HiscoreResult.java`, `ClogResult.java`
**source commit:** kcpdev `31be659` (2026-05-06, latest commit touching any of these), kcpdev HEAD `e17a291`
**scout reuse rating:** HIGH (the audit table groups them as one row)

**adaptation:** package declaration only. `com.killclog` -> `com.clanclog`. zero functional or structural changes.

reasoning: these are immutable value objects representing per-player data. clan clog's roster-level use case is "many players' worth of these," not "a different shape of these." wrapping with a future `ClanMember` (name + rank + join date + the above results) is the right composition story per the scout. forking the models would create a maintenance burden with no payoff.

**verified by inspection:**
- AccountType: pure enum, no dependencies. byte-identical except package line.
- HiscoreResult: depends only on AccountType + java.util.{Collections, Map}. byte-identical except package line.
- ClogResult: depends only on AccountType + java.util.{List, Map, Set} + j.u.c.ConcurrentHashMap. nested static class ClogItem also byte-identical. package line is the only diff.

**next steps from here:** port the cache + service layer. expected order, highest reuse first:
1. LocalClogCache (disk-backed swr cache, root dir rename ~/.runelite/kill-clog -> ~/.runelite/clan-clog)
2. HiscoreService (parallelized 4-endpoint fetch + 2-min swr cache, no functional changes)
3. ClogService (extract TempleApiClient-style interface if it falls out cleanly during the port; otherwise defer extraction per the "copy first, extract later" dev approach in the scout)

these three give clan clog its foundational data layer. with them in, the vertical-slice phase can wire wom-roster -> per-member hiscore fetch -> panel render.

---

## 2026-05-13 cycle 4: LocalClogCache

**ported:** `LocalClogCache.java`
**source commit:** kcpdev `2a6009e` (2026-05-07, "fix: don't await executor termination on shutdown, blocks runelite caller thread"), kcpdev HEAD `e17a291`
**scout reuse rating:** HIGH (audit table: "Single-threaded executor + 500ms debounce + per-player JSON files at ~/.runelite/kill-clog/. Replicate for clans at ~/.runelite/clan-clog/.")

**adaptations:** three targeted swaps, otherwise byte-identical 437 LOC body.
1. package `com.killclog` -> `com.clanclog`
2. cache root directory `new File(RuneLite.RUNELITE_DIR, "kill-clog")` -> `"clan-clog"`. ports persisted-cache discipline cleanly without cross-plugin file collisions.
3. background thread name `"kill-clog-disk"` -> `"clan-clog-disk"`. matches the kc naming pattern.
4. javadoc reference to `~/.runelite/kill-clog/` updated to `~/.runelite/clan-clog/` for accuracy.

**reasoning for porting now (vs. waiting for phase 2):** the cache is keyed by player name (`playerName.toLowerCase()`), and a clan roster is just a set of players. the existing per-player JSON files work as-is for clan members -- each member's data lives in its own file under the clan-clog root. no shape change needed. the scout's open question about "per-member vs per-clan vs hybrid persistence" is a phase-2 optimization, not a phase-1 blocker. the simplest correct port is to bring the kc cache over unchanged and revisit only if a clan-scope hot path forces it.

**phase-1 usage clarification:** clan clog phase 1 fetches hiscores per member (not clog data per member). LocalClogCache is still ported because (a) hiscore + clog data both share the same cache discipline, (b) the cache will be needed when clan clog gains per-member clog detail in a later phase, and (c) shipping the foundation now means future work is layer-on, not re-port.

**next step:** HiscoreService -- the 480-LOC parallelized 4-endpoint fetcher with 2-min swr cache. scout says no functional changes. probably a 5-line diff (package + a `kill-clog` user-agent string if present, otherwise zero adaptations).
