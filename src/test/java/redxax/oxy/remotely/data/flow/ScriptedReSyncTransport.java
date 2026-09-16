package redxax.oxy.remotely.data.flow;

import restudio.resync.flow.protocol.ProtocolBody;
import restudio.resync.flow.protocol.ProtocolEnvelope;
import restudio.resync.flow.protocol.ProtocolEnvelopeCodec;
import restudio.resync.flow.protocol.ResourceOperationKind;
import restudio.resync.flow.resource.ResourcePayloadCodecs;
import restudio.resync.protocol.ReSyncProtocolContract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScriptedReSyncTransport implements ReSyncFrameTransport {
    private static final ProtocolEnvelopeCodec<Map<String, Object>> ENVELOPES =
        new ProtocolEnvelopeCodec<>(ResourcePayloadCodecs.json());
    private final ReSyncFrameCodec frames = new ReSyncFrameCodec();
    private final List<ReSyncDecodedFrame> sent = new ArrayList<>();
    private Consumer<byte[]> frameHandler;
    private Runnable closeHandler;
    private boolean open = true;

    @Override
    public void setFrameHandler(Consumer<byte[]> handler) {
        frameHandler = handler;
    }

    @Override
    public void setCloseHandler(Runnable handler) {
        closeHandler = handler;
    }

    @Override
    public synchronized void send(byte[] frame) {
        sent.add(frames.decode(frame, null));
    }

    @Override
    public void close() {
        open = false;
        if (closeHandler != null) {
            closeHandler.run();
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    void receiveHandshake(String capabilities) {
        byte[] capabilityBytes = capabilities.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES * 7 + capabilityBytes.length);
        payload.put((byte) 1);
        payload.putInt(0);
        payload.putInt(ReSyncProtocolContract.PROTOCOL_VERSION);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(0);
        payload.putInt(capabilityBytes.length);
        payload.put(capabilityBytes);
        receive(ReSyncProtocolContract.MESSAGE_HANDSHAKE_RESPONSE, payload.array(), (short) 0, 1);
    }

    void receiveCatalogPublication(byte[] publication, int sequence) {
        ByteBuffer payload = ByteBuffer.allocate(1 + publication.length);
        payload.put(ReSyncProtocolContract.FLOW_PACKET_CATALOG_PUBLICATION);
        payload.put(publication);
        receive(ReSyncProtocolContract.MESSAGE_DATA, payload.array(), ReSyncProtocolContract.CHANNEL_FLOW_ID, sequence);
    }

    void receiveEnvelope(ProtocolEnvelope<Map<String, Object>> envelope, int sequence) {
        receive(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, ENVELOPES.encodeBytes(envelope),
            ReSyncProtocolContract.CHANNEL_CONTROL_ID, sequence);
    }

    void receiveEnvelope(byte[] envelope, int sequence) {
        receive(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE, envelope,
            ReSyncProtocolContract.CHANNEL_CONTROL_ID, sequence);
    }

    synchronized ProtocolEnvelope<Map<String, Object>> requireRequest(ResourceOperationKind operation, String id) {
        ProtocolEnvelope<Map<String, Object>> match = null;
        for (ReSyncDecodedFrame frame : sent) {
            if (frame.messageType() != ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE) {
                continue;
            }
            ProtocolEnvelope<Map<String, Object>> envelope = ENVELOPES.decodeBytes(frame.payload());
            if (!(envelope.body() instanceof ProtocolBody.ResourceRequest request)
                || request.operation().kind() != operation) {
                continue;
            }
            if (id != null && (envelope.resource() == null || !id.equals(envelope.resource().id()))) {
                continue;
            }
            match = envelope;
        }
        assertNotNull(match, () -> "Missing " + operation + " request for " + id);
        return match;
    }

    synchronized List<ReSyncDecodedFrame> sentFrames() {
        return List.copyOf(sent);
    }

    synchronized ReSyncDecodedFrame lastFrame(byte messageType, short channel) {
        for (int index = sent.size() - 1; index >= 0; index--) {
            ReSyncDecodedFrame frame = sent.get(index);
            if (frame.messageType() == messageType && frame.channel() == channel) {
                return frame;
            }
        }
        throw new IllegalStateException("Missing ReSync frame");
    }

    synchronized ReSyncDecodedFrame lastProtocolEnvelope() {
        return lastFrame(ReSyncProtocolContract.MESSAGE_PROTOCOL_ENVELOPE,
            ReSyncProtocolContract.CHANNEL_CONTROL_ID);
    }

    private void receive(byte messageType, byte[] payload, short channel, int sequence) {
        Consumer<byte[]> handler = frameHandler;
        assertNotNull(handler, "Transport frame handler");
        handler.accept(frames.encode(messageType, payload, channel, sequence));
    }
}
