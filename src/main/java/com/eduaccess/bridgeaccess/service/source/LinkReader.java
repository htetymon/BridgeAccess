package com.eduaccess.bridgeaccess.service.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.LinkReaderConfig.LinkReaderProperties;
import com.eduaccess.bridgeaccess.service.source.GuardedDnsResolver.BlockedAddressException;
import com.eduaccess.bridgeaccess.service.source.UnreadableLinkException.Reason;

/**
 * Fetches a contributor's link and returns its readable text, safely (SSRF protection):
 * <ul>
 * <li>only {@code http} and {@code https}, no credentials in the URL, only the allowed
 * ports;</li>
 * <li>every connection goes through {@link GuardedDnsResolver}, so private, local and
 * metadata addresses are refused, including through DNS rebinding;</li>
 * <li>redirects are followed by hand, at most {@code maxRedirects}, each target checked like
 * the original link;</li>
 * <li>no proxy, cookies or authentication; capped header sizes, body bytes and extracted
 * characters;</li>
 * <li>a wall-clock deadline cancels the request wherever it is, so a server dripping bytes
 * cannot hold a worker.</li>
 * </ul>
 * Returns only text found in the page; it never falls back to the title or other metadata.
 */
@Component
public class LinkReader implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(LinkReader.class);

	private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

	private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

	private final LinkReaderProperties properties;

	private final GuardedDnsResolver dns;

	private final CloseableHttpClient client;

	private final ScheduledExecutorService deadlines;

	public LinkReader(LinkReaderProperties properties, AddressPolicy addressPolicy) {
		this.properties = properties;
		this.dns = new GuardedDnsResolver(addressPolicy);
		this.client = HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(this.dns)
				.setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
					.http1Config(Http1Config.custom().setMaxLineLength(8 * 1024).setMaxHeaderCount(100).build())
					.build())
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.of(properties.connectTimeout()))
					.setSocketTimeout(Timeout.of(properties.readTimeout()))
					.build())
				.setMaxConnTotal(20)
				.setMaxConnPerRoute(2)
				.build())
			.disableRedirectHandling()
			.disableCookieManagement()
			.disableAuthCaching()
			.disableAutomaticRetries()
			.setUserAgent(properties.userAgent())
			.build();
		this.deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "link-reader-deadline");
			thread.setDaemon(true);
			return thread;
		});
	}

	public String read(String link) throws UnreadableLinkException {
		long deadline = System.nanoTime() + this.properties.totalTimeout().toNanos();
		URI uri = parse(link);
		for (int redirects = 0;; redirects++) {
			checkUri(uri);
			checkAddress(uri);
			HttpGet get = new HttpGet(uri);
			get.setHeader(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,text/plain;q=0.8");
			get.setConfig(RequestConfig.custom()
				.setResponseTimeout(Timeout.ofMilliseconds(
						Math.min(this.properties.readTimeout().toMillis(), remainingMillis(deadline))))
				.build());
			ScheduledFuture<?> cutoff = this.deadlines.schedule(get::cancel, remainingMillis(deadline),
					TimeUnit.MILLISECONDS);
			ClassicHttpResponse response = null;
			try {
				response = this.client.executeOpen(RoutingSupport.determineHost(get), get, null);
				int status = response.getCode();
				if (REDIRECTS.contains(status)) {
					uri = redirectTarget(uri, response, redirects);
					continue;
				}
				if (status < 200 || status >= 300) {
					throw new UnreadableLinkException(Reason.HTTP_STATUS, status + " from " + uri.getHost());
				}
				return text(uri, response.getEntity(), deadline);
			}
			catch (BlockedAddressException ex) {
				throw new UnreadableLinkException(Reason.BLOCKED_ADDRESS, ex.getMessage(), ex);
			}
			catch (IOException ex) {
				if (get.isCancelled() || ex instanceof InterruptedIOException || System.nanoTime() >= deadline) {
					throw new UnreadableLinkException(Reason.TIMEOUT, "reading " + uri.getHost(), ex);
				}
				throw new UnreadableLinkException(Reason.NETWORK_ERROR, uri.getHost() + ": " + ex, ex);
			}
			catch (HttpException ex) {
				throw new UnreadableLinkException(Reason.INVALID_URL, ex.getMessage(), ex);
			}
			finally {
				cutoff.cancel(false);
				// Cancelling closes the socket instead of draining a large unread body.
				get.cancel();
				closeQuietly(response);
			}
		}
	}

	private static URI parse(String link) throws UnreadableLinkException {
		try {
			return new URI(link == null ? "" : link.strip());
		}
		catch (URISyntaxException ex) {
			throw new UnreadableLinkException(Reason.INVALID_URL, ex.getMessage(), ex);
		}
	}

	private void checkUri(URI uri) throws UnreadableLinkException {
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			throw new UnreadableLinkException(Reason.INVALID_URL, "scheme not allowed: " + scheme);
		}
		if (uri.getHost() == null || uri.getHost().isEmpty()) {
			throw new UnreadableLinkException(Reason.INVALID_URL, "no host: " + uri);
		}
		if (uri.getRawUserInfo() != null) {
			throw new UnreadableLinkException(Reason.INVALID_URL, "credentials in URL");
		}
		if (uri.getPort() != -1 && !this.properties.allowedPorts().contains(uri.getPort())) {
			throw new UnreadableLinkException(Reason.INVALID_URL, "port not allowed: " + uri.getPort());
		}
	}

	/**
	 * Fails early with a clear reason. The client resolves again through the same guard when
	 * it connects, which is what actually protects the connection.
	 */
	private void checkAddress(URI uri) throws UnreadableLinkException {
		try {
			this.dns.resolve(uri.getHost());
		}
		catch (BlockedAddressException ex) {
			throw new UnreadableLinkException(Reason.BLOCKED_ADDRESS, ex.getMessage(), ex);
		}
		catch (UnknownHostException ex) {
			throw new UnreadableLinkException(Reason.NETWORK_ERROR, "unknown host " + uri.getHost(), ex);
		}
	}

	private URI redirectTarget(URI from, ClassicHttpResponse response, int redirects) throws UnreadableLinkException {
		if (redirects >= this.properties.maxRedirects()) {
			throw new UnreadableLinkException(Reason.TOO_MANY_REDIRECTS, "more than "
					+ this.properties.maxRedirects() + " redirects from " + from.getHost());
		}
		Header location = response.getFirstHeader(HttpHeaders.LOCATION);
		if (location == null || location.getValue().isBlank()) {
			throw new UnreadableLinkException(Reason.HTTP_STATUS, response.getCode() + " without Location");
		}
		try {
			return from.resolve(new URI(location.getValue().strip()));
		}
		catch (URISyntaxException | IllegalArgumentException ex) {
			throw new UnreadableLinkException(Reason.INVALID_URL, "bad redirect: " + ex.getMessage(), ex);
		}
	}

	private String text(URI uri, HttpEntity entity, long deadline) throws IOException, UnreadableLinkException {
		if (entity == null) {
			throw new UnreadableLinkException(Reason.TOO_LITTLE_TEXT, "empty response from " + uri.getHost());
		}
		ContentType type = ContentType.parseLenient(entity.getContentType());
		String mime = type == null ? null : type.getMimeType().toLowerCase(Locale.ROOT);
		boolean html = mime == null || HTML_TYPES.contains(mime);
		if (!html && !"text/plain".equals(mime)) {
			throw new UnreadableLinkException(Reason.UNSUPPORTED_CONTENT_TYPE, mime + " from " + uri.getHost());
		}
		byte[] body = readCapped(entity, deadline);
		Charset charset = type == null ? null : type.getCharset();
		String text = html ? HtmlTextExtractor.extract(body, charset, uri.toString(), this.properties.minChars())
				: TextNormalizer.normalize(new String(body, charset != null ? charset : StandardCharsets.UTF_8));
		text = TextNormalizer.truncate(text, this.properties.maxChars());
		if (text.length() < this.properties.minChars()) {
			throw new UnreadableLinkException(Reason.TOO_LITTLE_TEXT,
					text.length() + " characters of text from " + uri.getHost());
		}
		log.debug("Read {} characters from {}", text.length(), uri.getHost());
		return text;
	}

	private byte[] readCapped(HttpEntity entity, long deadline) throws IOException {
		int max = (int) Math.min(Integer.MAX_VALUE - 8, this.properties.maxBytes().toBytes());
		byte[] buffer = new byte[Math.min(max, 64 * 1024)];
		int length = 0;
		// Not closed here: closing would read the rest of an oversized body to the end. The
		// caller cancels the request, which closes the socket.
		InputStream in = entity.getContent();
		while (length < max) {
			if (System.nanoTime() >= deadline) {
				throw new InterruptedIOException("total timeout");
			}
			if (length == buffer.length) {
				buffer = Arrays.copyOf(buffer, Math.min(max, buffer.length * 2));
			}
			int n = in.read(buffer, length, buffer.length - length);
			if (n < 0) {
				break;
			}
			length += n;
		}
		return Arrays.copyOf(buffer, length);
	}

	private static long remainingMillis(long deadline) throws UnreadableLinkException {
		long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
		if (remaining <= 0) {
			throw new UnreadableLinkException(Reason.TIMEOUT, "total timeout reached");
		}
		return remaining;
	}

	private static void closeQuietly(ClassicHttpResponse response) {
		if (response != null) {
			try {
				response.close();
			}
			catch (IOException | RuntimeException ignored) {
				// Connection already cancelled.
			}
		}
	}

	@Override
	public void destroy() throws IOException {
		this.deadlines.shutdownNow();
		this.client.close();
	}

}
