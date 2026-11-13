package com.eduaccess.bridgeaccess.service.source;

import java.util.regex.Pattern;

/** Whitespace and length clean-up shared by HTML and plain-text sources. */
final class TextNormalizer {

	private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|\\uFFFD");

	private static final Pattern SPACES = Pattern.compile("[\\t\\x0B\\f\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000 ]+");

	private TextNormalizer() {
	}

	/** Collapses runs of spaces, trims every line and drops empty lines. */
	static String normalize(String text) {
		String cleaned = CONTROL.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
		StringBuilder out = new StringBuilder(cleaned.length());
		for (String line : cleaned.split("\n")) {
			String trimmed = SPACES.matcher(line).replaceAll(" ").strip();
			if (!trimmed.isEmpty()) {
				if (!out.isEmpty()) {
					out.append('\n');
				}
				out.append(trimmed);
			}
		}
		return out.toString();
	}

	/** Cuts to at most {@code maxChars}, at a word boundary when one is near the end. */
	static String truncate(String text, int maxChars) {
		if (text.length() <= maxChars) {
			return text;
		}
		int end = maxChars;
		if (Character.isLowSurrogate(text.charAt(end))) {
			end--; // do not split a surrogate pair
		}
		int space = Math.max(text.lastIndexOf(' ', end), text.lastIndexOf('\n', end));
		if (space > maxChars * 0.9) {
			end = space;
		}
		return text.substring(0, end).strip();
	}

}
