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
