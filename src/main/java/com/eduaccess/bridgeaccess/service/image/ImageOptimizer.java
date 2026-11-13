package com.eduaccess.bridgeaccess.service.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.eduaccess.bridgeaccess.config.ImageConfig.ImageProperties;
import com.eduaccess.bridgeaccess.service.ImageType;

/**
 * Resizes and re-encodes one image to about 80 KB or less (FR-6.5).
 *
 * <p>
 * Output is lossy WebP through libwebp ({@code webp-imageio}). If the native library cannot
 * load on this machine, the output is JPEG instead (transparent areas become white), and a
 * warning is logged once at startup. Callers store whatever {@link Optimised#type()} says.
 *
 * <p>
 * Steps: read only the header and refuse images with too many pixels; decode, subsampling
 * very large images while reading so memory stays small; shrink to {@code maxSide}; then
 * lower the quality, and if that is not enough the size, until the file fits the target.
 * Images are never enlarged. Everything happens in memory; nothing is written to disk.
 */
@Component
public class ImageOptimizer {

	private static final Logger log = LoggerFactory.getLogger(ImageOptimizer.class);

	/** Tried in order at each size; below 0.5 artefacts get worse faster than files shrink. */
	static final float[] QUALITIES = { 0.80f, 0.70f, 0.60f, 0.50f };

	/** Each further size step keeps this share of the longest side. */
	static final double SHRINK = 0.75;

	/** Database cap ({@code pi_size_cap}); a result above it is never returned. */
	static final int HARD_CAP_BYTES = 512_000;

	/** What became of the image. */
	public sealed interface Result {

	}

	/** Smaller, re-encoded image ready to store. */
	public record Optimised(byte[] bytes, ImageType type, int width, int height) implements Result {

		@Override
		public String toString() {
			return "Optimised[" + this.type + " " + this.width + "x" + this.height + ", " + this.bytes.length
					+ " bytes]";
		}

	}

	/**
	 * Could not be re-encoded here (no decoder for it, or it did not decode). The upload
	 * already passed the type and 500 KB checks, so it can still be served as it is.
	 */
	public record KeepOriginal(String reason) implements Result {

	}

	/** Must not be published, for example a decompression bomb. */
	public record Rejected(String reason) implements Result {

	}

	private final ImageProperties properties;

	private final boolean webp;

	@Autowired
	public ImageOptimizer(ImageProperties properties) {
		this(properties, webpWorks());
	}

	/** @param webp whether to encode WebP; false forces the JPEG fallback (tests) */
	ImageOptimizer(ImageProperties properties, boolean webp) {
		this.properties = properties;
		this.webp = webp;
	}

	/** The format new images are written in on this machine. */
	public ImageType outputType() {
		return this.webp ? ImageType.WEBP : ImageType.JPEG;
	}

	public Result optimize(byte[] input) {
		BufferedImage image;
		try {
			Decoded decoded = decode(input);
			if (decoded.rejected() != null) {
				return decoded.rejected();
			}
			image = decoded.image();
		}
		catch (IOException | RuntimeException | LinkageError ex) {
			return new KeepOriginal("could not decode: " + ex);
		}
		if (image == null) {
			return new KeepOriginal("no decoder for this image on this server");
		}

		BufferedImage flat = flatten(image);
		int longSide = Math.max(flat.getWidth(), flat.getHeight());
		int side = Math.min(longSide, this.properties.maxSide());
		long target = this.properties.targetSize().toBytes();
		Optimised smallest = null;
		try {
			while (true) {
				BufferedImage scaled = scale(flat, side);
				for (float quality : QUALITIES) {
					byte[] bytes = encode(scaled, quality);
					if (smallest == null || bytes.length < smallest.bytes().length) {
						smallest = new Optimised(bytes, outputType(), scaled.getWidth(), scaled.getHeight());
					}
					if (bytes.length <= target) {
						return smallest;
					}
				}
				int next = (int) (side * SHRINK);
				if (next < this.properties.minSide() || side <= this.properties.minSide()) {
					break;
				}
				side = next;
			}
		}
		catch (IOException | RuntimeException | LinkageError ex) {
			return new KeepOriginal("could not encode: " + ex);
		}
		// Did not reach the target even at the smallest size ("about" 80 KB); keep the best.
		if (smallest.bytes().length > HARD_CAP_BYTES) {
			return new KeepOriginal("could not get under " + HARD_CAP_BYTES + " bytes");
		}
		return smallest;
	}

