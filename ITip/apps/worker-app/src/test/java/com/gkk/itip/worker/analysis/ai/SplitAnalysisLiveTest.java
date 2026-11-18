package com.gkk.itip.worker.analysis.ai;

import com.gkk.itip.ai.OpenAiClients;
import com.gkk.itip.worker.analysis.caption.AnalysisCaptionGroups;
import com.gkk.itip.worker.analysis.caption.CaptionDocument;
import com.gkk.itip.worker.analysis.output.AnalysisOutputValidator;
import com.gkk.itip.worker.config.AnalysisGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;
import static com.gkk.itip.worker.analysis.ai.SplitAnalysisFixtures.MAPPER;

/** 명시적으로 켰을 때만 유료 호출한다. 입력은 직접 작성한 공개 가능한 가상 자막이다. */
@EnabledIfEnvironmentVariable(named="ITIP_LIVE_AI",matches="true")
class SplitAnalysisLiveTest {
    @Test void verifiesThreeStageSpringAiCallsWithSelfAuthoredCaptions() throws Exception {
        String key=System.getenv("OPENAI_API_KEY");assertThat(key).isNotBlank();
        var client=OpenAiClients.create(key,"https://api.openai.com/v1",Duration.ofSeconds(120),Duration.ofSeconds(120));
        var records=new CopyOnWriteArrayList<Map<String,Object>>();
        long started=System.nanoTime();
        try {
            var actual=OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(OpenAiChatOptions.builder().maxRetries(0).store(false).build()).build();
            ChatModel observed=prompt -> {
                long begin=System.nanoTime();var response=actual.call(prompt);var options=(OpenAiChatOptions)prompt.getOptions();
                var usage=response.getMetadata().getUsage();var record=new LinkedHashMap<String,Object>();
                record.put("system",prompt.getInstructions().getFirst().getText());record.put("input",MAPPER.readTree(prompt.getInstructions().getLast().getText()));
                record.put("schema",MAPPER.readTree(options.getResponseFormat().getJsonSchema()));record.put("model",options.getModel());
                record.put("reasoning_effort",options.getReasoningEffort());record.put("max_completion_tokens",options.getMaxCompletionTokens());
                record.put("providerModel",response.getMetadata().getModel());record.put("finishReason",response.getResult().getMetadata().getFinishReason());
                record.put("response",response.getResult().getOutput().getText());record.put("promptTokens",usage.getPromptTokens());record.put("completionTokens",usage.getCompletionTokens());
                record.put("cacheReadInputTokens",usage.getCacheReadInputTokens());record.put("cacheWriteInputTokens",usage.getCacheWriteInputTokens());
                record.put("seconds",(System.nanoTime()-begin)/1e9);records.add(record);return response;
            };
            var beans=new StaticListableBeanFactory();beans.addBean("model",observed);
            var properties=new AnalysisGenerationProperties(true,"gpt-6-luna","O200K_BASE",1050000,30000,16000,1024,4096,3072,3);
            var generator=new SplitAnalysisGenerator(beans.getBeanProvider(ChatModel.class),MAPPER,new AnalysisSchemas(MAPPER),new AnalysisCaptionGroups(MAPPER),properties);
            var captions=new CaptionDocument("self-authored-fixture","ko",List.of(
                new CaptionDocument.Segment("s000001",0,8000,"가상 팀 A는 배포 테스트를 자동화한 뒤 배포 시간이 40분에서 10분으로 줄었습니다."),
                new CaptionDocument.Segment("s000002",8000,14000,"이 수치는 해당 팀의 사례이며 모든 팀에서 같은 효과를 보장하지 않습니다. 배포 전 자동 테스트를 확인합니다.")));
            var output=generator.generate(captions,14000,"가상 팀의 배포 자동화 사례");
            new AnalysisOutputValidator().validate(output,captions);
            assertThat(records).hasSize(3);
            assertThat(output.sections()).hasSize(1);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("kind","live Spring AI self-authored smoke, not seven-video quality validation");
            evidence.put("date",java.time.LocalDate.now().toString());evidence.put("seconds",(System.nanoTime()-started)/1e9);
            evidence.put("captions",captions);evidence.put("calls",records);evidence.put("output",output);evidence.put("validationPassed",true);
            Path path=Path.of("build/reports/analysis-live-smoke.json");Files.createDirectories(path.getParent());Files.writeString(path,MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } finally {client.close();}
    }
}
