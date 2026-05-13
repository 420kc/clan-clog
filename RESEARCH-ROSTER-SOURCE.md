# clan clog: roster source research

evidence-gathered audit of every plausible source for clan member rosters in osrs. the linchpin question per the foundational scout. recommendation at the bottom.

all api samples below are real responses captured 2026-05-13 ~07:16 utc, not fabricated.

---

## 1. wise old man api

**endpoint (by id)** — `GET https://api.wiseoldman.net/v2/groups/{id}`
**endpoint (by name)** — `GET https://api.wiseoldman.net/v2/groups?name=<query>&limit=20&offset=0`

**auth** — anonymous read for public groups. write operations (create / update / sync) require a per-group `verificationCode`. patron groups get extras (homeworld lock, larger size, etc).

**rate limits** — wom enforces per-ip rate limits but doesn't publish the exact tier. the docs site (`docs.wiseoldman.net`) was empty when fetched and the rate-limits page returned 403 for this scout. confirmed via curl that single-group reads return immediately; bulk name search timed out at 20s on one attempt (likely transient or large-result-set throttle).

**coverage** — full roster of every group that exists on the wom platform. each membership row carries: `playerId, groupId, role, createdAt, updatedAt, player.{username, displayName, type, build, status, country, exp, ehp, ehb, ttm, tt200m, registeredAt, updatedAt, lastChangedAt, lastImportedAt}`. roles include `moderator`, `artisan`, `legend`, `medic`, `member`, plus the full osrs clan rank ladder when groups are seeded from in-game.

**gaps** — only groups that someone has registered on wom. niche / private clans likely missing. last-imported can be stale months out if no member triggers an update.

**real response sample (group id 139, "exclusive elite club", member_count 11)**

```json
{
  "id": 139,
  "name": "Exclusive Elite Club",
  "clanChat": "Psikoi",
  "homeworld": 305,
  "verified": true,
  "patron": true,
  "memberCount": 11,
  "createdAt": "2020-07-05T15:25:54.055Z",
  "updatedAt": "2026-01-28T07:26:15.006Z",
  "memberships": [
    {
      "playerId": 5967,
      "role": "moderator",
      "player": {
        "username": "boom",
        "displayName": "Boom",
        "type": "regular",
        "build": "main",
        "exp": 210802606,
        "ehp": 518.50,
        "ehb": 92.03,
        "country": "GB",
        "updatedAt": "2026-05-12T17:16:22.656Z",
        "lastChangedAt": "2026-04-06T22:40:12.406Z"
      }
    },
    {
      "playerId": 30051,
      "role": "moderator",
      "player": {
        "username": "aluminoti",
        "type": "ironman",
        "build": "main",
        "exp": 760836375,
        "ehp": 2267.03,
        "ehb": 1823.99,
        ...
      }
    },
    ...
  ]
}
```

**pros**
- richest per-member metadata of any public source (account type, build, ehp, ehb, exp, last-active windows)
- per-member role granularity matches in-game ranks when the group is seeded properly
- already a familiar surface to osrs clan leaders. lots of clans run wom officially
- single api call returns the whole roster + per-member metadata in one round trip
- official osrs-only platform. no rs3 confusion

**cons**
- only groups registered on wom. coverage gap for unregistered clans
- last-imported player records can lag if the clan is not actively tracked
- rate limits opaque without an account / patron tier
- 524-class transient errors observed (cloudflare). need retries + circuit-breaker on the plugin side
- name search appears to be slower / less reliable than id lookup. user typing a clan name = needs robust two-step (search → pick id → fetch) flow

---

## 2. runescape clan hiscores xml/csv (jagex)

**endpoint** — `GET https://secure.runescape.com/m=clan-hiscores/members_lite.ws?clanName=<name+url+encoded>`

**auth** — anonymous public.

**rate limits** — undocumented. jagex hiscores endpoints have historically tolerated ~1 req/sec sustained per ip. no api key tier.

**coverage** — **rs3 ONLY.** confirmed by probing the endpoint:
- the rs3 variant returns plausible csv with billions of total xp (rs3 cap is 5.2b/skill)
- there is no `m=clan-hiscores-oldschool` sibling. that path serves the framer marketing homepage instead
- the osrs wiki clans page documents no public roster / hiscores feed
- `clanName=OSRS` on the rs3 endpoint returns a 2-member rs3 clan whose name happens to be "OSRS", not osrs roster data

**gaps** — for clan clog this source is unusable. osrs has no analog. listing it for completeness so dyl sees the conclusion was investigated, not assumed.

**real response sample (rs3 clan "Efficiency Experts", 500 members capped, CSV)**

```
Clanmate, Clan Rank, Total XP, Kills
Dragonseance,Owner,4629567564,210
Evu,Deputy Owner,4753547296,90
Qtea,Deputy Owner,5169527236,76
LobsterDog,Deputy Owner,5044368926,0
Cam–e–ron,Deputy Owner,4776657425,97
Me–Eat–Book,Deputy Owner,5015979353,1927
...
```

`Content-Type: text/comma-separated-values`. 501 lines (header + 500 members; the lite endpoint is capped at 500 per response on rs3).

**pros**
- only relevant for the rs3 variant, where it is the canonical official roster source

**cons**
- **does not work for osrs. period.** rules itself out for clan clog phase 1
- even if it did, csv with no per-member metadata beyond xp + kills total

---

## 3. templeosrs

**endpoint (group info)** — `GET https://templeosrs.com/api/group_info.php?id=<groupId>`

**auth** — anonymous.

