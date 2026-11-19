package com.keranzhu.shop.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Remembers which event ids a consumer has already handled. */
@Component
public class ProcessedMessageStore {

    private final JdbcTemplate jdbc;

    public ProcessedMessageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event id in the caller's transaction.
     *
     * @return true the first time an id is seen, false for a redelivery
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markFirstDelivery(String eventId) {
        return jdbc.update("INSERT IGNORE INTO processed_messages (event_id) VALUES (?)", eventId) == 1;
    }
}
