# aeron-guide
An Aeron tutorial

[Short description]

This project is a fork from the awesome Aeron guide created by Mark Raynsford, which you can find here: <br>
http://www.io7m.com/documents/aeron-guide/

In case io7m.com is not accessible here's the preserved version of that article: <br>
https://github.com/shevkoplyas/aeron-guide/tree/master/original_guide_by_Mark_Raynsford_preserved_here

Original Aeron guide GitHub page: <br>
https://github.com/io7m/aeron-guide

-------------------------------------------------------------------------------

[Long description]

A mindlessly simple Echo server by Mark Raynsford was found here:
 http://www.io7m.com/documents/aeron-guide/
 https://github.com/io7m/aeron-guide.git
 
Mark's work is licensed under a Creative Commons Attribution 4.0 International License. (see README-LICENSE.txt)
 
The guide shows how to make simple echo client-server in 2 takes
take1 - is minimalistic code and then take2 is a bit more involved.
This "AeronMessagingServer" is kinda "take3" - a small modification
done to the main loop to make it able to shovel thousands of messages
per second. It also does not close initial "all client" connection,
so we end up with every client connected to the server with 4 channels:
   1) one-for-all publication (any published message will go to all connected clients)
   2) one-for-all subscription (any client can write a message to the server via that channel)
   3) "private" publication (server can send a message just to one particular client)
   4) "private" subscription (any client can use to send a message to the server, but there's  no difference between (4) and (2) so it is kinda redundant)
 
Also some steps were done to improve AeronMessagingServer integration into
other projects. In particular AeronMessagingServer is:
   - working on it's own thread
   - is accepting messages by exposed enqueue(String message) method
   - is adding all received messages into concurrent list(s)

Next "take4" added, which is no longer ECHO server, instead adding queues for all the incoming and outgoing messages.
The goal of "take4" is to make the whole aeron client/server as one of the transport classes for 
another project: "Delivery Service" https://github.com/shevkoplyas/delivery_service, which facilitates the messages exchange
between a delivery_service server part (all the instances on the "delivery_service" side can communicate by sending/receiving
messages) and arbitrary number of remote clients (<100) clients under good load (max bandwidth yet to be determined after
"take4" is complete, but it seems it can easily pump 60-70K small messages per second).
 
Update:
The "take4" now represent 2 main classes:
  1) AeronMessagingServer
  2) AeronMessagingClient

Both classes extend "Runnable" and supposed to be running in your projects in separate threads.
See AeronMessagingServer.main() and AeronMessagingClient.main() methods as just an example on
how to instantiate and run these 2 classes in their own threads and how to send/receive messages.

The AeronMessagingClient.main() function represents a busy loop, which is keep sending a message
to the server over and over via "private" channel and it runs on the "main thread" of the app.
Search for that loop in the AeronMessagingClient.java class and try to fine this comment line:
   // Stresstest: client keep sending messages to server from it's "main loop" by engaging aeron_messaging_client.send_private_message(msg) method.

Note: that loop has 1ms sleep during each iteration if there were no incoming messages received.
So in general, when there are no much of incoming messages from server to client, then the main loop
rate is sitting arount 1000 iterations per second. Now you can modify "how_many_messages_to_send",
which is the number of messages the client will enqueue by calling aeron_messaging_client.send_private_message(msg)
method (which is thread safe).


Beautiful ascii-art, which tells the whole story:
  _______________________                                               ______________________
 |                       |                                             |                      |
 |          subscription | <===== all-clients channel ================<| publication          |
 |                       |                                             |                      |
 |           publication | >===== all-clients channel ================>| subscription         |
 |                       |                                             |                      |
 | AeronMessagingClient  |                                             | AeronMessagingServer |
 |                       |                                             |                      |
 |          subscription | <===== private "per-client" channel =======<| publication          |
 |                       |                                             | session_id / client  |
 |                       |                                             |                      |
 |                       |                                             |                      |
 |                       |                                             |                      |
 |           publication | >===== private "per-client" channel =======>| subscription         |
 |                       |                                             | session_id / client  |
 |                       |                                             |                      |
 -------------------------                                             ------------------------

Anything the server sends via "all-clients" publication is going to be sent to all connected clients.
This is great for some cases, but not desirable for others.
Each connected client can send a message to the server's "all-client" subscription and it will not be
broadcasted by any means.
Each connected client have it's own "private / per-client" subscription and publication on the dedicated UDP ports
on the server and those 2 channels are used to only communicate between the server and this one particular client.
The server keeps track of all the successfully connected clients by the class ClientsStateTracker (see ConcurrentHashMap<Integer, AeronMessagingServerDuologue> client_duologues).
Each connected client got assigned unique session_id int value.

While testing different "stresstest" modes I found that when client/server are running on the same machine:
    8cores, 8G of RAM, Ubuntu 20.04.2 LTS, 
    java -version
        openjdk version "1.8.0_292"
        OpenJDK Runtime Environment (build 1.8.0_292-8u292-b10-0ubuntu1~20.04-b10)
        OpenJDK 64-Bit Server VM (build 25.292-b10, mixed mode)
    Aeron version 1.25.1
And client is sending to the server via "private" channel small (100 bytes) messages,
then everything works stable up to 125K messages per second! (way more than I need:)

