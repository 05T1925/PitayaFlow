package dev.pitayaflow.backend;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
final class UploadLimitAdvice {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,String>> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("code","MEDIA_TOO_LARGE"));
    }
}
