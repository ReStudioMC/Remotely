package redxax.oxy.remotely.data.flow;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class ReSyncFrameCodec {
    private static final byte MESSAGE_CHANNEL_REGISTRY = (byte) 0x08;

    public ReSyncDecodedFrame decode(byte[] data, Set<Short> validDataChannels) {
        if (data == null || data.length < 12) {
            throw new IllegalArgumentException("Frame too short");
        }
        if (data.length > ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too large");
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        byte flags = buffer.get();
        boolean compressed = (flags & 0x80) != 0;
        byte messageType = buffer.get();
        short channel = buffer.getShort();
        int sequence = buffer.getInt();
        int payloadLength = buffer.getInt();

        if (!isKnownMessageType(messageType)) {
            throw new IllegalArgumentException("Unknown message type: " + (messageType & 0xFF));
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("Negative payload length");
        }
        if (payloadLength > ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES - 12) {
            throw new IllegalArgumentException("Payload too large");
        }
        if (data.length < 12 + payloadLength) {
            throw new IllegalArgumentException("Incomplete frame");
        }
        if (data.length != 12 + payloadLength) {
            throw new IllegalArgumentException("Trailing frame bytes");
        }
        if (messageType == ReSyncProtocolContract.MESSAGE_DATA && validDataChannels != null && !validDataChannels.contains(channel)) {
            throw new IllegalArgumentException("Unknown channel: " + (channel & 0xFFFF));
        }

        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        if (compressed) {
            payload = decompress(payload);
        } else if (payload.length > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload too large");
        }
        return new ReSyncDecodedFrame(messageType, channel, sequence, payload, compressed);
    }

    public byte[] encode(int messageType, byte[] payload, short channel, int sequence) {
        byte[] data = payload == null ? new byte[0] : payload;
        if (!isKnownMessageType((byte) messageType)) {
            throw new IllegalArgumentException("Unknown message type: " + messageType);
        }
        if (data.length > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES || data.length > ReSyncProtocolContract.MAX_ENCODED_FRAME_BYTES - 12) {
            throw new IllegalArgumentException("Payload too large");
        }
        ByteBuffer frame = ByteBuffer.allocate(12 + data.length);
        frame.put((byte) 0);
        frame.put((byte) messageType);
        frame.putShort(channel);
        frame.putInt(sequence);
        frame.putInt(data.length);
        frame.put(data);
        return frame.array();
    }

    private byte[] decompress(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] temp = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(temp);
                if (count > 0) {
                    baos.write(temp, 0, count);
                    if (baos.size() > ReSyncProtocolContract.MAX_DECOMPRESSED_PAYLOAD_BYTES) {
                        throw new IllegalArgumentException("Decompressed payload too large");
                    }
                    continue;
                }
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new IllegalArgumentException("Incomplete compressed payload");
                }
                throw new IllegalArgumentException("No inflater progress");
            }
            return baos.toByteArray();
        } catch (DataFormatException exception) {
            throw new IllegalArgumentException("Decompression failed: " + exception.getMessage(), exception);
        } finally {
            inflater.end();
        }
    }

    private boolean isKnownMessageType(byte messageType) {
        return switch (messageType) {
            case ReSyncProtocolContract.MESSAGE_HANDSHAKE_REQUEST,
                 ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE,
                 ReSyncProtocolContract.MESSAGE_SUBSCRIBE,
                 ReSyncProtocolContract.MESSAGE_UNSUBSCRIBE,
                 ReSyncProtocolContract.MESSAGE_DATA,
                 ReSyncProtocolContract.MESSAGE_HEARTBEAT,
                 ReSyncProtocolContract.MESSAGE_ACK,
                 ReSyncProtocolContract.MESSAGE_ERROR,
                 ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE,
                 MESSAGE_CHANNEL_REGISTRY -> true;
            default -> false;
        };
    }
}
