package i5.las2peer.security;

import i5.las2peer.communication.Message;

/**
 * A simple interface to register message handlers to {@link Mediator}s.
 * 
 * 
 *
 */
public interface MessageHandler {

	/**
	 * Gets an already opened message from an agent mediator and tries to process it. Returns true, if this handler has
	 * successfully handled the message, false otherwise.
	 * 
	 * @param message
	 * @param context
	 * @return true, if the received message has been handled and can be removed
	 * @throws Exception
	 */
	public boolean handleMessage(Message message, AgentContext context) throws Exception;

}
