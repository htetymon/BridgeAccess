package com.eduaccess.bridgeaccess.service.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises a public YouTube video link and turns it into the canonical watch URL Gemini
 * accepts as a video source. Only a single video is recognised: playlists, channels and
 * other pages return {@code null} and are read as ordinary links.
 */
public final class YouTubeVideo {

	/** A video ID is 11 characters of base64url without padding. */
	private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");

	private static final Pattern PATH_ID = Pattern.compile("^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})/?$");

	private static final Pattern SHORT_PATH_ID = Pattern.compile("^/([A-Za-z0-9_-]{11})/?$");

	private static final Pattern QUERY_ID = Pattern.compile("(?:^|&)v=([^&]+)");

	private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
			"music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com");

	private static final String SHORT_HOST = "youtu.be";

	private YouTubeVideo() {
	}

	/**
	 * @return the canonical {@code https://www.youtube.com/watch?v=...} URL, or {@code null}
	 * when the link is not a single YouTube video
	 */
	public static String canonicalUrl(String link) {
		if (link == null || link.isBlank()) {
			return null;
		}
		URI uri = parse(link.strip());
		if (uri == null) {
			return null;
		}
		String host = host(uri);
		if (host == null) {
			return null;
		}
		String id = SHORT_HOST.equals(host) ? idFromShortLink(uri) : idFromYouTubeLink(uri);
		return id == null ? null : "https://www.youtube.com/watch?v=" + id;
	}

	private static URI parse(String link) {
		try {
			return new URI(link.contains("://") ? link : "https://" + link);
		}
		catch (URISyntaxException ex) {
			return null;
		}
	}

	private static String host(URI uri) {
		String host = uri.getHost();
		if (host == null) {
			return null;
		}
		host = host.toLowerCase(Locale.ROOT);
		return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
	}

	private static String idFromShortLink(URI uri) {
		Matcher matcher = SHORT_PATH_ID.matcher(uri.getPath() == null ? "" : uri.getPath());
		return matcher.matches() ? matcher.group(1) : null;
	}

	private static String idFromYouTubeLink(URI uri) {
		if (!YOUTUBE_HOSTS.contains(host(uri))) {
			return null;
		}
		String path = uri.getPath() == null ? "" : uri.getPath();
		Matcher pathMatcher = PATH_ID.matcher(path);
		if (pathMatcher.matches()) {
			return pathMatcher.group(1);
		}
		if ("/watch".equals(path) || "/watch/".equals(path)) {
			return idFromQuery(uri.getRawQuery());
		}
		return null;
	}

	private static String idFromQuery(String query) {
		if (query == null) {
			return null;
		}
		Matcher matcher = QUERY_ID.matcher(query);
		if (!matcher.find()) {
			return null;
		}
		String id = matcher.group(1);
		return ID.matcher(id).matches() ? id : null;
	}

}
