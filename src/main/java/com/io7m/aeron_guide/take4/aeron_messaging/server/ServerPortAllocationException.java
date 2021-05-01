package com.io7m.aeron_guide.take4.aeron_messaging.server;

import com.io7m.aeron_guide.take4.aeron_messaging.server.AeronMessagingServerException;

/**
 * A port could not be allocated.
 */

public final class ServerPortAllocationException extends AeronMessagingServerException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public ServerPortAllocationException(
    final String message)
  {
    super(message);
  }
}
