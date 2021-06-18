package com.io7m.aeron_guide.take4.aeron_messaging.server;

import com.io7m.aeron_guide.take4.aeron_messaging.common.MessagesHelper;
import com.io7m.aeron_guide.take4.aeron_messaging.common.AeronChannelsHelper;
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
import java.time.Clock;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;

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
 * This "AeronMessagingServer" is kinda "take3" and then "take4" - a set of small modifications
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
 *   - is adding all received messages into concurrent containers ConcurrentLinkedDeque.
 *     See details on Queue: https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/Queue.html
 *     In particular ConcurrentLinkedDeque: https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/concurrent/ConcurrentLinkedDeque.html
 * </pre>
 */
public final class AeronMessagingServer implements Closeable, Runnable {

    // We end up using just one stream ID.
    // Aeron is capable of multiplexing several independent streams of messages into a single connection,
    // but let's use it just as a bus and we'll discrementate different types of messages by their payload
    // (for examle we might inject message type as 1st N bytes into the byte buffer transfered to the other end).
    public static final int MAIN_STREAM_ID;

    private static final Logger LOG = LoggerFactory.getLogger(AeronMessagingServer.class);

    static {
        // We also specify a stream ID when creating the subscription. Aeron is capable of
        // multiplexing several independent streams of messages into a single connection.
        MAIN_STREAM_ID = 0x100500ff; // must be the save value on client and server
    }

    private final MediaDriver media_driver;
    private final Aeron aeron;
    private final AeronMessagingServerExecutorService executor; // Dimon: this type is an i-face, which extends AutoCloseable, Executor
    private final ClientsStateTracker clients_state_tracker;
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

