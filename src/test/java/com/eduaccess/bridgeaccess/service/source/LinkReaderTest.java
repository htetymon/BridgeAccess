package com.eduaccess.bridgeaccess.service.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import com.eduaccess.bridgeaccess.config.LinkReaderConfig.LinkReaderProperties;
import com.eduaccess.bridgeaccess.service.source.UnreadableLinkException.Reason;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Runs the reader against a local server. Loopback is allowed for the server only; every
 * other check uses the production {@link AddressPolicy}.
 */
class LinkReaderTest {

	private static final String PARAGRAPH = "Renewable energy comes from sources that are naturally replenished, "
			+ "such as sunlight, wind, rain, tides and geothermal heat. ";

	private HttpServer server;

	private ExecutorService serverThreads;

	private String base;

	private LinkReader reader;

	private LinkReader strictReader;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.serverThreads = Executors.newCachedThreadPool();
		this.server.setExecutor(this.serverThreads);
		this.server.start();
		int port = this.server.getAddress().getPort();
		this.base = "http://127.0.0.1:" + port;
		LinkReaderProperties properties = new LinkReaderProperties(Duration.ofSeconds(2), Duration.ofSeconds(1),
				Duration.ofSeconds(3), 2, DataSize.ofKilobytes(64), 10_000, 50, List.of(80, 443, port), "test");
		this.reader = new LinkReader(properties, new AddressPolicy() {
			@Override
			public boolean isBlocked(InetAddress address) {
				return !address.getHostAddress().equals("127.0.0.1") && super.isBlocked(address);
			}
		});
		this.strictReader = new LinkReader(properties, new AddressPolicy());
	}

	@AfterEach
	void stop() throws IOException {
		this.reader.destroy();
		this.strictReader.destroy();
		this.server.stop(0);
		this.serverThreads.shutdownNow();
	}

	// ----------------------------------------------------------------- reading

	@Test
	void readsArticleTextWithoutPageFurniture() throws Exception {
		html("/article", """
				<html><head><title>T</title><style>p{color:red}</style><script>var secret = 1;</script></head>
				<body><nav>Home | About | Login</nav>
				<article><h1>Renewable Energy 101</h1><p>%s</p><p>Second   paragraph.</p>
				<ul><li>Solar</li><li>Wind</li></ul></article>
				<footer>Copyright</footer><form>Subscribe</form></body></html>""".formatted(PARAGRAPH));

		String text = this.reader.read(this.base + "/article");

		assertThat(text).isEqualTo("Renewable Energy 101\n" + PARAGRAPH.strip() + "\nSecond paragraph.\nSolar\nWind");
	}

	@Test
	void readsPlainText() throws Exception {
		respond("/notes.txt", 200, "text/plain; charset=utf-8", (PARAGRAPH + "\r\n\r\n" + PARAGRAPH).getBytes());

		assertThat(this.reader.read(this.base + "/notes.txt")).isEqualTo(PARAGRAPH.strip() + "\n" + PARAGRAPH.strip());
	}

	@Test
	void detectsCharsetFromMetaTag() throws Exception {
		String page = "<html><head><meta charset=\"iso-8859-1\"></head><body><p>Café culture. " + PARAGRAPH
				+ "</p></body></html>";
		respond("/latin1", 200, "text/html", page.getBytes(StandardCharsets.ISO_8859_1));

		assertThat(this.reader.read(this.base + "/latin1")).startsWith("Café culture.");
	}

	@Test
	void followsRelativeRedirects() throws Exception {
		redirect("/old", "/older");
		redirect("/older", "article");
		html("/article", "<p>" + PARAGRAPH + "</p>");

		assertThat(this.reader.read(this.base + "/old")).isEqualTo(PARAGRAPH.strip());
	}

	// ------------------------------------------------------------- refusals

	@Test
	void stopsAfterMaxRedirects() {
		redirect("/a", "/b");
		redirect("/b", "/c");
		redirect("/c", "/d");
		html("/d", "<p>" + PARAGRAPH + "</p>");

		assertReason(() -> this.reader.read(this.base + "/a"), Reason.TOO_MANY_REDIRECTS);
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://10.0.0.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/",
			"http://192.168.0.1/admin" })
	void refusesRedirectToInternalAddress(String target) {
		redirect("/go", target);

		assertReason(() -> this.reader.read(this.base + "/go"), Reason.BLOCKED_ADDRESS);
	}

	@ParameterizedTest
	@ValueSource(strings = { "file:///etc/passwd", "ftp://example.com/x", "gopher://example.com/" })
	void refusesRedirectToOtherSchemes(String target) {
		redirect("/go", target);

		assertReason(() -> this.reader.read(this.base + "/go"), Reason.INVALID_URL);
	}

	@Test
	void checksTheAddressItActuallyConnectsTo() throws Exception {
		// Simulates DNS rebinding: the host looks public on the first lookup and internal on
		// the next. The connection must still be refused.
		html("/article", "<p>" + PARAGRAPH + "</p>");
		AtomicInteger lookups = new AtomicInteger();
		LinkReader rebinding = new LinkReader(new LinkReaderProperties(Duration.ofSeconds(2), Duration.ofSeconds(1),
				Duration.ofSeconds(3), 2, DataSize.ofKilobytes(64), 10_000, 50,
				List.of(this.server.getAddress().getPort()), "test"), new AddressPolicy() {
					@Override
					public boolean isBlocked(InetAddress address) {
						return lookups.incrementAndGet() > 1;
					}
				});
		try {
			assertReason(() -> rebinding.read(this.base + "/article"), Reason.BLOCKED_ADDRESS);
			assertThat(lookups).hasValueGreaterThan(1);
		}
		finally {
			rebinding.destroy();
		}
	}

	@Test
	void productionPolicyBlocksLoopback() {
		html("/article", "<p>" + PARAGRAPH + "</p>");
		int port = this.server.getAddress().getPort();

		assertReason(() -> this.strictReader.read(this.base + "/article"), Reason.BLOCKED_ADDRESS);
		assertReason(() -> this.strictReader.read("http://localhost:" + port + "/article"), Reason.BLOCKED_ADDRESS);
		assertReason(() -> this.strictReader.read("http://[::1]:" + port + "/article"), Reason.BLOCKED_ADDRESS);
		assertReason(() -> this.strictReader.read("http://[::ffff:127.0.0.1]:" + port + "/article"),
				Reason.BLOCKED_ADDRESS);
	}

	@ParameterizedTest
	@ValueSource(strings = { "file:///etc/passwd", "ftp://example.com/x", "javascript:alert(1)", "//example.com/x",
			"http:///nohost", "http://user:secret@example.com/", "http://example.com:22/", "http://example.com:8080/",
			"not a url", "" })
	void refusesBadLinksWithoutConnecting(String link) {
		assertReason(() -> this.strictReader.read(link), Reason.INVALID_URL);
	}

	@Test
	void httpErrorIsUnreadable() {
		respond("/gone", 404, "text/html", ("<p>" + PARAGRAPH + "</p>").getBytes());

		assertReason(() -> this.reader.read(this.base + "/gone"), Reason.HTTP_STATUS);
	}

	@Test
	void nonTextContentIsUnreadable() {
		respond("/doc.pdf", 200, "application/pdf", "%PDF-1.7".getBytes());

		assertReason(() -> this.reader.read(this.base + "/doc.pdf"), Reason.UNSUPPORTED_CONTENT_TYPE);
	}

	@Test
	void javascriptOnlyPageIsUnreadable() {
		html("/app", "<html><body><div id=\"root\"></div><noscript>Please enable JavaScript to continue.</noscript>"
				+ "<script>render('" + PARAGRAPH + "')</script></body></html>");

		assertReason(() -> this.reader.read(this.base + "/app"), Reason.TOO_LITTLE_TEXT);
	}

	// ----------------------------------------------------------------- limits

	@Test
	void readsOnlyUpToMaxBytesOfAHugeBody() throws Exception {
		this.server.createContext("/huge", exchange -> {
			byte[] chunk = ("<p>" + PARAGRAPH + "</p>\n").getBytes();
			long total = 50L * 1024 * 1024;
			exchange.getResponseHeaders().set("Content-Type", "text/html");
			exchange.sendResponseHeaders(200, total);
			try (OutputStream out = exchange.getResponseBody()) {
				for (long sent = 0; sent + chunk.length <= total; sent += chunk.length) {
					out.write(chunk);
				}
			}
			catch (IOException expected) {
				// Client hung up after its cap.
			}
		});

		long start = System.nanoTime();
		String text = this.reader.read(this.base + "/huge");

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
		assertThat(text).startsWith(PARAGRAPH.strip()).hasSizeLessThanOrEqualTo(64 * 1024);
	}

	@Test
	void cutsOffAServerThatDripsBytes() {
		this.server.createContext("/drip", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/html");
			exchange.sendResponseHeaders(200, 0); // chunked
			try (OutputStream out = exchange.getResponseBody()) {
				while (true) {
					out.write('a');
					out.flush();
					Thread.sleep(100); // well inside the 1s read timeout
				}
			}
			catch (IOException | InterruptedException expected) {
				// Client cancelled.
			}
		});

		long start = System.nanoTime();
		assertReason(() -> this.reader.read(this.base + "/drip"), Reason.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void timesOutWaitingForHeaders() {
		this.server.createContext("/slow", exchange -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException expected) {
				// Server stopping.
			}
			exchange.close();
		});

		long start = System.nanoTime();
		assertReason(() -> this.reader.read(this.base + "/slow"), Reason.TIMEOUT);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
	}

	// ---------------------------------------------------------------- helpers

	private void html(String path, String body) {
		respond(path, 200, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private void redirect(String path, String location) {
		this.server.createContext(path, exchange -> {
			exchange.getResponseHeaders().set("Location", location);
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
	}

	private void respond(String path, int status, String contentType, byte[] body) {
		this.server.createContext(path, exchange -> send(exchange, status, contentType, body));
	}

	private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static void assertReason(ThrowingCallable call, Reason reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(UnreadableLinkException.class,
				ex -> assertThat(ex.reason()).as(ex.getMessage()).isEqualTo(reason));
	}

}
