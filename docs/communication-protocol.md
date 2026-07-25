# Android/RP2040 Communication Protocol version 1.1.0

This document is the canonical communication contract between the Android host
and the RP2040 firmware. The link uses
[TinyFrame](https://github.com/MightyPork/TinyFrame) framing over USB serial.

## Transport

The serial connection carries an unmodified binary byte stream using 8N1. It
must not perform CR/LF translation.

TinyFrame is configured as follows:

| Parameter         | Value            | Meaning                                 |
|:------------------|:-----------------|:----------------------------------------|
| `TF_ID_BYTES`     | `1`              | One-byte frame ID                       |
| `TF_LEN_BYTES`    | `2`              | Two-byte payload length                 |
| `TF_TYPE_BYTES`   | `1`              | One-byte command type                   |
| `TF_CKSUM_TYPE`   | `TF_CKSUM_CRC16` | CRC-16/ARC header and payload checksums |
| `TF_USE_SOF_BYTE` | `1`              | Start-of-frame byte enabled             |
| `TF_SOF_BYTE`     | `0x01`           | Start-of-frame value                    |

### Frame layout

| Offset    |             Size | Field          | Encoding                           |
|:----------|-----------------:|:---------------|:-----------------------------------|
| 0         |                1 | SOF            | `0x01`                             |
| 1         |                1 | Frame ID       | TinyFrame peer bit and sequence ID |
| 2         |                2 | Payload length | Unsigned, big-endian               |
| 4         |                1 | Command type   | See command table below            |
| 5         |                2 | Header CRC     | Unsigned, big-endian               |
| 7         |            `LEN` | Payload        | Command-specific                   |
| `7 + LEN` | 2 when `LEN > 0` | Payload CRC    | Unsigned, big-endian               |

A zero-payload frame is exactly 7 bytes long and has no payload CRC. A
non-empty frame is `9 + LEN` bytes long. TinyFrame does not use a stop marker.

### Checksums

Both checksums use CRC-16/ARC with polynomial `0x8005`, reflected input/output,
initial value `0x0000`, and no final XOR.

- Header CRC input is bytes 0 through 4: `SOF + ID + LEN + TYPE`. With
  `TF_USE_SOF_BYTE=1`, the SOF byte is included.
- Payload CRC input is the payload only.
- The resulting 16-bit checksum is written to the frame in big-endian order.

Frame fields and checksums are big-endian because they are TinyFrame fields.
This is independent of the byte order used by multi-byte values inside a
command payload.

### Frame IDs

The most significant ID bit identifies the TinyFrame peer. Android-originated
frames set this bit and use the lower seven bits as a sequence ID. The sequence
ID used by the Android sender increments modulo 127.

## Commands

The same type identifies a request and its corresponding response.

| ID     | Name               | Android request payload              | RP2040 response payload             |
|:-------|:-------------------|:-------------------------------------|:------------------------------------|
| `0x00` | `GET_LOG`          | Empty                                | ASCII log entries separated by `\n` |
| `0x02` | `RESET_STATE`      | Empty                                | `RP2040_STATE`                      |
| `0x03` | `GET_STATE`        | Empty                                | `RP2040_STATE`                      |
| `0x04` | `SET_MOTOR_LEVELS` | Two motor-control bytes: left, right | `RP2040_STATE`                      |
| `0x06` | `GET_VERSION`      | Empty                                | Three bytes: major, minor, patch    |
| `0xFC` | `NACK`             | Command-specific or empty            | Command-specific or empty           |
| `0xFD` | `ACK`              | Command-specific or empty            | Command-specific or empty           |

`0x01` is reserved for TinyFrame SOF and is not a command type. `0xFF` is not
a stop marker in this protocol.

## Payload encoding

Multi-byte values within structured payloads use little-endian byte order.
Single-byte payloads, ASCII data, and version components have no byte-order
conversion.

### `SET_MOTOR_LEVELS` request

| Offset | Size | Field                            |
|:-------|-----:|:---------------------------------|
| 0      |    1 | Left DRV8830 motor-control byte  |
| 1      |    1 | Right DRV8830 motor-control byte |

### `GET_VERSION` response

| Offset | Size | Field         |
|:-------|-----:|:--------------|
| 0      |    1 | Major version |
| 1      |    1 | Minor version |
| 2      |    1 | Patch version |

The payload must be exactly 3 bytes.

### `RP2040_STATE`

`RP2040_STATE` is a packed 29-byte payload.

| Offset | Size | Field                      | Encoding      |
|:-------|-----:|:---------------------------|:--------------|
| 0      |    1 | Motor control, left        | Byte          |
| 1      |    1 | Motor control, right       | Byte          |
| 2      |    1 | Motor fault bits, left     | Byte          |
| 3      |    1 | Motor fault bits, right    | Byte          |
| 4      |    4 | Encoder count, left        | Little-endian |
| 8      |    4 | Encoder count, right       | Little-endian |
| 12     |    2 | Battery voltage, mV        | Little-endian |
| 14     |    1 | Battery safety status      | Byte          |
| 15     |    2 | Battery temperature        | Little-endian |
| 17     |    1 | Battery state of health    | Byte          |
| 18     |    2 | Battery flags              | Little-endian |
| 20     |    4 | MAX77976 charger details   | Little-endian |
| 24     |    1 | Wireless charger attached  | `0` or `1`    |
| 25     |    2 | USB charger voltage, mV    | Little-endian |
| 27     |    2 | Wireless charger VRECT, mV | Little-endian |

## Android parser behavior

`PacketBuffer` accepts arbitrary serial chunks, including partial frames and
multiple frames in one chunk. It validates SOF, declared payload length,
command type, header CRC, and payload CRC before producing a command.

Malformed frames produce `ReceivedErrorPacket`; the parser then searches for
the next `0x01` SOF byte. Payload lengths greater than 2048 bytes are rejected.
An incomplete frame produces `NotEnoughData` and remains buffered until more
bytes arrive.

## PacketBuffer API

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
