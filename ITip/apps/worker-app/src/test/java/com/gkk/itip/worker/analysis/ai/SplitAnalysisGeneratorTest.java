package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import com.gkk.itip.worker.analysis.output.AnalysisOutputValidator;
import com.gkk.itip.worker.config.AnalysisGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.gkk.itip.worker.analysis.ai.SplitAnalysisFixtures.*;

class SplitAnalysisGeneratorTest {
    private final ChatModel model = mock(ChatModel.class);
    private final CaptionDocument captions = new CaptionDocument("fixture-video", "ko", List.of(
        new CaptionDocument.Segment("s000001", 0, 12000, "앞부분 73%입니다. {format} ${secret}."),
        new CaptionDocument.Segment("s000002", 12000, 24000, "뒷부분 근거입니다.")));

    private SplitAnalysisGenerator generator() throws Exception { return generator(16000,1024,4096,3072,30000); }
    private SplitAnalysisGenerator generator(int total,int plan,int detail,int summary,int input) throws Exception {
        var beans = new StaticListableBeanFactory(); beans.addBean("model", model);
        return new SplitAnalysisGenerator(beans.getBeanProvider(ChatModel.class),MAPPER,new AnalysisSchemas(MAPPER),
            new AnalysisCaptionGroups(MAPPER),new AnalysisGenerationProperties(false,"gpt-6-luna","O200K_BASE",1050000,input,total,plan,detail,summary,3));
    }

