package com.io7m.aeron_guide.take4.aeron_messaging.client;

/**
 * An exception occurred whilst trying to create the client.
 */

public final class ClientCreationException extends ClientException
{
  /**
   * Create an exception.
   *
   * @param cause The cause
   */

  public ClientCreationException(final Exception cause)
  {
    super(cause);
  }
}
