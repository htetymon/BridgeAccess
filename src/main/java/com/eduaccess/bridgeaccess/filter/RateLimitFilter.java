package com.eduaccess.bridgeaccess.filter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.eduaccess.bridgeaccess.config.RateLimitProperties;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse;
import com.eduaccess.bridgeaccess.error.ErrorCode;

import tools.jackson.databind.ObjectMapper;

/**
 * Rate limit on every API request (API doc section 1): {@code 429 RATE_LIMITED} with
 * {@code Retry-After} once a client goes over.
 *
 * <p>
 * Two token buckets per request, both must have a token:
 * <ul>
 * <li>per device ({@code X-Device-Id}), default 120 per minute: what one learner's app can
 * reasonably send, including sync and status polling;</li>
 * <li>per IP address, default 1,200 per minute: higher, because a classroom or a mobile
 * carrier puts many devices behind one address, but it still caps a client that sends a
 * random device ID with every request to dodge the first limit.</li>
 * </ul>
 * A bucket holds a minute's allowance and refills continuously, so a short burst (opening the
 * app, syncing) is fine while a sustained flood is not.
 *
 * <p>
 * State is in memory, per instance: enough for one server. With several instances behind a
 * load balancer, each enforces its own share; a shared store would be needed for an exact
 * global limit. The client address is {@code getRemoteAddr()}: behind a reverse proxy, set
 * {@code server.forward-headers-strategy=native} so it is the real client, not the proxy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	private static final String API_PREFIX = "/api/v1/";

	/** Buckets untouched this long are full again and are dropped, so memory stays bounded. */
	static final long IDLE_NANOS = 5L * 60 * 1_000_000_000L;

	private static final long SWEEP_EVERY_NANOS = 60L * 1_000_000_000L;

	private final RateLimitProperties properties;

	private final ObjectMapper objectMapper;

	private final LongSupplier clock;

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	private volatile long lastSweep;

	@Autowired
	public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, System::nanoTime);
	}

	RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper, LongSupplier clock) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.lastSweep = clock.getAsLong();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !this.properties.enabled() || !path.startsWith(API_PREFIX) || CorsUtils.isPreFlightRequest(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long now = this.clock.getAsLong();
		sweep(now);
		String device = canonicalDevice(request.getHeader(DeviceIdFilter.HEADER));
		// The IP bucket is charged first: a request that fails it costs its device nothing.
		long ipWait = take("ip:" + request.getRemoteAddr(), this.properties.perIpPerMinute(), now);
		long wait = ipWait;
		String scope = "ip";
		if (ipWait == 0 && device != null) {
			wait = take("device:" + device, this.properties.perDevicePerMinute(), now);
			scope = "device";
		}
		if (wait > 0) {
			reject(request, response, wait, scope);
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * Takes one token.
	 *
	 * @return 0 when allowed, otherwise the nanoseconds until a token is available
	 */
	private long take(String key, int perMinute, long now) {
		Bucket bucket = this.buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now));
		return bucket.take(perMinute, now);
	}

	private void reject(HttpServletRequest request, HttpServletResponse response, long waitNanos, String scope)
			throws IOException {
		long retryAfterSeconds = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
		// No address or device ID in the log: they identify a learner's phone.
		log.atWarn().addKeyValue("event", "rate_limited").addKeyValue("scope", scope).log("Rate limit exceeded");
		String requestId = RequestIdFilter.resolve(request);
		response.setStatus(ErrorCode.RATE_LIMITED.status().value());
		response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
		response.setHeader(RequestIdFilter.HEADER, requestId);
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		this.objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(ErrorCode.RATE_LIMITED, requestId));
	}

	/** Drops idle buckets about once a minute. */
	private void sweep(long now) {
		if (now - this.lastSweep < SWEEP_EVERY_NANOS) {
			return;
		}
		this.lastSweep = now;
		this.buckets.values().removeIf(b -> b.idleSince(now) > IDLE_NANOS);
	}

	/** Only a well-formed ID gets its own bucket; anything else is limited by IP alone. */
	private static String canonicalDevice(String header) {
		if (header == null) {
			return null;
		}
		try {
			UUID id = UUID.fromString(header.trim());
			return id.toString().equalsIgnoreCase(header.trim()) ? id.toString() : null;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	int bucketCount() {
		return this.buckets.size();
	}

	/** Token bucket: holds up to a minute's allowance, refilled continuously. */
	private static final class Bucket {

		private double tokens;

		private long last;

		Bucket(int perMinute, long now) {
			this.tokens = perMinute;
			this.last = now;
		}

		synchronized long take(int perMinute, long now) {
			double perNano = perMinute / 60e9;
			this.tokens = Math.min(perMinute, this.tokens + (now - this.last) * perNano);
			this.last = now;
			if (this.tokens >= 1) {
				this.tokens -= 1;
				return 0;
			}
			return (long) Math.ceil((1 - this.tokens) / perNano);
		}

		synchronized long idleSince(long now) {
			return now - this.last;
		}

	}

}
