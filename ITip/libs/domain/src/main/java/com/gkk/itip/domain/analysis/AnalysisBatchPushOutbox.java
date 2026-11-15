package com.gkk.itip.domain.analysis;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

@Component
public class AnalysisBatchPushOutbox {
    public static final Duration TTL = Duration.ofHours(24);

    private final EntityManager em;
    private final JdbcTemplate jdbc;

    public AnalysisBatchPushOutbox(EntityManager em, JdbcTemplate jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    // Call while holding the owner lock, so concurrent terminal updates cannot miss each other.
    // Linked jobs determine the effective state, including jobs shared by multiple batches.
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueFinishedBatches(String ownerId, Instant now) {
        em.flush();
        jdbc.update("""
                with candidate as (
                    select b.id, counts.completed, counts.failed,
                           case when counts.total = 1 and counts.completed = 1 then counts.analysis_id end as analysis_id
                    from analysis_batches b
                    cross join lateral (
                        select count(*) filter (where item.status = 'COMPLETED') as completed,
                               count(*) filter (where item.status = 'FAILED') as failed,
                               count(*) as total,
                               min(item.analysis_id::text) filter (where item.status = 'COMPLETED') as analysis_id
                        from (
                            select case when i.job_id is null then i.status else j.status end as status,
                                   case when i.job_id is null then i.analysis_id else j.analysis_id end as analysis_id
                            from analysis_batch_items i
                            left join analysis_jobs j on j.id = i.job_id and j.owner_id = b.owner_id
                                and j.video_id = i.video_id
                            where i.batch_id = b.id
                        ) item
                    ) counts
                    where b.owner_id = ? and b.push_pending
                      and counts.total > 0 and counts.completed + counts.failed = counts.total
                ), finished as (
                    update analysis_batches b set push_pending = false
                    from candidate c where b.id = c.id and b.push_pending
                    returning b.id, b.owner_id, c.completed, c.failed, c.analysis_id
                )
                insert into analysis_push_deliveries
                    (batch_id, device_id, installation_id, completed_count, failed_count, analysis_id, next_attempt_at, expires_at)
                select b.id, d.id, d.installation_id, b.completed, b.failed, b.analysis_id::uuid, ?, ?
                from finished b join notification_devices d on d.owner_id = b.owner_id
                where d.permission = 'GRANTED' and d.fcm_token is not null
                on conflict (batch_id, installation_id) do nothing
                """, ownerId, now.atOffset(ZoneOffset.UTC), now.plus(TTL).atOffset(ZoneOffset.UTC));
    }
}
