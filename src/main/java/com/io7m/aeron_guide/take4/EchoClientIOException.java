package com.io7m.aeron_guide.take4;

import java.io.IOException;

public final class EchoClientIOException extends EchoClientException
{
  public EchoClientIOException(final IOException cause)
  {
    super(cause);
  }
}
