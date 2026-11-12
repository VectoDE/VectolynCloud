// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.cluster;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.vectolyn.protocol.ChannelName;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ProtocolException;
import net.vectolyn.protocol.ServiceId;

/**
 * Payloads of the placement on a cluster session: every member tells the head how much memory it
 * has for services and which services it runs ({@link NodeStatus}), so the head can choose a node
 * for a new service ({@link PlacementPlanner}). Every reader throws only {@link ProtocolException}
 * and checks every length before it allocates.
 */
public final class PlacementMessages {

    /** Push from a member: its {@link NodeStatus}. */
    public static final ChannelName NODE_STATUS = new ChannelName("cluster.node.status");

    /** Most services in one status (16 bytes at most per entry plus names: well below 64 KiB). */
    public static final int MAX_SERVICES = 512;
    /** Largest byte count in a status, 1 PiB: more than any machine, small enough to add up. */
    public static final long MAX_BYTES = 1L << 50;
    /** A task name, as {@code ConfigNames.NAME}. */
    private static final Pattern TASK = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final int MAX_TASK_BYTES = 32;

    private PlacementMessages() {
    }

    /**
     * One service a node runs or is about to run (it has a process, or is prepared for one).
     *
     * @param id      the service
     * @param task    the task it belongs to
     * @param dynamic whether it is a dynamic service (static ones keep their world on the node)
     * @param heap    its maximum heap in bytes ({@code heap.max} of its group)
     */
    public record RunningService(ServiceId id, String task, boolean dynamic, long heap) {

        /** Checks the values. */
        public RunningService {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(task, "task");
            if (!TASK.matcher(task).matches()) {
                throw new IllegalArgumentException("invalid task name");
            }
            checkBytes(heap);
        }

        static RunningService read(PacketReader in) {
            ServiceId id = ServiceId.read(in);
            String task = in.readString(MAX_TASK_BYTES);
            boolean dynamic = in.readBoolean();
            long heap = in.readVarLong();
            try {
                return new RunningService(id, task, dynamic, heap);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        void write(PacketWriter out) {
            id.write(out);
            out.writeString(task, MAX_TASK_BYTES).writeBoolean(dynamic).writeVarLong(heap);
        }
    }

    /**
     * What a node reports to the head. The memory follows the rules of the autoscaler on the node
     * ({@code docs/cloud/scaling.md}): the budget is {@code max-memory} of the node or the measured
     * memory minus the reserve, and the services count with their maximum heap.
     *
     * @param memoryKnown whether the node has a memory reading (its monitor may be off or not
     *                    have sampled yet); without one, {@code budget} and {@code free} are 0
     * @param budget      bytes the services of the node may use together
     * @param free        bytes free on the machine right now
     * @param services    the services with a process or about to get one
     */
    public record NodeStatus(boolean memoryKnown, long budget, long free, List<RunningService> services) {

        /** A node that knows nothing about its memory and runs nothing yet. */
        public static final NodeStatus UNKNOWN = new NodeStatus(false, 0, 0, List.of());

        /** Checks and copies the values. */
        public NodeStatus {
            services = List.copyOf(services);
            checkBytes(budget);
            checkBytes(free);
            if (!memoryKnown && (budget != 0 || free != 0)) {
                throw new IllegalArgumentException("memory values without a memory reading");
            }
            if (services.size() > MAX_SERVICES) {
                throw new IllegalArgumentException("too many services");
            }
        }

        /**
         * The maximum heaps of every service together.
         *
         * @return bytes
         */
        public long declared() {
            long sum = 0;
            for (RunningService service : services) {
                sum += service.heap();
            }
            return sum;
        }

        /**
         * The services of one task.
         *
         * @param task the task name
         * @return how many this node runs
         */
        public int servicesOf(String task) {
            int count = 0;
            for (RunningService service : services) {
                if (service.task().equals(task)) {
                    count++;
                }
            }
            return count;
        }

        /**
         * This status with one more service, as the head counts a placement it decided until the
         * node reports again.
         *
         * @param service the new service
         * @return the new status
         */
        public NodeStatus with(RunningService service) {
            List<RunningService> more = new java.util.ArrayList<>(services);
            more.add(service);
            return new NodeStatus(memoryKnown, budget, memoryKnown ? Math.max(0, free - service.heap()) : 0, more);
        }

        /** Reads the payload. */
        public static NodeStatus read(PacketReader in) {
            boolean known = in.readBoolean();
            long budget = in.readVarLong();
            long free = in.readVarLong();
            List<RunningService> services = in.readList(MAX_SERVICES, RunningService::read);
            in.expectEnd();
            try {
                return new NodeStatus(known, budget, free, services);
            } catch (IllegalArgumentException e) {
                throw new ProtocolException(e.getMessage(), e);
            }
        }

        /** Writes the payload. */
        public byte[] write() {
            PacketWriter out = new PacketWriter().writeBoolean(memoryKnown).writeVarLong(budget).writeVarLong(free);
            out.writeList(services, MAX_SERVICES, (writer, service) -> service.write(writer));
            return out.toByteArray();
        }
    }

    private static void checkBytes(long value) {
        if (value < 0 || value > MAX_BYTES) {
            throw new IllegalArgumentException("byte count out of range");
        }
    }
}
