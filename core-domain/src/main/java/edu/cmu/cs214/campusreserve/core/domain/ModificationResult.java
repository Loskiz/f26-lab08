package edu.cmu.cs214.campusreserve.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What came of a modification. A modification that changed the interval
 * reports the bookings promoted off the waitlist into the time it freed.
 */
public sealed interface ModificationResult {

    /** The booking now holds the new details. {@code promotions} may be empty. */
    record Modified(Booking previous, Booking booking, List<CancellationResult.Promotion> promotions)
        implements ModificationResult {
        public Modified {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(booking, "booking");
            promotions = List.copyOf(promotions);
        }
    }

    /** The request matched the booking as it stands. Nothing was recorded. */
    record Unchanged(Booking booking) implements ModificationResult {
        public Unchanged {
            Objects.requireNonNull(booking, "booking");
        }
    }

    /** The modification was refused. Nothing changed. */
    record Rejected(RejectionReason reason, String detail) implements ModificationResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
            detail = detail == null ? reason.message() : detail;
        }
    }

    /** Builds a rejection carrying the reason's own message. */
    static ModificationResult rejected(RejectionReason reason) {
        return new Rejected(reason, reason.message());
    }

    /** Did the modification succeed, whether or not it changed anything? */
    default boolean succeeded() {
        return !(this instanceof Rejected);
    }

    /** The booking as it stands after a successful modification. */
    default Optional<Booking> currentBooking() {
        return switch (this) {
            case Modified modified -> Optional.of(modified.booking());
            case Unchanged unchanged -> Optional.of(unchanged.booking());
            case Rejected ignored -> Optional.empty();
        };
    }

    /** Promotions triggered by the modification, empty unless the interval changed. */
    default List<CancellationResult.Promotion> promotions() {
        return this instanceof Modified modified ? modified.promotions() : List.of();
    }

    /** The reason, when the modification was refused. */
    default Optional<RejectionReason> rejectionReason() {
        return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
    }
}
