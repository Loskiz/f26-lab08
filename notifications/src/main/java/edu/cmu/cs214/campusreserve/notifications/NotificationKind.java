package edu.cmu.cs214.campusreserve.notifications;

import edu.cmu.cs214.campusreserve.core.events.BookingCancelledEvent;
import edu.cmu.cs214.campusreserve.core.events.BookingCreatedEvent;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.events.DomainEvent;
import edu.cmu.cs214.campusreserve.core.events.WaitlistPromotedEvent;
import java.util.Objects;

/**
 * What a message is about.
 *
 * <p>Every kind comes from a domain event, and the codes match the event wire
 * names, which keeps a stored notification easy to line up against the event
 * log.
 */
public enum NotificationKind {

    /** A booking now holds a room. */
    BOOKING_CONFIRMED("booking-confirmed"),

    /** A booking gave its room back. */
    BOOKING_CANCELLED("booking-cancelled"),

    /** A waitlist entry turned into a booking. */
    WAITLIST_PROMOTED("waitlist-promoted");

    private final String code;

    NotificationKind(String code) {
        this.code = code;
    }

    /** The stable wire name, for example {@code booking-confirmed}. */
    public String code() {
        return code;
    }

    /** Looks a kind up by its wire code. */
    public static NotificationKind fromCode(String code) {
        for (NotificationKind kind : values()) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown notification kind code: " + code);
    }

    /**
     * The kind of message an event asks for. The switch is exhaustive over the
     * sealed event hierarchy, so a new event type breaks the build here rather
     * than silently producing no message.
     */
    public static NotificationKind forEvent(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case BookingCreatedEvent ignored -> BOOKING_CONFIRMED;
            case BookingCancelledEvent ignored -> BOOKING_CANCELLED;
            case BookingModifiedEvent ignored ->
                throw new UnsupportedOperationException("No message for booking-modified yet");
            case WaitlistPromotedEvent ignored -> WAITLIST_PROMOTED;
        };
    }
}
