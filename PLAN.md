# PLAN.md

One section per milestone. Fill them in order, with the plan before you run
anything, the guardrail before you run a task, and the run after.

Keep it short and specific. Point at modules, gates, and file paths.

---

## Milestone 1: The plan

Written 2026-10-21, before any task was run. Baseline: `mvn -B clean test`
418 passed, `pytest` 137 passed.

Every checkpoint below also ends with `git add -N . && git diff --stat`, and
the file list must sit inside the task's declared modules. A shipped test in
that list fails the checkpoint outright (`AGENTS.md` rule 7).

### T1. Modify a booking in the domain (CR clauses 1-5, domain half)

**Delivers.** `BookingService.modifyBooking(bookingId, requesterId, newInterval,
newAttendees)`. It enforces the following:

- Owner only, and only while the booking is upcoming and not cancelled (clause 1).
- The full `BookingRules` request check runs against the new interval and
  attendee count, with the cap on bookings held waived (clause 3).
- The room conflict check and the booker's own-calendar check run through
  `ConflictChecker`, excluding the booking being modified (clause 2).
- A rejection leaves the store exactly as it was.
- When the interval changed, the room's waitlist is walked with the same
  first-in, first-out, re-checked promotion that `cancelBooking` uses (clause 4).
- Each real change appends a history entry (previous interval, previous
  attendees, `occurredAt`) to the `Booking` and publishes a new
  `BookingModifiedEvent` that carries both the old and the new details.
- A modification that changes nothing returns success, records no history and
  publishes no event (clause 5).

**Modules.** `core-domain`. Plus the minimum needed to keep the build
compiling: `DomainEvent` is `sealed`, and `notifications`'
`NotificationKind.forEvent` and `NotificationService` switch over every event
type in it, and `forEvent` returns a kind rather than an `Optional`. T1 adds
only the smallest `BookingModifiedEvent` arm in each of the two switches that
compiles and keeps the shipped notifications tests green. T3 owns the real
kind and template. **Depends on** nothing.

**Checkpoint.** `mvn -pl core-domain -am test` shows a count above 167, with new
tests in a new `BookingServiceModifyTest` that assert:

- **Stored state after a success.** Re-read through `findBooking`: new interval
  and attendees, same id, room and owner, and exactly one history entry holding
  the old values.
- **Moving by 15 minutes,** which overlaps the booking's own old interval,
  succeeds.
- **A move onto another booking** is rejected, and the stored booking, its
  history and the publisher's events are all unchanged.
- **A move onto the booker's own booking in another room** is rejected.
- **A booker at the booking cap** can still modify.
- **Each clause-3 rule** rejects with its own `RejectionReason`: too short, too
  long, misaligned, outside hours, crossing midnight UTC, over capacity, past
  the advance window, in the past, room out of service.
- **Not the owner, already started, cancelled:** each rejected.
- **Shortening or moving with someone waiting on the freed time:** the waiter is
  promoted. With two waiters, the first to arrive wins, and a waiter who is busy
  at that time is skipped.
- **A change to attendees only, or a pure lengthening:** nobody is promoted.
- **A no-op:** success, no history entry, no event.

Then `mvn -B clean test` is green, and the count is 418 plus T1's new tests.

**Green but wrong.** A `modifyBooking` that never actually saves passes every
rejection test, since "unchanged" is exactly what the rejection tests check. The
first assertion, the stored-state check after a success, is the one that closes
this. A second blind spot: adding `BookingModifiedEvent` to the sealed interface
leaves this module's gate green and breaks the `notifications` compile, which is
why the closing check is the cold full build.

### T2. Log `booking-modified` and count it at its current length (clause 8)

**Delivers.**

- `EventLogFormat.toLine` writes `booking-modified` with the shared fields
  (`start` and `end` are the new interval) plus `previousStart`,
  `previousEnd` and `previousAttendees`.
- `eventTypes()` lists it.
- `analytics` parses it.
- `build_ledger` updates the existing `BookingRecord`'s `start`, `end` and
  `attendees` in place, keeping `created_at`, `from_waitlist` and
  `cancelled_at`.
