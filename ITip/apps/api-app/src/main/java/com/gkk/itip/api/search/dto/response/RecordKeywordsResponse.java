package com.gkk.itip.api.search.dto.response;

import java.util.List;

public record RecordKeywordsResponse(List<KeywordCount> keywords, long matchedCount) {
    public record KeywordCount(String keywordId, String name, long recordCount, boolean selected) {}
}
