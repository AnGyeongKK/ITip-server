package com.gkk.itip.common.web;

import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.common.response.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        HttpStatus status = switch (exception.getErrorCode()) {
            case INVALID_REQUEST, INVALID_CURSOR -> HttpStatus.BAD_REQUEST;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IDEMPOTENCY_CONFLICT, STATE_CONFLICT, ANALYSIS_NOT_READY, UPLOAD_NOT_READY, JOB_NOT_RETRYABLE, CHAT_GENERATION_IN_PROGRESS, DUPLICATE_CHAT_REQUEST, CHAT_MESSAGE_NOT_RETRYABLE, SCOPE_UNAVAILABLE -> HttpStatus.CONFLICT;
            case CAPTION_REUPLOAD_REQUIRED -> HttpStatus.GONE;
            case CAPTION_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case STORAGE_UNAVAILABLE, CHAT_AI_NOT_CONFIGURED, CHAT_CAPACITY_EXCEEDED -> HttpStatus.SERVICE_UNAVAILABLE;
            case VALIDATION_FAILED, EMPTY_CHAT_SCOPE, INVALID_CHAT_MESSAGE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.fail(exception.getErrorCode(), exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode errorCode = switch (statusCode.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> ErrorCode.STATE_CONFLICT;
            case 422 -> ErrorCode.VALIDATION_FAILED;
            default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
        var jsonHeaders = new HttpHeaders();
        jsonHeaders.putAll(headers);
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(ApiResponse.fail(errorCode), jsonHeaders, statusCode);
    }
}
