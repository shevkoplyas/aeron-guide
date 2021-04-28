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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <pre>
 * A mindlessly simple Echo server by Mark Raynsford was found here:
 * http://www.io7m.com/documents/aeron-guide/
 * https://github.com/io7m/aeron-guide.git
 *
 * Mark's work is licensed under a Creative Commons Attribution 4.0 International License. (see README-LICENSE.txt)
 *
 * The guide shows how to make simple echo client-server in 2 takes
 * take1 - is minimalistic code and then take2 is a bit more involved.
 * This "AeronMessagingServer" is kinda "take3" - a small modification
 * done to the main loop to make it able to shovel thousands of messages
 * per second. It also does not close initial "all client" connection,
 * so we end up with every client connected to the server with 4 channels:
 *   1) one-for-all publication (any published message will go to all connected clients)
 *   2) one-for-all subscription (any client can write a message to the server via that channel)
 *   3) "private" publication (server can send a message just to one particular client)
 *   4) "private" subscription (any client can use to send a message to the server, but there's  no difference between (4) and (2) so it is kinda redundant)
 *
 * Also some steps were done to improve AeronMessagingServer integration into
 * other projects. In particular AeronMessagingServer is:
 *   - working on it's own thread
 *   - is accepting messages by exposed send_broadcast(String message) method, which will simply enqueue(message)
 *     Later let's add send_private(message) method inside
 *   - is adding all received messages into concurrent containers ConcurrentLinkedQueue.
 *     See details on Queue: https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/Queue.html
 *     In particular ConcurrentLinkedQueue: https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html
 * </pre>
 */
public final class AeronMessagingServer implements Closeable {

    // We end up using just one stream ID.
    // Aeron is capable of multiplexing several independent streams of messages into a single connection,
    // but let's use it just as a bus and we'll discrementate different types of messages by their payload
    // (for examle we might inject message type as 1st N bytes into the byte buffer transfered to the other end).
    public static final int MAIN_STREAM_ID;

    private static final Logger LOG = LoggerFactory.getLogger(AeronMessagingServer.class);

    static {
        // We also specify a stream ID when creating the subscription. Aeron is capable of
        // multiplexing several independent streams of messages into a single connection.
        MAIN_STREAM_ID = 0x100500ff;
    }

    private final MediaDriver media_driver;
    private final Aeron aeron;
    private final AeronMessagingServerExecutorService executor; // Dimon: this type is an i-face, which extends AutoCloseable, Executor
    private final ClientState clients;
    private final AeronMessagingServerConfiguration configuration;

    private AeronMessagingServer(
            final Clock in_clock,
            final AeronMessagingServerExecutorService in_exec,
            final MediaDriver in_media_driver,
            final Aeron in_aeron,
            final AeronMessagingServerConfiguration in_config) {
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
                        this.configuration,
                        this.incoming_messages_from_all_clients_queue);
    }

    /**
     * Create a new server.
     *
     * @param clock A clock used for internal operations involving time
     * @param configuration The server configuration
     *
     * @return A new server
     *
     * @throws AeronMessagingServerException On any initialization error
     */
    public static AeronMessagingServer create(
            final Clock clock,
            final AeronMessagingServerConfiguration configuration)
            throws AeronMessagingServerException {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(configuration, "configuration");

        final String directory
                = configuration.baseDirectory().toAbsolutePath().toString();

        final MediaDriver.Context media_context
                = new MediaDriver.Context()
                        .dirDeleteOnStart(true)
                        .publicationReservedSessionIdLow(AeronMessagingServerSessions.RESERVED_SESSION_ID_LOW) // When the media driver automatically assigns session IDs, it must
                        .publicationReservedSessionIdHigh(AeronMessagingServerSessions.RESERVED_SESSION_ID_HIGH) // use values outside of this range to avoid conflict with any that we assign ourselves.
                        .aeronDirectoryName(directory);

        final Aeron.Context aeron_context
                = new Aeron.Context()
                        .aeronDirectoryName(directory);

        AeronMessagingServerExecutorService executor = null;
        try {
            executor = AeronMessagingServerExecutor.create();

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
            throw new AeronMessagingServerCreationException(e);
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

        final AeronMessagingServerConfiguration config
                = ImmutableAeronMessagingServerConfiguration.builder()
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
//    private Clock debug_clock = new Clock();
    public void run() {
        try (final Publication all_clients_publication = this.setupAllClientsPublication()) {
            try (final Subscription all_clients_subscription = this.setupAllClientsSubscription()) {

                final FragmentHandler handler
                        = new FragmentAssembler(
                                (buffer, offset, length, header)
                                -> this.on_all_clients_message_received(
                                        all_clients_publication,
                                        buffer,
                                        offset,
                                        length,
                                        header));

                Clock clock = Clock.systemUTC();

                /**
                 * main loop
                 */
                UnsafeBuffer tmp_send_buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));
                while (true) {
                    long epoch_ms = clock.millis();

                    Future<Integer> future_polled_fragments_count = this.executor.my_call(() -> {
                        // Dimon: this will subscirption.pull() on "allClientSubscription",
                        //        which triggers: this::onInitialClientConnected and this::onInitialClientDisconnected);
                        Integer polled_fragmetns_count = 0;
                        polled_fragmetns_count += all_clients_subscription.poll(handler, 100);

                        // Dimon: This will iterate all clients subscription.poll(this.handler, 10);
                        //        which will trigger: onMessageReceived.onMessageReceived
                        polled_fragmetns_count += this.clients.poll();
                        // Basically server passively pulling messages from "all clients" channel and 
                        // from all individual connected clients channels, reacting on them (not sending anything proactively)

                        // Let's proactively send some message to all connected clients.
                        // [Q] How do we use different channels MAIN_STREAM_ID ?
                        // Every 5 sec send private message to all clients
                        if (epoch_ms % 5000 == 0) {
                            this.clients.sent_private_message_to_all_clients("server ----private---> client: local server time is " + clock.instant().toString());
                        }

                        // Every 7 sec send PUBLISH message via all_clients_publication
                        if (epoch_ms % 7000 == 0) {
                            try {
                                if (all_clients_publication.isConnected()) {
                                    MessagesHelper.sendMessage(
                                            all_clients_publication,
                                            tmp_send_buffer,
                                            "server ----all_clients_publication---> clients: some message to all connected clients!");
                                }
                            } catch (IOException ex) {
                                LOG.error("Exception while trying to sendMessage() via all_clients_publication. Details: ", ex);
                            }
                        }
                        return polled_fragmetns_count;
                    });

                    // Now wait for executor.execute() to finish with our lambda (the original code would sleep 0.1sec and keep
                    // the loop going effectivevly calling ExecutorService.submit(runnable) over and over either overfilling the queue or
                    // making it too slow (sleeping way too much time, which will affect our messaging client/server perfomance).
                    // Let's simply re-submit polling again without any sleep if previous poll got some fragments from the subscription.
                    // If no fragments extracted on the previous poll, then sleep 1ms and submit polling iteration again.
                    // And only submit() polling runnable lambda to the executor in case the previous one is complete (there's no sense of piling them up).
//                    while (!future_polled_fragments_count.isDone() && !future_polled_fragments_count.isCancelled()) {
//                        // Previously submitted task is not complete yet. Just keep waiting and keep checking every 1ms.
//                        try {
//                            Thread.sleep(1L);
//                        } catch (final InterruptedException e) {
//                            Thread.currentThread().interrupt();
//                        }
//                    }
                    // Instead of waiting "while(future.isDone()" we can simply "future.get()", which is blocking (untill lambda comletes
                    Integer polled_fragments_count = 0;
                    try {
                        polled_fragments_count = future_polled_fragments_count.get();  // throws InterruptedException, ExecutionException
//                    } catch (final InterruptedException e) {
                    } catch (final Exception ex) {
                        Thread.currentThread().interrupt();
                        LOG.error("Main loop: unexpected exception while waiting for future_polled_fragments_count. Details: " + ex);
                    }

                    // Now try to shovel our "OUTBOX" queue: broadcast_messages_to_all_clients_queue
                    Integer sent_broadcast_messages_count = 0;
                    if (!broadcast_messages_to_all_clients_queue.isEmpty()) {
                        try {
                            if (all_clients_publication.isConnected()) {
                                MessagesHelper.sendMessage(
                                        all_clients_publication,
                                        tmp_send_buffer,
                                        broadcast_messages_to_all_clients_queue.poll());  // Queue.poll() - Retrieves and removes the head of this queue, or returns null if this queue is empty.
                            }
                        } catch (IOException ex) {
                            LOG.error("Exception while trying to sendMessage() via all_clients_publication. Details: ", ex);
                        }
                    }

                    // Previous polling and sending complete. Check if we got any fragments received or messaes sent,
                    // then run next itaration w/o extra delay.
                    if ((polled_fragments_count + sent_broadcast_messages_count) == 0) {
                        // We got no fragments from subscription. Let's fall asleep for 1ms
                        try {
                            Thread.sleep(1L);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    // else: do nothing, just continue to the next "main loop" iteration.

                }
            }
        }
    }

    /**
     * See details on Queue:
     * https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/Queue.html
     * In particular ConcurrentLinkedQueue:
     * https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html
     *
     */
    private ConcurrentLinkedQueue<String> broadcast_messages_to_all_clients_queue = new ConcurrentLinkedQueue();
    private ConcurrentLinkedQueue<String> incoming_messages_from_all_clients_queue = new ConcurrentLinkedQueue();

    /**
     * Inserts the specified element at the tail of
     * broadcast_messages_to_all_clients_queue (fifo) queue.
     *
     * @param message
     */
    public void send_broadcast_message_to_all_clients(String message) {
        broadcast_messages_to_all_clients_queue.add(message);
    }

    private void on_all_clients_message_received(
            final Publication all_clients_publication, // Dimon: we simply pass "publication" to know where to reply (if needed).. The "official params" are the next 4 (buffer, offset, length, header)
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header) {
        final String message
                = MessagesHelper.parseMessageUTF8(buffer, offset, length);

        final String session_name
                = Integer.toString(header.sessionId());
        final Integer session_boxed
                = Integer.valueOf(header.sessionId());

// Dimon: disabling extra layer of executor.execute() call here since this whole f-n "onAllClientsClientMessage()" is alrady called from main loop via executor.execute()
//        this.executor.execute(() -> {
        try {
            this.clients.onClientMessage(
                    all_clients_publication, // Dimon: we simply pass "publication" to know where to reply (if needed) and message is now a simple String.
                    session_name,
                    session_boxed,
                    message);
        } catch (final Exception e) {
            LOG.error("could not process client message: ", e);
        }
//        });
    }

    /**
     * Configure the publication for the "all-clients" channel.
     */
    private Publication setupAllClientsPublication() {
        return AeronChannelsHelper.createPublicationDynamicMDC(this.aeron,
                this.configuration.localAddress(),
                this.configuration.localInitialControlPort(),
                MAIN_STREAM_ID);
    }

    /**
     * Configure the subscription for the "all-clients" channel.
     */
    private Subscription setupAllClientsSubscription() {
        return AeronChannelsHelper.createSubscriptionWithHandlers(this.aeron,
                this.configuration.localAddress(),
                this.configuration.localInitialPort(),
                MAIN_STREAM_ID,
                this::onInitialClientConnected,
                this::onInitialClientDisconnected);
    }

    private void onInitialClientConnected(
            final Image image) {
        this.executor.my_call(() -> {
            LOG.debug(
                    "[{}] initial client connected ({})",
                    Integer.toString(image.sessionId()),
                    image.sourceIdentity());

            this.clients.onInitialClientConnected(
                    image.sessionId(),
                    IPAddressesHelper.extractAddress(image.sourceIdentity()));
            return 0; // this value does not really matter, we'll use Callable<Integer> to return number of received fragments and decide if we need to sleep or not in the main loop.
        });
    }

    private void onInitialClientDisconnected(
            final Image image) {
        this.executor.my_call(() -> {
            LOG.debug(
                    "[{}] initial client disconnected ({})",
                    Integer.toString(image.sessionId()),
                    image.sourceIdentity());

            this.clients.onInitialClientDisconnected(image.sessionId());
            return 0; // this value does not really matter, we'll use Callable<Integer> to return number of received fragments and decide if we need to sleep or not in the main loop.
        });
    }

    @Override
    public void close() {
        this.aeron.close();
        this.media_driver.close();
    }
}
