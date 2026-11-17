package com.gkk.itip.domain.analysis;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AnalysisOwnerLock {
    private final JdbcTemplate jdbc;
    public AnalysisOwnerLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String KEY_PREFIX = "itip-analysis:";

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(String ownerId) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", KEY_PREFIX + ownerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(String ownerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, KEY_PREFIX + ownerId));
    }
}
