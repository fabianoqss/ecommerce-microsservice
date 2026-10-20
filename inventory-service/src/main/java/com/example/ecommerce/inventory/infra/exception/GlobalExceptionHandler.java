package com.example.ecommerce.inventory.infra.exception;

import com.example.ecommerce.inventory.exception.InsufficientStockException;
import com.example.ecommerce.inventory.exception.InventoryNotFoundException;
import com.example.ecommerce.inventory.exception.ReservationConflictException;
import com.example.ecommerce.inventory.exception.ReservationGoneException;
import com.example.ecommerce.inventory.exception.ReservationNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI DEFAULT_TYPE = URI.create("about:blank");

    @ExceptionHandler({InventoryNotFoundException.class, ReservationNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Reuso de reservationId com itens diferentes do registrado originalmente => 422.
    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<ProblemDetail> handleReservationConflict(ReservationConflictException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    // Reserva já liberada ou lápide (tombstone) não ressuscita => 410 Gone.
    @ExceptionHandler(ReservationGoneException.class)
    public ResponseEntity<ProblemDetail> handleReservationGone(ReservationGoneException ex) {
        return problem(HttpStatus.GONE, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Payload ou parâmetro inválido");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Payload inválido");
        problemDetail.setType(DEFAULT_TYPE);
        problemDetail.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    // Relança exceções de segurança para que o filtro do Spring Security devolva 401/403.
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void handleSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        log.error("Erro interno inesperado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setType(DEFAULT_TYPE);
        return ResponseEntity.status(status).body(problemDetail);
    }
}
