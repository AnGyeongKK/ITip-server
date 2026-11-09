package com.gkk.itip.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import java.net.URI;
import java.time.Duration;

/** 실제 두 앱에서 쓰는 SDK 연결·시간 제한 설정. 반환 client는 Bean 종료 시 close한다. */
public final class OpenAiClients {
    private OpenAiClients() {}
    public static OpenAIClient create(String key,String baseUrl,Duration total,Duration idle) {
        var uri=URI.create(baseUrl);
        if(key.isBlank() || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw new IllegalArgumentException("valid OpenAI API key and base URL required");
        var timeout=Timeout.builder().request(total).connect(Duration.ofSeconds(3)).read(idle).write(Duration.ofSeconds(8)).build();
        var http=SpringAiOpenAiHttpClient.builder().timeout(timeout).build();
        return new OpenAIClientImpl(ClientOptions.builder().httpClient(http).apiKey(key).baseUrl(baseUrl)
            .timeout(timeout).maxRetries(0).build());
    }
}
