package net.vectora.cloud.network;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.vectora.protocol.ServiceId;
import net.vectora.protocol.message.Disconnect;
import net.vectora.protocol.message.Message;
import net.vectora.protocol.message.ServiceStatus;

/** Records every listener call as a line of text, in order. */
final class RecordingListener implements ServiceListener {

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @Override
    public void serviceConnected(ServiceId service, InetSocketAddress remote) {
        events.add("connected " + service.value());
    }

    @Override
    public void serviceReady(ServiceId service) {
        events.add("ready " + service.value());
    }

    @Override
    public void serviceStatus(ServiceStatus status) {
        events.add("status " + status.service().value() + " " + status.playersOnline() + " heapMax=" + status.heapMax());
    }

    @Override
    public void messageReceived(ServiceId service, Message message) {
        events.add("message " + service.value() + " " + message.getClass().getSimpleName());
    }

    @Override
    public void serviceDisconnected(ServiceId service, Disconnect.Reason reason) {
        events.add("disconnected " + service.value() + " " + reason);
    }

    /** Waits up to five seconds for the next event. */
    String next() throws InterruptedException {
        String event = events.poll(5, TimeUnit.SECONDS);
        if (event == null) {
            throw new AssertionError("no listener event within 5 s");
        }
        return event;
    }

    /** Returns the next event if one arrives within {@code millis}, else {@code null}. */
    String poll(long millis) throws InterruptedException {
        return events.poll(millis, TimeUnit.MILLISECONDS);
    }
}
