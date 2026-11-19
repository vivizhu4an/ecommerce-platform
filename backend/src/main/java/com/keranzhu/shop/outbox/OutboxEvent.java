package com.keranzhu.shop.outbox;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** Already-serialized JSON; MySQL validates it on insert into the JSON column. */
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventId, Long aggregateId, String eventType, String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
    }

    public void recordFailedAttempt() {
        this.attempts++;
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public Long getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public int getAttempts() { return attempts; }
    public Instant getPublishedAt() { return publishedAt; }
}
