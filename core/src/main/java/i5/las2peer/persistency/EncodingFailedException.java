package i5.las2peer.persistency;

/**
 * Exception thrown if an encoding has failed for some reason.
 */
public class EncodingFailedException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * This constructor sets only the message for this exception and leaves the cause to {@code null}.
	 *
	 * @param message A message that describes the error.
	 */
	public EncodingFailedException(String message) {
		this(message, null);
	}

	/**
	 * This constructor leaves the message {@code null} and sets only the cause for this exception. This is usually used
	 * to wrap other exception types and unify exception handling for the developer.
	 *
	 * @param cause An other exception that caused this one.
	 */
	public EncodingFailedException(Throwable cause) {
		this("", cause);
	}

	/**
	 * This constructor allows to set message and cause for this exception.
	 *
	 * @param message A message that describes the error.
	 * @param cause An other exception that caused this one.
	 */
	public EncodingFailedException(String message, Throwable cause) {
		super(message);
		this.initCause(cause);
	}

}
