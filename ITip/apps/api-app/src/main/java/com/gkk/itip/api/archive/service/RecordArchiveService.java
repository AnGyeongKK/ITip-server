package com.gkk.itip.api.archive.service;

import com.gkk.itip.api.analysis.service.AnalysisResultQueryService;
import com.gkk.itip.api.archive.dto.ReadingPositionResponse;
import com.gkk.itip.api.archive.dto.ReadingPositionResponse.ReadingPosition;
import com.gkk.itip.api.archive.dto.RecordDetailResponse;
import com.gkk.itip.api.archive.dto.RecordDetailResponse.RecordInfo;
import com.gkk.itip.api.archive.dto.SaveReadingPositionRequest;
import com.gkk.itip.api.archive.repository.FolderRepository;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.analysis.AnalysisRecord;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.INTERNAL_ERROR;
import static com.gkk.itip.common.exception.ErrorCode.RESOURCE_NOT_FOUND;
import static com.gkk.itip.common.exception.ErrorCode.VALIDATION_FAILED;

@Service
public class RecordArchiveService {
    private final AnalysisRecordRepository records;
    private final AnalysisResultQueryService results;
    private final FolderRepository folders;
    private final AnalysisOwnerLock ownerLock;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public RecordArchiveService(AnalysisRecordRepository records, AnalysisResultQueryService results, FolderRepository folders,
                                AnalysisOwnerLock ownerLock, JdbcTemplate jdbc, Clock clock) {
        this.records = records;
        this.results = results;
        this.folders = folders;
        this.ownerLock = ownerLock;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecordDetailResponse get(String ownerId, UUID recordId) {
        var record = ownedRecord(ownerId, recordId);
        var analysis = results.get(ownerId, record.getAnalysisId());
        var info = new RecordInfo(recordId, record.getAnalysisId(), record.getVideoId(), record.getAnalyzedAt());
        var position = record.getLastViewedAt() == null ? null : readingPosition(record);
        return new RecordDetailResponse(info, analysis, folders.memberships(ownerId, recordId), position);
    }

    @Transactional
    public ReadingPositionResponse saveReadingPosition(String ownerId, UUID recordId, SaveReadingPositionRequest request) {
        ownerLock.lock(ownerId);
        var record = ownedRecord(ownerId, recordId);
        UUID sectionId = request.sectionId();
        if (sectionId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from analysis_sections where id = ? and analysis_id = ?)
                """, Boolean.class, sectionId, record.getAnalysisId()))) {
            throw new BusinessException(VALIDATION_FAILED);
        }
        record.saveReadingPosition(request.tab(), sectionId, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return new ReadingPositionResponse(readingPosition(record));
    }

    @Transactional
    public void delete(String ownerId, UUID recordId) {
        ownerLock.lock(ownerId);
        var record = ownedRecord(ownerId, recordId);
        UUID analysisId = record.getAnalysisId();
        records.delete(record);
        records.flush();
        jdbc.update("delete from analysis_chunks where analysis_id = ? and owner_id = ?", analysisId, ownerId);
        jdbc.update("delete from analysis_sections where analysis_id = ?", analysisId);
        if (jdbc.update("delete from analysis_results where id = ? and owner_id = ?", analysisId, ownerId) != 1) {
            throw new BusinessException(INTERNAL_ERROR);
        }
    }

    private AnalysisRecord ownedRecord(String ownerId, UUID recordId) {
        return records.findByIdAndOwnerId(recordId, ownerId).orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    }

    private ReadingPosition readingPosition(AnalysisRecord record) {
        return new ReadingPosition(record.getReadingTab(), record.getReadingSectionId(), null, record.getLastViewedAt());
    }
}
