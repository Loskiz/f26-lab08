package edu.cmu.cs214.campusreserve.notifications;

import edu.cmu.cs214.campusreserve.core.events.BookingCancelledEvent;
import edu.cmu.cs214.campusreserve.core.events.BookingCreatedEvent;
import edu.cmu.cs214.campusreserve.core.events.BookingModifiedEvent;
import edu.cmu.cs214.campusreserve.core.events.DomainEvent;
import edu.cmu.cs214.campusreserve.core.events.WaitlistPromotedEvent;
import edu.cmu.cs214.campusreserve.core.ports.EventPublisher;
import edu.cmu.cs214.campusreserve.notifications.templates.MessageTemplate;
import edu.cmu.cs214.campusreserve.notifications.templates.Placeholders;
import edu.cmu.cs214.campusreserve.notifications.templates.TemplateRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns domain events into messages and puts them in the outbox.
 *
 * <p>This is a subscriber on the booking flow, so it is fire and forget in the
 * strict sense: {@link #publish(DomainEvent)} never throws for anything that
 * goes wrong while writing a message. A user who is not in the directory, a
 * kind with no template, and a template that disagrees with the values on hand
 * all end up on {@link #skipped()} with a reason, and the booking that caused
 * the event stands either way.
 *
 * <p>Callers who care whether messages are going out read {@link #skippedCount()}
 * rather than catching exceptions. A skip count climbing while the queued count
 * stays flat is the signal that something is wrong.
 */
public final class NotificationService implements EventPublisher {

    private final UserDirectory directory;
    private final Outbox outbox;
    private final TemplateRegistry templates;
    private final Clock clock;
    private final Channel channel;

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final List<SkippedEvent> skipped = new CopyOnWriteArrayList<>();

    /**
     * @param directory where contact details come from
     * @param outbox where finished messages go
     * @param templates the wording to use, one template per kind
     * @param clock stamps each message: injected so tests do not read the wall clock
     * @param channel how messages from this service go out
     */
    public NotificationService(
        UserDirectory directory,
        Outbox outbox,
        TemplateRegistry templates,
        Clock clock,
        Channel channel) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /**
     * A service with the shipped templates, sending over email. This is the
     * constructor an application wires up unless it has its own wording.
     */
    public NotificationService(UserDirectory directory, Outbox outbox, Clock clock) {
        this(directory, outbox, TemplateRegistry.standard(), clock, Channel.EMAIL);
    }

    /**
     * Writes the message an event calls for and queues it.
     *
     * <p>Nothing about a message is worth failing a booking over, so every
     * runtime failure below is caught and recorded here.
     */
    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        Optional<Recipient> recipient = directory.find(event.userId());
        if (recipient.isEmpty()) {
            skip(event.type(), event.bookingId(), event.userId(), "no recipient in the directory");
            return;
        }
        try {
            NotificationKind kind = NotificationKind.forEvent(event);
            Map<String, String> values = valuesFor(event, recipient.get());
            outbox.enqueue(write(kind, recipient.get(), values));
            queued.incrementAndGet();
        } catch (RuntimeException failure) {
            skip(event.type(), event.bookingId(), event.userId(), failure.getMessage());
        }
    }

    /** How many messages this service has queued. */
    public long queuedCount() {
        return queued.get();
    }

    /** How many messages this service could not write. */
    public int skippedCount() {
        return skipped.size();
    }

    /** Everything this service could not write, oldest first. */
    public List<SkippedEvent> skipped() {
        return List.copyOf(skipped);
    }

    private Notification write(NotificationKind kind, Recipient recipient, Map<String, String> values) {
        MessageTemplate template = templates.require(kind);
        return new Notification(
            nextId(),
            recipient.userId(),
            recipient.email(),
            channel,
            kind,
            template.subject(values),
            channel.fit(template.body(values)),
            clock.instant());
    }

    private String nextId() {
        return String.format("ntf-%06d", sequence.incrementAndGet());
    }

    private void skip(String eventType, String bookingId, String userId, String reason) {
        skipped.add(new SkippedEvent(eventType, bookingId, userId,
            reason == null ? "no reason given" : reason));
    }

    /**
     * The values the shipped templates ask for, per event type. The switch is
     * exhaustive over the sealed event hierarchy, so an added event type is a
     * compile error here rather than a message that never goes out.
     */
    private static Map<String, String> valuesFor(DomainEvent event, Recipient recipient) {
        String name = recipient.greetingName();
        String when = IntervalText.formatUtc(event.interval());
        return switch (event) {
            case BookingCreatedEvent created -> Map.of(
                Placeholders.NAME, name,
                Placeholders.ROOM_ID, created.roomId(),
                Placeholders.WHEN, when,
                Placeholders.ATTENDEES, String.valueOf(created.attendees()),
                Placeholders.BOOKING_ID, created.bookingId());
            case BookingCancelledEvent cancelled -> Map.of(
                Placeholders.NAME, name,
                Placeholders.ROOM_ID, cancelled.roomId(),
                Placeholders.WHEN, when,
                Placeholders.BOOKING_ID, cancelled.bookingId());
            case BookingModifiedEvent ignored ->
                throw new UnsupportedOperationException("No message for booking-modified yet");
            case WaitlistPromotedEvent promoted -> Map.of(
                Placeholders.NAME, name,
                Placeholders.ROOM_ID, promoted.roomId(),
                Placeholders.WHEN, when,
                Placeholders.ATTENDEES, String.valueOf(promoted.attendees()),
                Placeholders.POSITION, String.valueOf(promoted.waitlistSequence()),
                Placeholders.BOOKING_ID, promoted.bookingId());
        };
    }

    /**
     * A message that was not written, and why.
     *
     * @param eventType the wire name of the event
     * @param bookingId the booking the message would have been about
     * @param userId who the message would have gone to
     * @param reason what stopped it
     */
    public record SkippedEvent(String eventType, String bookingId, String userId, String reason) {
    }
}
