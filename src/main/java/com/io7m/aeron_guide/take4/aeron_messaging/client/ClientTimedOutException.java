package com.io7m.aeron_guide.take4.aeron_messaging.client;

/**
 * The client timed out when it attempted to connect to the server.
 */

public final class ClientTimedOutException extends ClientException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public ClientTimedOutException(final String message)
  {
    super(message);
  }
}
