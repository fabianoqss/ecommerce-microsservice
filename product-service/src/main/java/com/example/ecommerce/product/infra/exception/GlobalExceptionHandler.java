package com.example.ecommerce.product.infra.exception;

import com.example.ecommerce.product.exception.PreconditionRequiredException;
import com.example.ecommerce.product.exception.ProductVersionConflictException;
import com.example.ecommerce.product.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI DEFAULT_TYPE = URI.create("about:blank");

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler({ProductVersionConflictException.class, OptimisticLockingFailureException.class})
    public ProblemDetail handleVersionConflict(RuntimeException ex, HttpServletRequest request) {
        String detail = ex instanceof ProductVersionConflictException
                ? ex.getMessage()
                : "O produto foi alterado por outra requisição; recarregue e tente novamente";
        return problem(HttpStatus.PRECONDITION_FAILED, "Precondition Failed", detail, request);
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    public ProblemDetail handlePreconditionRequired(PreconditionRequiredException ex, HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "Precondition Required", ex.getMessage(), request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(DEFAULT_TYPE);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> fields = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Erro de validação nos campos informados");
        problem.setType(DEFAULT_TYPE);
        problem.setTitle("Validation Failed");
        problem.setProperty("fields", fields);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }
}
