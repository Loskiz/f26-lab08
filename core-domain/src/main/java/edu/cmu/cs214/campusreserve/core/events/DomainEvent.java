package edu.cmu.cs214.campusreserve.core.events;

import edu.cmu.cs214.campusreserve.core.domain.TimeInterval;
import java.time.Instant;

/**
 * Something that happened to a booking. Events are facts about the past: they
 * are emitted after the service has already committed the change.
 *
 * <p>The type strings are part of the platform's contract with anything that
 * reads the event log, so they are declared once here and nowhere else.
 */
public sealed interface DomainEvent
    permits BookingCreatedEvent, BookingCancelledEvent, BookingModifiedEvent, WaitlistPromotedEvent {

    /** The wire name of this event, for example {@code booking-created}. */
    String type();

    /** When the platform recorded the event. */
    Instant occurredAt();

    /** The booking the event is about. */
    String bookingId();

    /** The room the booking holds or held. */
    String roomId();

    /** The user who holds or held the booking. */
    String userId();

    /** The booked interval. */
    TimeInterval interval();

    /** How many people the booking is for. */
    int attendees();
}
