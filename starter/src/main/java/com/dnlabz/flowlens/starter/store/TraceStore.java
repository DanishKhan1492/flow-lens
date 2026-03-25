package com.dnlabz.flowlens.starter.store;

import com.dnlabz.flowlens.starter.model.TraceRecord;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe in-memory ring buffer for {@link TraceRecord}s.
 *
 * <p>Stores the most recent {@value #MAX_RECORDS} traces. Oldest entries
 * are evicted automatically when the limit is reached.
 */
public class TraceStore {

    private static final int MAX_RECORDS = 100;

    /** Deque: newest trace is at the front (index 0 in getAll()). */
    private final Deque<TraceRecord> deque = new ConcurrentLinkedDeque<>();

    public void add(TraceRecord record) {
        deque.addFirst(record);
        // Evict from the tail to stay within the ring-buffer limit
        while (deque.size() > MAX_RECORDS) {
            deque.pollLast();
        }
    }

    /** Returns all stored traces, newest first. */
    public List<TraceRecord> getAll() {
        return new ArrayList<>(deque);
    }

    /** Looks up a single trace by its ID. Returns {@code null} if not found. */
    public TraceRecord getById(String id) {
        for (TraceRecord r : deque) {
            if (r.getId().equals(id)) return r;
        }
        return null;
    }

    public void clear() {
        deque.clear();
    }

    public int size() {
        return deque.size();
    }
}
