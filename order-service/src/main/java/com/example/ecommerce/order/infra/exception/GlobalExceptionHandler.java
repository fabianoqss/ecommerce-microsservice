package com.example.ecommerce.order.infra.exception;

import com.example.ecommerce.order.exception.IdempotencyKeyReuseException;
import com.example.ecommerce.order.exception.InsufficientStockException;
import com.example.ecommerce.order.exception.InvalidOrderStatusTransitionException;
import com.example.ecommerce.order.exception.InvalidProductDataException;
import com.example.ecommerce.order.exception.OrderInProgressException;
import com.example.ecommerce.order.exception.OrderNotFoundException;
import com.example.ecommerce.order.exception.OrderReservationException;
import com.example.ecommerce.order.exception.OrderTotalLimitExceededException;
import com.example.ecommerce.order.exception.ProductNotFoundException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI DEFAULT_TYPE = URI.create("about:blank");

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidOrderStatusTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidTransition(InvalidOrderStatusTransitionException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidProductDataException.class)
    public ResponseEntity<ProblemDetail> handleInvalidProductData(InvalidProductDataException ex) {
        return problem(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrentModification(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "O pedido foi alterado por outra operação; consulte e tente novamente");
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(OrderReservationException.class)
    public ResponseEntity<ProblemDetail> handleOrderReservationFailure(OrderReservationException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyReuse(IdempotencyKeyReuseException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler(OrderTotalLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleOrderTotalLimitExceeded(OrderTotalLimitExceededException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler(OrderInProgressException.class)
    public ResponseEntity<ProblemDetail> handleOrderInProgress(OrderInProgressException ex) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problemDetail.setProperty("orderId", ex.getOrderId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Payload inválido");
        problemDetail.setProperty("errors", errors);
        return new ResponseEntity<>(problemDetail, headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(err -> result.getMethodParameter().getParameterName() + ": " + err.getDefaultMessage()))
                .toList();
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Parâmetros inválidos");
        problemDetail.setProperty("errors", errors);
        return new ResponseEntity<>(problemDetail, headers, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, "Parâmetro inválido: " + mismatch.getName());
            return new ResponseEntity<>(problemDetail, headers, HttpStatus.BAD_REQUEST);
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ProblemDetail> handleFeign(FeignException ex) {
        if (ex.status() == HttpStatus.NOT_FOUND.value()) {
            return problem(HttpStatus.NOT_FOUND, "Recurso não encontrado em serviço dependente");
        }
        log.error("Falha ao comunicar com serviço dependente", ex);
        return problem(HttpStatus.BAD_GATEWAY, "Falha ao comunicar com serviço dependente");
    }

    @ExceptionHandler(OAuth2AuthorizationException.class)
    public ResponseEntity<ProblemDetail> handleServiceTokenFailure(OAuth2AuthorizationException ex) {
        log.error("Não foi possível obter o token de serviço no provedor de identidade: {}", ex.getError().getErrorCode());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Serviço de autenticação indisponível");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        log.error("Erro interno inesperado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado");
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail) {
            problemDetail.setType(DEFAULT_TYPE);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
