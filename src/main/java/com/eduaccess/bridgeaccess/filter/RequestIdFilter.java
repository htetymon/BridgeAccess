package com.eduaccess.bridgeaccess.filter;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request an ID: the client's {@code X-Request-Id} when it is a valid UUID,
 * otherwise a fresh one. The ID is echoed in the response, exposed as a request attribute
 * (used by error responses) and put in the logging MDC as {@code requestId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
	public static final String MDC_KEY = "requestId";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolve(request);
		response.setHeader(HEADER, requestId);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		// Error dispatches reuse the attribute set on the original request.
		return true;
	}

	/**
	 * Returns this request's ID, assigning one if this filter has not run yet (for example
	 * in a standalone MockMvc test).
	 */
	public static String resolve(ServletRequest request) {
		Object existing = request.getAttribute(REQUEST_ATTRIBUTE);
		if (existing instanceof String id) {
			return id;
		}
		String id = fromHeader(request instanceof HttpServletRequest http ? http.getHeader(HEADER) : null);
		request.setAttribute(REQUEST_ATTRIBUTE, id);
		return id;
	}

	private static String fromHeader(String header) {
		if (header != null) {
			try {
				return UUID.fromString(header.trim()).toString();
			}
			catch (IllegalArgumentException ignored) {
				// Malformed client value: fall through to a generated ID.
			}
		}
		return UUID.randomUUID().toString();
	}

}
