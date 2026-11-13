package com.eduaccess.bridgeaccess.service.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.eduaccess.bridgeaccess.config.ImageConfig.ImageProperties;
import com.eduaccess.bridgeaccess.service.ImageType;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.KeepOriginal;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Optimised;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Rejected;
import com.eduaccess.bridgeaccess.service.image.ImageOptimizer.Result;

class ImageOptimizerTest {

	private static final ImageProperties DEFAULTS = new ImageProperties(DataSize.ofKilobytes(80), 1280, 320,
			40_000_000);

	private static final long TARGET = DataSize.ofKilobytes(80).toBytes();

	private final ImageOptimizer webp = new ImageOptimizer(DEFAULTS, true);

	private final ImageOptimizer jpeg = new ImageOptimizer(DEFAULTS, false);

	@Test
	void webpEncoderLoadsOnThisMachine() {
		// Natives ship for Windows, macOS and glibc Linux; if this fails, production would
		// silently fall back to JPEG, so it is worth knowing.
		assertThat(ImageOptimizer.webpWorks()).isTrue();
	}

	@Test
	void shrinksALargeDetailedPhotoToWebpUnderTarget() throws IOException {
		byte[] input = encode(photo(3000, 2000), "png");

		Optimised out = optimised(this.webp.optimize(input));

		assertThat(out.type()).isEqualTo(ImageType.WEBP);
		assertThat(out.bytes().length).isLessThanOrEqualTo((int) TARGET);
		assertThat(Math.max(out.width(), out.height())).isLessThanOrEqualTo(1280);
		assertThat((double) out.width() / out.height()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
		assertThat(ImageType.detect(out.bytes())).contains(ImageType.WEBP);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
		assertThat(decoded.getWidth()).isEqualTo(out.width());
	}

	@Test
	void jpegFallbackAlsoFitsAndPaintsTransparencyWhite() throws IOException {
		BufferedImage transparent = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = transparent.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(200, 150, 400, 300);
		g.dispose();

		Optimised out = optimised(this.jpeg.optimize(encode(transparent, "png")));

		assertThat(out.type()).isEqualTo(ImageType.JPEG);
		assertThat(out.bytes().length).isLessThanOrEqualTo((int) TARGET);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
		assertThat(new Color(decoded.getRGB(5, 5))).satisfies(c -> assertThat(c.getRed() + c.getGreen() + c.getBlue())
			.as("corner should be white, not black").isGreaterThan(700));
	}

	@Test
	void keepsTransparencyInWebp() throws IOException {
		BufferedImage transparent = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = transparent.createGraphics();
		g.setColor(Color.BLUE);
		g.fillRect(100, 100, 200, 100);
		g.dispose();

		Optimised out = optimised(this.webp.optimize(encode(transparent, "png")));

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(out.bytes()));
		assertThat(decoded.getColorModel().hasAlpha()).isTrue();
		assertThat(decoded.getRGB(5, 5) >>> 24).as("corner alpha").isLessThan(16);
	}

	@Test
	void neverEnlargesSmallImages() throws IOException {
		Optimised out = optimised(this.webp.optimize(encode(photo(200, 100), "jpg")));

		assertThat(out.width()).isEqualTo(200);
		assertThat(out.height()).isEqualTo(100);
	}

	@Test
	void readsWebpInput() throws IOException {
		byte[] webpInput = ImageOptimizer.encode(photo(1600, 1200), ImageType.WEBP, 0.95f);

		Optimised out = optimised(this.webp.optimize(webpInput));

		// The noisy test photo needs a smaller size than a real photo to fit; only the shape is fixed.
		assertThat(out.width()).isLessThanOrEqualTo(1280);
		assertThat(out.width() * 3).isEqualTo(out.height() * 4);
		assertThat(out.bytes().length).isLessThanOrEqualTo((int) TARGET);
	}

	@Test
	void shrinksFurtherWhenQualityAloneIsNotEnough() throws IOException {
		ImageOptimizer tight = new ImageOptimizer(new ImageProperties(DataSize.ofKilobytes(15), 1280, 320, 40_000_000),
				true);

		Optimised out = optimised(tight.optimize(encode(photo(2000, 2000), "png")));

		assertThat(out.width()).isLessThan(1280);
	}

	@Test
	void refusesImagesWithTooManyPixelsBeforeDecoding() throws IOException {
		ImageOptimizer small = new ImageOptimizer(new ImageProperties(DataSize.ofKilobytes(80), 1280, 320, 10_000),
				true);

		Result result = small.optimize(encode(new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB), "png"));

		assertThat(result).isInstanceOf(Rejected.class);
		assertThat(((Rejected) result).reason()).contains("200x100");
	}

	@Test
	void keepsTheOriginalWhenItCannotBeDecoded() {
		byte[] truncatedPng = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R' };

		assertThat(this.webp.optimize(truncatedPng)).isInstanceOf(KeepOriginal.class);
		assertThat(this.webp.optimize(new byte[] { 1, 2, 3 })).isInstanceOf(KeepOriginal.class);
	}

	@Test
	void scaleKeepsAspectRatioAndExactTarget() {
		BufferedImage scaled = ImageOptimizer.scale(new BufferedImage(4000, 1000, BufferedImage.TYPE_INT_RGB), 1280);

		assertThat(scaled.getWidth()).isEqualTo(1280);
		assertThat(scaled.getHeight()).isEqualTo(320);
	}

	private static Optimised optimised(Result result) {
		assertThat(result).isInstanceOf(Optimised.class);
		return (Optimised) result;
	}

	/** Gradients plus noise: compresses like a real photo, unlike a flat test image. */
	static BufferedImage photo(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Random random = new Random(42);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int r = (x * 255 / width + random.nextInt(40)) & 0xFF;
				int g = (y * 255 / height + random.nextInt(40)) & 0xFF;
				int b = ((x + y) * 128 / (width + height) + random.nextInt(40)) & 0xFF;
				image.setRGB(x, y, (r << 16) | (g << 8) | b);
			}
		}
		return image;
	}

	static byte[] encode(BufferedImage image, String format) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

}
