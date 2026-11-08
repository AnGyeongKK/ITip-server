package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.*;
import com.gkk.itip.domain.upload.CaptionUpload;
import com.gkk.itip.domain.upload.UploadStatus;
import com.gkk.itip.domain.video.Video;
import com.gkk.itip.worker.analysis.caption.CaptionChunker.Chunk;
import com.gkk.itip.worker.analysis.output.ResultDocument;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkerResultStore {
    private final AnalysisJobRepository jobs;
    private final AnalysisResultRepository results;
    private final AnalysisSectionRepository sections;
    private final AnalysisRecordRepository records;
    private final AnalysisOwnerLock ownerLock;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public WorkerResultStore(AnalysisJobRepository jobs, AnalysisResultRepository results,
                             AnalysisSectionRepository sections, AnalysisRecordRepository records,
                             AnalysisOwnerLock ownerLock, EntityManager em, JdbcTemplate jdbc, JsonMapper mapper) {
        this.jobs = jobs;
        this.results = results;
        this.sections = sections;
        this.records = records;
        this.ownerLock = ownerLock;
        this.em = em;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Draft(UUID analysisId, ResultDocument document, boolean indexed) {}
    public record Source(String bucket, String objectKey, long generation, Long durationMs, long declaredSizeBytes) {}

    @Transactional(readOnly = true)
    public Optional<Draft> draft(JobQueue.Claim claim, Instant now) {
        var job = requireLease(claim, now, false);
        return job.getAnalysisId() == null ? Optional.empty() : Optional.of(loadDraft(job));
    }

    @Transactional(readOnly = true)
    public Source source(JobQueue.Claim claim, Instant now) {
        var job = requireLease(claim, now, false);
        var upload = requireUpload(job);
        if (upload.getStatus() != UploadStatus.CONSUMED)
            throw new IllegalStateException("job upload has not been consumed");
        var video = em.find(Video.class, job.getVideoId());
        if (video == null) throw new IllegalStateException("job video is missing");
        return new Source(upload.getBucket(), upload.getObjectKey(), job.getGeneration(),
                video.getDurationMs(), upload.getDeclaredSizeBytes());
    }

    @Transactional
    public Draft saveDraft(JobQueue.Claim claim, ResultDocument document, Instant now) {
        var job = requireLease(claim, now, true);
        if (job.getAnalysisId() != null) return loadDraft(job);
        validateDocument(document);
        UUID id = UUID.randomUUID();
        var result = AnalysisResult.draft(id, job.getOwnerId(), job.getVideoId(), job.getId(), mapper.writeValueAsString(document), now);
        results.saveAndFlush(result);
        for (var section : document.sections()) {
            sections.save(new AnalysisSection(UUID.fromString(section.sectionId()), id, section.title(),
                    section.startMs(), section.endMs(), mapper.writeValueAsString(section.detail())));
        }
        sections.flush();
        job.attachDraft(claim.runToken(), id, now);
        return new Draft(id, document, false);
    }

    @Transactional
    public void saveIndex(JobQueue.Claim claim, UUID analysisId, List<Chunk> chunks, String embeddingModel,
                          List<float[]> vectors, Instant now) {
        var job = requireLease(claim, now, true);
        requireAnalysis(job, analysisId);
        var draft = loadDraft(job);
        if (draft.indexed()) return;
        if (indexState(job).count() > 0)
            throw new IllegalStateException("stored index has inconsistent ownership, sections, model or dimensions");
        var savedSections = requireSections(draft);
        int dimensions = validateIndex(chunks, embeddingModel, vectors, savedSections);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var arrays = new ArrayList<Array>();
            try (var statement = connection.prepareStatement("""
                    insert into analysis_chunks
                    (id, owner_id, analysis_id, section_ids, source_segment_ids, text, start_ms, end_ms,
                     source_type, embedding, embedding_model, embedding_dimensions)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'CAPTION', ?::vector, ?, ?)
                    """)) {
                for (int i = 0; i < chunks.size(); i++) {
                    var chunk = chunks.get(i);
                    Array sectionIds = connection.createArrayOf("uuid", chunk.sectionIds().toArray(UUID[]::new));
                    arrays.add(sectionIds);
                    Array sourceIds = connection.createArrayOf("text", chunk.sourceSegmentIds().toArray(String[]::new));
                    arrays.add(sourceIds);
                    statement.setObject(1, chunk.id());
                    statement.setString(2, job.getOwnerId());
                    statement.setObject(3, analysisId);
                    statement.setArray(4, sectionIds);
                    statement.setArray(5, sourceIds);
                    statement.setString(6, chunk.text());
                    statement.setLong(7, chunk.startMs());
                    statement.setLong(8, chunk.endMs());
                    statement.setString(9, vectorLiteral(vectors.get(i)));
                    statement.setString(10, embeddingModel);
                    statement.setInt(11, dimensions);
                    statement.addBatch();
                }
                statement.executeBatch();
            } finally {
                for (Array array : arrays) array.free();
            }
            return null;
        });
    }

    @Transactional
    public UUID complete(JobQueue.Claim claim, UUID analysisId, Instant now) {
        ownerLock.lock(claim.ownerId());
        var job = requireLease(claim, now, true);
        requireAnalysis(job, analysisId);
        var draft = loadDraft(job);
        requireSections(draft);
        if (!draft.indexed()) throw new IllegalStateException("completion requires a prepared caption index");
        job.changeStage(claim.runToken(), AnalysisStage.FINISHING, now);
        var result = requirePrivateResult(job);
        result.markReady(now);
        var record = records.saveAndFlush(AnalysisRecord.fromCompleted(result));
        job.complete(claim.runToken(), analysisId, record.getId(), now);
        return record.getId();
    }

    private AnalysisJob requireLease(JobQueue.Claim claim, Instant now, boolean lock) {
        Objects.requireNonNull(claim);
        Objects.requireNonNull(now);
        var job = (lock ? jobs.findByIdForUpdate(claim.jobId()) : jobs.findById(claim.jobId()))
                .orElseThrow(LostJobLeaseException::new);
        if (!job.isLeaseOwned(claim.runToken(), now)) throw new LostJobLeaseException();
        if (!job.getOwnerId().equals(claim.ownerId()) || !job.getVideoId().equals(claim.videoId())
                || !job.getBatchId().equals(claim.batchId()) || !job.getBatchItemId().equals(claim.batchItemId())
                || !job.getUploadId().equals(claim.uploadId()) || job.getGeneration() != claim.generation())
            throw new IllegalStateException("claim source does not match the stored job");
        var batch = em.find(AnalysisBatch.class, job.getBatchId());
        var item = em.find(AnalysisBatchItem.class, job.getBatchItemId());
        if (batch == null || !batch.getOwnerId().equals(job.getOwnerId()) || item == null
                || !item.getBatchId().equals(job.getBatchId()) || !item.getVideoId().equals(job.getVideoId())
                || !job.getId().equals(item.getJobId()))
            throw new IllegalStateException("job batch ownership or video is inconsistent");
        requireUpload(job);
        return job;
    }

    private CaptionUpload requireUpload(AnalysisJob job) {
        var upload = em.find(CaptionUpload.class, job.getUploadId());
        // A manual retry owns a new batch item, but deliberately reads the previous consumed object.
        if (upload == null || !upload.getOwnerId().equals(job.getOwnerId()) || !upload.getVideoId().equals(job.getVideoId())
                || !Objects.equals(upload.getGeneration(), job.getGeneration()))
            throw new IllegalStateException("job upload ownership, video or generation is inconsistent");
        return upload;
    }

    private void requireAnalysis(AnalysisJob job, UUID analysisId) {
        if (analysisId == null || !analysisId.equals(job.getAnalysisId()))
            throw new IllegalStateException("analysis does not belong to this job");
    }

    private AnalysisResult requirePrivateResult(AnalysisJob job) {
        var result = results.findById(job.getAnalysisId()).orElseThrow(() -> new IllegalStateException("job draft is missing"));
        if (!result.getOwnerId().equals(job.getOwnerId()) || !result.getVideoId().equals(job.getVideoId()) || result.isChatReady())
            throw new IllegalStateException("job requires its owner's unfinished result for the same video");
        return result;
    }

    private Draft loadDraft(AnalysisJob job) {
        var result = requirePrivateResult(job);
        ResultDocument document;
        try {
            document = mapper.readValue(result.getResultJson(), ResultDocument.class);
            validateDocument(document);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("stored draft JSON is invalid", failure);
        }
        return new Draft(result.getId(), document, indexState(job).ready());
    }

    private Map<UUID, AnalysisSection> requireSections(Draft draft) {
        var saved = sections.findAllByAnalysisIdOrderByStartMsAscIdAsc(draft.analysisId());
        var byId = saved.stream().collect(Collectors.toMap(AnalysisSection::getId, Function.identity()));
        if (saved.isEmpty() || saved.size() != draft.document().sections().size())
            throw new IllegalStateException("stored draft sections are incomplete");
        for (var section : draft.document().sections()) {
            var row = byId.get(UUID.fromString(section.sectionId()));
            if (row == null || row.getStartMs() != section.startMs() || row.getEndMs() != section.endMs()
                    || !row.getTitle().equals(section.title()) || !row.getDetailJson().equals(mapper.writeValueAsString(section.detail())))
                throw new IllegalStateException("stored draft section differs from screen JSON");
        }
        return byId;
    }

    private record IndexState(long count, boolean ready) {}

    private IndexState indexState(AnalysisJob job) {
        return jdbc.queryForObject("""
                select count(*) as total,
                    count(*) > 0 and count(distinct embedding_model) = 1
                    and count(distinct embedding_dimensions) = 1
                    and bool_and(owner_id = ? and embedding_dimensions = vector_dims(embedding)
                        and embedding_dimensions > 0 and length(trim(embedding_model)) > 0
                        and not exists (
                            select 1 from unnest(c.section_ids) as ids(id)
                            left join analysis_sections s on s.id = ids.id and s.analysis_id = c.analysis_id
                            where s.id is null or not (c.start_ms < s.end_ms and s.start_ms < c.end_ms)
                        )) as ready
                from analysis_chunks c where analysis_id = ?
                """, (rs, row) -> new IndexState(rs.getLong("total"), rs.getBoolean("ready")), job.getOwnerId(), job.getAnalysisId());
    }

    private static void validateDocument(ResultDocument document) {
        if (document == null || !"1.0".equals(document.schemaVersion()) || document.summary() == null
                || document.mindmap() == null || document.sections() == null || document.sections().isEmpty())
            throw new IllegalArgumentException("validated screen result and nonempty sections are required");
        var ids = new HashSet<UUID>();
        for (var section : document.sections()) {
            if (section == null || section.sectionId() == null || !ids.add(UUID.fromString(section.sectionId()))
                    || section.startMs() < 0 || section.endMs() <= section.startMs()
                    || section.title() == null || section.title().isBlank() || section.title().length() > 500 || section.detail() == null)
                throw new IllegalArgumentException("unique section UUID, title, detail and valid time range are required");
        }
        if (document.summary().highlightSectionIds() == null || document.mindmap().sectionIds() == null
                || document.mindmap().sectionIds().isEmpty())
            throw new IllegalArgumentException("screen result section references are required");
        for (String id : document.summary().highlightSectionIds()) requireSectionReference(id, ids);
        for (String id : document.mindmap().sectionIds()) requireSectionReference(id, ids);
    }

    private static void requireSectionReference(String id, Set<UUID> sectionIds) {
        if (id == null || !sectionIds.contains(UUID.fromString(id)))
            throw new IllegalArgumentException("screen result refers to an unknown section");
    }

    private static int validateIndex(List<Chunk> chunks, String model, List<float[]> vectors, Map<UUID, AnalysisSection> sections) {
        if (chunks == null || chunks.isEmpty() || vectors == null || chunks.size() != vectors.size())
            throw new IllegalArgumentException("a nonempty index requires one embedding per chunk");
        if (model == null || model.isBlank() || model.length() > 255)
            throw new IllegalArgumentException("embedding model must contain 1 to 255 characters");
        int dimensions = vectors.getFirst() == null ? 0 : vectors.getFirst().length;
        if (dimensions == 0) throw new IllegalArgumentException("embedding dimensions must be positive");
        for (int i = 0; i < chunks.size(); i++) {
            var vector = vectors.get(i);
            if (vector == null || vector.length != dimensions)
                throw new IllegalArgumentException("embedding dimensions must match");
            boolean nonzero = false;
            for (float value : vector) {
                if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding values must be finite");
                nonzero |= value != 0;
            }
            if (!nonzero) throw new IllegalArgumentException("embedding must not be a zero vector");
            var chunk = chunks.get(i);
            if (chunk == null || chunk.id() == null || chunk.text() == null || chunk.text().isBlank()
                    || chunk.startMs() < 0 || chunk.endMs() <= chunk.startMs() || chunk.sourceSegmentIds().isEmpty()
                    || chunk.sourceSegmentIds().stream().anyMatch(id -> id == null || id.isBlank()))
                throw new IllegalArgumentException("caption chunk requires source text, source IDs and a valid time range");
            for (UUID sectionId : chunk.sectionIds()) {
                var section = sections.get(sectionId);
                if (section == null || chunk.startMs() >= section.getEndMs() || section.getStartMs() >= chunk.endMs())
                    throw new IllegalArgumentException("chunk section must belong to this analysis and overlap its source time");
            }
        }
        return dimensions;
    }

    private static String vectorLiteral(float[] vector) {
        var literal = new StringJoiner(",", "[", "]");
        for (float value : vector) literal.add(Float.toString(value));
        return literal.toString();
    }
}
