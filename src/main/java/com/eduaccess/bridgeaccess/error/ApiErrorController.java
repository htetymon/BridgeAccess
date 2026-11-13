package com.eduaccess.bridgeaccess.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.eduaccess.bridgeaccess.filter.RequestIdFilter;

/**
 * Replaces Spring Boot's whitelabel/JSON {@code /error} endpoint so that errors raised
 * outside Spring MVC (in a filter, or by the servlet container) use the same envelope.
 * Plain {@code @Controller} on purpose: {@code WebConfig} adds the {@code /api/v1} prefix
 * only to {@code @RestController}s.
 */
@Controller
public class ApiErrorController implements ErrorController {

	@RequestMapping("${server.error.path:${error.path:/error}}")
	@ResponseBody
	public ResponseEntity<ApiErrorResponse> error(HttpServletRequest request) {
		if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof ApiException ex) {
			return ResponseEntity.status(ex.getCode().status())
				.body(ApiErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getDetails(),
						RequestIdFilter.resolve(request), ex.getExtras()));
		}
		int status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code) ? code
				: 500;
		ErrorCode code = ErrorCode.fromStatus(status);
		return ResponseEntity.status(status).body(ApiErrorResponse.of(code, RequestIdFilter.resolve(request)));
	}

}
