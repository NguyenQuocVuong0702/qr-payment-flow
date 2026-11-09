package vn.vnpay.qr.common.error;

import java.time.Clock;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Stable precedence if both fields are invalid; never echo rejected values.
        String field = java.util.List.of("amount", "description", "expireMinutes", "merchantId", "orderId")
                .stream().filter(name -> ex.getBindingResult().hasFieldErrors(name)).findFirst().orElse("request");
        return invalidField(field, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = ex.getCause();
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            String field = mapping.getPath().getFirst().getFieldName();
            if (field != null && java.util.Set.of("amount", "description", "expireMinutes").contains(field)) {
                return invalidField(field, headers, status);
            }
        }
        return new ResponseEntity<>(error("INVALID_REQUEST", "Request body must be a valid JSON object"),
                headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(error("INVALID_REQUEST", "HTTP request is not supported or is invalid"),
                headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpectedException(Exception ex) {
        // Do not log payloads or exception messages that could contain credentials later.
        LOG.error("Unhandled request failure: {}", ex.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("INTERNAL_ERROR", "An unexpected server error occurred"));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(error(ex.errorCode(), ex.getMessage()));
    }

    private ResponseEntity<Object> invalidField(String field, HttpHeaders headers, HttpStatusCode status) {
        ApiError body = switch (field) {
            case "amount" -> error("INVALID_AMOUNT", "amount must be an integer between 1000 and 500000000 VND");
            case "description" -> error("INVALID_DESCRIPTION", "description must contain 1-19 ASCII letters, digits or spaces and not be blank");
            case "expireMinutes" -> error("INVALID_EXPIRE_MINUTES", "expireMinutes must be an integer between 1 and 60");
            default -> error("INVALID_REQUEST", "Request fields are missing or invalid");
        };
        return new ResponseEntity<>(body, headers, status);
    }

    private ApiError error(String code, String message) {
        return new ApiError(code, message, OffsetDateTime.ofInstant(clock.instant(), BUSINESS_ZONE));
    }
}
