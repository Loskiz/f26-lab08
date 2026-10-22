package edu.cmu.cs214.campusreserve.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.BookingChange;
import edu.cmu.cs214.campusreserve.core.domain.BookingStatus;
import edu.cmu.cs214.campusreserve.core.domain.ModificationResult;
import edu.cmu.cs214.campusreserve.core.domain.RejectionReason;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.policy.BookingPolicy;
import edu.cmu.cs214.campusreserve.core.policy.ConflictChecker;
import edu.cmu.cs214.campusreserve.core.support.Fixtures;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BookingServiceModifyTest extends BookingServiceTestBase {

    private Booking stored(String id) {
        return service.findBooking(id).orElseThrow();
    }

    /** Asserts the modification was refused for {@code reason} and left the booking and the log alone. */
    private void assertRejectedUnchanged(String id, TimeInterval interval, int attendees, RejectionReason reason) {
        Booking before = stored(id);
        List<String> eventsBefore = publisher.types();
        ModificationResult result = service.modifyBooking(id, "u-1", interval, attendees);
        assertEquals(Optional.of(reason), result.rejectionReason());
        assertEquals(before, stored(id));
        assertEquals(eventsBefore, publisher.types());
    }

    // Clause 1-2: a successful modification is actually stored.

    @Test
    void storesTheNewDetailsAndOneHistoryEntry() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 6);

        assertInstanceOf(ModificationResult.Modified.class, result);
        Booking after = stored(id);
        assertEquals(Fixtures.interval("13:00", "14:00"), after.interval());
        assertEquals(6, after.attendees());
        assertEquals("r-1", after.roomId());
        assertEquals("u-1", after.userId());
        assertEquals(BookingStatus.CONFIRMED, after.status());
        assertEquals(
            List.of(new BookingChange(Instant.parse(Fixtures.NOW), Fixtures.interval("10:00", "11:00"), 4)),
            after.history());
        assertEquals(1, bookings.count());
    }

    @Test
    void eachModificationAppendsToTheHistoryInOrder() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 4);
        clock.advanceMinutes(5);
        service.modifyBooking(id, "u-1", Fixtures.interval("12:00", "13:00"), 2);

        List<BookingChange> history = stored(id).history();
        assertEquals(2, history.size());
        assertEquals(Fixtures.interval("10:00", "11:00"), history.get(0).previousInterval());
        assertEquals(Fixtures.interval("12:00", "13:00"), history.get(1).previousInterval());
        assertEquals(4, history.get(1).previousAttendees());
        assertEquals(Instant.parse("2026-10-19T09:05:00Z"), history.get(1).changedAt());
    }

    @Test
    void publishesAModifiedEventWithOldAndNewDetails() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 6);

        assertEquals(List.of("booking-created", "booking-modified"), publisher.types());
        BookingModifiedEvent event = publisher.ofType(BookingModifiedEvent.class).get(0);
        assertEquals(id, event.bookingId());
        assertEquals("u-1", event.userId());
        assertEquals(Fixtures.interval("13:00", "14:00"), event.interval());
        assertEquals(6, event.attendees());
        assertEquals(Fixtures.interval("10:00", "11:00"), event.previousInterval());
        assertEquals(4, event.previousAttendees());
    }

    @Test
    void aFailingPublisherDoesNotUndoTheModification() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        publisher.failWith(new IllegalStateException("subscriber down"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 4);

        assertTrue(result.succeeded());
        assertEquals(Fixtures.interval("13:00", "14:00"), stored(id).interval());
    }

    @Test
    void cancellingKeepsTheHistory() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 4);
        service.cancelBooking(id, "u-1");
        assertEquals(1, stored(id).history().size());
    }

    // Clause 5: a modification that changes nothing records nothing.

    @Test
    void aNoOpSucceedsAndRecordsNothing() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = stored(id);

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertInstanceOf(ModificationResult.Unchanged.class, result);
        assertEquals(before, stored(id));
        assertTrue(stored(id).history().isEmpty());
        assertEquals(List.of("booking-created"), publisher.types());
    }

    // Clause 1: ownership and timing.

    @Test
    void rejectsAnUnknownBooking() {
        ModificationResult result = service.modifyBooking("bk-missing", "u-1", Fixtures.interval("10:00", "11:00"), 4);
        assertEquals(Optional.of(RejectionReason.BOOKING_NOT_FOUND), result.rejectionReason());
    }

    @Test
    void rejectsSomeoneElsesBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        Booking before = stored(id);
        ModificationResult result = service.modifyBooking(id, "u-2", Fixtures.interval("13:00", "14:00"), 4);
        assertEquals(Optional.of(RejectionReason.NOT_BOOKING_OWNER), result.rejectionReason());
        assertEquals(before, stored(id));
    }

    @Test
    void rejectsABookingThatHasStarted() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        clock.setTo("2026-10-20T10:00:00Z");
        assertRejectedUnchanged(id, Fixtures.interval("10:00", "12:00"), 4, RejectionReason.BOOKING_ALREADY_STARTED);
    }

    @Test
    void rejectsACancelledBooking() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        service.cancelBooking(id, "u-1");
        assertRejectedUnchanged(id, Fixtures.interval("13:00", "14:00"), 4, RejectionReason.BOOKING_ALREADY_CANCELLED);
    }

    // Clause 2: conflict re-check, ignoring the booking itself.

    @Test
    void nudgingByOneSlotOverlapsOnlyItselfAndIsAllowed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:15", "11:15"), 4);
        assertTrue(result.succeeded());
        assertEquals(Fixtures.interval("10:15", "11:15"), stored(id).interval());
    }

    @Test
    void movingOntoAnotherBookingIsRejectedAndTheOriginalStands() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        String other = bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        Booking otherBefore = stored(other);

        assertRejectedUnchanged(id, Fixtures.interval("12:30", "13:30"), 4, RejectionReason.ROOM_UNAVAILABLE);
        assertEquals(otherBefore, stored(other));
        assertTrue(waitlist.findByRoom("r-1").isEmpty());
    }

    @Test
    void endingExactlyWhereAnotherStartsIsAllowed() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-1", "u-2", Fixtures.interval("12:00", "13:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "12:00"), 4).succeeded());
    }

    @Test
    void movingOntoTheOwnersBookingInAnotherRoomIsRejected() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        bookAndGetId("r-2", "u-1", Fixtures.interval("13:00", "14:00"));
        assertRejectedUnchanged(id, Fixtures.interval("13:00", "14:00"), 4, RejectionReason.USER_DOUBLE_BOOKED);
    }

    @Test
    void theBookingCapDoesNotApply() {
        build(BookingPolicy.defaults().withMaxActiveBookingsPerUser(1));
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 4).succeeded());
    }

    // Clause 3: every booking rule still applies.

    @Test
    void rejectsTooShort() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.interval("10:00", "10:15"), 4, RejectionReason.INTERVAL_TOO_SHORT);
    }

    @Test
    void rejectsTooLongForTheRoom() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.interval("10:00", "14:00"), 4, RejectionReason.INTERVAL_TOO_LONG);
    }

    @Test
    void rejectsMisaligned() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.interval("10:05", "11:05"), 4, RejectionReason.INTERVAL_NOT_ALIGNED);
    }

    @Test
    void rejectsCrossingMidnightUtc() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        TimeInterval overnight = TimeInterval.parse("2026-10-20T23:00:00Z", "2026-10-21T00:30:00Z");
        assertRejectedUnchanged(id, overnight, 4, RejectionReason.INTERVAL_SPANS_DAYS);
    }

    @Test
    void rejectsOutsideOpeningHours() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.interval("05:00", "06:00"), 4, RejectionReason.OUTSIDE_OPENING_HOURS);
    }

    @Test
    void rejectsMovingIntoThePast() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.intervalOn("2026-10-19", "08:00", "09:00"), 4,
            RejectionReason.INTERVAL_IN_PAST);
    }

    @Test
    void rejectsBeyondTheAdvanceWindow() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.intervalOn("2026-11-10", "10:00", "11:00"), 4,
            RejectionReason.TOO_FAR_IN_ADVANCE);
    }

    @Test
    void rejectsMoreAttendeesThanTheRoomSeats() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertRejectedUnchanged(id, Fixtures.interval("10:00", "11:00"), 11, RejectionReason.ATTENDEES_EXCEED_CAPACITY);
    }

    @Test
    void rejectsWhenTheRoomHasLeftService() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        rooms.save(rooms.findById("r-1").orElseThrow().withActive(false));
        assertRejectedUnchanged(id, Fixtures.interval("13:00", "14:00"), 4, RejectionReason.ROOM_INACTIVE);
    }

    // Clause 4: freed time goes to the waitlist.

    @Test
    void shorteningPromotesTheWaiterForTheFreedTime() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "12:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("11:00", "12:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 4);

        assertEquals(1, result.promotions().size());
        assertEquals("u-2", result.promotions().get(0).booking().userId());
        assertEquals(1, service.activeBookingsForUser("u-2").size());
        assertTrue(service.waitlistForRoom("r-1").isEmpty());
        assertEquals(List.of("booking-created", "booking-modified", "waitlist-promoted"), publisher.types());
    }

    @Test
    void movingAwayPromotesTheFirstWaiterOnly() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:00", "11:00")).isWaitlisted());
        assertTrue(book("r-1", "u-3", Fixtures.interval("10:00", "11:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 4);

        assertEquals(List.of("u-2"), result.promotions().stream().map(p -> p.booking().userId()).toList());
        assertEquals(1, service.waitlistForUser("u-3").size());
        assertFalse(new ConflictChecker().containsMutualConflict(service.bookingsForRoom("r-1")));
    }

    @Test
    void aWaiterWhoIsBusyElsewhereIsSkipped() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:00", "11:00")).isWaitlisted());
        assertTrue(book("r-1", "u-3", Fixtures.interval("10:00", "11:00")).isWaitlisted());
        bookAndGetId("r-2", "u-2", Fixtures.interval("10:00", "11:00"));

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("13:00", "14:00"), 4);

        assertEquals(List.of("u-3"), result.promotions().stream().map(p -> p.booking().userId()).toList());
        assertEquals(1, service.activeBookingsForUser("u-2").size());
    }

    @Test
    void changingOnlyTheAttendeesPromotesNobody() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:00", "11:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "11:00"), 2);

        assertTrue(result.succeeded());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, service.waitlistForRoom("r-1").size());
        assertEquals(List.of("booking-created", "booking-modified"), publisher.types());
    }

    @Test
    void onlyLengtheningPromotesNobody() {
        String id = bookAndGetId("r-1", "u-1", Fixtures.interval("10:00", "11:00"));
        assertTrue(book("r-1", "u-2", Fixtures.interval("10:00", "11:00")).isWaitlisted());

        ModificationResult result = service.modifyBooking(id, "u-1", Fixtures.interval("10:00", "12:00"), 4);

        assertTrue(result.succeeded());
        assertTrue(result.promotions().isEmpty());
        assertEquals(1, service.waitlistForRoom("r-1").size());
    }
}
