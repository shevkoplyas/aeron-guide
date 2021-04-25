package com.io7m.aeron_guide.take3;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.logbuffer.FragmentHandler;
import io.aeron.logbuffer.Header;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A mindlessly simple Echo server.
 * Found here: http://www.io7m.com/documents/aeron-guide/#client_server_take_2
 */
public final class AeronMessagingServer implements Closeable {

    /**
     * The stream used for echo messages.
     */
    public static final int ECHO_STREAM_ID;

    private static final Logger LOG = LoggerFactory.getLogger(AeronMessagingServer.class);

    static {
        ECHO_STREAM_ID = 0x2044f002;
    }

    private final MediaDriver media_driver;
    private final Aeron aeron;
    private final EchoServerExecutorService executor; // Dimon: this type is an i-face, which extends AutoCloseable, Executor
    private final ClientState clients;
    private final EchoServerConfiguration configuration;

    private AeronMessagingServer(
            final Clock in_clock,
            final EchoServerExecutorService in_exec,
            final MediaDriver in_media_driver,
            final Aeron in_aeron,
            final EchoServerConfiguration in_config) {
        this.executor
                = Objects.requireNonNull(in_exec, "executor");
        this.media_driver
                = Objects.requireNonNull(in_media_driver, "media_driver");
        this.aeron
                = Objects.requireNonNull(in_aeron, "aeron");
        this.configuration
                = Objects.requireNonNull(in_config, "configuration");

        this.clients
                = new ClientState(
                        this.aeron,
                        Objects.requireNonNull(in_clock, "clock"),
                        this.executor,
                        this.configuration);
    }

    /**
     * Create a new server.
     *
     * @param clock A clock used for internal operations involving time
     * @param configuration The server configuration
     *
     * @return A new server
     *
     * @throws EchoServerException On any initialization error
     */
    public static AeronMessagingServer create(
            final Clock clock,
            final EchoServerConfiguration configuration)
            throws EchoServerException {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(configuration, "configuration");

        final String directory
                = configuration.baseDirectory().toAbsolutePath().toString();

        final MediaDriver.Context media_context
                = new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .publicationReservedSessionIdLow(EchoSessions.RESERVED_SESSION_ID_LOW) // When the media driver automatically assigns session IDs, it must
                        .publicationReservedSessionIdHigh(EchoSessions.RESERVED_SESSION_ID_HIGH) // use values outside of this range to avoid conflict with any that we assign ourselves.
                        .aeronDirectoryName(directory);

        final Aeron.Context aeron_context
                = new Aeron.Context()
                        .aeronDirectoryName(directory);

        EchoServerExecutorService executor = null;
        try {
            executor = EchoServerExecutor.create();

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

                return new AeronMessagingServer(clock, executor, media_driver, aeron, configuration);
            } catch (final Exception e) {
                closeIfNotNull(media_driver);
                throw e;
            }
        } catch (final Exception e) {
            try {
                closeIfNotNull(executor);
            } catch (final Exception c_ex) {
                e.addSuppressed(c_ex);
            }
            throw new EchoServerCreationException(e);
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
        if (args.length < 6) {
            LOG.error(
                    "usage: directory local-address local-initial-data-port local-initial-control-port local-clients-base-port client-count");
            System.exit(1);
        }

        final Path directory = Paths.get(args[0]);
        final InetAddress local_address = InetAddress.getByName(args[1]);
        final int local_initial_data_port = Integer.parseUnsignedInt(args[2]);
        final int local_initial_control_port = Integer.parseUnsignedInt(args[3]);
        final int local_clients_base_port = Integer.parseUnsignedInt(args[4]);
        final int client_count = Integer.parseUnsignedInt(args[5]);

        final EchoServerConfiguration config
                = ImmutableEchoServerConfiguration.builder()
                        .baseDirectory(directory)
                        .localAddress(local_address)
                        .localInitialPort(local_initial_data_port)
                        .localInitialControlPort(local_initial_control_port)
                        .localClientsBasePort(local_clients_base_port)
                        .clientMaximumCount(client_count)
                        .maximumConnectionsPerAddress(3)
                        .build();

        try (final AeronMessagingServer server = create(Clock.systemUTC(), config)) {
            server.run();
        }
    }

    /**
     * Run the server, returning when the server is finished.
     */
    private int debug_iteration_counter = 0;

