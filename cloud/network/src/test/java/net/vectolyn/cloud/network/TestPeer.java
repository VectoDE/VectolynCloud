package net.vectolyn.cloud.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import net.vectolyn.protocol.AuthToken;
import net.vectolyn.protocol.PeerId;
import net.vectolyn.protocol.Protocol;
import net.vectolyn.protocol.message.AuthResult;
import net.vectolyn.protocol.message.Envelope;
import net.vectolyn.protocol.message.Hello;
import net.vectolyn.protocol.message.Message;
import net.vectolyn.protocol.message.MessageCodec;
import net.vectolyn.protocol.message.SessionTicket;

/**
 * A hand-driven peer on a real loopback socket. Unlike the CloudClient it does exactly what a
 * test tells it, including things a well-behaved client never does.
 */
final class TestPeer implements AutoCloseable {

    private static final MessageCodec CODEC = MessageCodec.standard();

    private final Socket socket;
    private final OutputStream out;
    private final InputStream in;
    private byte[] buffer = new byte[0];
    private AuthToken ticket;

    private TestPeer(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.in = socket.getInputStream();
    }

    static TestPeer connect(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(address, 2_000);
        socket.setSoTimeout(5_000);
        return new TestPeer(socket);
    }

    /** Connects, says Hello and returns the outcome of the AuthResult. */
    static TestPeer authenticated(InetSocketAddress address, PeerId peer, AuthToken token) throws IOException {
        TestPeer test = connect(address);
        AuthResult result = test.hello(peer, token);
        if (result.outcome() != AuthResult.Outcome.ACCEPTED) {
            test.close();
            throw new IllegalStateException("not accepted: " + result.outcome());
        }
        return test;
    }

    /**
     * Says Hello and returns the AuthResult. After an acceptance the cloud sends the ticket for
     * the next connection right behind it; it is read here and kept in {@link #ticket()}.
     */
    AuthResult hello(PeerId peer, AuthToken token) throws IOException {
        send(new Hello(Protocol.PROTOCOL_VERSION, peer, token));
        AuthResult result = (AuthResult) read().message();
        if (result.outcome() == AuthResult.Outcome.ACCEPTED) {
            ticket = ((SessionTicket) read().message()).ticket();
            // Like the client: a frame right after the ticket tells the cloud it arrived.
            send(new net.vectolyn.protocol.message.Ping(0));
            readUntil(net.vectolyn.protocol.message.Pong.class);
        }
        return result;
    }

    /** The ticket of the last accepted Hello, or null. */
    AuthToken ticket() {
        return ticket;
    }

    void send(Message message) throws IOException {
        send(Envelope.of(message));
    }

    void send(Envelope envelope) throws IOException {
        sendRaw(CODEC.encode(envelope));
    }

    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** Reads the next message; fails with EOFException when the server has closed. */
    Envelope read() throws IOException {
        while (true) {
            ByteBuffer view = ByteBuffer.wrap(buffer);
            Optional<Envelope> next = CODEC.tryDecode(view);
            if (next.isPresent()) {
                buffer = Arrays.copyOfRange(buffer, view.position(), buffer.length);
                return next.get();
            }
            byte[] chunk = new byte[4096];
            int read = in.read(chunk);
            if (read < 0) {
                throw new EOFException("closed by the server");
            }
            byte[] grown = Arrays.copyOf(buffer, buffer.length + read);
            System.arraycopy(chunk, 0, grown, buffer.length, read);
            buffer = grown;
        }
    }

    /** Reads messages until one of {@code type} arrives, skipping pings and the like. */
    <T extends Message> T readUntil(Class<T> type) throws IOException {
        while (true) {
            Message message = read().message();
            if (type.isInstance(message)) {
                return type.cast(message);
            }
        }
    }

    /** Returns whether the server closes the connection within {@code timeout}, ignoring messages. */
    boolean closedWithin(Duration timeout) throws IOException {
        socket.setSoTimeout((int) timeout.toMillis());
        long end = System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < end) {
                if (in.read() < 0) {
                    return true;
                }
            }
            return false;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException reset) {
            // A reset counts as closed as well.
            return true;
        } finally {
            socket.setSoTimeout(5_000);
        }
    }

    void setReadTimeout(Duration timeout) throws IOException {
        socket.setSoTimeout((int) timeout.toMillis());
    }

    /** Drops the connection without a Disconnect, like a crashed server. */
    void abort() throws IOException {
        socket.close();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
