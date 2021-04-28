package com.io7m.aeron_guide.take3;

/**
 * An exception occurred whilst trying to create the server.
 */

public final class AeronMessagingServerCreationException extends AeronMessagingServerException
{
  /**
   * Create an exception.
   *
   * @param cause The cause
   */

  public AeronMessagingServerCreationException(final Exception cause)
  {
    super(cause);
  }
}
