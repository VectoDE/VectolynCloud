package net.vectora.cloud.bridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.vectora.protocol.message.Message;

/** Records every call with the thread it ran on; can be told to block like a stuck socket. */
final class FakeLink implements CloudLink {

    record Call(String what, Object argument, Thread thread) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch blocked = new CountDownLatch(1);
    volatile boolean block;
    volatile boolean fail;

    private void record(String what, Object argument) {
        calls.add(new Call(what, argument, Thread.currentThread()));
        if (fail) {
            throw new IllegalStateException("broken link");
        }
        if (block) {
            blocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void start() {
        record("start", null);
    }

    @Override
    public void markReady() {
        record("ready", null);
    }

    @Override
    public void reportStatus(StatusSnapshot status) {
        record("status", status);
    }

    @Override
    public void send(Message playerEvent) {
        record("send", playerEvent);
    }

    /** Like closing a real socket: never blocks and frees a call that hangs. */
    @Override
    public void close() {
        calls.add(new Call("close", null, Thread.currentThread()));
        release.countDown();
    }

    List<String> names() {
        return calls.stream().map(Call::what).toList();
    }
}