- Utilization then reports the booking at its current length.
- Revenue keeps reading the same ledger and is not edited.

**Modules.** `storage`, `analytics` (`events.py`). **Depends on** T1, for the
event type. **Human edit:** the event-type list in `AGENTS.md` rule 6 and in
`ARCHITECTURE.md` "The event log". Both files are guarded, so I make these edits.

**Checkpoint.** Both gates are green in the same change (rule 6):

- `mvn -pl storage -am test` shows a count above 63, with a new `EventLogTest`
  case asserting one exact `booking-modified` line, byte for byte.
- `pytest` from `analytics/` shows a count above 137, with:
  - a fixture file containing that same line;
  - a ledger test showing the record's interval replaced and `from_waitlist`
    kept;
  - a utilization test over a window with a booking shortened from 120 to 60
    minutes that reports exactly 60 booked minutes.

**Green but wrong.** Java writes one set of field names and the Python fixture
is hand-typed with another, for example `previous_start` against
`previousStart`. Both gates pass, and the real log breaks the reader. The guard
is that the Python fixture line is copied from the Java test's expected string,
and I diff the two by eye. A second blind spot: putting `booking-modified` in
`HOLDING_EVENT_TYPES` makes the fold open a fresh record, which wipes
`from_waitlist`. The ledger test asserts that the flag survives.

### T3. Tell the owner what changed (clause 6)

**Delivers.** A `BOOKING_MODIFIED` notification kind and a template that takes
the old and new interval and attendee count from `BookingModifiedEvent`. The
owner receives it through the existing outbox. Promotions keep the
promotion notice that already exists.

**Modules.** `notifications`. **Depends on** T1, because the event must carry
the old details.

**Checkpoint.** `mvn -pl notifications -am test` shows a count above 41, with new
tests in `NotificationServiceTest` and `templates/`:

- After a `BookingModifiedEvent` for a move from 10:00-11:00 to 13:00-14:00,
  the outbox holds exactly one message.
- It is addressed to the owner.
- Its body contains both the old and the new interval text exactly, as
  `IntervalText` renders them, and both attendee counts.

**Green but wrong.** The template fills "old" and "new" from the same booking,
so both show the new times. A test that only asserts "a message exists" or
"contains 13:00" passes. The guard is the assertion on the 10:00-11:00 text.

### T4. `PATCH /bookings/{id}` and history on the existing read (clauses 5, 7)

**Delivers.**

- `BookingsHandler` registers `PATCH /bookings/{bookingId}`, owner only, and
  takes `start`, `end` and `attendees` as form parameters. Any field that is
  left out keeps its current value.
- Rejections go through `ErrorMapping`, the same way as for create.
- `JsonViews.booking` adds the history list, so `GET /bookings/{bookingId}`
  shows it to the owner and to an administrator.
- The notification wiring in `CampusReservePlatform` already subscribes every
  event, and this is confirmed by test.

**Modules.** `http-api`. **Depends on** T1 and T3. **Human edit:** add the
`PATCH` row to the `ARCHITECTURE.md` HTTP table.

**Checkpoint.** `mvn -pl http-api -am test` shows a count above 78, with new
`EndpointTest` cases, sent as real HTTP through `ApiTestServer`:

- The owner moves a booking, gets a 200 with the new times, and the follow-up
  `GET` shows one history entry.
- A `PATCH` with only `attendees` keeps the interval.
- A move into a taken slot returns the same status as a conflicting create, and
  the `GET` is unchanged.
- An administrator's token gets a 403 on `PATCH` but a 200 on `GET`.
- No token gets a 401.
- After a successful `PATCH`, the platform's outbox holds the owner's
  modified notice.

**Green but wrong.** An agent reuses the owner-or-administrator check that `GET`
uses, and every test uses the owner's token, so an administrator can rewrite
anyone's booking. The guard is the administrator-403 case. A second blind spot:
a `PATCH` with only `attendees` is read as "start and end missing" and returns
200 having changed nothing. The partial-`PATCH` case catches this.

### AGENTS.md rules 3 and 4

