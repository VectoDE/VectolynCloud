package net.vectolyn.cloud.network;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.vectolyn.protocol.ServiceId;
import net.vectolyn.protocol.message.Disconnect;
import net.vectolyn.protocol.message.Message;
import net.vectolyn.protocol.message.ServiceStatus;

/** Records every listener call as a line of text, in order. */
final class RecordingListener implements ChannelListener {

    private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

    @Override
    public void onEvent(ServiceEvent event) {
        events.add(switch (event) {
            case ServiceEvent.Connected connected -> "connected " + connected.service().value();
            case ServiceEvent.Ready ready -> "ready " + ready.service().value();
            case ServiceEvent.StatusReported(ServiceStatus status) ->
                    "status " + status.service().value() + " " + status.playersOnline() + " heapMax=" + status.heapMax();
            case ServiceEvent.MessageReceived(ServiceId service, Message message) ->
                    "message " + service.value() + " " + message.getClass().getSimpleName();
            case ServiceEvent.Disconnected(ServiceId service, Disconnect.Reason reason) ->
                    "disconnected " + service.value() + " " + reason;
        });
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
