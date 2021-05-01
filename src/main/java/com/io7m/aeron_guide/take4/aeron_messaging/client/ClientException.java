package com.io7m.aeron_guide.take4.aeron_messaging.client;

import java.util.Objects;

/**
 * The type of exceptions raised by the client.
 */

public abstract class ClientException extends Exception
{
  /**
   * Create an exception.
   *
   * @param message The message
   */

  public ClientException(final String message)
  {
    super(Objects.requireNonNull(message, "message"));
  }

  /**
   * Create an exception.
   *
   * @param cause The cause
   */

  public ClientException(final Throwable cause)
  {
    super(Objects.requireNonNull(cause, "cause"));
  }
}
