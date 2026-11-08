package com.gkk.itip.api.analysis.dto.response;

import com.gkk.itip.domain.analysis.ItemStatus;
import java.util.UUID;

public record CollectionFailureResponse(UUID batchId, UUID itemId, ItemStatus status, BatchStatusResponse.Failure error) {}
