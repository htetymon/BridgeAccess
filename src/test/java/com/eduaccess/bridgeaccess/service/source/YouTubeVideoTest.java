package com.eduaccess.bridgeaccess.service.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class YouTubeVideoTest {

	@ParameterizedTest
	@CsvSource({
			"https://youtu.be/Nmcgbcbpsp8?si=rlGBv-UoKT8kjSX2, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/watch?v=Nmcgbcbpsp8&t=30s, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://youtube.com/watch?app=desktop&v=Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://m.youtube.com/watch?v=Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://music.youtube.com/watch?v=Nmcgbcbpsp8&feature=share, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/shorts/Nmcgbcbpsp8?feature=share, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/embed/Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/live/Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/v/Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"https://www.youtube-nocookie.com/embed/Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"  https://youtu.be/Nmcgbcbpsp8/  , https://www.youtube.com/watch?v=Nmcgbcbpsp8",
			"youtu.be/Nmcgbcbpsp8, https://www.youtube.com/watch?v=Nmcgbcbpsp8" })
	void canonicalisesSingleVideoLinks(String link, String expected) {
		assertThat(YouTubeVideo.canonicalUrl(link)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "https://example.org/watch?v=Nmcgbcbpsp8",
			"https://www.youtube.com/playlist?list=PL123", "https://www.youtube.com/@channel",
			"https://www.youtube.com/watch?v=tooShort", "https://www.youtube.com/watch?v=Nmcgbcbpsp8extra",
			"https://www.youtube.com/watch", "https://www.youtube.com/", "not a link",
			"https://youtu.be/", "https://youtu.be/Nmcgbcbpsp8/extra" })
	void rejectsLinksThatAreNotOneVideo(String link) {
		assertThat(YouTubeVideo.canonicalUrl(link)).isNull();
	}

	@Test
	void rejectsNull() {
		assertThat(YouTubeVideo.canonicalUrl(null)).isNull();
	}

}