    @Test void splitsLocalContextsMapsOriginalIdsAndAssemblesTheCompleteContract() throws Exception {
        var requests = new CopyOnWriteArrayList<Prompt>();
        MDC.put("jobId","fixture-job");
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            assertThat(MDC.get("jobId")).isEqualTo("fixture-job");
            Prompt prompt = call.getArgument(0);requests.add(prompt);return reply(prompt);
        });
        var output = generator().generate(captions,24000,"원문 제목");MDC.clear();
        assertThat(output.sections()).hasSize(2);
        assertThat(output.sections().getFirst().startSegmentId()).isEqualTo("s000001");
        assertThat(output.sections().getLast().endSegmentId()).isEqualTo("s000002");
        assertThat(output.sections().getLast().sourceSegmentIds()).containsExactly("s000002");
        assertThat(output.mindmap().sectionKeys()).containsExactly("section-01","section-02");
        assertThatCode(() -> new AnalysisOutputValidator().validate(output,captions)).doesNotThrowAnyException();
        assertThat(requests).hasSize(4);
        assertThat(input(requests.getFirst()).has("groups")).isTrue();
        assertThat(input(requests.getLast()).has("evidence")).isTrue();
        assertThat(requests.stream().filter(p -> input(p).has("section")).map(SplitAnalysisFixtures::input))
            .allSatisfy(data -> assertThat(data.get("groups").size()).isEqualTo(1));
        for(var request:requests) {
            var options=(OpenAiChatOptions)request.getOptions();
            assertThat(options.getModel()).isEqualTo("gpt-6-luna");assertThat(options.getReasoningEffort()).isEqualTo("low");
            assertThat(options.getResponseFormat().getStrict()).isTrue();assertThat(options.getMaxRetries()).isZero();
        }
    }

    @ParameterizedTest @ValueSource(strings={"not-json","null","{} {}","```json\n{}\n```","{\"lastSectionTitle\":\"a\",\"lastSectionTitle\":\"b\",\"earlierSections\":[]}"})
    void rejectsMalformedOrDuplicateJsonWithoutRepairOrFurtherCalls(String text) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply(text,"STOP",100));
        var generator=generator();assertInvalid(() -> generator.generate(captions,24000,null));
        verify(model).call(any(Prompt.class));
    }

    @Test void rejectsReorderedBoundariesBeforeAnyDetails() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"earlierSections\":[{\"title\":\"a\",\"endSegmentId\":\"g000001\"},{\"title\":\"b\",\"endSegmentId\":\"g000001\"}],\"lastSectionTitle\":\"c\"}","STOP",100));
        var generator=generator();assertInvalid(() -> generator.generate(captions,24000,null));verify(model).call(any(Prompt.class));
    }

    @Test void rejectsOutOfSectionSourcesAndDoesNotCallSummary() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(c -> replyWithInvalidDetail(c.getArgument(0)));
        var generator=generator();assertInvalid(() -> generator.generate(captions,24000,null));
        var requests=ArgumentCaptor.forClass(Prompt.class);verify(model,times(3)).call(requests.capture());
        assertThat(requests.getAllValues()).noneMatch(p -> input(p).has("evidence"));
    }

    @Test void lengthFailureStopsBeforeSummaryAndDoesNotRetryTheDetail() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(c -> {
            Prompt p=c.getArgument(0);return input(p).has("section")?reply("","LENGTH",4096):reply(p);
        });
        var generator=generator();assertInvalid(() -> generator.generate(captions,24000,null));verify(model,times(3)).call(any(Prompt.class));
    }

    @Test void reservesSummaryBudgetAcrossConcurrentDetails() throws Exception {
        var requests=new CopyOnWriteArrayList<Prompt>();
        when(model.call(any(Prompt.class))).thenAnswer(c -> {
            Prompt p=c.getArgument(0);requests.add(p);
            return reply(MAPPER.writeValueAsString(payload(input(p))),"STOP",((OpenAiChatOptions)p.getOptions()).getMaxCompletionTokens());
        });
        generator(6000,1024,4096,3072,30000).generate(captions,24000,null);
        assertThat(requests.stream().mapToInt(p -> ((OpenAiChatOptions)p.getOptions()).getMaxCompletionTokens()).sum()).isEqualTo(6000);
        assertThat(requests.stream().filter(p -> input(p).has("section")).map(p -> ((OpenAiChatOptions)p.getOptions()).getMaxCompletionTokens()))
            .containsExactly(952,952);
    }

    @Test void rejectsUnknownUsageAndOversizedInputBeforeFurtherCalls() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
        var generator=generator();assertInvalid(() -> generator.generate(captions,24000,null));
        reset(model);
        var small=generator(16000,1024,4096,3072,1);
        assertThatThrownBy(() -> small.generate(captions,24000,null)).isInstanceOfSatisfying(AnalysisAiException.class,e -> assertThat(e.code()).isEqualTo("AI_INVALID_INPUT"));
        verifyNoInteractions(model);
    }

    @Test void providerFailuresRetainRetryClassificationWithoutSensitiveBodies() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new TransientAiException("PRIVATE_PROVIDER_BODY"));
        var generator=generator();
        assertThatThrownBy(() -> generator.generate(captions,24000,null)).isInstanceOfSatisfying(AnalysisAiException.class,e -> {
            assertThat(e.code()).isEqualTo("AI_UNAVAILABLE");assertThat(e.retryable()).isTrue();assertThat(e.getMessage()).doesNotContain("PRIVATE");assertThat(e.getCause()).isNull();
        });
    }

    @Test void preservesExcludedCueBoundariesAndExpandsOnlySpokenSourceIds() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(c -> reply(c.getArgument(0)));
        var document=new CaptionDocument("fixture-video","ko",List.of(
            new CaptionDocument.Segment("s0",0,1000,"[음악]"),
            new CaptionDocument.Segment("s1",1000,13000,"앞부분 근거입니다."),
            new CaptionDocument.Segment("s2",13000,14000,"[박수]"),
            new CaptionDocument.Segment("s3",14000,26000,"뒷부분 근거입니다."),
            new CaptionDocument.Segment("s4",26000,27000,"[음악]")));
        var output=generator().generate(document,27000,null);
        assertThat(output.sections().getFirst().startSegmentId()).isEqualTo("s0");
        assertThat(output.sections().getFirst().sourceSegmentIds()).containsExactly("s1");
        assertThat(output.sections().getLast().startSegmentId()).isEqualTo("s2");
        assertThat(output.sections().getLast().endSegmentId()).isEqualTo("s4");
        assertThat(output.sections().getLast().sourceSegmentIds()).containsExactly("s3");
        assertThatCode(() -> new AnalysisOutputValidator().validate(output,document)).doesNotThrowAnyException();
    }

    @Test void expandingOneGroupCanLegitimatelyYieldMoreThanFiveOriginalIds() throws Exception {
        when(model.call(any(Prompt.class))).thenAnswer(c -> reply(c.getArgument(0)));
        var originals=new ArrayList<CaptionDocument.Segment>();
        for(int i=0;i<10;i++)originals.add(new CaptionDocument.Segment("s"+i,i*2000,(i+1)*2000,"연속 원문"));
        var document=new CaptionDocument("fixture-video","ko",originals);
        var output=generator().generate(document,20000,null);
        assertThat(output.sections()).hasSize(1);
        assertThat(output.sections().getFirst().sourceSegmentIds()).hasSize(10);
        assertThatCode(() -> new AnalysisOutputValidator().validate(output,document)).doesNotThrowAnyException();
    }

    @Test void retainsUncitedUnicodeNumericOriginalsForTheSummary() throws Exception {
        var inputs=new CopyOnWriteArrayList<tools.jackson.databind.JsonNode>();
        when(model.call(any(Prompt.class))).thenAnswer(c -> {
            Prompt prompt=c.getArgument(0);var data=input(prompt);inputs.add(data);
            if(!data.has("section") && !data.has("evidence"))return reply("{\"earlierSections\":[],\"lastSectionTitle\":\"전체 주제\"}","STOP",100);
            return reply(prompt);
        });
        var document=new CaptionDocument("fixture-video","ko",List.of(
            new CaptionDocument.Segment("s1",0,12000,"첫 근거입니다."),
            new CaptionDocument.Segment("s2",12000,24000,"성공률은 ７３％입니다."),
            new CaptionDocument.Segment("s3",24000,36000,"마지막 설명입니다.")));
        generator().generate(document,36000,null);
        var summary=inputs.stream().filter(i -> i.has("evidence")).findFirst().orElseThrow();
        assertThat(summary.get("evidence").findValuesAsString("groupId")).containsExactly("g000001","g000002");
        assertThat(summary.get("evidence").findValuesAsString("text")).contains("성공률은 ７３％입니다.");
    }
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AnalysisAiException.class,e -> {assertThat(e.code()).isEqualTo("AI_INVALID_RESPONSE");assertThat(e.retryable()).isFalse();});
    }
}
