# Fossify Calendar — "Contextual Event" Mode: Execution plan

Written 12 Aug 2026 (Cowork session), revised same day. Grounded in an actual
read of the repo at commit 015f957 (main, 10 Aug 2026), not from memory.

Recovered to the laptop 20 Aug 2026 — was previously only on the phone.

---

## 0. Read this first: two findings that change the plan

### 0.1 The cloud sandbox cannot build Android
Network tested. Every Maven host is blocked by the egress proxy:

| Host                  | Needed for                       | Result |
| --------------------- | -------------------------------- | ------ |
| dl.google.com         | Android SDK, AGP, AndroidX, Room | 403    |
| repo.maven.apache.org | Kotlin stdlib, JodaTime          | 403    |
| jitpack.io            | org.fossify:commons              | 403    |

GitHub is reachable (the repo cloned fine) and JDK 21 + Gradle 8.14.3 are
installed, but with no dependency resolution there is no compile, no lint, no
Room schema validation, no tests.

**Consequence:** the build loop needs a machine with a working compiler. See §7.
Design work below is unaffected — it came from reading the source, which needs
no compiler.

### 0.2 The naive spec conflicts with "keep Google sync working"
`CalDAVHelper.kt` (~line 264) builds a brand-new `Event` on every sync and writes
it through `EventsDao.insertOrUpdate`, which is
`@Insert(onConflict = OnConflictStrategy.REPLACE)` — a full row delete-and-reinsert.

So any local-only field stored on the event row is destroyed — whether that's a
new `is_contextual` column or a new bit in the existing flags bitfield
(FLAG_ALL_DAY=1, FLAG_IS_IN_PAST=2, FLAG_MISSING_YEAR=4, FLAG_TASK_COMPLETED=8).

**Correction to an earlier draft of this doc.** I first wrote "wiped on every
sync." That was too strong. The main sync path guards the write: the row is only
rewritten when the event actually changed upstream. The correct claim is: **a
per-event local field is destroyed whenever the upstream event is edited.**

This is worse in practice, not better. A flag that vanished on every sync would
be obviously broken within an hour. A flag that vanishes only when someone
reschedules the event survives long enough to feel reliable, then quietly drops
exactly the events whose timing just moved — which for a kids' or shift calendar
is the whole point of looking.

Google Calendar's API has nowhere to store a custom boolean that Fossify would
read back, so round-tripping upstream isn't available either.

**The fix is to store the marking outside the events table entirely.** Everything
below follows from that.

---

## 1. The design: a rules table

A single mechanism covers per-calendar rules, pattern rules, and individually
marked events. It lives in its own table, so REPLACE on `events` cannot touch it.

An event is contextual if **any enabled rule matches it** (OR semantics). No
rules → behaves exactly as upstream Fossify, which keeps the default install
unchanged and makes the feature opt-in.

This subsumes the per-calendar enum from the earlier draft — "this whole calendar
is ambient" is just one row with `match_type = MATCH_ALL`. The nullable
`calendar_id` gets global rules for free, so a pattern like `^Ctx:` can mark
events ambient regardless of which calendar they arrived on.

### 1.1 Why it survives sync
Nothing in `CalDAVHelper` knows this table exists. Sync rewrites `events` rows;
rules live elsewhere and are joined at read time. The marking is durable **by
construction** rather than by remembering to patch the sync path.

### 1.2 The duration rule and its boundary problem
`MATCH_DURATION_OVER` is the sharpest idea in this design. It handles the
mixed-calendar case no per-calendar flag can: on one shared family calendar,
"kids at their dad's, Fri–Sun" is a 3-day span and genuinely ambient, while
"dentist, 2pm" on the same calendar is a real commitment. Duration is a decent
proxy for life-context vs. appointment.

**Boundary caveat — resolve before implementing.** Fossify normalises all-day
events through `toLocalAllDayEvent()`, so a single all-day event lands right on
the 24-hour boundary and may flicker in and out of ambient depending on which
side the comparison falls. The rule likely wants "spans more than one day code"
(`Formatter.getDayCodeFromTS` is already used for exactly this kind of comparison
throughout the codebase) rather than a raw `endTS - startTS > 86400`.
Not verified against the code — check first.

