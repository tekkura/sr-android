# Communication Protocol

The Android phone and robot firmware communicate with a custom packet-based
protocol over USB serial.

## Packet Structure

Packets are framed with start and stop markers so the receiver can recover from
partial or malformed streams:

- `START` marker: `0xFE`
- Command type: 1 byte identifying the command.
- Payload size: 2 bytes, little endian.
- Payload: variable-length data.
- `STOP` marker: `0xFF`

## PacketBuffer API

`PacketBuffer` is the primary abstraction for parsing incoming serial data. It
manages the parser state for partial packets and multiple packets received in a
single transmission.

```kotlin
val packetBuffer = PacketBuffer()

packetBuffer.consume(incomingBytes) { result ->
    when (result) {
        is PacketBuffer.ParseResult.ReceivedPacket -> {
            val command = result.command
            // Handle the parsed RP2040IncomingCommand.
        }
        is PacketBuffer.ParseResult.ReceivedErrorPacket -> {
            // Malformed packet or framing error. The parser tries to
            // resynchronize with the next START marker.
        }
        is PacketBuffer.ParseResult.Overflow -> {
            // Internal buffer limit reached. The parser clears its state and
            // waits for a fresh START marker.
        }
        is PacketBuffer.ParseResult.NotEnoughData -> {
            // Incomplete packet. The parser keeps current state and waits for
            // more bytes.
        }
    }
}
```
