package com.eduaccess.bridgeaccess.service.generation;

/**
 * Generation failed in an expected way. The message becomes the package's
 * {@code failureReason}, shown to the contributor, so it must be safe to display (API doc,
 * section 10, "Failure messages").
 */
public class GenerationFailedException extends RuntimeException {

	public static final String UNREADABLE_LINK = "We couldn't read this link. Please paste the content and try again.";

	public static final String UNREADABLE_VIDEO = "We couldn't read this video. Please paste the transcript and try again.";

	public static final String UNRELIABLE_OUTPUT = "We couldn't build a reliable lesson from this. Please try again.";

	public static final String TIMED_OUT = "This took too long. Please try again.";

	public GenerationFailedException(String safeMessage) {
		super(safeMessage);
	}

	public GenerationFailedException(String safeMessage, Throwable cause) {
		super(safeMessage, cause);
	}

}