### 1.3 Marking individual events — this works
Because the marking lives outside the events table, a rule can target one
specific event without that marking being wiped when the event is edited
upstream. `MATCH_EVENT_ID` with the event's key in `pattern` does it.

**Which key — verified.** The main sync path does `event.id = originalEventId`
after looking the event up by `import_id`, so the local `id` is preserved across
syncs for CalDAV events, and nothing rewrites purely local events at all. Local
`id` is a stable key for both kinds.

One wrinkle: local-only events have `import_id = ""`, so `import_id` alone can't
key everything — but it is more durable than `id` across a full wipe-and-resync,
where local ids get reassigned and import ids don't. **Store both:** `event_id`
for the fast join, `import_id` (nullable) as a repair key.

**Recurrence caveat.** One row in `events` represents an entire repeating series —
occurrences are generated at query time. So a rule keyed on event id marks the
whole series, not one occurrence. Marking a single occurrence needs `id + dayCode`,
mirroring how `repetitionExceptions` already works. Decide whether you need that
before building; whole-series is much simpler and may well be enough.

**Orphan rules** linger when their event is deleted. Either clean up on delete or
accept a little dead data — for a personal app, accepting it is fine.

### 1.4 Performance
Everything matched against is already in hand at render time — `addEvents()`
iterates real `Event` objects, and the existing sort comparator already touches
title, location, and description. Matching a precompiled `Pattern` against a
short title is microseconds.

Two rules to follow:
1. **Compile once, cache per rule.** Week view re-renders on scroll, page change,
   and pinch-zoom. A naive `Regex(pattern)` inside the loop would recompile
   thousands of times a second. Invalidate the cache when rules change.
2. **Evaluate at fetch, not at draw.** Java's regex engine has no match timeout,
   so a catastrophically-backtracking pattern can wedge whatever thread it runs
   on. Resolving contextual-ness when events are loaded rather than inside the
   draw path keeps a bad pattern from freezing the UI.

### 1.5 The regex UI is the real cost
Typing a regex into a text field on a phone, with no feedback, is unpleasant —
and a wrong pattern fails silently. You see nothing and can't tell whether the
rule is broken or there's simply nothing to match.

Making it usable needs a **live preview**: "matches 7 of your next 30 events" plus
a sample list, updating as you type. That preview is more work than the regex
support itself, and without it the feature is unpleasant enough that you probably
wouldn't use it.

Worth checking against yourself first: most calendar-tagging in practice is a
prefix or substring — "Shift", "Kids:", "on call". If you already prefix these
events, or would, `MATCH_TITLE_CONTAINS` covers it with a plain text field and no
ReDoS surface. **Recommendation: ship TITLE_CONTAINS as the primary interface,
regex as the escape hatch behind it.**

---

## 2. The other thing the spec assumes that isn't true

You asked for stripes in "day/week views". Those are built completely differently:

- **Week view** (`WeekFragment.kt`, 1122 lines) is a true time grid — 24 hourly
  rows x 7 day columns, events absolutely positioned by timestamp. A background
  stripe is natural here. There's precedent for the visual too: `addEvents()`
  already does `backgroundColor.adjustAlpha(MEDIUM_ALPHA)` for past events and
  `LOWER_ALPHA` for all-day ones.
- **Day view** (`DayFragment.kt`, 155 lines) is **not** a grid. `fragment_day.xml`
  is a single `MyRecyclerView` fed by `DayEventsAdapter` — a flat scrolling list
  with no time axis. **There is no canvas to stripe.**

So "same feature, both views" isn't buildable as stated. Options for day view,
ascending effort: a compact context band pinned under the date header; contextual
events as tinted list rows; or rebuilding day view as a real time grid (big job,
high upstream-divergence cost).

**Default unless overridden:** week view gets real stripes, day view gets a
context band, month view untouched.

---

## 3. Step-by-step plan

[BOT] = doable unattended by an agent with a compiler. [YOU] = needs you or a device.