Here some notes on other different "speeds" I tried and ~150K messages / second seems to be highest stable rate,
but of course things can be optimized alot (for example instead of treating each message as a String we could
use Simple Binary Encoding (SBE) and we can optimize lots and lots of things in the "take4":
            // Define how many messages to inject into the queue in 1 iteration. Note: we have ~1000 iterations / sec due to 1ms sleep.
//            int how_many_messages_to_send = 300; // ~100K messages per second: does not fly.. crushes something in Aeron driver
//            int how_many_messages_to_send = 200; // ~200K messages per second: starts then slip down to ~170-180K messages / second throughput, then network UDP shows spikes with pauses (instead of steady flat UDP bps/pps lines)
//            int how_many_messages_to_send = 160; // ~150K messages per second: works great for hours,   stable (20Kpps, 24.5MiBps = 196mbps shown by "bmon" v.4.0)
//            int how_many_messages_to_send = 125; // ~120K messages per second: works great for hours,   stable (16Kpps, 19MiBps = 152mbps shown by "bmon" v.4.0)
//            int how_many_messages_to_send = 110; // ~100K messages per second: works great > 7 hours straight, (13.5Kpps, 16MiBps = 128mbps shown by "bmon" v.4.0) no errors, no losses, bps/pps on "lo" i-face are perfect flat lines!


-------------------------------------------------------------------------------
[More details on installed Aeron, docs, versions, etc.]

# Docs on Aeron by all versions!
https://www.javadoc.io/doc/io.aeron/aeron-driver/1.12.0/io/aeron/Publication.html#isConnected--

# Keep your Aeron version up-to-date by periodically reviewing official Aeron releases:
https://github.com/real-logic/aeron/releases

For example today (30-Apr-2021) the latest release is "1.31.2" published on Feb 14, 2021,
but we use 1.8.2!

# To check which Aeron version you require as as dependency check pom.xml file.

# To check which Aeron version(s) maven have installed on your machine:
tree ~/.m2/repository/io/aeron
/home/dima/.m2/repository/io/aeron
├── aeron-agent
│   └── 1.8.2
│       ├── aeron-agent-1.8.2-all.jar
│       ├── aeron-agent-1.8.2-all.jar.sha1
│       ├── aeron-agent-1.8.2.pom
│       ├── aeron-agent-1.8.2.pom.sha1
│       └── _remote.repositories
├── aeron-client
│   └── 1.8.2
│       ├── aeron-client-1.8.2.jar
│       ├── aeron-client-1.8.2.jar.sha1
│       ├── aeron-client-1.8.2.pom
│       ├── aeron-client-1.8.2.pom.sha1
│       └── _remote.repositories
└── aeron-driver
    └── 1.8.2
        ├── aeron-driver-1.8.2.jar
        ├── aeron-driver-1.8.2.jar.sha1
        ├── aeron-driver-1.8.2.pom
        ├── aeron-driver-1.8.2.pom.sha1
        └── _remote.repositories

To bump up Aeron version we're using we need to edit our pom.xml in 3 places:
  <dependencies>
    <dependency>
      <groupId>io.aeron</groupId>
      <artifactId>aeron-client</artifactId>
      <version>1.8.2</version>
    </dependency>
    <dependency>
      <groupId>io.aeron</groupId>
      <artifactId>aeron-driver</artifactId>
      <version>1.8.2</version>
    </dependency>
    <dependency>
      <groupId>io.aeron</groupId>
      <artifactId>aeron-agent</artifactId>
      <version>1.8.2</version>
      <classifier>all</classifier>
    </dependency>


https://repo.maven.apache.org/maven2/
https://repo.maven.apache.org/maven2/io/aeron/aeron-client/

google: maven aeron-client

io.aeron » aeron-client - Maven Repository:
https://mvnrepository.com/artifact/io.aeron/aeron-client
click latest 1.31.2
Copy maven snippet:

<!-- https://mvnrepository.com/artifact/io.aeron/aeron-client -->
<dependency>
    <groupId>io.aeron</groupId>
    <artifactId>aeron-client</artifactId>
    <version>1.31.2</version>
    <type>pom</type>
</dependency>


Copy-paste maven snippets for all all 3 client, driver, agent:
https://mvnrepository.com/artifact/io.aeron/aeron-client/1.31.2
https://mvnrepository.com/artifact/io.aeron/aeron-driver/1.31.2
https://mvnrepository.com/artifact/io.aeron/aeron-agent/1.31.2

# When maven dependencies failing, one can flush cached maven repo packages and increase verbosity:
# Where -U will force update the repo, see: https://stackoverflow.com/a/50120807/7022062 for details.
# To see the full stack trace of the errors, re-run Maven with the -e switch.
# Re-run Maven using the -X switch to enable full debug logging.
# 
mvn -e -X -U clean install

1.32.x
1.32.0      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.32.0 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
Jan, 2021
1.31.x
1.31.2      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.31.2 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
Feb, 2021
1.31.1	
Nov, 2020
1.31.0	
Oct, 2020
1.30.x
1.30.0      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.30.0 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
Sep, 2020
1.29.x
1.29.0	
Jul, 2020
1.28.x
1.28.2      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.28.2 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
May, 2020
1.28.1	
May, 2020
1.28.0      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.28.0 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
May, 2020
1.27.x
1.27.0      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.27.0 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
Apr, 2020
1.26.x
1.26.0      <-- [ERROR] Failed to execute goal on project com.io7m.aeron-guide: Could not resolve dependencies for project com.io7m.aeron-guide:com.io7m.aeron-guide:jar:0.0.1: Could not find artifact io.aeron:aeron-agent:jar:all:1.26.0 in central (https://repo.maven.apache.org/maven2) -> [Help 1]
Mar, 2020
1.25.x
1.25.1      <--- compiles fine (*) our current version for now... better than 1.8.2 (Mar 2018)
Jan, 2020
1.25.0	
Jan, 2020
1.24.x      <--- compiles fine
1.24.0	
Nov, 2019
