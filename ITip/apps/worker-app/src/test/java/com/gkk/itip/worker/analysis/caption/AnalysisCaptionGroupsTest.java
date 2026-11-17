package com.gkk.itip.worker.analysis.caption;

import com.gkk.itip.worker.analysis.ai.AnalysisAiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AnalysisCaptionGroupsTest {
    private final AnalysisCaptionGroups groups = new AnalysisCaptionGroups(JsonMapper.builder().build());
    private CaptionDocument document(CaptionDocument.Segment... segments) { return new CaptionDocument("fixture-video","ko",List.of(segments)); }
    private CaptionDocument.Segment segment(String id,long start,long end,String text) { return new CaptionDocument.Segment(id,start,end,text); }

    @Test void groupsAtSentenceBoundariesAndPreservesTheShortTailAndOriginalIds() {
        var input=document(segment("s1",0,6000,"첫  문장"),segment("s2",6000,12000,"이어집니다."),segment("s3",12000,15000,"마지막"));
        var prepared=groups.prepare(input,20000,null);
        assertThat(prepared.groups()).hasSize(2);
        assertThat(prepared.groups().getFirst().originalSegmentIds()).containsExactly("s1","s2");
        assertThat(prepared.groups().getFirst().text()).isEqualTo("첫 문장 이어집니다.");
        assertThat(prepared.groups().getLast().originalSegmentIds()).containsExactly("s3");
        assertThat(prepared.input()).doesNotContainKey("title");
        assertThat(input.segments().getFirst().text()).isEqualTo("첫  문장");
    }

    @Test void clipsRollingEndsOnlyForGroupingAndKeepsTheActualMaximumEnd() {
        var prepared=groups.prepare(document(segment("s1",0,50000,"겹치는 자막"),segment("s2",5000,15000,"이어지는 문장.")),50000,null);
        assertThat(prepared.groups()).hasSize(1);
        assertThat(prepared.groups().getFirst().endMs()).isEqualTo(50000);
        assertThat(prepared.groups().getFirst().originalSegmentIds()).containsExactly("s1","s2");
    }

    @Test void doesNotSplitLongCaptionTextOrInventIntermediateTimes() {
        var prepared=groups.prepare(document(segment("s1",0,45000,"긴 원문입니다.")),45000,"제목");
        assertThat(prepared.groups()).hasSize(1);
        assertThat(prepared.groups().getFirst().startMs()).isZero();
        assertThat(prepared.groups().getFirst().endMs()).isEqualTo(45000);
    }

    @Test void excludesNonverbalCuesButDoesNotDropTheirOriginalMapping() {
        var input=document(segment("s1",0,1000,"[음악]"),segment("s2",1000,13000,"근거입니다."),segment("s3",13000,14000,"[박수]"));
        var prepared=groups.prepare(input,14000,null);
        assertThat(prepared.groups()).hasSize(1);
        assertThat(prepared.groups().getFirst().originalSegmentIds()).containsExactly("s2");
        assertThat(prepared.original()).isEqualTo(input);
        assertThatThrownBy(() -> groups.prepare(document(segment("s1",0,1000,"[음악]")),1000,null)).isInstanceOf(AnalysisAiException.class);
    }

    @Test void rejectsDuplicateOriginalIdsAndOutOfRangeTimes() {
        assertThatThrownBy(() -> groups.prepare(document(segment("s1",0,1000,"a"),segment("s1",1000,2000,"b")),2000,null)).isInstanceOf(AnalysisAiException.class);
        assertThatThrownBy(() -> groups.prepare(document(segment("s1",0,3000,"a")),2000,null)).isInstanceOf(AnalysisAiException.class);
    }
}
