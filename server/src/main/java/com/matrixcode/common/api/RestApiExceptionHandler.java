package com.matrixcode.common.api;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class RestApiExceptionHandler {

    /**
     * 保留控制器主动声明的 HTTP 状态码。
     *
     * <p>部分业务接口会用 {@link ResponseStatusException} 表达 409、404 等明确语义。
     * 该处理器必须优先于通用业务异常处理，否则异常 cause 可能被匹配为 400，导致前端无法区分冲突和参数错误。</p>
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        var message = exception.getReason() == null || exception.getReason().isBlank()
                ? exception.getMessage()
                : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(message));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBusinessError(RuntimeException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage() {
        return ResponseEntity.badRequest().body(new ErrorResponse("请求内容格式不正确"));
    }

    public record ErrorResponse(String message) {
    }
}