### Phase 1 — repo setup [BOT]
1. Fork `FossifyOrg/Calendar` -> `ZiziSolomon/Calendar` via the GitHub API.
2. Branch `feature/contextual-events` off `main` at 015f957.
3. Commit this doc to `docs/CONTEXTUAL_EVENTS.md` so the reasoning travels with the code.

### Phase 2 — data model [BOT]
4. Add `ContextualRule` entity + `MATCH_*` constants (in `Constants.kt` near the
   existing `FLAG_*` block).
5. Add `ContextualRulesDao` — CRUD plus `getEnabledRules()`.
6. Register the entity on `EventsDatabase`, bump version 11 -> 12, add
   `MIGRATION_11_12` creating the table. Ten worked migrations in that file to
   copy the house style from.
7. Write `ContextualRuleEvaluator` — one place that decides ambient-or-not, with
   the compiled-pattern cache. Week view, day view, and widget all consult it, so
   they can't drift apart. Resolve §1.2's boundary caveat here.
8. **Unit-test the evaluator.** Pure logic, no Android dependencies — the one part
   that's cheap and worthwhile to test properly. Cover the all-day boundary,
   multi-day spans, empty rule set, and a malformed regex.

### Phase 3 — week view rendering [BOT]
9. Add a background layer `View` to `fragment_week.xml`, z-ordered beneath
   `weekEventsColumnsHolder`.
10. Write `ContextualStripeView` — takes (dayIndex, startMinute, endMinute, colour)
    tuples, draws rounded rects at ~15–20% alpha. Keeping it a separate view rather
    than threading through `addEvents()` keeps the diff small and rebases cheap.
11. In `addEvents()` (line ~614), partition events: contextual ones feed the stripe
    view and `continue` before the normal layout path, so they never enter
    `eventTimeRanges` or the collision-packing logic. **This matters** — otherwise
    ambient events shove real events sideways, defeating the entire purpose.
