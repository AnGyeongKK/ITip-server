package com.gkk.itip.worker.analysis.caption;

import com.gkk.itip.worker.analysis.output.ResultDocument;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CaptionChunkerTest {
    private final CaptionChunker chunker = new CaptionChunker();
    private final UUID analysisId = UUID.randomUUID();
    private final String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();

    @Test void keepsRealSourceTextTimesAndSectionIds() {
        var captions = new CaptionDocument("dQw4w9WgXcQ", "ko", List.of(
                new CaptionDocument.Segment("s000001", 0, 2000, "원문 검색"),
                new CaptionDocument.Segment("s000002", 1500, 3000, "근거 답변")));
        var chunks = chunker.split(analysisId, captions, sections(), 20);
        assertThat(chunks).hasSize(1);
        var chunk = chunks.getFirst();
        assertThat(chunk.text()).isEqualTo("원문 검색\n근거 답변");
        assertThat(chunk.sourceSegmentIds()).containsExactly("s000001", "s000002");
        assertThat(chunk.sectionIds()).containsExactly(UUID.fromString(first), UUID.fromString(second));
        assertThat(chunk.startMs()).isZero();
        assertThat(chunk.endMs()).isEqualTo(3000);
        assertThat(chunker.split(analysisId, captions, sections(), 20)).isEqualTo(chunks);
    }

    @Test void splitsLongSegmentsWithoutBreakingUnicodeOrInventingTimePositions() {
        var captions = new CaptionDocument("dQw4w9WgXcQ", "ko", List.of(
                new CaptionDocument.Segment("s000001", 0, 2000, "가😀나다😀라")));
        var chunks = chunker.split(analysisId, captions, sections(), 2);
        assertThat(chunks).extracting(CaptionChunker.Chunk::text).containsExactly("가😀", "나다", "😀라");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.startMs()).isZero();
            assertThat(chunk.endMs()).isEqualTo(2000);
            assertThat(chunk.sourceSegmentIds()).containsExactly("s000001");
        });
        assertThat(chunks).extracting(CaptionChunker.Chunk::id).doesNotHaveDuplicates();
    }

    @Test void doesNotPutSummarySentencesIntoCaptionSearchData() {
        var captions = new CaptionDocument("dQw4w9WgXcQ", "ko", List.of(
                new CaptionDocument.Segment("s000001", 0, 1000, "원본 발언")));
        assertThat(chunker.split(analysisId, captions, sections(), 100)).extracting(CaptionChunker.Chunk::text)
                .containsExactly("원본 발언");
        assertThatThrownBy(() -> chunker.split(analysisId, captions, sections(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private List<ResultDocument.Section> sections() {
        return List.of(new ResultDocument.Section(first, "첫 주제", 0, 2000, "AI 요약", List.of(), List.of(), null),
                new ResultDocument.Section(second, "둘째 주제", 2000, 3000, "AI 요약", List.of(), List.of(), null));
    }
}
