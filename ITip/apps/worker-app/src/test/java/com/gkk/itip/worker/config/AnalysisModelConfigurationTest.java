package com.gkk.itip.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.ChatModel;
import static org.assertj.core.api.Assertions.*;

class AnalysisModelConfigurationTest {
    private final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(AnalysisModelConfiguration.class);

    @Test void staysDisabledWithoutSecretsOrModelFallbacks() {
        context.run(c -> {assertThat(c).hasNotFailed();assertThat(c).doesNotHaveBean(ChatModel.class);
            assertThat(c.getBean(AnalysisGenerationProperties.class).detailOutputTokens()).isEqualTo(4096);});
    }

    @Test void requiresExplicitModelTokenizerAndCapacityWhenEnabled() {
        context.withPropertyValues("itip.analysis.generation.enabled=true").run(c -> assertThat(c).hasFailed());
    }

    @Test void invalidBudgetsAndConcurrencyFailBeforeExternalRequests() {
        for(String property: new String[]{"detail-concurrency=4","total-output-tokens=2000","detail-output-tokens=0","input-token-budget=0"})
            context.withPropertyValues("itip.analysis.generation."+property).run(c -> assertThat(c).hasFailed());
    }
}
