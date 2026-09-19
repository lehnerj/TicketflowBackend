package com.ibm.ticketflow.controller;

import com.ibm.ticketflow.model.EventRequest;
import com.ibm.ticketflow.model.EventRequest.Action;
import com.ibm.ticketflow.model.EventRequest.Outcome;
import com.ibm.ticketflow.model.TicketEvent;
import com.ibm.ticketflow.repository.TicketEventRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Primary REST endpoint for the TicketFlow demo backend.
 *
 * <pre>
 * POST /api/event/{action}/{outcome}/{iteration}
 *   body: EventRequest JSON
 *   → 200  when expectedOutcome == SUCCESS and action is processed
 *   → 500  when expectedOutcome == FAILURE  (intentional error path)
 *
 * GET /api/events
 *   → 200  list of all TicketEvent records
 *
 * GET /api/events/{id}
 *   → 200  single TicketEvent
 *   → 404  if id not found
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class EventController {

    private static final Logger log = LogManager.getLogger(EventController.class);

    private final TicketEventRepository repository;

    /**
     * Target CPU burn duration per WRITE request in milliseconds.
     * Set via environment variable CPU_BURN_MS (default: 10).
     * Set to 0 to disable the burn entirely.
     */
    @Value("${app.cpu-burn-ms:10}")
    private long cpuBurnMs;

    public EventController(TicketEventRepository repository) {
        this.repository = repository;
    }

    // ── CPU burn (Sieve of Eratosthenes, time-bounded) ─────────────────────────
    //
    // Runs the sieve repeatedly until the target wall-clock duration has elapsed.
    // Using a real algorithm (not Thread.sleep) ensures the CPU core is actually
    // busy — visible as real CPU usage in Turbonomic and as a custom span in
    // Instana.  The sieve limit is chosen so one pass takes ~1 ms on a modest
    // container, giving reasonable granularity without overshooting badly.

    private void burnCpu(long targetMs) {
        if (targetMs <= 0) return;
        final long deadline = System.nanoTime() + targetMs * 1_000_000L;
        final int  limit    = 50_000; // sieve up to 50 000 per pass
        do {
            boolean[] sieve = new boolean[limit + 1];
            Arrays.fill(sieve, true);
            sieve[0] = sieve[1] = false;
            for (int i = 2; (long) i * i <= limit; i++) {
                if (sieve[i]) {
                    for (int j = i * i; j <= limit; j += i) {
                        sieve[j] = false;
                    }
                }
            }
        } while (System.nanoTime() < deadline);
    }

    // ── POST /api/event/{action}/{outcome}/{iteration} ─────────────────────────

    @PostMapping("/event/{action}/{outcome}/{iteration}")
    public ResponseEntity<?> handleEvent(
            @PathVariable String action,
            @PathVariable String outcome,
            @PathVariable int iteration,
            @RequestBody EventRequest request) {

        log.info("Received request iteration={} sentAt={} action={} expectedOutcome={}",
                request.iteration(), request.sentAt(), request.action(), request.expectedOutcome());

        // ── Intentional failure path ──────────────────────────────────────────
        if (request.expectedOutcome() == Outcome.FAILURE) {
            log.error("Intentional failure triggered for iteration={} – returning HTTP 500",
                    request.iteration());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "status",    "FAILURE",
                            "iteration", request.iteration(),
                            "message",   "Intentional failure as requested by load driver"
                    ));
        }

        // ── WRITE path ────────────────────────────────────────────────────────
        if (request.action() == Action.WRITE) {
            burnCpu(cpuBurnMs);
            TicketEvent event = new TicketEvent(
                    request.iteration(),
                    request.sentAt(),
                    request.action().name(),
                    request.expectedOutcome().name()
            );
            repository.save(event);
            log.info("WRITE success – saved event id={} iteration={}", event.getId(), event.getIteration());
            return ResponseEntity.ok(Map.of(
                    "status",    "SUCCESS",
                    "action",    "WRITE",
                    "iteration", request.iteration(),
                    "id",        event.getId().toString()
            ));
        }

        // ── SEARCH path ───────────────────────────────────────────────────────
        if (request.action() == Action.SEARCH) {
            List<TicketEvent> results = repository.findAll();
            log.info("SEARCH success – found {} records for iteration={}", results.size(), request.iteration());
            return ResponseEntity.ok(Map.of(
                    "status",    "SUCCESS",
                    "action",    "SEARCH",
                    "iteration", request.iteration(),
                    "count",     results.size()
            ));
        }

        // Should never reach here given enum constraints, but be defensive
        log.warn("Unknown action={} for iteration={}", request.action(), request.iteration());
        return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Unknown action"));
    }

    // ── GET /api/events ────────────────────────────────────────────────────────

    @GetMapping("/events")
    public ResponseEntity<List<TicketEvent>> getAllEvents() {
        List<TicketEvent> events = repository.findAll();
        log.info("GET /api/events – returning {} records", events.size());
        return ResponseEntity.ok(events);
    }

    // ── GET /api/events/{id} ───────────────────────────────────────────────────

    @GetMapping("/events/{id}")
    public ResponseEntity<?> getEventById(@PathVariable String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("GET /api/events/{} – invalid UUID format", id);
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "ERROR", "message", "Invalid UUID: " + id));
        }

        return repository.findById(uuid)
                .<ResponseEntity<?>>map(event -> {
                    log.info("GET /api/events/{} – found event iteration={}", id, event.getIteration());
                    return ResponseEntity.ok(event);
                })
                .orElseGet(() -> {
                    log.warn("GET /api/events/{} – not found", id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(Map.of("status", "NOT_FOUND", "id", id));
                });
    }
}
