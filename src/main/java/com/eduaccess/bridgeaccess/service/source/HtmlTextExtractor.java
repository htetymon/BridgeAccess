package com.eduaccess.bridgeaccess.service.source;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Turns an HTML page into its readable text: scripts, navigation, forms and other page
 * furniture are dropped, the main article is preferred when the page marks one, and block
 * elements become line breaks. Nothing is added that is not in the page.
 */
final class HtmlTextExtractor {

	private static final String NOISE = "script, style, noscript, template, svg, canvas, iframe, object, embed, "
			+ "form, button, select, nav, header, footer, aside, [hidden], [aria-hidden=true]";

	private HtmlTextExtractor() {
	}

	/**
	 * @param charset from the {@code Content-Type} header, or null to let jsoup detect it
	 * from the BOM or {@code <meta charset>} (UTF-8 otherwise)
	 * @param minChars a marked article or main region shorter than this is ignored in favour
	 * of the whole body
	 */
	static String extract(byte[] html, Charset charset, String baseUri, int minChars) {
		Document doc;
		try {
			doc = Jsoup.parse(new ByteArrayInputStream(html), charset == null ? null : charset.name(), baseUri);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex); // in-memory stream; does not happen
		}
		doc.select(NOISE).remove();
		Element root = mainRegion(doc, minChars);
		return root == null ? "" : TextNormalizer.normalize(blockText(root));
	}

	private static Element mainRegion(Document doc, int minChars) {
		for (String selector : new String[] { "article", "main", "[role=main]" }) {
			Element candidate = doc.selectFirst(selector);
			if (candidate != null && candidate.text().length() >= minChars) {
				return candidate;
			}
		}
		return doc.body();
	}

	private static String blockText(Element root) {
		StringBuilder out = new StringBuilder();
		NodeTraversor.traverse(new NodeVisitor() {

			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode text) {
					out.append(text.text());
				}
				else if (node instanceof Element element && (element.isBlock() || element.normalName().equals("br"))) {
					out.append('\n');
				}
			}

			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element element && element.isBlock()) {
					out.append('\n');
				}
			}

		}, root);
		return out.toString();
	}

}
