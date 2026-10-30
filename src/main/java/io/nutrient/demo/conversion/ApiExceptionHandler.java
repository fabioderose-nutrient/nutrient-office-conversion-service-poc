package io.nutrient.demo.conversion;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Turns every error into a JSON body with an error code and a readable message. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final String maxFileSize;

    public ApiExceptionHandler(@Value("${spring.servlet.multipart.max-file-size}") String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        log.info("{} {}: {}", e.status().value(), e.code(), e.getMessage());
        return json(e.status(), e.body());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return json(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "The upload is too large. The limit is " + maxFileSize + " per file.");
    }

    @ExceptionHandler({MultipartException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> notMultipart(Exception e) {
        return json(HttpStatus.BAD_REQUEST, "invalid_request",
                "Send the files as multipart/form-data in the 'files' field.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected error", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "The conversion failed unexpectedly. See the service log.");
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, String code, String message) {
        return json(status, new ApiException(status, code, message, null).body());
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
