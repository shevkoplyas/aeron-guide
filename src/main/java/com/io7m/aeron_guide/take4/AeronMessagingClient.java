package com.io7m.aeron_guide.take4;

import io.aeron.Aeron;
import io.aeron.ConcurrentPublication;
import io.aeron.FragmentAssembler;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import org.agrona.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mindlessly simple Echo client.
 * Found here: http://www.io7m.com/documents/aeron-guide/#client_server_take_2
 */
public final class AeronMessagingClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(AeronMessagingClient.class);

    private static final int ECHO_STREAM_ID = 0x100500ff;

    private static final Pattern PATTERN_ERROR
            = Pattern.compile("^ERROR (.*)$");
    private static final Pattern PATTERN_CONNECT
            = Pattern.compile("^CONNECT ([0-9]+) ([0-9]+) ([0-9A-F]+)$");
    private static final Pattern PATTERN_ECHO
            = Pattern.compile("^ECHO (.*)$");

    private final MediaDriver media_driver;
    private final Aeron aeron;
    private final AeronMessagingClientConfiguration configuration;
    private final SecureRandom random;
    private volatile int remote_data_port;
    private volatile int remote_control_port;
    private volatile boolean remote_ports_received;
    private volatile boolean failed;
    private volatile int remote_session;
    private volatile int duologue_key;

    private AeronMessagingClient(
            final MediaDriver in_media_driver,
            final Aeron in_aeron,
            final AeronMessagingClientConfiguration in_configuration) {
        this.media_driver
                = Objects.requireNonNull(in_media_driver, "media_driver");
        this.aeron
                = Objects.requireNonNull(in_aeron, "aeron");
        this.configuration
                = Objects.requireNonNull(in_configuration, "configuration");

        this.random = new SecureRandom();
    }

    /**
     * Create a new client.
     *
     * @param configuration The client configuration data
     *
     * @return A new client
     *
     * @throws EchoClientCreationException On any initialization error
     */
    public static AeronMessagingClient create(
            final AeronMessagingClientConfiguration configuration)
            throws EchoClientException {
        Objects.requireNonNull(configuration, "configuration");

        final String directory
                = configuration.baseDirectory()
                        .toAbsolutePath()
                        .toString();

        final MediaDriver.Context media_context
                = new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .publicationReservedSessionIdLow(AeronMessagingServerSessions.RESERVED_SESSION_ID_LOW)
                        .publicationReservedSessionIdHigh(AeronMessagingServerSessions.RESERVED_SESSION_ID_HIGH)
                        .aeronDirectoryName(directory);

        final Aeron.Context aeron_context
                = new Aeron.Context().aeronDirectoryName(directory);

        MediaDriver media_driver = null;

        try {
            media_driver = MediaDriver.launch(media_context);

            Aeron aeron = null;
            try {
                aeron = Aeron.connect(aeron_context);
            } catch (final Exception e) {
                closeIfNotNull(aeron);
                throw e;
            }

            return new AeronMessagingClient(media_driver, aeron, configuration);
        } catch (final Exception e) {
            try {
                closeIfNotNull(media_driver);
            } catch (final Exception c_ex) {
                e.addSuppressed(c_ex);
            }
            throw new EchoClientCreationException(e);
        }
    }

    private static void closeIfNotNull(
            final AutoCloseable closeable)
            throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }

    /**
     * Command-line entry point.
     *
     * @param args Command-line arguments
     *
     * @throws Exception On any error
     */
    public static void main(
            final String[] args)
            throws Exception {
        if (args.length < 4) {
            LOG.error(
                    "usage: directory remote-address remote-data-port remote-control-port");
            System.exit(1);
        }

        final Path directory = Paths.get(args[0]);
        final InetAddress remote_address = InetAddress.getByName(args[1]);
        final int remote_data_port = Integer.parseUnsignedInt(args[2]);
        final int remote_control_port = Integer.parseUnsignedInt(args[3]);

        final ImmutableAeronMessagingClientConfiguration configuration
                = ImmutableAeronMessagingClientConfiguration.builder()
                        .baseDirectory(directory)
                        .remoteAddress(remote_address)
                        .remoteInitialControlPort(remote_control_port)
                        .remoteInitialPort(remote_data_port)
                        .build();

        try (final AeronMessagingClient client = create(configuration)) {
            client.run();
        }
    }

    /**
     * Run the client, returning when the client is finished.
     *
     * @throws EchoClientException On any error
     */
    public void run()
            throws EchoClientException {
        /**
         * Generate a one-time pad.
         */

        this.duologue_key = this.random.nextInt();

        final UnsafeBuffer buffer
                = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));

        final String session_name;
        Subscription all_client_subscription = null;
        Publication all_client_publication = null;

