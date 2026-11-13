package com.eduaccess.bridgeaccess.service.generation;

/**
 * Generation could not finish because Gemini was busy or unreachable, not because anything
 * is wrong with the package. {@link GenerationJob} puts the package back in the queue so the
 * sweeper tries again later; only when it is past {@code deferWindow} is it failed, with
 * this exception's (inherited, contributor-safe) message.
 */
public class GenerationDeferredException extends GenerationFailedException {

	public GenerationDeferredException(Throwable cause) {
		super(UNRELIABLE_OUTPUT, cause);
	}

}
