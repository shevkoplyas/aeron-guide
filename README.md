# aeron-guide
An Aeron tutorial

This project is a fork from the awesome Aeron guide created by Mark Raynsford, which you can find here: <br>
http://www.io7m.com/documents/aeron-guide/

In case io7m.com is not accessible here's the preserved version of that article: <br>
https://github.com/shevkoplyas/aeron-guide/tree/master/original_guide_by_Mark_Raynsford_preserved_here

Original Aeron guide GitHub page: <br>
https://github.com/io7m/aeron-guide

--------------------------------------------
# More details:

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
 The goal of "take4" is to make it generic bass to exchange messages between a server and arbitrary number (<100) clients
 under good load (max bandwidth yet to be determined after "take4" is compleete).
 
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
