# muxable
Java API for multiplexing multiple channels over a single parent channel using Java NIO

The **[muxable-api](https://archiecobbs.github.io/muxable/site/apidocs/index.html?org/dellroad/muxable/MuxableChannel.html)** module defines a API by which multiple independent "nested" channels are multiplexed over a single, bidirectional byte-oriented parent channel. The "nested" channels are first class NIO channels that can be used completely independently from each another, but they are scoped to the parent channel.

How exactly the API is implemented is up to individual implementations. Included is the **[muxable-simple](https://archiecobbs.github.io/muxable/site/apidocs/index.html?org/dellroad/muxable/simple/SimpleMuxableChannel.html)** module, which uses a simple framing protocol to map the nested channels onto a single underlying `ByteChannel` (e.g., a TCP connection). Other implementations are possible and envisioned, e.g., one based on **[QUIC](https://en.wikipedia.org/wiki/QUIC)**.

See the [Javadocs API](http://archiecobbs.github.io/muxable/site/apidocs/index.html) for more details.
