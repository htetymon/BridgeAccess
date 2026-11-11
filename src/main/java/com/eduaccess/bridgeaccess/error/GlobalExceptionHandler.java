package com.eduaccess.bridgeaccess.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.eduaccess.bridgeaccess.filter.RequestIdFilter;

/**
 * Turns every exception raised by a controller into the standard {@link ApiErrorResponse}.
 * Framework exceptions (bad JSON, unknown route, wrong method, ...) are routed through
 * {@link #handleExceptionInternal} so they get the same envelope.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
		ErrorCode code = ex.getCode();
		if (code.status().is5xxServerError()) {
			log.error("API error {}", code, ex);
		}
		return body(code, ex.getMessage(), ex.getDetails(), request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
		List<ApiErrorResponse.Detail> details = ex.getConstraintViolations()
			.stream()
			.map(v -> new ApiErrorResponse.Detail(leafName(v.getPropertyPath().toString()),
					toCode(v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()),
					v.getMessage()))
			.toList();
		return body(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), details, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception", ex);
		return body(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiErrorResponse.Detail> details = new ArrayList<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			details.add(new ApiErrorResponse.Detail(error.getField(), toCode(error.getCode()),
					error.getDefaultMessage()));
		}
		ex.getBindingResult()
			.getGlobalErrors()
			.forEach(error -> details
				.add(new ApiErrorResponse.Detail(null, toCode(error.getCode()), error.getDefaultMessage())));
		return handleExceptionInternal(ex, validationBody(details, request), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiErrorResponse.Detail> details = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String field = result.getMethodParameter().getParameterName();
			for (MessageSourceResolvable error : result.getResolvableErrors()) {
				String[] codes = error.getCodes();
				String code = (codes != null && codes.length > 0) ? codes[codes.length - 1] : null;
				details.add(new ApiErrorResponse.Detail(field, toCode(code), error.getDefaultMessage()));
			}
		});
		return handleExceptionInternal(ex, validationBody(details, request), headers, status, request);
	}

	/**
	 * Every framework-handled exception ends up here; replace Spring's ProblemDetail body
	 * with our envelope.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (!(body instanceof ApiErrorResponse)) {
			ErrorCode code = ErrorCode.fromStatus(statusCode.value());
			body = ApiErrorResponse.of(code, code.defaultMessage(), detailsFor(ex), requestId(request));
		}
		if (statusCode.is5xxServerError()) {
			log.error("Request failed with {}", statusCode, ex);
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	private List<ApiErrorResponse.Detail> detailsFor(Exception ex) {
		if (ex instanceof MissingServletRequestParameterException e) {
			return List.of(new ApiErrorResponse.Detail(e.getParameterName(), "REQUIRED", "This value is required."));
		}
		if (ex instanceof MissingRequestHeaderException e) {
			return List.of(new ApiErrorResponse.Detail(e.getHeaderName(), "REQUIRED", "This header is required."));
		}
		if (ex instanceof TypeMismatchException e && e.getPropertyName() != null) {
			return List.of(new ApiErrorResponse.Detail(e.getPropertyName(), "INVALID_FORMAT",
					"This value has the wrong format."));
		}
		return null;
	}

	private ApiErrorResponse validationBody(List<ApiErrorResponse.Detail> details, WebRequest request) {
		return ApiErrorResponse.of(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
				details, requestId(request));
	}

	private ResponseEntity<Object> body(ErrorCode code, String message, List<ApiErrorResponse.Detail> details,
			WebRequest request) {
		return ResponseEntity.status(code.status())
			.body(ApiErrorResponse.of(code, message, details, requestId(request)));
	}

	private static String requestId(WebRequest request) {
		if (request instanceof NativeWebRequest nativeRequest) {
			HttpServletRequest servletRequest = nativeRequest.getNativeRequest(HttpServletRequest.class);
			if (servletRequest != null) {
				return RequestIdFilter.resolve(servletRequest);
			}
		}
		return null;
	}

	private static String leafName(String path) {
		int dot = path.lastIndexOf('.');
		return dot >= 0 ? path.substring(dot + 1) : path;
	}

	/** {@code NotBlank} becomes {@code NOT_BLANK}. */
	static String toCode(String constraint) {
		if (constraint == null || constraint.isBlank()) {
			return "INVALID";
		}
		return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}

}
