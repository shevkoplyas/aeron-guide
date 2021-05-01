package com.io7m.aeron_guide.take4.aeron_messaging.client;

import java.io.IOException;

public final class ClientIOException extends ClientException
{
  public ClientIOException(final IOException cause)
  {
    super(cause);
  }
}
