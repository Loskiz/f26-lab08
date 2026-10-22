package edu.cmu.cs214.campusreserve.core.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry in a booking's modification history: what the booking was just
 * before a modification, and when that modification happened.
 */
public record BookingChange(Instant changedAt, TimeInterval previousInterval, int previousAttendees) {

    public BookingChange {
        Objects.requireNonNull(changedAt, "changedAt");
        Objects.requireNonNull(previousInterval, "previousInterval");
        if (previousAttendees <= 0) {
            throw new IllegalArgumentException("Attendees must be positive: " + previousAttendees);
        }
    }
}
