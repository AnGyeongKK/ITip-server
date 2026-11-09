package com.gkk.itip.worker.analysis;

import com.gkk.itip.domain.analysis.AnalysisBatchItemRepository;
import com.gkk.itip.domain.analysis.AnalysisOwnerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
public class ExpiredPreparationService {
    private final AnalysisBatchItemRepository items;
    private final AnalysisOwnerLock ownerLock;

    public ExpiredPreparationService(AnalysisBatchItemRepository items, AnalysisOwnerLock ownerLock) {
        this.items = items; this.ownerLock = ownerLock;
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
        return changed;
    }
}
