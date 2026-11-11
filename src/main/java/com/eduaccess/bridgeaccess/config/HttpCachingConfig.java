package com.eduaccess.bridgeaccess.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * ETag / 304 support for GET and HEAD responses under {@code /api/v1}.
 * <p>
 * The filter hashes the response body, sets {@code ETag}, and answers {@code 304 Not Modified}
 * with no body when {@code If-None-Match} matches. Weak ETags are used because the body may be
 * gzipped afterwards by the server; a weak validator stays valid across content encodings.
 * <p>
 * Notes for endpoint authors:
 * <ul>
 * <li>Responses that already set an {@code ETag} keep it (for example stable per-image tags).</li>
 * <li>Streaming or large binary responses should call
 * {@link ShallowEtagHeaderFilter#disableContentCaching} so they are not buffered in memory.</li>
 * <li>{@code Cache-Control: no-store} opts a response out.</li>
 * </ul>
 */
@Configuration
public class HttpCachingConfig {

	@Bean
	FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter() {
		ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
		filter.setWriteWeakETag(true);
		FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns("/api/v1/*");
		registration.setName("shallowEtagHeaderFilter");
		// After RequestIdFilter and DeviceIdFilter, so rejected requests skip hashing.
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
		return registration;
	}

}