T1's checkpoint catches both. Its diff is read for any `start`/`end` comparison
outside `TimeInterval.overlaps` (rule 3), and
`grep -rn "WaitlistStore\|waitlist\.\(save\|remove\)" */src/main` must show
`BookingService` as the only caller (rule 4). Its tests for the first-in, first-out
order and for skipping a busy waiter catch a promotion that does not route
through the shared re-check.

### Out of scope, with reasons

- **`cli`.** CR: "Nothing in the CLI." It must still compile against the new
  `Booking`, which the cold build checks.
- **`billing`.** CR: "The price does not change ... Billing is not part of this
  change request." No re-pricing and no re-invoicing.
- **`accounts`.** CR: "Nothing in account management. Modification uses the
  token and ownership checks that already exist."
- **`analytics/campusreserve_analytics/revenue.py`.** CR: "The revenue report
  is not part of this change ... Do not filter modified bookings out of it
  either, and do not fork the reading." It changes only through the shared
  ledger in T2.
- **`AGENTS.md` and `CHANGE-REQUEST.md` for the agent.** The work is judged
  against them. The two doc edits named in T2 and T4 are made by me (clause 9).

---

## Milestone 2: The guardrail

**The recipe.** I use Recipe A, the permission boundary, configured in
`.claude/settings.json`. Its rules are written on `Edit`, which also covers
`Write` and `NotebookEdit`, and each path is anchored to the repository root.

The denied paths come from the Milestone 1 out-of-scope list:

- `/cli/**`
- `/billing/**`
- `/accounts/**`
- `/analytics/campusreserve_analytics/revenue.py`

These are also denied:

- The conventions and docs files: `/AGENTS.md`, `/ARCHITECTURE.md`,
  `/CLAUDE.md` and `/CHANGE-REQUEST.md`. I make the clause 9 doc edits myself.
- The guardrail itself: `/.claude/**` and `/guardrails/**`.
- The commands `git push`, `rm` and `sudo`.

**What it cannot catch.** It only sees the built-in file tools and the file
commands Claude Code recognizes. A `cp`, `mv`, `sed -i` or agent-written script
that writes `cli/` gets through. So does an allowed edit, for example a change
in `core-domain` that breaks the CLI's compile or changes what it prints. The
diff audit and the cold `mvn -B clean test` are what catch those.

**The captured block.** I ran this in Claude Code 2.1.306 from the repository
root. The status line reads "manual mode on". The screenshot is
[`evidence/milestone2-guardrail-block.webp`](evidence/milestone2-guardrail-block.webp).

```
> Use your Edit tool to add the comment // guardrail test at the top of
  cli/src/main/java/edu/cmu/cs214/campusreserve/cli/Format.java.

● Need to read the file first.
  Read 1 file

● Update(cli/src/main/java/edu/cmu/cs214/campusreserve/cli/Format.java)
  ⎿ Error editing file

● I didn't add the comment. My Edit on
  cli/src/main/java/edu/cmu/cs214/campusreserve/cli/Format.java was blocked
  because your permission settings deny edits in that directory. The file is
  unchanged.

  That looks like the guardrail working as intended, so I haven't tried to get
  around it with a shell command or another tool. ...
```

The `Read` went through, as it should: the rules deny `Edit`, not `Read`. The
`Edit` call was refused by the `Edit(/cli/**)` deny rule.

After the turn, the file was unchanged: `git status --short cli/` and
`git diff --stat cli/` both printed nothing.

---

## Milestone 3: The run

Run exactly ONE task from your plan with the agent, under the guardrail, through
its gate. Any task whose dependencies are done qualifies, including the
smallest one.

**The takeover point.** If getting T1's tests to pass needs harness changes in
a module I put out of scope (a guardrail deny, or a file outside T1's scope in
`git diff --stat`), I stop the agent, revert those changes, and write that piece
myself.

**The evidence.** Task: T1, modifying a booking in the domain.

- `mvn -pl core-domain -am test`, core-domain block:
  `[INFO] Tests run: 196, Failures: 0, Errors: 0, Skipped: 0` (was 167).
