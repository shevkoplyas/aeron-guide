package com.io7m.aeron_guide.take4.aeron_messaging.server;

import com.io7m.aeron_guide.take4.aeron_messaging.server.AeronMessagingServerException;

/**
 * A session could not be allocated.
 */

public final class ServerSessionAllocationException
  extends AeronMessagingServerException
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public ServerSessionAllocationException(
    final String message)
  {
    super(message);
  }
}