    public void run() {
        try (final Publication all_clients_publication = this.setupAllClientsPublication()) {
            try (final Subscription all_clients_subscription = this.setupAllClientsSubscription()) {

                final FragmentHandler handler
                        = new FragmentAssembler(
                                (buffer, offset, length, header)
                                -> this.onAllClientsClientMessage(
                                        all_clients_publication,
                                        buffer,
                                        offset,
                                        length,
                                        header));

                Clock server_clock = Clock.systemUTC();

                /**
                 * main loop
                 */
                UnsafeBuffer tmp_send_buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));
                while (true) {
                    debug_iteration_counter++;
                    this.executor.execute(() -> {
                        // Dimon: this will subscirption.pull() on "allClientSubscription",
                        //        which triggers: this::onInitialClientConnected and this::onInitialClientDisconnected);
                        all_clients_subscription.poll(handler, 100);

                        // Dimon: This will iterate all clients subscription.poll(this.handler, 10);
                        //        which will trigger: onMessageReceived.onMessageReceived
                        this.clients.poll();
                        // Basically server passively pulling messages from "all clients" channel and 
                        // from all individual connected clients channels, reacting on them (not sending anything proactively)

                        // Let's proactively send some message to all connected clients.
                        // [Q] How do we use different channels ECHO_STREAM_ID ?
                        // Every 5 sec send private message to all clients
                        if (debug_iteration_counter % 50 == 0) {
                            this.clients.sent_private_message_to_all_clients("server ----private---> client: local server time is " + server_clock.instant().toString());
                        }

                        // Every 7 sec send PUBLIC message via all_clients_publication
                        if (debug_iteration_counter % 70 == 0) {
                            try {
                                if (all_clients_publication.isConnected()){
                                EchoMessages.sendMessage(
                                        all_clients_publication,
                                        tmp_send_buffer,
                                        "server ----all_clients_publication---> clients: some message to all connected clients!");
                                }
                            } catch (IOException ex) {
                                LOG.error("Exception while trying to sendMessage() via all_clients_publication. Details: ", ex);
                            }
                        }
                    });
                    try {
                        Thread.sleep(100L);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void onAllClientsClientMessage(
            final Publication publication, // Dimon: we simply pass "publication" to know where to reply (if needed).. The "official params" are the next 4 (buffer, offset, length, header)
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header) {
        final String message
                = EchoMessages.parseMessageUTF8(buffer, offset, length);

        final String session_name
                = Integer.toString(header.sessionId());
        final Integer session_boxed
                = Integer.valueOf(header.sessionId());

        this.executor.execute(() -> {
            try {
                this.clients.onClientMessage(
                        publication, // Dimon: we simply pass "publication" to know where to reply (if needed) and message is now a simple String.
                        session_name,
                        session_boxed,
                        message);
            } catch (final Exception e) {
                LOG.error("could not process client message: ", e);
            }
        });
    }

    /**
     * Configure the publication for the "all-clients" channel.
     */
    private Publication setupAllClientsPublication() {
        return EchoChannels.createPublicationDynamicMDC(
                this.aeron,
                this.configuration.localAddress(),
                this.configuration.localInitialControlPort(),
                ECHO_STREAM_ID);
    }

    /**
     * Configure the subscription for the "all-clients" channel.
     */
    private Subscription setupAllClientsSubscription() {
        return EchoChannels.createSubscriptionWithHandlers(
                this.aeron,
                this.configuration.localAddress(),
                this.configuration.localInitialPort(),
                ECHO_STREAM_ID,
                this::onInitialClientConnected,
                this::onInitialClientDisconnected);
    }

    private void onInitialClientConnected(
            final Image image) {
        this.executor.execute(() -> {
            LOG.debug(
                    "[{}] initial client connected ({})",
                    Integer.toString(image.sessionId()),
                    image.sourceIdentity());

            this.clients.onInitialClientConnected(
                    image.sessionId(),
                    EchoAddresses.extractAddress(image.sourceIdentity()));
        });
    }

    private void onInitialClientDisconnected(
            final Image image) {
        this.executor.execute(() -> {
            LOG.debug(
                    "[{}] initial client disconnected ({})",
                    Integer.toString(image.sessionId()),
                    image.sourceIdentity());

            this.clients.onInitialClientDisconnected(image.sessionId());
        });
    }

    @Override
    public void close() {
        this.aeron.close();
        this.media_driver.close();
    }
}
