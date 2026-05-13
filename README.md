# clan clog

kill clog for clans. type a clan name, see every member ranked. native-feeling osrs interface.

sibling plugin to kill clog. shares the kc voice, palette, and engineering disciplines. heavy code reuse from kill-clog-plugin is the strategic accelerator — the data-fetch + cache + result-model layer ports across, the panel + member-table is new surface.

## scope (phase 1)

local-only. one user, one clan at a time. roster source is wise old man api (decided 2026-05-13 after a 5-source audit, evidence in `RESEARCH-ROSTER-SOURCE.md`). runelite clanchannel powers the live in-game overlay where it exists. manual paste covers unregistered clans.

## scope (phase 2, later)

plugin calls `killclog.com/api/clan/<name>` once; killclog.com handles upstream aggregation across wise old man, templeosrs, and runelite-derived clan-list-open events. the plugin's http client stays slim. swap is a one-line url change away.

## status

scaffold. compiles. behavior empty.

## structure

```
src/main/java/com/clanclog/
  ClanClogPlugin.java   runelite plugin entry, @PluginDescriptor
  ClanClogConfig.java   @ConfigGroup("clanclog")
  ClanClogPanel.java    side-panel surface, empty for now
runelite-plugin.properties
build.gradle
settings.gradle
```

## naming

> clan clog. kill clog for clans. the name itself trades on kill clog's existing brand equity.

per the 2026-05-12 council lock. lowercase in prose, title case as a wordmark.
