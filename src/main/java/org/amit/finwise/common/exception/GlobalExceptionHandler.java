package org.amit.finwise.common.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.price.PriceDataProvider.PriceProviderException;
import org.amit.finwise.marketdata.client.AmfiClient.AmfiFetchException;
import org.amit.finwise.marketdata.client.NseApiClient.NseUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:finwise:validation-error"));
        pd.setTitle("Validation Failed");
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, fe -> {
                    String msg = fe.getDefaultMessage();
                    return msg != null ? msg : "invalid value";
                }, (a, b) -> a + "; " + b));
        pd.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:finwise:validation-error"));
        pd.setTitle("Constraint Violation");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setType(URI.create("urn:finwise:bad-request"));
        pd.setTitle("Malformed Request Body");
        pd.setDetail("Could not parse request body: " + ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest().body(pd);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setType(URI.create("urn:finwise:unauthorized"));
        pd.setTitle("Unauthorized");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setType(URI.create("urn:finwise:forbidden"));
        pd.setTitle("Forbidden");
        pd.setDetail("You do not have permission to access this resource.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(pd);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setType(URI.create("urn:finwise:not-found"));
        pd.setTitle("Resource Not Found");
        pd.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(PriceProviderException.class)
    public ResponseEntity<ProblemDetail> handlePriceProvider(PriceProviderException ex) {
        log.warn("All price providers failed: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        pd.setType(URI.create("urn:finwise:price-provider-error"));
        pd.setTitle("Price Provider Unavailable");
        pd.setDetail("Could not fetch price data from any provider. Please try again later.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(pd);
    }

    @ExceptionHandler(AmfiFetchException.class)
    public ResponseEntity<ProblemDetail> handleAmfi(AmfiFetchException ex) {
        log.warn("AMFI fetch error: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        pd.setType(URI.create("urn:finwise:amfi-error"));
        pd.setTitle("AMFI Data Unavailable");
        pd.setDetail("Could not fetch mutual fund data from AMFI.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(pd);
    }

    @ExceptionHandler(NseUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleNse(NseUnavailableException ex) {
        log.warn("NSE unavailable: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        pd.setType(URI.create("urn:finwise:nse-unavailable"));
        pd.setTitle("NSE Service Unavailable");
        pd.setDetail("NSE India API is currently unavailable. Please try again later.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex, WebRequest request) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(URI.create("urn:finwise:internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setDetail("An unexpected error occurred. Please contact support.");
        return ResponseEntity.internalServerError().body(pd);
    }
}