	private record Decoded(BufferedImage image, Rejected rejected) {
	}

	private Decoded decode(byte[] input) throws IOException {
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext()) {
				return new Decoded(null, null);
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(in, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0) {
					return new Decoded(null, new Rejected("image has no size"));
				}
				if ((long) width * height > this.properties.maxPixels()) {
					return new Decoded(null, new Rejected(
							"image is " + width + "x" + height + ", over " + this.properties.maxPixels() + " pixels"));
				}
				ImageReadParam param = reader.getDefaultReadParam();
				// Read every n-th pixel when the image is far bigger than needed: a 6000 px photo
				// decodes at 2000 px, using a ninth of the memory, then is scaled down smoothly.
				int step = Math.max(1, Math.max(width, height) / (this.properties.maxSide() * 2));
				if (step > 1) {
					param.setSourceSubsampling(step, step, 0, 0);
				}
				return new Decoded(reader.read(0, param), null);
			}
			finally {
				reader.dispose();
			}
		}
	}

	/**
	 * A plain RGB (or ARGB for WebP with transparency) image the encoders accept. JPEG has no
	 * transparency, so transparent areas are painted white rather than black.
	 */
	private BufferedImage flatten(BufferedImage image) {
		boolean alpha = image.getColorModel().hasAlpha() && this.webp;
		int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
		if (image.getType() == type) {
			return image;
		}
		BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), type);
		Graphics2D g = out.createGraphics();
		try {
			if (!alpha) {
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, out.getWidth(), out.getHeight());
			}
			g.drawImage(image, 0, 0, null);
		}
		finally {
			g.dispose();
		}
		return out;
	}

	/**
	 * Scales so the longest side is {@code side}, halving step by step first: one big
	 * bilinear step skips pixels and looks jagged, halving keeps it smooth.
	 */
	static BufferedImage scale(BufferedImage image, int side) {
		int longSide = Math.max(image.getWidth(), image.getHeight());
		if (side >= longSide) {
			return image;
		}
		double ratio = (double) side / longSide;
		int targetW = Math.max(1, (int) Math.round(image.getWidth() * ratio));
		int targetH = Math.max(1, (int) Math.round(image.getHeight() * ratio));
		BufferedImage current = image;
		int w = image.getWidth();
		int h = image.getHeight();
		do {
			w = Math.max(targetW, w / 2);
			h = Math.max(targetH, h / 2);
			BufferedImage next = new BufferedImage(w, h, image.getType());
			Graphics2D g = next.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				g.drawImage(current, 0, 0, w, h, null);
			}
			finally {
				g.dispose();
			}
			current = next;
		}
		while (w != targetW || h != targetH);
		return current;
	}

	private byte[] encode(BufferedImage image, float quality) throws IOException {
		return encode(image, outputType(), quality);
	}

	static byte[] encode(BufferedImage image, ImageType type, float quality) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(type.mimeType());
		if (!writers.hasNext()) {
			throw new IOException("No ImageIO writer for " + type.mimeType());
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			String[] types = param.getCompressionTypes();
			if (types != null && types.length > 0) {
				// WebP offers "Lossy" and "Lossless"; JPEG has a single type.
				param.setCompressionType(
						Arrays.stream(types).filter("Lossy"::equalsIgnoreCase).findFirst().orElse(types[0]));
			}
			param.setCompressionQuality(quality);
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/**
	 * Encodes a tiny image once, so a missing or broken native library is found at startup
	 * rather than on a contributor's package.
	 */
	static boolean webpWorks() {
		try {
			byte[] probe = encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), ImageType.WEBP, 0.8f);
			if (probe.length > 0) {
				return true;
			}
			log.warn("WebP encoder produced no data; images will be stored as JPEG");
		}
		catch (IOException | RuntimeException | LinkageError ex) {
			log.warn("WebP encoder unavailable on this server ({}); images will be stored as JPEG", ex.toString());
		}
		return false;
	}

}
