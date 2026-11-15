package com.eduaccess.bridgeaccess.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One log line per API request, with structured fields: {@code http.method}, {@code url.path},
 * {@code http.status}, {@code duration_ms} (plus {@code requestId} from the MDC). With
 * structured logging on ({@code LOG_FORMAT=ecs}, see application.properties) they are JSON
 * fields a log tool can filter and chart; in plain text the message carries the same.
 *
 * <p>
 * Deliberately left out: the query string (it holds learners' search terms), the device ID
 * and the client address (both identify a learner's phone). 5xx responses log at WARN.
 * Health checks are not logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = path(request);
		return !path.startsWith("/api/") || path.equals("/api/v1/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			long durationMs = (System.nanoTime() - start) / 1_000_000;
			int status = response.getStatus();
			log.atLevel(status >= 500 ? Level.WARN : Level.INFO)
				.addKeyValue("http.method", request.getMethod())
				.addKeyValue("url.path", path(request))
				.addKeyValue("http.status", status)
				.addKeyValue("duration_ms", durationMs)
				.log("{} {} {} {} ms", request.getMethod(), path(request), status, durationMs);
		}
	}

	private static String path(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length());
	}

}
