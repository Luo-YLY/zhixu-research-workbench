package local.research.workbench.shared;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,String>> known(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String,String>> validation(MethodArgumentNotValidException e) {
        var field = e.getBindingResult().getFieldErrors().getFirst();
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_ERROR", "message", field.getField() + ": " + field.getDefaultMessage()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,String>> badInput(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "请求格式不正确"));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,String>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(Map.of("code","FILE_SIZE","message","上传文件须在 20 MB 以内"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> unexpected(Exception e) {
        LOG.error("Unhandled API failure", e);
        return ResponseEntity.internalServerError().body(Map.of("code", "INTERNAL_ERROR", "message", "操作失败，请查看本地服务日志"));
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String,String>> resourceNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(Map.of("code","NOT_FOUND","message","资源不存在"));
    }
}
