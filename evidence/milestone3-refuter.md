# Milestone 3: refuter, claims and how I checked them

Task under test: **T1**, `BookingService.modifyBooking` (commit `466c3a7`).

The refuter ran in a fresh Claude Code session. It only had `CHANGE-REQUEST.md`,
the diff from `git add -N . && git diff`, and T1's one-line scope (the brief is
in `PLAN.md`). It wrote its probes into a new file, `RefuterProbeTest.java`,
and edited no existing file.

## What it ran

7 probes: 3 failed and 4 passed.

| # | Probe | Result | What it claims |
|---|---|---|---|
| 1 | `onlyLengtheningPromotesNobodyEvenWithAStrandedWaiter` | FAIL | A booking that is only lengthened still promotes a waiter |
| 2 | `nudgingPromotesOnlyIntoFreedTime` | FAIL | Moving a booking by one slot promotes a waiter into time the move never freed |
| 3 | `zeroAttendeesIsARejectionNotAThrow` | ERROR | An attendee count of 0 throws instead of coming back as a rejection |
| - | `attendeeOnlyChangeWithStrandedWaiterPromotesNobody` | pass | Changing only the attendee count starts no promotion (clause 4) |
| - | `cannotSwallowOwnAdjacentBookingInSameRoom` | pass | Stretching over the owner's own next booking in the same room is rejected (clause 2) |
| - | `overTheCapStillModifies` | pass | A user who is already over the cap can still modify (clause 2, the cap is waived) |
| - | `noOpOnInactiveRoomRecordsNothing` | pass | A no-op succeeds and records no event, even after the room left service (clause 5) |

## The setup behind claims 1 and 2: a "stranded" waiter

The probe helper `strandAWaiter()` leaves u-2 on r-1's waitlist for
15:00-16:00, while that slot is free and u-2 is free too:

1. u-3 books r-1 15:00-16:00.
2. u-2 asks for r-1 15:00-16:00 and is waitlisted.
3. u-2 books r-2 15:00-16:00.
4. u-3 cancels. The promotion pass skips u-2, who is busy in r-2.
5. u-2 cancels the r-2 booking. That pass only walks **r-2's** waitlist, so
   u-2's r-1 entry stays.

Then u-1, who holds r-1 10:00-11:00, modifies that booking. The modification
has nothing to do with 15:00-16:00.

## Claim 1, the strongest: settled by dismissing it with a run

**Claim.** u-1 lengthens 10:00-11:00 to 10:00-12:00, which frees no time. The
refuter expected no promotions, quoting clause 4: "A modification that only
lengthens a booking frees nothing either. Running the pass and promoting nobody
is the right outcome there."

**I reproduced it.** I ran

```
mvn -pl core-domain -am test -Dtest=RefuterProbeTest -Dsurefire.failIfNoSpecifiedTests=false
```

and `result.promotions()` held u-2 → `bk-5`, r-1 15:00-16:00. The raw output is
in `milestone3-refuter-run.txt`.

**Why I dismissed it.** Clause 4 says the promotion is "the same first in,
first out waitlist promotion that a cancellation triggers today", and "The
promotion pass runs when a successful modification changed the interval."
Lengthening does change the interval, so the pass has to run. The question is
whether a cancellation would behave any differently on the same setup. I added
one probe to the same file to find out:

```java
@Test
void cancellationOfUnrelatedTimeAlsoPromotesTheStrandedWaiter() {
    String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
    strandAWaiter();
    var result = service.cancelBooking(id, "u-1");   // cancel instead of modify
    assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
    assertEquals(Fixtures.interval("15:00", "16:00"), result.promotions().get(0).booking().interval());
}
```

**It passed.** Cancelling a 10:00 booking, which is shipped code I didn't
touch, also promotes u-2 into 15:00-16:00. So the modification does exactly what
a cancellation does, which is what the change request asks for. The extra
promotion comes from the stranded waiter, and the stranded waiter comes from
step 5: the existing cancellation pass only walks the cancelled booking's own
room. That is pre-existing behavior outside T1's scope. "Promoting nobody" is
right when there is no free slot sitting on the waitlist, and that is the case
in my own `onlyLengtheningPromotesNobody` test.

If I had wanted it to promote "only into freed time", that would contradict
"the same ... promotion that a cancellation triggers today". So I read this as
a spec question rather than a T1 bug.

## Claims 2 and 3: not settled separately

- **Claim 2** (moving 10:00-11:00 to 10:15-11:15 promotes u-2 into
  15:00-16:00). This has the same cause and the same setup as claim 1, so the
  same cancellation probe answers it.
- **Claim 3** (`modifyBooking(id, "u-1", 10:00-11:00, 0)` throws
  `IllegalArgumentException: Attendees must be positive: 0`). This is the same
  behavior `requestBooking` already has, because both go through
  `BookingRequest.of`. The throw happens before any save, publish or waitlist
  write, so the stored booking is unchanged. The refuter itself ranked this one
  weakest. It's worth handling as a 400 in T4's HTTP layer.

## Cleanup

I deleted `RefuterProbeTest.java` afterwards. `mvn -q -pl core-domain -am test`
is green again (196 tests). No probe file is committed.
