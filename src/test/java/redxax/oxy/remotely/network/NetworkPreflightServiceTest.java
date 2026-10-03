package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkPreflightServiceTest {
    @Test
    void performsMinecraftStatusHandshakeThroughEntry() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> respond(server));

            NetworkPreflightService.StatusResponse response = new NetworkPreflightService(null, null, null, null).minecraftStatus("127.0.0.1", server.getLocalPort());

            responder.join();
            assertEquals("Velocity Test", response.version());
            assertEquals(3, response.onlinePlayers());
            assertEquals(100, response.maximumPlayers());
        }
    }

    @Test
    void rejectsPayloadThatCrossesTheDeclaredPacketBoundary() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> responder = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    input.readNBytes(readVarInt(input));
                    input.readNBytes(readVarInt(input));
                    socket.getOutputStream().write(new byte[]{2, 0, 2, '{', '}'});
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });

            assertThrows(IOException.class, () -> new NetworkPreflightService(null, null, null, null).minecraftStatus("127.0.0.1", server.getLocalPort()));
            responder.join();
        }
    }

    private void respond(ServerSocket server) {
        try (Socket socket = server.accept()) {
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            input.readNBytes(readVarInt(input));
            input.readNBytes(readVarInt(input));
            byte[] json = "{\"version\":{\"name\":\"Velocity Test\",\"protocol\":769},\"players\":{\"max\":100,\"online\":3},\"description\":{\"text\":\"Ready\"}}".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            DataOutputStream payload = new DataOutputStream(payloadBytes);
            writeVarInt(payload, 0);
            writeVarInt(payload, json.length);
            payload.write(json);
            byte[] response = payloadBytes.toByteArray();
            writeVarInt(output, response.length);
            output.write(response);
            output.flush();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int readVarInt(DataInputStream input) throws IOException {
        int value = 0;
        int position = 0;
        byte current;
        do {
            current = input.readByte();
            value |= (current & 0x7F) << position;
            position += 7;
        } while ((current & 0x80) != 0);
        return value;
    }

    private void writeVarInt(DataOutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            byte part = (byte) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                part |= (byte) 0x80;
            }
            output.writeByte(part);
        } while (remaining != 0);
    }
}
