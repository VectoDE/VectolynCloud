package net.vectolyn.protocol.message;

import static net.vectolyn.protocol.message.MessageLimits.checkCount;
import static net.vectolyn.protocol.message.MessageLimits.checkNotNegative;
import static net.vectolyn.protocol.message.MessageLimits.checkRange;

import java.util.Objects;
import net.vectolyn.protocol.PacketReader;
import net.vectolyn.protocol.PacketWriter;
import net.vectolyn.protocol.ServiceId;

/**
 * Periodic health report of a service. Online players may exceed the maximum (for example
 * operators who bypass the limit), so the two are not compared.
 *
 * <p>Wire: service (string), players online and max (VarInt each), TPS and MSPT (8-byte double
 * each), heap used and max (VarLong each; heap max may be {@value #UNKNOWN_HEAP_MAX}).
 *
 * @param service the reporting service
 * @param playersOnline players connected now, 0 to {@value MessageLimits#MAX_PLAYERS}
 * @param maxPlayers configured player limit, 0 to {@value MessageLimits#MAX_PLAYERS}
 * @param tps ticks per second, 0 to {@value MessageLimits#MAX_TPS}; finite
 * @param mspt milliseconds per tick, 0 to {@value MessageLimits#MAX_MSPT}; finite
 * @param heapUsed used heap in bytes, 0 or more
 * @param heapMax maximum heap in bytes, 0 or more, or {@value #UNKNOWN_HEAP_MAX} if the JVM has no
 *     limit or does not report one ({@code Runtime.maxMemory()} returns {@code Long.MAX_VALUE} then)
 */
public record ServiceStatus(ServiceId service, int playersOnline, int maxPlayers, double tps, double mspt,
        long heapUsed, long heapMax) implements Message {

    /** Value of {@link #heapMax()} when the maximum heap is unknown or unlimited. */
    public static final long UNKNOWN_HEAP_MAX = -1;

    /**
     * Creates the message.
     *
     * @throws IllegalArgumentException if a number is outside its range
     */
    public ServiceStatus {
        Objects.requireNonNull(service, "service");
        checkCount(playersOnline, "players online");
        checkCount(maxPlayers, "max players");
        checkRange(tps, MessageLimits.MAX_TPS, "TPS");
        checkRange(mspt, MessageLimits.MAX_MSPT, "MSPT");
        checkNotNegative(heapUsed, "heap used");
        if (heapMax != UNKNOWN_HEAP_MAX) {
            checkNotNegative(heapMax, "heap max");
        }
    }

    /** Reads the payload. */
    public static ServiceStatus read(PacketReader in) {
        ServiceId service = ServiceId.read(in);
        int playersOnline = in.readVarInt();
        int maxPlayers = in.readVarInt();
        double tps = in.readDouble();
        double mspt = in.readDouble();
        long heapUsed = in.readVarLong();
        long heapMax = in.readVarLong();
        return new ServiceStatus(service, playersOnline, maxPlayers, tps, mspt, heapUsed, heapMax);
    }

    @Override
    public void write(PacketWriter out) {
        service.write(out);
        out.writeVarInt(playersOnline)
                .writeVarInt(maxPlayers)
                .writeDouble(tps)
                .writeDouble(mspt)
                .writeVarLong(heapUsed)
                .writeVarLong(heapMax);
    }
}
