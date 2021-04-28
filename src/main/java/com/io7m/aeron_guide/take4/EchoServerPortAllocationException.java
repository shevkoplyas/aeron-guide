package com.io7m.aeron_guide.take4;

/**
 * A port could not be allocated.
 */

public final class EchoServerPortAllocationException extends AeronMessagingServerException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public EchoServerPortAllocationException(
    final String message)
  {
    super(message);
  }
}