12. Edge cases: all-day contextual events (stripe the full column), events crossing
    midnight, overlapping stripes (blend, don't stack alpha to mud).

### Phase 4 — day view [BOT]
13. Context band under `top_navigation` in `fragment_day.xml`; filter contextual
    events out of `DayEventsAdapter`.

### Phase 5 — rules UI [BOT]
14. Rules management screen: list, add, edit, delete, enable/disable. Reachable
    from both settings and a long-press on a calendar.
15. Rule editor with **live preview** (§1.5) — match count + sample list against the
    next ~30 days, updating as you type. **Do not skip this.**
16. "Mark as contextual" action in the event long-press menu -> creates a
    `MATCH_EVENT_ID` rule.
17. Global "show contextual events" master switch in settings.

### Phase 6 — the rest of the surface [BOT]
18. Widget: `MyWidgetListProvider` / `WidgetService` build lists independently of
    the fragments. Filter there too, or contextual events clutter the widget
    exactly as they'd have cluttered the list.
19. Search, month view, export: default is **yes** to search and export (they're
    still real events), **no** to month-view dots.

### Phase 7 — build and verify [YOU]
20. Build. Room fails at runtime if entity and migration disagree — enable
    `exportSchema` and confirm `MIGRATION_11_12` lands.
21. Install on the 9a.
22. **The one test that must pass:** mark a Google-synced event contextual, edit
    that event in Google Calendar, force a sync, confirm it is still contextual.
    This is the entire reason for the rules table (§0.2).
23. Verify widgets render and sync still round-trips.

### Phase 8 — visual iteration [YOU]
24. Alpha, corner radius, stripe inset. Aesthetic, needs your eyes on real data on
    the actual screen — best done with hot reload on the laptop.

---

## 4. Open decisions

| #   | Decision                                  | Default                                       |
| --- | ----------------------------------------- | --------------------------------------------- |
| 1   | Rules table vs per-calendar enum          | Rules table — settled 12 Aug                  |
| 2   | Per-event marking                         | Supported via MATCH_EVENT_ID — settled 12 Aug |
| 3   | Whole-series vs single-occurrence marking | Whole series; revisit if it bites             |
| 4   | Primary pattern interface                 | TITLE_CONTAINS, regex as escape hatch         |
| 5   | All-day boundary for duration rule        | "spans more than one day code" — unverified   |
| 6   | Day view treatment                        | Context band under header                     |
| 7   | Stripe alpha                              | 15% light / 20% dark, tuned on device         |
| 8   | Contextual events in widget / month dots  | Excluded from both                            |
| 9   | Base ref                                  | main @ 015f957                                |
| 10  | Fork visibility                           | Public (GPL-3.0 — Fossify is copyleft)        |

---

## 5. Risk register

| Risk                                              | Likelihood | Notes                                                                                                         |
| ------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------------- |
| `WeekFragment.addEvents()` gnarlier than it reads | Medium     | 1122 lines with scale gestures, drag-to-move, and all-day row packing interleaved. Phase 3 is the hard phase. |
| Rules UI is more work than the rendering          | Medium     | The preview (§1.5) is easy to underestimate.                                                                  |
| Upstream rebase pain                              | Low-Medium | New table + new view keeps most of the diff out of hot files.                                                 |
| Room migration wrong                              | Low        | Ten worked examples in the same file.                                                                         |
| Orphan rules after event deletion                 | Low        | Cosmetic for personal use. **Resolved in 9.2** (§8).                                                          |
| GPL-3.0 obligations                               | n/a        | Personal use, no distribution, nothing triggers. Relevant only if you ever share the APK.                     |

---

## 6. Confidence

**Verified by reading the source:**
- Sync destroys per-event local fields, and only on upstream change (§0.2) — read
  both the exception and main paths, confirmed REPLACE.
- Local `id` is preserved across syncs via `importIdsMap` lookup (§1.3).
- Local-only events carry `import_id = ""`.
- Day view is a RecyclerView, not a grid (§2).
- Schema at version 11; ten existing migrations; `FLAG_*` constants as listed.
- Maven blockage (§0.1) — tested directly.

**Unverified — check before relying on:**
- All-day boundary behaviour for the duration rule (§1.2).
- Exactly how `MyWidgetListProvider` builds its event list.
- Whether `import_id` survives a full wipe-and-resync (asserted from how
  `getEventIdWithImportId` is used; not traced end to end).
- Whether Fossify has an existing hidden-calendar mechanism that could shortcut
  some of this.

---

## 7. Status — parked 12 Aug 2026

Design is settled enough to build from. **The unresolved question is tooling, not
design.** That sandbox couldn't compile Android (§0.1), so the build loop needed a
home:

1. **Claude Code on the laptop** — treat §§1–3 as the spec and execute locally.
   Most reliable. <- *this machine; §0.1 does not apply here*
2. Claude desktop app + `device_bash` — a cloud session drives a build on your own
   machine. Unverified; needs a headless Android SDK install first.
3. Blind scaffolding — push Phase 2 only, compile later. Low risk, low value;
   skips all rendering work.

**To resume:** hand this file to whichever agent has a compiler, starting at §1.
It is self-contained — no conversation context required.

---

## 8. Since the build — what changed against this plan (Oct 2026)

- **Wipe-and-resync, verified on the emulator.** `import_id` does survive (the §6
  question), but two things didn't. (1) An *edited occurrence* is a child row that
  points at its series only by local `parentId`, so it lost its mark until the
  mark was re-keyed. (2) A synced *calendar* is deleted and recreated under a new
  local id, so calendar-scoped rules matched nothing. Both are repaired at the end
  of `CalDAVHelper.refreshCalendars()` by `ContextualRulesHelper.repairKeys()`
  (pure logic in `ContextualRuleRepair`).
- **DB v13:** `contextual_rules.caldav_calendar_id` (nullable) is the calendar's
  repair key, mirroring `import_id` for events. It's filled when a rule is saved
  and backfilled for v12 rules. `MIGRATION_12_13` is a single `ALTER TABLE … ADD
  COLUMN`.
- **Risky regexes are refused up front** (`RegexRisk`: nested quantifiers,
  repeated alternation, backreferences), in both the editor and the evaluator.
  `BoundedRunner` stays as the backstop (§1.4).
- **Stripe labels** (setting, default on) stick to the top of the visible area
  and stack when nested. **TalkBack:** each week-view day header speaks that
  day's contexts.
- **No `saveLayer` for stripes** (§3.12 still holds): each stripe is drawn
  translucent, with the stripes above it clipped out. The full-height offscreen
  layer doubled janky frames during week swipes.

### Phases 9–13 (worker-written, 4–5 Oct 2026)

These go past the plan's Phase 6 scope. All are pure-logic-first with JVM tests;
there is no DB change after v13.

- **Settings export/import carries contexts (9.1).** Keys `show_contextual_events`,
  `label_contextual_stripes`, `mute_contextual_reminders` and `contextual_rules` (one
  JSON array; `ContextualRulesBackup`, with the serialised type in
  `models/ContextualRuleBackupEntry` so R8 keeps its field names). Import *merges*:
  calendar rules bind by CalDAV calendar id, then by calendar name; marks bind by
  `import_id`. A rule that can't bind is skipped and counted, never widened (a
  calendar rule without its calendar would otherwise match every calendar).
  Limitation: provider ids are per-phone, so marks on synced events don't move to a
  new phone.
