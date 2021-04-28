/**
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.io7m.aeron_guide.take3;

import io.aeron.Aeron;
import io.aeron.Publication;
import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.agrona.BufferUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The majority of the interesting work that the server does is now performed by
 * a static inner class called ClientState. This class is responsible for
 * accepting requests from clients, checking access restrictions (such as
 * enforcing the limit on duologues by a single IP address), polling existing
 * duologues for activity, and so on. We establish a rule that access to the
 * ClientState class is confined to a single thread via the EchoServerExecutor
 * type. The EchoServer class defines three methods that each essentially
 * delegate to the ClientState class:
 * 
 */
public final class ClientState {
    
    private static final Logger LOG = LoggerFactory.getLogger(AeronMessagingServer.class);
    
    private static final Pattern PATTERN_HELLO
            = Pattern.compile("^HELLO ([0-9A-F]+)$");
    
    private final Map<Integer, InetAddress> client_session_addresses;  // Dimon: map stores session_id and remote address upon "onInitialClientConnected"
    private final Map<Integer, AeronMessagingServerDuologue> client_duologues;   // Dimon: map stores session_id and allocated EchoServerDuologue (with dedicated publication/subscription)
    private final EchoServerPortAllocator port_allocator;
    private final Aeron aeron;
    private final Clock clock;
    private final AeronMessagingServerConfiguration configuration;
    private final UnsafeBuffer send_buffer;
    private final AeronMessagingServerExecutorService executor;
    private final EchoServerAddressCounter address_counter;
    private final EchoServerSessionAllocator session_allocator;
    private final ConcurrentLinkedQueue<String> incoming_messages_from_all_clients_queue;  // reference to the "inbox" queue for "all-clients" channel
    
    ClientState(
            final Aeron in_aeron,
            final Clock in_clock,
            final AeronMessagingServerExecutorService in_executor,
            final AeronMessagingServerConfiguration in_configuration,
            final ConcurrentLinkedQueue<String> in_incoming_messages_from_all_clients_queue) {  // server passes us a reference to "inbox" queue for "all-clients" channel
        this.aeron
                = Objects.requireNonNull(in_aeron, "Aeron");
        this.clock
                = Objects.requireNonNull(in_clock, "Clock");
        this.executor
                = Objects.requireNonNull(in_executor, "Executor");
        this.configuration
                = Objects.requireNonNull(in_configuration, "Configuration");
        this.incoming_messages_from_all_clients_queue
                = Objects.requireNonNull(in_incoming_messages_from_all_clients_queue, "incoming_messages_from_all_clients_queue");
        
        this.client_duologues = new HashMap<>(32);
        this.client_session_addresses = new HashMap<>(32);
        
        this.port_allocator
                = EchoServerPortAllocator.create(
                        this.configuration.localClientsBasePort(),
                        2 * this.configuration.clientMaximumCount());
        
        this.address_counter
                = EchoServerAddressCounter.create();
        
        this.session_allocator
                = EchoServerSessionAllocator.create(
                        AeronMessagingServerSessions.RESERVED_SESSION_ID_LOW,
                        AeronMessagingServerSessions.RESERVED_SESSION_ID_HIGH,
                        new SecureRandom());
        
        this.send_buffer
                = new UnsafeBuffer(BufferUtil.allocateDirectAligned(1024, 16));
    }
    
    private static String connectMessage(
            final String session_name,
            final int port_data,
            final int port_control,
            final String session) {
        return new StringBuilder(64)
                .append("CONTROL ")
                .append(session_name)
                .append(" CONNECT ")
                .append(port_data)
                .append(" ")
                .append(port_control)
                .append(" ")
                .append(session)
                .toString();
    }
    
    private static String errorMessage(
            final String session_name,
            final String message) {
        return new StringBuilder(64)
                .append(session_name)
                .append(" ERROR ")
                .append(message)
                .toString();
    }
    