        this.clients_state_tracker
                = new ClientsStateTracker(
                        this.aeron,
                        Objects.requireNonNull(in_clock, "clock"),
                        this.executor,
                        this.configuration,
                        this.incoming_messages_from_all_clients_queue);
    }

    // We have 4 queues: 2 outgoing queues (private and broadcast) and 2 corresponding incoming queues.
    // See details on Queue:
    // https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/Queue.html
    // In particular ConcurrentLinkedDeque:
    // https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/util/concurrent/ConcurrentLinkedDeque.html
    //
    private final ConcurrentLinkedDeque<String> outgoing_messages_to_all_clients_queue = new ConcurrentLinkedDeque();
    private final ConcurrentLinkedDeque<String> incoming_messages_from_all_clients_queue = new ConcurrentLinkedDeque();

    //////////////////////////////// public methods to send a message to the server (enqueue messages to be sent to the server) //////////////////////
    // simply synonym / alias  for "send_all_clients_control_channel_message(message)"
    public boolean send_message(String message) {
        // Check if at least 1 client is connected
        if (this.get_number_of_connected_clients() == 0) {
            // no clients connected, no point of enqueueing the message
            return false;
        }

        return send_all_clients_control_channel_message(message);
    }

    public boolean send_private_message(String message, int session_id) {
        // Check if at least 1 client is connected
        if (this.get_number_of_connected_clients() == 0) {
            // no clients connected, no point of enqueueing the message
            return false;
        }

        return this.clients_state_tracker.enqueue_private_message_to_client_by_session_id(message, session_id);  // we should enqueue
        // return this.clients_state_tracker.send_private_message_to_client_by_session_id(message, session_id);   // but we can bypass thequeue and send (not sure if this is useful at all)        
    }

    // "control" aka "broadcast" or "all-clients" channel
    public boolean send_all_clients_control_channel_message(String message) {
        // Check if at least 1 client is connected
        if (this.get_number_of_connected_clients() == 0) {
            // no clients connected, no point of enqueueing the message
            return false;
        }

        this.outgoing_messages_to_all_clients_queue.add(message);
        return true;
    }

    //////////////////////////////// public methods to get a message to the server (dequeue already received messages) //////////////////////
    /**
     * Simply synonym / alias for "get_all_clients_control_channel_message()"
     *
     * @return message from corresponding queue or null if no messages in queue
     */
    public String get_message() {
        return get_all_clients_control_channel_message();
    }

    /**
     * Basically dequeue incoming messages from given client state tracking
     * object.
     *
     * @param session_id
     * @return message as String or null if queue is empty (no messages).
     */
    public String get_private_message(int session_id) {
        return this.clients_state_tracker.get_private_message_by_session_id(session_id);
    }

    /**
     *
     * @return
     */
    public Enumeration<Integer> list_connected_clients_session_ids() {
        return this.clients_state_tracker.list_connected_clients_session_ids();
    }

    public String get_all_clients_control_channel_message() {
        if (!incoming_messages_from_all_clients_queue.isEmpty()) {
            return incoming_messages_from_all_clients_queue.poll();
        }
        return null;
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
     * Let's move aeron_messaging_server_thread into the class members since
     * we'll need the reference to that thread if/when server.close() is called
     * (for example we're shutting down the framework and need to send .close()
     * to all it's parts/components).
     *
     */
    private Thread aeron_messaging_server_thread = null;

    /**
     * Let's say server.is_alive == true only when server's messaging thread
     * is_alive.
     *
     * @return
     */
    public boolean is_alive() {
        return aeron_messaging_server_thread != null && aeron_messaging_server_thread.isAlive();
    }

    /**
     * The main() f-n is a simple example on how to instantiate and run the
     * AeronMessagingServer class in its own thread. It is not an "entry point"
     * of the class and is here only for demo purposes. The actual "entry point"
     * is the run() method, which will be engaged when you call thread.start()
     * (see main() for details).
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

        // Parse command-line args
        final Path directory = Paths.get(args[0]);
        final InetAddress local_address = InetAddress.getByName(args[1]);
        final int local_initial_data_port = Integer.parseUnsignedInt(args[2]);
        final int local_initial_control_port = Integer.parseUnsignedInt(args[3]);
        final int local_clients_base_port = Integer.parseUnsignedInt(args[4]);
        final int client_count = Integer.parseUnsignedInt(args[5]);

        // Create AeronMessagingServerConfiguration object
        final AeronMessagingServerConfiguration configuration
                = ImmutableAeronMessagingServerConfiguration.builder()
                        .baseDirectory(directory)
                        .localAddress(local_address)
                        .localInitialPort(local_initial_data_port)
                        .localInitialControlPort(local_initial_control_port)
                        .localClientsBasePort(local_clients_base_port)
                        .clientMaximumCount(client_count)
                        .maximumConnectionsPerAddress(3)
                        .build();

        // Start aeron_messaging_server in it's own thread, we'll use it's public methods to enqueue (send) / dequeue (receive) messages
        final AeronMessagingServer aeron_messaging_server = create(Clock.systemUTC(), configuration);
        aeron_messaging_server.aeron_messaging_server_thread = new Thread(aeron_messaging_server);
        aeron_messaging_server.aeron_messaging_server_thread.start();

        //
        // Main consumer loop (it uses aeron_messaging_server to send receive messages from the client(s))
        //
        LOG.debug("Main thread keep chaga away while AeronMessagingClient is working in it's own separate thread...");
        // Some activity on the main thread, which shovels/sends messages from/to aeron_messaging_client

        long total_incoming_public_messages_count = 0;
        long total_incoming_private_messages_count = 0;
        long iteration_incoming_public_messages_count = 0;
        long iteration_incoming_private_messages_count = 0;

        Clock clock = Clock.systemUTC();
        long last_stats_sent_epoch_ms = 0; // keep track of exact ms when report was generated to avoid sending it multiple times (loop is fast)
        Instant main_loop_start_instant = clock.instant();
        long main_loop_start_epoch_ms = main_loop_start_instant.toEpochMilli();
        long main_loop_iterations_count = 0;
        // Sometimes we can't read just 1 incoming message in 1 main loop iteration since
        // it might fundamentally limit the throughput of our application (for example the main
        // loop of our app does lots of other things, so processing only one message per one
        // iteration can be too cpu-expensive).
        // On the other hand we can't process ALL incoming messages, since the queue might
        // be already "too full" and a new messages stream is constantly coming thus we would be blocked
        // at this attempt to process all the imcoming messages at once.
        // As a solution let's introduce new variable: "number_of_messages_to_read_in_one_go_limit",
        // which would allow us to limit the max number of messages to process in one main loop iteration.
        // Reasonable values would be a couple hundreds (unless your messages are some huge JSON objects,
        // in which case you probably want to make this value down to a couple dosen). Play with it.
        int number_of_messages_to_read_in_one_go_limit = 100;

        // This "main loop" mimicking the demo application (aka "consumer loop"), that created AeronMessagingServer instance.
        while (true) {
            main_loop_iterations_count++;

            // Get current iteration time
            Instant now_instant = clock.instant();
            long now_epoch_ms = now_instant.toEpochMilli();

            // Per-iteration counts
            iteration_incoming_public_messages_count = 0;
            iteration_incoming_private_messages_count = 0;

            // Try to get public-channel (all-clients) message
            String incoming_public_message = aeron_messaging_server.get_all_clients_control_channel_message();
            if (incoming_public_message != null) {
                total_incoming_public_messages_count++;
                iteration_incoming_public_messages_count++;
            }

            // Try to get private (per agent channels) messages
            Enumeration<Integer> clients_session_ids = aeron_messaging_server.list_connected_clients_session_ids();
            while (clients_session_ids.hasMoreElements()) {
                // Get i-th connected client session_id
                Integer client_session_id = clients_session_ids.nextElement();

                // Count how many private messages we've got from this particular
                // session_id during current main loop iteration. We need to know
                // this count to be able to track number_of_messages_to_read_in_one_go_limit.
                long ith_session_private_message_iteration_count = 0;

                // Try to read N private messages from i-th connected client (by given session_id)
                // Keep shoveling N private messages from this session_id until
                // we read them all or until we hit the limit (number_of_messages_to_read_in_one_go_limit)
                String private_message = aeron_messaging_server.get_private_message(client_session_id);
                while (private_message != null && ith_session_private_message_iteration_count < number_of_messages_to_read_in_one_go_limit) {
                    ith_session_private_message_iteration_count++;

                    // For better stats, let's consider server's main_loop_start_epoch_ms when we got 1st private message from the client.
                    // TODO: redo the whole stats collection logic, so it works properly even if the server is running for few weeks and
                    // the client just connected. The messages rate calculation can not rely on the server lifetime number of seconds...
                    if (total_incoming_private_messages_count == 0) {
                        // First ever private message received!
                        main_loop_start_instant = clock.instant();
                        main_loop_start_epoch_ms = main_loop_start_instant.toEpochMilli();
                    }

                    total_incoming_private_messages_count++;
                    iteration_incoming_private_messages_count++;

                    // Get the next message, unitll we whovel them all to zero:
                    private_message = aeron_messaging_server.get_private_message(client_session_id);
                }
            }

            // Only sleep 1ms in case we haven't received any messages, otherwise it is a "busy loop"
            if (iteration_incoming_public_messages_count == 0 && iteration_incoming_private_messages_count == 0) {
                Thread.sleep(1);
                // Main thread checks periodically if messaging thread is still runnig.

                // Check if aeron_messaging_thread is still running
                // OR shall we use aeron_messaging_thread.getState() ?
                if (!aeron_messaging_server.aeron_messaging_server_thread.isAlive()) {
                    break;
                }
            }

            // Log "main loop stats" once in a while
            if (now_epoch_ms > last_stats_sent_epoch_ms + 5000
                    && last_stats_sent_epoch_ms != now_epoch_ms) {
                // Time to generate some stats on the "consumer main loop"
                long main_loop_run_duration_ms = now_epoch_ms - main_loop_start_epoch_ms + 1;  // +1 is a cheesy way to avoid /0 and it won't matter after few seconds run

                long average_rx_private_messages_per_s = total_incoming_private_messages_count * 1000 / main_loop_run_duration_ms;
                long main_loop_iterations_rate_per_ms = main_loop_iterations_count / main_loop_run_duration_ms;

                LOG.debug("Main consumer loop: thread: " + Thread.currentThread().toString()
                        + " total_incoming_public_messages_count: " + total_incoming_public_messages_count
                        + " total_incoming_private_messages_count: " + total_incoming_private_messages_count
                        + " main_loop_iterations_rate_per_ms: " + main_loop_iterations_rate_per_ms
                        + " average_rx_private_messages_per_s: " + average_rx_private_messages_per_s + " <--- !!!"
                );
                last_stats_sent_epoch_ms = now_epoch_ms;
            }
        }
        LOG.debug("AeronMessagingServer.main(): main loop is complete. That's all falks!");
        // Need to close Aeron and MediaDriver, otherwise the program will not terminate.
        aeron_messaging_server.close();

    }

    public int get_number_of_connected_clients() {
        return this.clients_state_tracker.get_number_of_connected_clients();
    }

    // Run the server, returning when the server is finished.
    //
    public void run() {
        try (final Publication all_clients_publication = this.setupAllClientsPublication()) {
            try (final Subscription all_clients_subscription = this.setupAllClientsSubscription()) {

                final FragmentHandler all_client_subscription_message_handler
                        = new FragmentAssembler(
                                (buffer, offset, length, header)
                                -> this.on_all_clients_message_received(
                                        all_clients_publication,
                                        buffer,
                                        offset,
                                        length,
                                        header));

                // 
                // Main loop (server side, inside aeron_messaging_thread):
                //   - polling messages from subscriptions (both "all-client" subscription and all "per each agent" subscriptions)
                //   - sending outbound messages by shovelling outgoing_messages_to_all_clients_queue
                //   - every 10 sec send PUBLISH message via all_clients_publication with server stats
                //
                Clock clock = Clock.systemUTC();
                long total_messages_sent_count = 0;
                long total_messages_sent_size = 0;
                long polled_fragmetns_total_count = 0;
                long failed_to_send_count = 0;
                UnsafeBuffer tmp_send_buffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));
                Instant main_loop_start_instant = clock.instant();
                long main_loop_start_epoch_ms = main_loop_start_instant.toEpochMilli();
                long main_loop_iterations_count = 0;
                long stats_report_count = 0;
                long last_stats_sent_epoch_ms = 0; // keep track of exact ms when report was generated to avoid sending it multiple times (loop is fast)

                LOG.debug("Server is ready and is waiting for clients to connect...");

                while (true) {
                    main_loop_iterations_count++;

                    // Get current time in 2 forms: Instant and epoch_ms (one will be used for human-readable time, other for "robots":)
                    Instant now_instant = clock.instant();
                    long now_epoch_ms = now_instant.toEpochMilli();

                    // Try to poll messages from both "all clients" and private "per client" channels.
                    // Use executor thread (not Aeron thread).
                    Future<Integer> future_polled_fragments_count = this.executor.my_call(() -> {

                        // Poll messages from "all_clients" channel
                        Integer polled_fragmetns_count = all_clients_subscription.poll(all_client_subscription_message_handler, 100);

                        // Iterate all client_duologues and duologue.poll() on each of them (basically private_subscription.poll())
                        polled_fragmetns_count += this.clients_state_tracker.poll();

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
                    // Instead of waiting "while(future.isDone()" we can simply "future.get()", which is blocking (untill lambda completes
                    Integer polled_fragments_count = 0;
                    try {
                        // Blocking call! Throws InterruptedException, ExecutionException
                        polled_fragments_count = future_polled_fragments_count.get();
                        // Collecting some stats
                        polled_fragmetns_total_count += polled_fragments_count;
//                    } catch (final InterruptedException e) {
                    } catch (final Exception ex) {
                        Thread.currentThread().interrupt();
                        LOG.error("Main loop: unexpected exception while waiting for future_polled_fragments_count. Details: " + ex);
                    }

                    // Now try to shovel our 2 types of our "OUTBOX" queues: 1-of-2) outgoing_messages_to_all_clients_queue
                    int current_iteration_messages_sent_count = 0;
                    if (!outgoing_messages_to_all_clients_queue.isEmpty() && get_number_of_connected_clients() > 0) {
                        String outgoing_message = outgoing_messages_to_all_clients_queue.poll();   // Queue.poll() - Retrieves and removes the head of this queue, or returns null if this queue is empty.
                        try {
                            // Try to send the message.
                            // This might throw: java.io.IOException: Could not send message: Error code: Back pressured
                            MessagesHelper.send_message(
                                    all_clients_publication,
                                    tmp_send_buffer,
                                    outgoing_message
                            );

                            // Successfully sent message, increase some stats
                            total_messages_sent_count++;
                            current_iteration_messages_sent_count++;
                            total_messages_sent_size += outgoing_message.length();

                        } catch (IOException ex) {
                            // Failed to send the message, put it back into the front of the queue
                            outgoing_messages_to_all_clients_queue.addFirst(outgoing_message);
                            // Increase "failed_to_send_count" stats
                            failed_to_send_count++;
                        }
                    }

                    // Try to send messages from the private "per client" queues (located inside corresponding duologue instance)
                    // Use executor thread (not Aeron thread).
                    Future<Integer> future_sent_private_messages_count = this.executor.my_call(() -> {

                        // Iterate all client_duologues and duologue.poll() on each of them (basically private_subscription.poll())
                        Integer sent_private_messages_count = this.clients_state_tracker.send_enqueued_messages();
                        return sent_private_messages_count;
                    });

                    // Instead of waiting "while(future.isDone()" we can simply "future.get()", which is blocking (untill lambda completes
                    Integer sent_private_messages_count = 0;
                    try {
                        // Blocking call! Throws InterruptedException, ExecutionException
                        sent_private_messages_count = future_sent_private_messages_count.get();
                        // Collecting some stats
                        total_messages_sent_count += sent_private_messages_count;
//                    } catch (final InterruptedException e) {
                    } catch (final Exception ex) {
                        Thread.currentThread().interrupt();
                        LOG.error("Main loop: unexpected exception while waiting for future_sent_private_messages_count. Details: " + ex);
                    }

                    // Every 10 sec send PUBLISH stats message via all_clients_publication with some messaging-server stats.
                    // Only if at least 1 client is connected.
                    if (now_epoch_ms > last_stats_sent_epoch_ms + 15000
                            // && all_clients_publication.isConnected()  - let's pring stats even when no clients connected, just for debug, and we'll .send_message() only if anybody connected
                            && last_stats_sent_epoch_ms != now_epoch_ms) {
                        // Calculate some stats
                        long main_loop_run_duration_ms = now_epoch_ms - main_loop_start_epoch_ms + 1;  // +1 is a cheesy way to avoid /0 and it won't matter after few seconds run
                        long average_tx_message_rate_per_s = total_messages_sent_count * 1000 / main_loop_run_duration_ms;
                        long average_tx_bytes_rate_per_ms = total_messages_sent_size / main_loop_run_duration_ms;
                        long average_rx_fragments_rate_per_ms = polled_fragmetns_total_count / main_loop_run_duration_ms;
                        long main_loop_iterations_rate_per_ms = main_loop_iterations_count / main_loop_run_duration_ms;
                        last_stats_sent_epoch_ms = now_epoch_ms;
                        stats_report_count++;

                        try {

                            String server_stats = "{\"mime_type\": \"server/stats\", "
                                    + "\"stats_report_count\": " + stats_report_count + ", "
                                    + "\"sent_messages_count\": " + total_messages_sent_count + ", "
                                    + "\"sent_messages_size\": " + total_messages_sent_size + ", "
                                    // This might be terribly slow on large gueues since it will have to walk through the whole chain of objects in the queue                                        
                                    //                                        + "\"incoming_messages_from_all_clients_queue_size\": " + incoming_messages_from_all_clients_queue.size() + ", "
                                    + "\"polled_fragmetns_total_count\": " + polled_fragmetns_total_count + ", "
                                    + "\"failed_to_send_count\": " + failed_to_send_count + ", "
                                    + "\"main_loop_run_duration_ms\": " + main_loop_run_duration_ms + ", "
                                    + "\"average_tx_message_rate_per_s\": " + average_tx_message_rate_per_s + ", "
                                    + "\"average_tx_bytes_rate_per_ms\": " + average_tx_bytes_rate_per_ms + ", "
                                    + "\"average_rx_fragments_rate_per_ms\": " + average_rx_fragments_rate_per_ms + ", "
                                    + "\"main_loop_iterations_rate_per_ms\": " + main_loop_iterations_rate_per_ms + ", "
                                    + "\"timestamp_epoch_ms\": " + now_epoch_ms + ", "
                                    + "\"timestamp\": " + now_instant.toString()
                                    + "}";

                            // Print stats to the terminal even if nobody connected
                            LOG.debug("SERVER STATS: " + server_stats);

                            // Send the stats message to the client, only if at least 1 client is connected
                            if (all_clients_publication.isConnected()) {
                                // We're bypassing the outgoing queue and directly "submit()" message into the ExecutorService here.
                                // We could do both - .sendMessage() and inject it into the queue, so the receiving side (client) 
                                // will get server stats and can track what is the lag on that particular queue shovelling.
                                // For example if we have 100 messages in the outgoing queue, then it will take 100 "main loop" iterations
                                // to get to that enqueued message.
                                // The epoch_ms can be used as a "key" to match the two messages on the client side.
                                MessagesHelper.send_message(
                                        all_clients_publication,
                                        tmp_send_buffer,
                                        server_stats
                                );
                                // Successfully sent message, increase some stats
                                total_messages_sent_count++;
                                total_messages_sent_size += server_stats.length();
                                current_iteration_messages_sent_count++;

//                                // Let's actually do it!
//                                this.send_all_clients_control_channel_message(server_stats);
//                                // Alternatively we could iterate all private connections and send message to all clients
//                                // using: clients_state_tracker.sent_private_message_to_all_clients(server_stats),
//                                // which would deliver the message to all clients once, but via different channel
//                                // Successfully sent message, increase some stats
//                                total_messages_sent_count++;
//                                total_messages_sent_size += server_stats.length();
//                                current_iteration_messages_sent_count++;
                            }

                        } catch (IOException ex) {
                            LOG.error("Exception while trying to sendMessage() via all_clients_publication. Details: ", ex);
                        }
                    }

                    // Previous polling and sending complete. Check if we got any fragments received or messaes sent,
                    // then run next itaration w/o extra delay.
                    if ((polled_fragments_count + current_iteration_messages_sent_count) == 0) {
                        // We got no fragments from subscription. Let's fall asleep for 1ms
                        try {
                            Thread.sleep(1L);
                            // shall we sleep even longer (say 10ms) if all_clients_publication.isConnected() == false? (means no any clients connected)
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
     * Inserts the specified element at the tail of
     * outgoing_messages_to_all_clients_queue (fifo) queue.
     *
     * @param message
     */
    public void send_broadcast_message_to_all_clients(String message) {
        outgoing_messages_to_all_clients_queue.add(message);
    }

    private void on_all_clients_message_received(
            final Publication all_clients_publication, // Dimon: we simply pass "publication" to know where to reply (if needed).. The "official params" are the next 4 (buffer, offset, length, header)
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header) {
        final String message
                = MessagesHelper.parse_message_utf8(buffer, offset, length);

        final String session_name
                = Integer.toString(header.sessionId());
        final Integer session_boxed
                = Integer.valueOf(header.sessionId());

        try {
            this.clients_state_tracker.onClientMessage(
                    all_clients_publication, // Dimon: we simply pass "publication" to know where to reply (if needed) and message is now a simple String.
                    session_name,
                    session_boxed,
                    message);
        } catch (final Exception ex) {
            LOG.error("could not process client message: " + message + ". Exception details: ", ex);
        }
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

            this.clients_state_tracker.onInitialClientConnected(
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

            this.clients_state_tracker.onInitialClientDisconnected(image.sessionId());
            return 0; // this value does not really matter, we'll use Callable<Integer> to return number of received fragments and decide if we need to sleep or not in the main loop.
        });
    }

    @Override
    public void close() {
        // Interrupt aeron_messaging_server_thread
        try {
            if (this.aeron_messaging_server_thread != null) {
                this.aeron_messaging_server_thread.interrupt();
            }
        } catch (Exception ex) {
            LOG.error("AeronMessagignServer.close() somehow failed to interrupt aeron_messaging_server_thread... exception: " + ex);
        }

        // Try to close Aeron
        try {
            closeIfNotNull(this.aeron);
        } catch (Exception ex) {
            LOG.error("AeronMessagignServer.close() somehow failed to close aeron... exception: " + ex);
        }

        // Try to close MediaDriver
        try {
            closeIfNotNull(this.media_driver);
        } catch (Exception ex) {
            LOG.error("AeronMessagignServer.close() somehow failed to close media_driver... exception: " + ex);
        }
    }
}