- **Orphan cleanup on user deletion (9.2, resolves the risk-register row).** The hook
  keys on `deleteFromCalDAV = true` in `EventsHelper.deleteEvents` (user deletes) and
  on local-calendar deletion in `deleteCalendars`. Sync removals pass `false`, so
  wipe-and-resync still keeps marks. If upstream ever passes `true` from a sync path
  for whole events, marks would be lost there (commented at the call).
- **Week-view labels are tap targets (9.3)** that open the event; the rest of the
  stripe keeps the grid's tap-to-create.
- **"Why is this contextual?" (10.1).** The event screen names the matching rules
  (`ContextualRuleEvaluator.matchingRules`), so a rule-matched event no longer offers
  a misleading "Mark as contextual" with no explanation.
- **Mute reminders for contextual events (10.2)**, default off. Skipped at fire time
  in `NotificationReceiver` (`ContextualReminderPolicy`) while the next reminder is
  still scheduled, so turning it off works immediately.
- **.ics carries marks (10.3).** `X-FOSSIFY-CONTEXTUAL:TRUE` on directly marked
  events; import re-creates the mark without duplicates (`IcsContextualMark`). Other
  apps ignore unknown `X-` properties (RFC 5545 §3.8.8.2).
- **Lists say "Context" (11.1).** List view, search and the month day list append
  " · Context" to contextual rows rather than changing colour or alpha, which
  upstream already uses for past events and tasks.
- **Rules show their reach (11.2, 12.2).** Each rule shows "Matches N events" or
  "Matches no events" over a month back to a year ahead, with a series counted once
  (`ContextualRuleUsage`). Long-press → "Show matches" lists those same events and
  opens one; the dialog title names the calendar (13.2).
- **Quick toggle (11.3):** the main overflow menu's "Show contextual events" is bound
  to the same setting.
- **Rule from an event's title (12.1):** opens the editor prefilled with *Title
  contains* scoped to the event's calendar, so the live preview shows the reach
  before saving.
- **Unmark from lists (12.3):** the selection menu offers "Unmark" when every
  selected event is directly marked (`ContextualSelection`). Day view is excluded by
  design, since contextual events never enter its list.
- **Upstream rebase (10.4 dry run):** one conflict, in `EventsHelper.deleteCalendars`
  (upstream wrapped it in `synchronized(HolidayHelper.lock)`). Fix: move our
  `forgetDeleted(calendarIds = …)` call to just after
  `calendarsDB.deleteCalendars(typesToDelete)`. Upstream's DB was still v11.
- **Lint (13.1):** 0 errors. The remaining warnings in branch files follow upstream's
  own patterns (MissingTranslation, which Weblate fills; UseKtx; Overdraw;
  AlwaysShowAction on the selection-bar Delete).
