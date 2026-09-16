package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReSyncFrameCodecTest {
    @Test
    void protocolEnvelopeRoundTripsAndUnknownTypesRemainRejected() {
        ReSyncFrameCodec codec = new ReSyncFrameCodec();
        byte[] payload = {1, 2, 3};

        byte[] frame = codec.encode(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE,
            payload, (short) 0, 17);
        ReSyncDecodedFrame decoded = codec.decode(frame, null);

        assertEquals(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, decoded.messageType());
        assertEquals(17, decoded.sequence());
        assertArrayEquals(payload, decoded.payload());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(0x7F, payload, (short) 0, 17));

        byte[] unknownFrame = ByteBuffer.allocate(12)
            .put((byte) 0)
            .put((byte) 0x7F)
            .putShort((short) 0)
            .putInt(17)
            .putInt(0)
            .array();
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unknownFrame, null));
    }
}
