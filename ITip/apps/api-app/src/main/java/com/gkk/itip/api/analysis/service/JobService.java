package com.gkk.itip.api.analysis.service;

import com.gkk.itip.api.analysis.dto.request.SubmitJobRequest;
import com.gkk.itip.api.analysis.dto.response.*;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.storage.GcsAccessException;
import com.gkk.itip.common.storage.GcsObjects;
import org.springframework.stereotype.Service;
import java.util.UUID;

import static com.gkk.itip.common.exception.ErrorCode.*;

/** 외부 GCS 요청은 DB 서비스의 짧은 트랜잭션 사이에서 실행한다. */
@Service
public class JobService {
    private final JobDbService database;
    private final GcsObjects objects;
    public JobService(JobDbService database, GcsObjects objects) { this.database = database; this.objects = objects; }

    public JobSubmittedResponse submit(String ownerId, UUID key, SubmitJobRequest request) {
        var plan = database.prepareSubmit(ownerId, key, request);
        if (plan.response() != null) return plan.response();
        return database.admitSubmit(ownerId, key, request, plan.upload(), metadata(plan.upload(), false));
    }

    public JobStatusResponse get(String ownerId, UUID jobId) { return database.get(ownerId, jobId); }

    public JobRetriedResponse retry(String ownerId, UUID key, UUID previousJobId) {
        var plan = database.prepareRetry(ownerId, key, previousJobId);
        if (plan.response() != null) return plan.response();
        return database.admitRetry(ownerId, key, previousJobId, plan.upload(), metadata(plan.upload(), true));
    }

    private GcsObjects.Metadata metadata(JobDbService.UploadSnapshot upload, boolean retry) {
        try { return objects.metadata(upload.bucket(), upload.objectKey(), upload.generation()); }
        catch (GcsAccessException exception) {
            throw new BusinessException(switch (exception.getReason()) {
                case OBJECT_NOT_FOUND, OBJECT_VERSION_CHANGED -> retry ? CAPTION_REUPLOAD_REQUIRED : UPLOAD_NOT_READY;
                case CAPTION_TOO_LARGE -> CAPTION_TOO_LARGE;
                case STORAGE_NOT_CONFIGURED, STORAGE_UNAVAILABLE -> STORAGE_UNAVAILABLE;
                case INVALID_OBJECT_METADATA -> UPLOAD_NOT_READY;
            });
        }
    }
}
