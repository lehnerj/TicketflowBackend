package com.ibm.ticketflow.model;

import org.springframework.data.cassandra.core.mapping.PrimaryKey;
import org.springframework.data.cassandra.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Cassandra row stored in the {@code ticket_events} table.
 */
@Table("ticket_events")
public class TicketEvent {

    @PrimaryKey
    private UUID id;

    private int    iteration;
    private String sentAt;
    private String action;
    private String outcome;
    private Instant processedAt;

    public TicketEvent() {}

    public TicketEvent(int iteration, String sentAt, String action, String outcome) {
        this.id          = UUID.randomUUID();
        this.iteration   = iteration;
        this.sentAt      = sentAt;
        this.action      = action;
        this.outcome     = outcome;
        this.processedAt = Instant.now();
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    public UUID    getId()          { return id; }
    public int     getIteration()   { return iteration; }
    public String  getSentAt()      { return sentAt; }
    public String  getAction()      { return action; }
    public String  getOutcome()     { return outcome; }
    public Instant getProcessedAt() { return processedAt; }

    // ── Setters (required by Spring Data Cassandra) ────────────────────────────

    public void setId(UUID id)                   { this.id = id; }
    public void setIteration(int iteration)      { this.iteration = iteration; }
    public void setSentAt(String sentAt)         { this.sentAt = sentAt; }
    public void setAction(String action)         { this.action = action; }
    public void setOutcome(String outcome)       { this.outcome = outcome; }
    public void setProcessedAt(Instant t)        { this.processedAt = t; }
}
