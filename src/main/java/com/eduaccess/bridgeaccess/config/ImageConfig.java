package com.eduaccess.bridgeaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

@Configuration
@EnableConfigurationProperties(ImageConfig.ImageProperties.class)
public class ImageConfig {

	/**
	 * Image optimisation in the AI job (FR-6.5).
	 *
	 * @param targetSize aim for each image ("about 80 KB or less")
	 * @param maxSide longest side after resizing; never enlarged
	 * @param minSide the optimiser stops shrinking here even if the target is not met
	 * @param maxPixels images with more pixels are refused before decoding (decompression
	 * bomb guard: a 500 KB PNG can declare billions of pixels)
	 */
	@ConfigurationProperties("bridgeaccess.images")
	public record ImageProperties(@DefaultValue("80KB") DataSize targetSize, @DefaultValue("1280") int maxSide,
			@DefaultValue("320") int minSide, @DefaultValue("40000000") long maxPixels) {

		public ImageProperties {
			if (minSide < 1 || maxSide < minSide) {
				throw new IllegalArgumentException("bridgeaccess.images needs 1 <= min-side <= max-side");
			}
		}

	}

}