// Commented out "try-with-resources" approach since this imply the "resource" to be closed after try block finishes,
// but we want to maintain "all_client" sub and pub!
//        try (all_client_subscription = this.setupAllClientsSubscription()) {
//            try (all_client_publication = this.setupAllClientsPublication()) {
        try {
            all_client_subscription = this.setupAllClientsSubscription();
            all_client_publication = this.setupAllClientsPublication();

            /**
             * Send a one-time pad to the server.
             */
            MessagesHelper.sendMessage(
                    all_client_publication,
                    buffer,
                    "HELLO " + Integer.toUnsignedString(this.duologue_key, 16).toUpperCase());

            session_name = Integer.toString(all_client_publication.sessionId());
            this.waitForConnectResponse(all_client_subscription, session_name);
        } catch (final IOException e) {
            throw new EchoClientIOException(e);
        }

        /**
         * Connect to the publication and subscription that the server has sent
         * back to this client.
         */
        try (final Subscription private_subscription = this.setupConnectSubscription()) {
            try (final Publication private_publication = this.setupConnectPublication()) {
                this.runMainMessageProcessingLoop(
                        buffer,
                        session_name,
                        private_subscription,
                        private_publication,
                        all_client_subscription,
                        all_client_publication
                );
            } catch (final IOException e) {
                throw new EchoClientIOException(e);
            }
        }
    }

    private void runMainMessageProcessingLoop(
            final UnsafeBuffer buffer,
            final String session_name,
            final Subscription private_subscription,
            final Publication private_publication,
            final Subscription all_client_subscription,
            final Publication all_client_publication
    )
            throws IOException {
        final FragmentHandler private_subscription_message_handler
                = new FragmentAssembler(
                        (data, offset, length, header)
                        -> onEchoResponse(session_name, data, offset, length));

        final FragmentHandler all_client_subscription_message_handler
                = new FragmentAssembler(
                        (data, offset, length, header)
                        -> onEchoResponse(session_name, data, offset, length));

        Clock clock = Clock.systemUTC();

        /**
         * main loop
         */
        int packets_count = 0;
        while (packets_count++ < 1000) {

            // Send ECHO messages to the server and wait for responses. (via private publication)
            MessagesHelper.sendMessage(
                    private_publication,
                    buffer,
                    "ECHO " + Long.toUnsignedString(this.random.nextLong(), 16));

            // Also send some random message (to check the server won't barf) - also via private publication
            MessagesHelper.sendMessage(
                    private_publication,
                    buffer,
                    "client ---private---> server: Hi server! My local client-time is " + clock.instant().toString());

            // Just for fun: let's periodically send some other message to the server via all_client_publication!
            if (packets_count % 3 == 0) {
                MessagesHelper.sendMessage(
                        all_client_publication,
                        buffer,
                        "client ---all_client_publication---> server: Random number: " + Long.toUnsignedString(this.random.nextLong(), 16) + ". Local client-time is " + clock.instant().toString());
            }

            // Time to try to receive (poll) messages from all subscriptions
            for (int index = 0; index < 100; ++index) {
                // We have 1 private subscription (server sends messages to only this client)
                int fragments_received = private_subscription.poll(private_subscription_message_handler, 1000);
                
                // We have "all client" subscription, which sends the same things to all connected clients.
                fragments_received += all_client_subscription.poll(all_client_subscription_message_handler, 1000);

                // Sleep only if no fragments received
                if (fragments_received == 0){
                    try {
                        Thread.sleep(10L);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private static void onEchoResponse(
            final String session_name,
            final DirectBuffer buffer,
            final int offset,
            final int length) {
        final String response
                = MessagesHelper.parseMessageUTF8(buffer, offset, length);

        LOG.debug("[{}] response: {}", session_name, response);

        final Matcher echo_matcher = PATTERN_ECHO.matcher(response);
        if (echo_matcher.matches()) {
            final String message = echo_matcher.group(1);
            LOG.debug("[{}] ECHO {}", session_name, message);
            return;
        }

//        LOG.error(
//                "[{}] server returned unrecognized message: {}",
//                session_name,
//                response);
    }

    private static void all_client_subscription_message_handler(
            final String session_name,
            final DirectBuffer buffer,
            final int offset,
            final int length) {
        final String response
                = MessagesHelper.parseMessageUTF8(buffer, offset, length);

        LOG.debug("[{}] all_client_subscription_message_handler: {}", session_name, response);
    }

    private Publication setupConnectPublication()
            throws EchoClientTimedOutException {
        final ConcurrentPublication publication
                = AeronChannelsHelper.createPublicationWithSession(
                        this.aeron,
                        this.configuration.remoteAddress(),
                        this.remote_data_port,
                        this.remote_session,
                        ECHO_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (publication.isConnected()) {
                LOG.debug("CONNECT publication connected   +++++ this.configuration.remoteAddress() = " + this.configuration.remoteAddress() + " this.remote_data_port = " + this.remote_data_port);
                return publication;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        publication.close();
        throw new EchoClientTimedOutException("Making CONNECT publication to server");
    }

    private Subscription setupConnectSubscription()
            throws EchoClientTimedOutException {
        final Subscription subscription
                = AeronChannelsHelper.createSubscriptionDynamicMDCWithSession(
                        this.aeron,
                        this.configuration.remoteAddress(),
                        this.remote_control_port,
                        this.remote_session,
                        ECHO_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (subscription.isConnected() && subscription.imageCount() > 0) {
                LOG.debug("CONNECT subscription connected   ++++ this.configuration.remoteAddress() = " + this.configuration.remoteAddress() + " control port: " + this.remote_control_port);
                return subscription;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        subscription.close();
        throw new EchoClientTimedOutException(
                "Making CONNECT subscription to server");
    }

    private void waitForConnectResponse(
            final Subscription all_client_subscription,
            final String session_name)
            throws EchoClientTimedOutException, EchoClientRejectedException {
        LOG.debug("waiting for response");

        final FragmentHandler handler
                = new FragmentAssembler(
                        (data, offset, length, header)
                        -> this.onInitialResponse(session_name, data, offset, length));

        long start_wait_for_connect_response_epoch_ms = System.currentTimeMillis();
        long connect_timeout_ms = 10000;
        while (true) {
//        for (int index = 0; index < 1000; ++index) {

            long current_epoch_ms = System.currentTimeMillis();

            all_client_subscription.poll(handler, 1000);

            if (this.failed) {
                throw new EchoClientRejectedException("Server rejected this client");
            }

            if (this.remote_ports_received) {
                return;
            }

            try {
                Thread.sleep(1L);
                // Dimon: we keep trying to pull our "CONNECT" welcome message from the server only 1000 times,
                // so if the control channel blusting messages for all other (already connected) clients, then
                // we have high chances to fail to connect here..
                // We can address it by:
                //   - onInitialResponse() should not consider incoming messages with length > N bytes
                //   - let's make 1st few bytes in the control stream known, say start with "CONTROL ", then no need to even prematurely use regexp
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Break the loop if timeout time exceeded
            if (current_epoch_ms - start_wait_for_connect_response_epoch_ms >= connect_timeout_ms) {
                break;
            }
        }

        throw new EchoClientTimedOutException(
                "Waiting for CONNECT response from server (connect_timeout_ms value was " + connect_timeout_ms + ")");
    }

    /**
     * Parse the initial response from the server.
     */
    private void onInitialResponse(
            final String session_name,
            final DirectBuffer buffer,
            final int offset,
            final int length) {

        LOG.trace("debug: got response with length: " + length + ", offset: " + offset);

        // The initial "CONNECT" response can't be shorter than 21 byte and can't be longer than ~60 bytes
        if (length < 21 || length > 60) {
            return;
        }

        // The initial "CONNECT" response from the server is now supposed to start with "CONTROL " prefix
        if (buffer.getByte(offset + 0) != 'C'
                || buffer.getByte(offset + 1) != 'O'
                || buffer.getByte(offset + 2) != 'N'
                || buffer.getByte(offset + 3) != 'T'
                || buffer.getByte(offset + 4) != 'R'
                || buffer.getByte(offset + 5) != 'O'
                || buffer.getByte(offset + 6) != 'L'
                || buffer.getByte(offset + 7) != ' ') {
            return;
        }

        final String response = MessagesHelper.parseMessageUTF8(buffer, offset, length);

        LOG.trace("[{}] response: {}", session_name, response);

        /**
         * Try to extract the session identifier to determine whether the
         * message was intended for this client or not.
         */
        final int space = response.indexOf(" ", 8);  // +8 is for "CONTROL " prefix
        if (space == -1) {
            LOG.error(
                    "[{}] server returned unrecognized message (can not find space): {}",
                    session_name,
                    response);
            return;
        }

        final String message_session = response.substring(8, space);  // +8 is for "CONTROL " prefix
        if (!Objects.equals(message_session, session_name)) {
            LOG.trace(
                    "[{}] ignored message intended for another client (expected session=" + session_name + ", but received session=" + message_session + ")",
                    session_name);
            return;
        }

        /**
         * The message was intended for this client. Try to parse it as one of
         * the available message types.
         */
        final String text = response.substring(space).trim();   // Trim leading session and space, so now response text should start with CONNECT

        final Matcher error_matcher = PATTERN_ERROR.matcher(text);
        if (error_matcher.matches()) {
            final String message = error_matcher.group(1);
            LOG.error("[{}] server returned an error: {}", session_name, message);
            this.failed = true;
            return;
        }

        final Matcher connect_matcher = PATTERN_CONNECT.matcher(text);
        if (connect_matcher.matches()) {
            final int port_data
                    = Integer.parseUnsignedInt(connect_matcher.group(1));
            final int port_control
                    = Integer.parseUnsignedInt(connect_matcher.group(2));
            final int session_crypted
                    = Integer.parseUnsignedInt(connect_matcher.group(3), 16);

            LOG.debug(
                    "[{}] connect {} {} (encrypted {})",
                    session_name,
                    Integer.valueOf(port_data),
                    Integer.valueOf(port_control),
                    Integer.valueOf(session_crypted));
            this.remote_control_port = port_control;
            this.remote_data_port = port_data;
            this.remote_session = this.duologue_key ^ session_crypted;
            this.remote_ports_received = true;
            return;
        }

        LOG.error(
                "[{}] server returned unrecognized message: {}",
                session_name,
                text);
    }

    private Publication setupAllClientsPublication()
            throws EchoClientTimedOutException {
        final ConcurrentPublication publication
                = AeronChannelsHelper.createPublication(
                        this.aeron,
                        this.configuration.remoteAddress(),
                        this.configuration.remoteInitialPort(),
                        ECHO_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (publication.isConnected()) {
                LOG.debug("initial publication connected");
                return publication;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        publication.close();
        throw new EchoClientTimedOutException("Making initial publication to server");
    }

    private Subscription setupAllClientsSubscription()
            throws EchoClientTimedOutException {
        final Subscription subscription
                = AeronChannelsHelper.createSubscriptionDynamicMDC(
                        this.aeron,
                        this.configuration.remoteAddress(),
                        this.configuration.remoteInitialControlPort(),
                        ECHO_STREAM_ID);

        for (int index = 0; index < 1000; ++index) {
            if (subscription.isConnected() && subscription.imageCount() > 0) {
                LOG.debug("initial subscription connected");
                return subscription;
            }

            try {
                Thread.sleep(10L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        subscription.close();
        throw new EchoClientTimedOutException(
                "Making initial subscription to server");
    }

    @Override
    public void close() {
        this.aeron.close();
        this.media_driver.close();
    }
}