- `mvn -B clean test`: `[INFO] BUILD SUCCESS`. All 7 modules pass. The other
  module counts are unchanged, and `pytest` still shows 137 passed.
- The takeover point didn't fire: there was no guardrail deny and no file
  outside T1's scope.
- I checked my green-but-wrong case by commenting out `bookings.save(modified)`.
  8 of the new tests went red, and then I put the line back.

**The diff.** `git add -N . && git diff --stat` shows 9 files, all in T1's scope:

- `core-domain/.../domain/Booking.java`: adds a `history` field. The old
  7-argument constructor is kept so `cli` and `billing` still compile.
- `core-domain/.../domain/BookingChange.java` (new): one history entry, holding
  the previous interval, the previous attendee count and the time of the change.
- `core-domain/.../domain/ModificationResult.java` (new): the result is
  Modified, Unchanged or Rejected.
- `core-domain/.../events/BookingModifiedEvent.java` (new), and
  `DomainEvent.java` adds it to `permits`.
- `core-domain/.../service/BookingService.java`: `modifyBooking`, plus an
  `othersThan` helper.
- `core-domain/.../service/BookingServiceModifyTest.java` (new): 29 tests.
- `notifications/.../NotificationKind.java` and `NotificationService.java`: a
  stub `BookingModifiedEvent` arm that throws. `NotificationService` catches it
  and records the event as skipped. This was planned, since the sealed switch
  forces it, and T3 replaces it.

There was nothing unexpected. No shipped test was touched. Every waitlist
`save`/`remove` call is still in `BookingService`, and the diff adds no new
start/end comparison.

The line I read hardest:

```java
if (conflictChecker.hasConflict(othersThan(booking, bookings.findActiveByRoom(booking.roomId())),
```

I was checking that "a booking doesn't collide with itself" is handled by
leaving the booking out of the list. The overlap test itself is still
`ConflictChecker`, not a second start/end comparison (rule 3).

**The refuter.** I ran it in a fresh Claude Code session. The brief:

```
You are a refuter. Your job is to break a change, not to fix it.

Inputs. Use ONLY these:
1. CHANGE-REQUEST.md (the spec)
2. The task's diff: run `git add -N . && git diff`
3. The task's scope, in one line: "T1: BookingService.modifyBooking in core-domain enforces CHANGE-REQUEST clauses 1-4, records history on the Booking, publishes a booking-modified event, and records nothing for a no-op."

Do not read PLAN.md, AGENTS.md or ARCHITECTURE.md. You may read other source files only to understand code the diff calls.

Task: find inputs or call sequences where modifyBooking behaves differently from what the change request says. [...list of places to start...]
For each claim: the call sequence, the expected result quoting the CHANGE-REQUEST line it comes from, and the actual result.
Probe tests go in a NEW file, RefuterProbeTest.java. Never edit an existing file. Rank claims strongest first.
```

It ran 7 probes and 3 failed. I settled the strongest one, claim 1: with a
"stranded" waiter left on r-1 for 15:00-16:00, a modification that only
lengthens a booking still promotes that waiter. The refuter quotes clause 4:
"Running the pass and promoting nobody is the right outcome there."

**Dismissed.** Clause 4 also says "The promotion pass runs when a successful
modification changed the interval", and that it is "the same first in, first
out waitlist promotion that a cancellation triggers today". So I added one probe
with the same setup, where a cancellation of an unrelated 10:00 r-1 booking
happens instead. It also promotes u-2 into 15:00-16:00, and that probe passed. The
modification does exactly what a cancellation does. The stranded waiter comes
from existing cancellation behavior: the pass only walks the cancelled
booking's room. That is outside T1. The run is in
`evidence/milestone3-refuter-run.txt`. Every probe, the stranded-waiter setup
step by step, and my check are in
[`evidence/milestone3-refuter.md`](evidence/milestone3-refuter.md).

Claims 2 (a nudge promotes the waiter) and 3 (zero attendees throws instead of
being rejected) I left alone. Claim 2 has the same cause as claim 1. For claim
3, `requestBooking` throws the same way, and nothing is written before the
throw. I deleted the probe file afterwards, and the core-domain gate is green
again.
