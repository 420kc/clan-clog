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
