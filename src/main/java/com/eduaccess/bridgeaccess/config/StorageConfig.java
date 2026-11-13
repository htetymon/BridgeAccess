package com.eduaccess.bridgeaccess.config;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.eduaccess.bridgeaccess.storage.ImageStore;
import com.eduaccess.bridgeaccess.storage.LocalImageStore;
import com.eduaccess.bridgeaccess.storage.SupabaseImageStore;

/**
 * Picks where images are stored: the private Supabase Storage bucket when
 * {@code SUPABASE_URL} and {@code SUPABASE_SECRET_KEY} are set (production), otherwise a
 * folder on this server (development).
 */
@Configuration
@EnableConfigurationProperties(StorageConfig.StorageProperties.class)
public class StorageConfig {

	private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

	/**
	 * @param supabaseUrl project URL, e.g. {@code https://abcd.supabase.co}
	 * @param secretKey a secret key ({@code sb_secret_...}) or the legacy {@code service_role}
	 * key; server only, never in the web app
	 * @param bucket private bucket for package images
	 * @param localDir folder used when Supabase is not configured
	 * @param connectTimeout connect timeout for Storage calls
	 * @param readTimeout whole-response timeout for Storage calls (files are at most 500 KB)
	 */
	@ConfigurationProperties("bridgeaccess.storage")
	public record StorageProperties(@DefaultValue("") String supabaseUrl, @DefaultValue("") String secretKey,
			@DefaultValue("package-images") String bucket, @DefaultValue("./data/images") Path localDir,
			@DefaultValue("5s") Duration connectTimeout, @DefaultValue("30s") Duration readTimeout) {

		public boolean usesSupabase() {
			return !this.supabaseUrl.isBlank() && !this.secretKey.isBlank();
		}

		@Override
		public String toString() {
			return "StorageProperties[supabaseUrl=" + this.supabaseUrl + ", bucket=" + this.bucket + ", secretKey="
					+ (this.secretKey.isBlank() ? "<missing>" : "****") + ", localDir=" + this.localDir + "]";
		}

	}

	@Bean
	ImageStore imageStore(StorageProperties properties) {
		if (!properties.usesSupabase()) {
			if (!properties.supabaseUrl().isBlank() || !properties.secretKey().isBlank()) {
				log.warn("Only one of SUPABASE_URL and SUPABASE_SECRET_KEY is set; storing images on local disk");
			}
			log.info("Storing images on local disk in {}", properties.localDir().toAbsolutePath());
			return new LocalImageStore(properties.localDir());
		}
		log.info("Storing images in Supabase Storage bucket '{}'", properties.bucket());
		return new SupabaseImageStore(supabaseRestClient(properties), properties.bucket());
	}

	static RestClient supabaseRestClient(StorageProperties properties) {
		HttpClient http = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.readTimeout());
		String baseUrl = properties.supabaseUrl().replaceAll("/+$", "");
		return RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(factory)
			// Both headers, as supabase-js sends them; works with new secret keys and legacy JWT keys.
			.defaultHeader("apikey", properties.secretKey())
			.defaultHeader("Authorization", "Bearer " + properties.secretKey())
			.build();
	}

}
