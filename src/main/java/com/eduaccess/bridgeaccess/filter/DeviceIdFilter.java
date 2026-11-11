package com.eduaccess.bridgeaccess.filter;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.eduaccess.bridgeaccess.error.ApiErrorResponse;
import com.eduaccess.bridgeaccess.error.ErrorCode;

import tools.jackson.databind.ObjectMapper;

/**
 * Requires a valid anonymous device UUID on every API request except the health check
 * (FR-1.3). The parsed value is exposed as a request attribute for controllers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DeviceIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Device-Id";
	public static final String REQUEST_ATTRIBUTE = "deviceId";

	private static final String API_PREFIX = "/api/v1";
	private static final String HEALTH_PATH = "/api/v1/health";

	private final ObjectMapper objectMapper;

	public DeviceIdFilter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String path = pathWithinApplication(request);
		if (!isApiRequest(path) || CorsUtils.isPreFlightRequest(request) || HEALTH_PATH.equals(path)) {
			filterChain.doFilter(request, response);
			return;
		}

		UUID deviceId = parseUuid(request.getHeader(HEADER));
		if (deviceId == null) {
			writeInvalidDeviceId(request, response);
			return;
		}

		request.setAttribute(REQUEST_ATTRIBUTE, deviceId);
		filterChain.doFilter(request, response);
	}

	private boolean isApiRequest(String path) {
		return path.equals(API_PREFIX) || path.startsWith(API_PREFIX + "/");
	}

	private String pathWithinApplication(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		return contextPath.isEmpty() ? uri : uri.substring(contextPath.length());
	}

	private UUID parseUuid(String value) {
		if (value == null) {
			return null;
		}
		String candidate = value.trim();
		if (candidate.isEmpty()) {
			return null;
		}
		try {
			UUID uuid = UUID.fromString(candidate);
			// UUID.fromString also accepts shortened groups; require the canonical form.
			return uuid.toString().equalsIgnoreCase(candidate) ? uuid : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private void writeInvalidDeviceId(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String requestId = RequestIdFilter.resolve(request);
		ApiErrorResponse body = ApiErrorResponse.of(ErrorCode.INVALID_DEVICE_ID, requestId);

		response.setStatus(ErrorCode.INVALID_DEVICE_ID.status().value());
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		response.setHeader(RequestIdFilter.HEADER, requestId);
		objectMapper.writeValue(response.getOutputStream(), body);
	}

}