    void onClientMessage(
            final Publication publication,
            final String session_name,
            final Integer session_boxed,
            final String message)
            throws AeronMessagingServerException, IOException {
        this.executor.assertIsExecutorThread();

        // Dimon: check if this is the 1st client message (by checking if duolog is already assigned by given session_id)
        if (this.client_duologues.keySet().contains(session_boxed)) {
            // Dimon: this client already has duolog, so this isn't initial message
            on_all_cilents_message_received(
                    publication,
                    session_name,
                    session_boxed,
                    message
            );
        } else {
            // Dimon: this is client's 1st message - pass it to the onInitialClientMessageProcess()
            // to check some limits and to allocate a new EchoServerDuologue for this client.
            on_initial_all_cilents_message_received(
                    publication,
                    session_name,
                    session_boxed,
                    message);
        }
    }
    
    void on_all_cilents_message_received(
            final Publication publication,
            final String session_name,
            final Integer session_boxed,
            final String message)
            throws AeronMessagingServerException, IOException {
        LOG.debug("debug: +++ client send another message into the 'all clients' channel: " + message);

// The queues will wait till "take4", which will be no longer echo client/server, but more generic message bus (no regexps on each message - too expensive!)
//        // enqueue into server.incoming_messages_from_all_clients
//        this.incoming_messages_from_all_clients_queue.add(message);
    }
    
    void on_initial_all_cilents_message_received(
            final Publication publication,
            final String session_name,
            final Integer session_boxed,
            final String message)
            throws AeronMessagingServerException, IOException {
        this.executor.assertIsExecutorThread();
        
        LOG.debug("[session: {}] received: {}", session_name, message);

        /**
         * The HELLO command is the only acceptable 1st message from clients on
         * the all-clients channel.
         */
        final Matcher hello_matcher = PATTERN_HELLO.matcher(message);
        if (!hello_matcher.matches()) {
            MessagesHelper.sendMessage(
                    publication,
                    this.send_buffer,
                    errorMessage(session_name, "bad message"));
            return;
        }

        /**
         * Check to see if there are already too many clients connected.
         */
        if (this.client_duologues.size() >= this.configuration.clientMaximumCount()) {
            LOG.debug("server is full");
            MessagesHelper.sendMessage(
                    publication,
                    this.send_buffer,
                    errorMessage(session_name, "server full"));
            return;
        }

        /**
         * Check to see if this IP address already has the maximum number of
         * duologues allocated to it.
         */
        final InetAddress owner
                = this.client_session_addresses.get(session_boxed);
        
        if (this.address_counter.countFor(owner)
                >= this.configuration.maximumConnectionsPerAddress()) {
            LOG.debug("too many connections for IP address");
            MessagesHelper.sendMessage(
                    publication,
                    this.send_buffer,
                    errorMessage(session_name, "too many connections for IP address"));
            return;
        }

        /**
         * Parse the one-time pad with which the client wants the server to
         * encrypt the identifier of the session that will be created.
         */
        final int duologue_key
                = Integer.parseUnsignedInt(hello_matcher.group(1), 16);

        /**
         * Allocate a new duologue, encrypt the resulting session ID, and send a
         * message to the client telling it where to find the new duologue.
         */
        final AeronMessagingServerDuologue duologue
                = this.allocateNewDuologue(session_name, session_boxed, owner);
        
        final String session_crypt
                = Integer.toUnsignedString(duologue_key ^ duologue.session(), 16)
                        .toUpperCase();
        
        MessagesHelper.sendMessage(
                publication,
                this.send_buffer,
                connectMessage(
                        session_name,
                        duologue.portData(),
                        duologue.portControl(),
                        session_crypt));
    }
    
