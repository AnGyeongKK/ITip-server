package com.gkk.itip.api.analysis.service;

import com.gkk.itip.api.analysis.dto.response.AnalysisResultResponse;
import com.gkk.itip.api.analysis.dto.response.AnalysisSectionResponse;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.domain.analysis.AnalysisRecord;
import com.gkk.itip.domain.analysis.AnalysisRecordRepository;
import com.gkk.itip.domain.analysis.AnalysisResult;
import com.gkk.itip.domain.analysis.AnalysisResultRepository;
import com.gkk.itip.domain.analysis.result.ResultDocument;
import com.gkk.itip.domain.analysis.result.ResultDocumentValidator;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.domain.video.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AnalysisResultQueryService {
    private final AnalysisResultRepository results;
    private final AnalysisRecordRepository records;
    private final VideoRepository videos;
    private final JsonMapper mapper;

    public AnalysisResultQueryService(AnalysisResultRepository results, AnalysisRecordRepository records,
                                      VideoRepository videos, JsonMapper mapper) {
        this.results = results;
        this.records = records;
        this.videos = videos;
        this.mapper = mapper;
    }

    public AnalysisResultResponse get(String ownerId, UUID analysisId) {
        var stored = completed(ownerId, analysisId);
        var document = stored.document();
        return new AnalysisResultResponse(analysisId, stored.record().getId(), "COMPLETED", document.schemaVersion(),
                AnalysisResultResponse.VideoInfo.from(stored.video()), document.summary(),
                document.sections().stream().sorted(Comparator.comparingLong(ResultDocument.Section::startMs)
                                .thenComparingLong(ResultDocument.Section::endMs).thenComparing(ResultDocument.Section::sectionId))
                        .map(AnalysisResultResponse.SectionItem::from).toList(),
                document.mindmap(), true, stored.result().getCompletedAt());
    }

    private CompletedResult completed(String ownerId, UUID analysisId) {
        var result = results.findByIdAndOwnerId(analysisId, ownerId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (!result.isChatReady() || result.getCompletedAt() == null) throw new BusinessException(ANALYSIS_NOT_READY);
        var record = records.findByOwnerIdAndAnalysisId(ownerId, analysisId)
                .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        if (!record.getVideoId().equals(result.getVideoId())) throw new BusinessException(INTERNAL_ERROR);
        var video = videos.findById(result.getVideoId()).orElseThrow(() -> new BusinessException(INTERNAL_ERROR));
        return new CompletedResult(result, record, video, readDocument(result.getResultJson()));
    }

    public AnalysisSectionResponse getSection(String ownerId, UUID analysisId, UUID sectionId) {
        var stored = completed(ownerId, analysisId);
        var section = stored.document().sections().stream().filter(s -> s.sectionId().equals(sectionId.toString()))
                .findFirst().orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
        String videoId = stored.video().getVideoId();
        String originalUrl = UriComponentsBuilder.fromUriString("https://www.youtube.com/watch")
                .queryParam("v", videoId).queryParam("t", section.startMs() / 1000).build().encode().toUriString();
        return new AnalysisSectionResponse(analysisId, stored.record().getId(), section.sectionId(), section.title(),
                section.startMs(), section.endMs(), section.summary(), section.subtopics(), section.keywords(),
                videoId, originalUrl, section.detail(), AnalysisResultResponse.VideoInfo.from(stored.video()),
                stored.document().summary().oneLine());
    }

    private record CompletedResult(AnalysisResult result, AnalysisRecord record, Video video, ResultDocument document) {}

    private ResultDocument readDocument(String json) {
        try {
            var document = mapper.readValue(json, ResultDocument.class);
            ResultDocumentValidator.validate(document);
            return document;
        } catch (RuntimeException exception) {
            throw new BusinessException(INTERNAL_ERROR);
        }
    }

}