**rate limits** — undocumented. kill clog's existing temple integration uses a 5-minute freshness gate + 3-minute per-player failure cooldown, which has held up fine. assume the same shape is safe here.

**coverage** — only groups registered on templeosrs. analogous gap to wom. groups must be created by an admin manually. some overlap with wom registrations but not all clans bother.

**gaps**
- **per-member role granularity is binary** — the response exposes `members[]` and `leaders[]` only. no owner / deputy / overseer / coordinator ladder
- no per-member account type / build / last-active timestamp in the group response. would need a second hop per member to populate
- the obvious sibling endpoint `/api/group_members.php` returns 404 — there is no separate dedicated members endpoint, everything routes through `group_info.php`

**real response sample (group id 1, "Templeosrs")**

```json
{
  "data": {
    "info": {
      "id": 1,
      "name": "Templeosrs",
      "total_xp": 25822577293,
      "average_xp": 2869175254,
      "total_ehp": 65937.79,
      "average_ehp": 7326.42,
      "total_ehb": 8525.14,
      "clan_type": "Group",
      "member_count": 9
    },
    "members": ["Gustav","Harmony","Dudash","Yonex","Cairo","Esc","Kela","Hexis Omar"],
    "leaders": ["Mikael"]
  }
}
```

`Content-Type: application/json`. 455 bytes. compact.

**pros**
- kill clog already speaks temple. the foundational hiscoreservice + clogservice plumbing is largely portable
- aggregate totals (total_xp, total_ehp, total_ehb, average_ehp) come for free in the same call — useful for the panel header
- temple is the source kc users already trust. reuse of the "same data source" framing

**cons**
- binary role granularity is a real product cost. dyl's locked killer feature includes "configurable title slot, rank ladder per row." temple can't power that without an extra source
- per-member metadata thin. every detailed row needs a second temple call (or a hiscore call) to populate

---

## 4. runelite clanchannel

**source** — `net.runelite.api.clan.ClanChannel` (client-side, no http).

**auth** — n/a. this is a local java api inside the runelite client.

**rate limits** — n/a (in-process).

**coverage** — **currently-online clan members only.** the `ClanChannel` object is the in-game representation of who is presently in the clan chat. it does not enumerate the offline roster. fields per `ClanChannelMember` include name + rank + world (and a few flags). when a user opens their clan tab, the client populates a clanchannel for each joined clan; when nobody from a clan is online, the channel is empty / null.

**pros**
- zero network cost. zero rate limit. zero tos risk
- maps directly into dyl's locked killer feature (right-click any online member from inside the redesigned clan-list overlay → kill clog them)
- the only source that is correct in real time about world / online status

**cons**
- not a full roster. **cannot answer "show me every member of clan x"** when most members are offline
- only usable for the user's own clan(s). cannot be used to look up an arbitrary clan name
- requires the user to be in-game with the plugin running. not viable for a "type a clan name and see all members" core flow

**verdict** — necessary for the live-overlay portion of the killer feature, insufficient as the primary roster source.

---

## 5. manual roster upload (csv / paste)

**source** — user pastes a roster (csv, discord rank export, or rsn-per-line). plugin stores it locally + treats it as authoritative.

**auth** — n/a.

**rate limits** — n/a.

**coverage** — exactly what the user provides. zero gaps if user maintains it, infinite gaps if they don't.

**pros**
- works for every clan including private / unregistered ones
- zero external api dependency. zero rate limit risk. zero tos risk
- a fine fallback / bootstrap path

**cons**
- worst ux of the five. clan leader has to curate + refresh
- per-member metadata is whatever the user pastes. no last-active / type / role unless they format it that way
- not a primary path for the "i typed a clan name and want the data right now" experience the locked direction promises

---

## recommendation

**primary: wise old man api. secondary: runelite clanchannel for the in-game live overlay. fallback: manual paste for unregistered clans.**

reasoning, in plain order:

1. **only wom delivers the core promise** — type a clan name, get the full roster with per-member metadata in one round trip, for osrs. temple is the only viable alternative and its role granularity is too thin for the locked killer feature (configurable title slots per rank ladder).
2. **rs clan hiscores xml is rs3-only.** confirmed by probe. rules itself out.
3. **runelite clanchannel is the right surface for the killer feature's right-click overlay.** it gives world/online state for free and has zero tos risk. clan clog uses it as the live-state layer on top of the wom-fetched roster, not in place of it.
4. **manual paste covers the unregistered-clan gap.** clan leaders who don't already run a wom group can bootstrap by pasting one. lowest priority of the three but cheap to support.
5. **temple stays warm as an aux data source for per-member kc / xp** — kill clog's existing clogservice already pulls it. the foundational layer ports as-is. temple group_info contributes aggregate totals to the panel header. but it does not serve as the primary roster source.

**architectural note for phase 2** — per `project_killclog_data_aggregation_layer.md`, in the long term the plugin will call `killclog.com/api/clan/<name>` and killclog.com handles upstream aggregation across wom + temple + runelite-derived data. the wom-direct choice above is the phase-1 expedient. it's also the right contract design exercise: the plugin's http client should target a single source today so the eventual swap to killclog.com is a one-line url change.

**known follow-ups (do not block scaffold)**
- wom name-search endpoint reliability — observed one 20s timeout. the plugin needs retry + circuit breaker
- wom rate limit specifics — docs page was empty when scouted. ask in the wom discord or test empirically once the integration is in place
- runelite clanchannel exact api surface — the javadoc url returned 404. confirm fields by reading the runelite api jar locally before writing the live-overlay code

**status decision** — `roster_source = "wise_old_man"`.
