# Android/RP2040 Communication Protocol version 1.2.0

This document is the canonical communication contract between the Android host
and the RP2040 firmware. The link uses a custom length-prefixed frame with a
CRC-16 checksum over USB serial.

## Transport

The serial connection carries an unmodified binary byte stream using 8N1. It
must not perform CR/LF translation.

### Frame layout

| Offset    |      Size | Field        | Encoding                                    |
|:----------|----------:|:-------------|:--------------------------------------------|
| 0         |         1 | Start        | `0xFE`                                      |
| 1         |         2 | Data length  | Unsigned, little-endian                     |
| 3         |         1 | Command type | See command table below                     |
| 4         | `LEN - 1` | Payload      | Command-specific                            |
| `3 + LEN` |         2 | CRC          | CRC-16/CCITT-FALSE, unsigned, little-endian |

`LEN` counts the command type and payload, but not the start byte, length field,
or CRC. It must therefore be at least 1. A zero-payload frame has `LEN = 1` and
is exactly 6 bytes long. A frame with an `N`-byte payload is `N + 6` bytes long.
There is no stop marker.

### Checksum

The checksum uses CRC-16/CCITT-FALSE with polynomial `0x1021`, initial value
`0xFFFF`, no reflection, and no final XOR.

The CRC input begins with the two encoded length bytes and continues through
the command type and payload. The `0xFE` start byte is not included. The
resulting 16-bit checksum is written to the frame in little-endian order.

## Commands

The same type identifies a request and its corresponding response.

| ID     | Name               | Android request payload              | RP2040 response payload             |
|:-------|:-------------------|:-------------------------------------|:------------------------------------|
| `0x00` | `GET_LOG`          | Empty                                | ASCII log entries separated by `\n` |
| `0x01` | `SET_MOTOR_LEVELS` | Two motor-control bytes: left, right | `RP2040_STATE`                      |
| `0x02` | `RESET_STATE`      | Empty                                | `RP2040_STATE`                      |
| `0x03` | `GET_STATE`        | Empty                                | `RP2040_STATE`                      |
| `0x06` | `GET_VERSION`      | Empty                                | Three bytes: major, minor, patch    |
| `0xFC` | `NACK`             | Command-specific or empty            | Command-specific or empty           |
| `0xFD` | `ACK`              | Command-specific or empty            | Command-specific or empty           |

`0xFE` is reserved for the start marker and cannot be used as a command type.
`0xFF` is not a stop marker in this protocol.

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

The response payload must be exactly 3 bytes.

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
multiple frames in one chunk. It validates the start marker, declared data
length, command type, and CRC before producing a command.

Malformed frames produce `ReceivedErrorPacket`; the parser then searches for
the next `0xFE` start byte. Data lengths greater than 2048 bytes are rejected.
An incomplete frame produces `NotEnoughData` and remains buffered until more
bytes arrive. A malformed `GET_VERSION` response produces
`FirmwareCompatibilityFailure`.

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
        is PacketBuffer.ParseResult.FirmwareCompatibilityFailure -> {
            // The firmware version response is malformed or unsupported.
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
