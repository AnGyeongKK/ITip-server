package com.gkk.itip.api.video.service;

import com.gkk.itip.api.video.dto.response.YoutubeResourceResponse.Existing;
import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import com.gkk.itip.domain.analysis.AnalysisJobRepository;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.JobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class ExistingVideoAnalyses {
    static final Existing NONE = new Existing(Existing.Status.NONE, null, null, null, null);
    private static final Set<JobStatus> ACTIVE = Set.of(JobStatus.QUEUED, JobStatus.PROCESSING, JobStatus.RETRY_WAIT);

    private final AnalysisRecordRepository records;
    private final AnalysisJobRepository jobs;
    private final AnalysisBatchItemRepository items;
    private final Clock clock;

    public ExistingVideoAnalyses(AnalysisRecordRepository records, AnalysisJobRepository jobs,
                                 AnalysisBatchItemRepository items, Clock clock) {
        this.records = records;
        this.jobs = jobs;
        this.items = items;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Existing> find(String ownerId, Set<String> videoIds) {
        if (videoIds.isEmpty()) return Map.of();

        Map<String, Existing> found = new HashMap<>();
        for (var record : records.findLatestByOwnerAndVideoIds(ownerId, videoIds)) {
            found.put(record.getVideoId(), new Existing(Existing.Status.COMPLETED,
                    record.getAnalysisId(), record.getId(), null, null));
        }
        Set<String> remaining = new HashSet<>(videoIds);
        remaining.removeAll(found.keySet());
        if (remaining.isEmpty()) return found;

        for (var job : jobs.findAllByOwnerIdAndVideoIdInAndStatusIn(ownerId, remaining, ACTIVE)) {
            found.put(job.getVideoId(), new Existing(Existing.Status.PROCESSING, null, null, job.getId(), null));
        }
        remaining.removeAll(found.keySet());
        if (remaining.isEmpty()) return found;

        for (var preparation : items.findUnexpiredPreparations(ownerId, remaining, clock.instant())) {
            found.put(preparation.getVideoId(), new Existing(Existing.Status.AWAITING_UPLOAD,
                    null, null, null, preparation.getBatchId()));
        }
        return found;
    }
}
