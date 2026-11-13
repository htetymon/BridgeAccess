package com.eduaccess.bridgeaccess.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.eduaccess.bridgeaccess.dto.PackageRequest;
import com.eduaccess.bridgeaccess.error.ApiErrorResponse.Detail;
import com.eduaccess.bridgeaccess.error.ApiException;
import com.eduaccess.bridgeaccess.error.ErrorCode;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks a {@code POST /packages} request against the contract (API doc, section 8). The
 * rules mirror the database check constraints on {@code learning_packages} and
 * {@code package_images}, so invalid input gets a 400 with field details, never a
 * constraint-violation 500.
 *
 * Order: field rules first (400, every failing field listed at once), then the image count
 * (422), then each image's size (413) and type (415).
 */
@Component
public class CreatePackageValidator {

	public static final int MAX_TITLE_CHARS = 300;
	public static final int MAX_AUTHOR_CHARS = 200;
	public static final int MAX_CONTENT_CHARS = 10_000;
	public static final int MAX_LINK_CHARS = 2_048;
	public static final int MAX_ALT_TEXT_CHARS = 300;
	public static final int MAX_IMAGES = 3;
	/** 500 KB, the {@code pi_size_cap} constraint. */
	public static final int MAX_IMAGE_BYTES = 512_000;

	static final Set<String> RESOURCE_TYPES = Set.of("text", "video");

	/** Same pattern as the {@code lp_video_is_youtube} constraint. */
	private static final Pattern YOUTUBE = Pattern.compile("^https?://([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be)/",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern HTTP = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE);

	/** A request that passed every rule. Strings are trimmed; blank optionals are null. */
	public record NewPackage(String resourceType, String originalTitle, String author, String resourceLink,
			String content, List<NewImage> images) {
	}

	/** {@code position} is 1 to 3, in upload order (0 is reserved for the AI diagram). */
	public record NewImage(int position, ImageType type, byte[] bytes, String altText) {
	}

	private final ObjectMapper objectMapper;

	public CreatePackageValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public NewPackage validate(String rawData, List<MultipartFile> files) {
		List<MultipartFile> images = nonEmpty(files);
		PackageRequest data = parse(rawData);
		List<Detail> errors = new ArrayList<>();

		String resourceType = trimToNull(data.resourceType());
		if (resourceType == null) {
			errors.add(required("resourceType"));
		}
		else if (!RESOURCE_TYPES.contains(resourceType)) {
			errors.add(new Detail("resourceType", "INVALID_VALUE", "Choose text or video."));
		}

		String title = text("originalTitle", data.originalTitle(), MAX_TITLE_CHARS, true, errors);
		String author = text("author", data.author(), MAX_AUTHOR_CHARS, true, errors);
		String link = link(data.resourceLink(), "video".equals(resourceType), errors);
		String content = text("content", data.content(), MAX_CONTENT_CHARS, false, errors);

		List<String> altTexts = data.imageAltTexts() == null ? List.of() : data.imageAltTexts();
		if (altTexts.size() > images.size()) {
			errors.add(new Detail("imageAltTexts", "TOO_MANY", "Add one alt text per image, at most."));
		}
		List<String> cleanAltTexts = new ArrayList<>();
		for (int i = 0; i < altTexts.size(); i++) {
			cleanAltTexts.add(text("imageAltTexts[" + i + "]", altTexts.get(i), MAX_ALT_TEXT_CHARS, false, errors));
		}

		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		if (images.size() > MAX_IMAGES) {
			throw new ApiException(ErrorCode.TOO_MANY_IMAGES);
		}

		List<NewImage> newImages = new ArrayList<>();
		for (int i = 0; i < images.size(); i++) {
			MultipartFile file = images.get(i);
			if (file.getSize() > MAX_IMAGE_BYTES) {
				throw new ApiException(ErrorCode.IMAGE_TOO_LARGE);
			}
			byte[] bytes = bytes(file);
			ImageType type = ImageType.detect(bytes)
				.orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE));
			String altText = i < cleanAltTexts.size() ? cleanAltTexts.get(i) : null;
			newImages.add(new NewImage(i + 1, type, bytes, altText));
		}

		return new NewPackage(resourceType, title, author, link, content, List.copyOf(newImages));
	}

	private PackageRequest parse(String rawData) {
		if (rawData == null || rawData.isBlank()) {
			throw ApiException.validation(List.of(required("data")));
		}
		try {
			PackageRequest data = this.objectMapper.readValue(rawData, PackageRequest.class);
			if (data == null) {
				throw ApiException.validation(List.of(required("data")));
			}
			return data;
		}
		catch (JacksonException ex) {
			throw ApiException.validation(
					List.of(new Detail("data", "INVALID_JSON", "The data part must be a JSON object.")));
		}
	}

	private static String link(String raw, boolean video, List<Detail> errors) {
		String link = trimToNull(raw);
		if (link == null) {
			errors.add(required("resourceLink"));
			return null;
		}
		if (link.length() > MAX_LINK_CHARS) {
			errors.add(new Detail("resourceLink", "TOO_LONG", "Use a link of at most " + MAX_LINK_CHARS
					+ " characters."));
			return null;
		}
		if (!HTTP.matcher(link).find() || !hasHost(link)) {
			errors.add(new Detail("resourceLink", "INVALID_URL", "Enter a link that starts with http:// or https://."));
			return null;
		}
		if (video && !YOUTUBE.matcher(link).find()) {
			errors.add(new Detail("resourceLink", "NOT_YOUTUBE", "Enter a YouTube link (youtube.com or youtu.be)."));
			return null;
		}
		return link;
	}

	private static boolean hasHost(String link) {
		try {
			String host = new URI(link).getHost();
			return host != null && !host.isBlank();
		}
		catch (URISyntaxException ex) {
			return false;
		}
	}

	/** Lengths count Unicode characters, as Postgres {@code char_length} does. */
	private static String text(String field, String raw, int max, boolean required, List<Detail> errors) {
		String value = trimToNull(raw);
		if (value == null) {
			if (required) {
				errors.add(required(field));
			}
			return null;
		}
		if (value.codePointCount(0, value.length()) > max) {
			errors.add(new Detail(field, "TOO_LONG", "Use at most " + String.format("%,d", max) + " characters."));
			return null;
		}
		return value;
	}

	/** Browsers send an empty part for an unused file input; it is not an image. */
	private static List<MultipartFile> nonEmpty(List<MultipartFile> files) {
		return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
	}

	private static byte[] bytes(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static Detail required(String field) {
		return new Detail(field, "REQUIRED", "This field is required.");
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
