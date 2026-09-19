package com.ibm.ticketflow.model;

/**
 * Payload sent to the backend for every request.
 *
 * <ul>
 *   <li><b>iteration</b>       – globally unique, contiguous request number (1-based)</li>
 *   <li><b>sentAt</b>          – human-readable ISO-8601 timestamp of when the request was built</li>
 *   <li><b>action</b>          – {@code WRITE} to insert a record, {@code SEARCH} to query</li>
 *   <li><b>expectedOutcome</b> – {@code SUCCESS} if the backend should process normally,
 *                                {@code FAILURE} to trigger an intentional error path</li>
 * </ul>
 */
public record EventRequest(
        int     iteration,
        String  sentAt,
        Action  action,
        Outcome expectedOutcome
) {
    public enum Action  { WRITE, SEARCH }
    public enum Outcome { SUCCESS, FAILURE }
}
