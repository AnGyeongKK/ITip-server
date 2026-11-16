package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import com.gkk.itip.domain.analysis.AnalysisBatchPushOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class ExpiredPreparationService {
    private final AnalysisBatchItemRepository items;
    private final AnalysisOwnerLock ownerLock;
    private final AnalysisBatchPushOutbox pushOutbox;

    public ExpiredPreparationService(AnalysisBatchItemRepository items, AnalysisOwnerLock ownerLock,
                                     AnalysisBatchPushOutbox pushOutbox) {
        this.items = items;
        this.ownerLock = ownerLock;
        this.pushOutbox = pushOutbox;
    }

    @Transactional(readOnly = true)
    public List<String> findOwners(Instant now) { return items.findExpiredPreparationOwners(now); }

    @Transactional
    public int expire(String ownerId, Instant now) {
        ownerLock.lock(ownerId);
        int changed = 0;
        for (var item : items.findExpiredPreparations(ownerId, now)) {
            if (item.expireAwaitingUpload(now)) changed++;
        }
        if (changed > 0) pushOutbox.enqueueFinishedBatches(ownerId, now);
        return changed;
    }
}
