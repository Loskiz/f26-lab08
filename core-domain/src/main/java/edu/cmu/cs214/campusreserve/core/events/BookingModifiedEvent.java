package edu.cmu.cs214.campusreserve.core.events;

import edu.cmu.cs214.campusreserve.core.domain.Booking;
import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Instant;
import java.util.Objects;

/**
 * A booking changed its interval, its attendee count, or both. {@code interval}
 * and {@code attendees} are the new details; the event also carries what the
 * booking was before.
 */
public record BookingModifiedEvent(
    Instant occurredAt,
    String bookingId,
    String roomId,
    String userId,
    TimeInterval interval,
    int attendees,
    TimeInterval previousInterval,
    int previousAttendees) implements DomainEvent {

    /** The wire name of this event. */
    public static final String TYPE = "booking-modified";

    public BookingModifiedEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(bookingId, "bookingId");
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(previousInterval, "previousInterval");
    }

    /** Builds the event from the booking before and after the modification. */
    public static BookingModifiedEvent of(Booking before, Booking after, Instant occurredAt) {
        return new BookingModifiedEvent(
            occurredAt,
            after.id(),
            after.roomId(),
            after.userId(),
            after.interval(),
            after.attendees(),
            before.interval(),
            before.attendees());
    }

    @Override
    public String type() {
        return TYPE;
    }
}