    private AeronMessagingServerDuologue allocateNewDuologue(
            final String session_name,
            final Integer session_boxed,
            final InetAddress owner)
            throws
            EchoServerPortAllocationException,
            EchoServerSessionAllocationException {
        this.address_counter.increment(owner);
        
        final AeronMessagingServerDuologue duologue;
        try {
            final int[] ports = this.port_allocator.allocate(2);
            try {
                final int session = this.session_allocator.allocate();
                try {
                    duologue
                            = AeronMessagingServerDuologue.create(
                                    this.aeron,
                                    this.clock,
                                    this.executor,
                                    this.configuration.localAddress(),
                                    owner,
                                    session,
                                    ports[0],
                                    ports[1]);
                    LOG.debug("[{}] created new duologue", session_name);
                    this.client_duologues.put(session_boxed, duologue);
                } catch (final Exception e) {
                    this.session_allocator.free(session);
                    throw e;
                }
            } catch (final EchoServerSessionAllocationException e) {
                this.port_allocator.free(ports[0]);
                this.port_allocator.free(ports[1]);
                throw e;
            }
        } catch (final EchoServerPortAllocationException e) {
            this.address_counter.decrement(owner);
            throw e;
        }
        return duologue;
    }
    
    void onInitialClientDisconnected(
            final int session_id) {
        this.executor.assertIsExecutorThread();
        
        this.client_session_addresses.remove(Integer.valueOf(session_id));
    }
    
    void onInitialClientConnected(
            final int session_id,
            final InetAddress client_address) {
        this.executor.assertIsExecutorThread();
        
        LOG.debug("debug: +++ onInitialClientConnected: session_id=" + session_id + ", client_address=" + client_address);  // Dimon: just wonder to see the session while many cilents talking to the server

        this.client_session_addresses.put(
                Integer.valueOf(session_id), client_address);
    }

    /**
     * Dimon: this is kinda useless f-n, which iterates all connected clients
     * and send them a "private" message (not seen by other clients). Instead of
     * this waste of CPU cycles we'll reuse server's "all_clients_publication"
     * channel. It uses MDC (multi-destination-cast) to satisfy clients behind
     * NAT, and by it's nature it will broadcast 1 outgoing message to all the
     * connected clients for us!
     *
     * @param msg
     */
    public void sent_private_message_to_all_clients(String msg) {
        final Iterator<Map.Entry<Integer, AeronMessagingServerDuologue>> iter
                = this.client_duologues.entrySet().iterator();
        
        while (iter.hasNext()) {
            final Map.Entry<Integer, AeronMessagingServerDuologue> entry = iter.next();
            final AeronMessagingServerDuologue duologue = entry.getValue();
            try {
                duologue.send_private_message_to_client(msg);
                System.err.println("+++ debug: server sending private msg to client");
            } catch (Exception ex) {
                System.err.println("Exception while trying to send_message_to_client: " + ex);
            }
        }
    }
    
    public int poll() {
        this.executor.assertIsExecutorThread();
        
        final Iterator<Map.Entry<Integer, AeronMessagingServerDuologue>> iter
                = this.client_duologues.entrySet().iterator();

        /**
         * Get the current time; used to expire duologues.
         */
        final Instant now = this.clock.instant();
        
        // Let's keep track on total sum of all poll'ed fragmetns
        int polled_fragments_count = 0;
        
        while (iter.hasNext()) {
            final Map.Entry<Integer, AeronMessagingServerDuologue> entry = iter.next();
            final AeronMessagingServerDuologue duologue = entry.getValue();
            
            final String session_name
                    = Integer.toString(entry.getKey().intValue());

            /**
             * If the duologue has either been closed, or has expired, it needs
             * to be deleted.
             */
            boolean delete = false;
            if (duologue.isExpired(now)) {
                LOG.debug("[{}] duologue expired", session_name);
                delete = true;
            }
            
            if (duologue.isClosed()) {
                LOG.debug("[{}] duologue closed", session_name);
                delete = true;
            }
            
            if (delete) {
                try {
                    duologue.close();
                } finally {
                    LOG.debug("[{}] deleted duologue", session_name);
                    iter.remove();
                    this.port_allocator.free(duologue.portData());
                    this.port_allocator.free(duologue.portControl());
                    this.address_counter.decrement(duologue.ownerAddress());
                }
                continue;
            }

            /**
             * Otherwise, poll the duologue for activity.
             */
            polled_fragments_count += duologue.poll();
        }
        return polled_fragments_count;
    }
}
