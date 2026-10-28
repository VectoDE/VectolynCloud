package net.vectora.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Round trips and limits of {@link PacketWriter} and {@link PacketReader}. */
class PacketCodecTest {

    @Test
    void primitivesRoundTrip() {
        UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        byte[] bytes = new PacketWriter()
                .writeByte(0xAB)
                .writeBoolean(true)
                .writeBoolean(false)
                .writeShort(65_535)
                .writeInt(-42)
                .writeLong(Long.MIN_VALUE)
                .writeDouble(19.97)
                .writeVarInt(300)
                .writeVarLong(1L << 40)
                .writeUuid(uuid)
                .toByteArray();

        PacketReader in = new PacketReader(bytes);
        assertEquals(0xAB, in.readUnsignedByte());
        assertTrue(in.readBoolean());
        assertFalse(in.readBoolean());
        assertEquals(65_535, in.readUnsignedShort());
        assertEquals(-42, in.readInt());
        assertEquals(Long.MIN_VALUE, in.readLong());
        assertEquals(19.97, in.readDouble());
        assertEquals(300, in.readVarInt());
        assertEquals(1L << 40, in.readVarLong());
        assertEquals(uuid, in.readUuid());
        in.expectEnd();
    }

    @Test
    void readerDoesNotMoveTheCallersBuffer() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {1, 2, 3});
        new PacketReader(buffer).readByte();
        assertEquals(0, buffer.position());
    }

    @Test
    void stringsRoundTripIncludingSupplementaryCharacters() {
        String text = "Grüße 😀 Welt";
        byte[] bytes = new PacketWriter().writeString(text, 64).toByteArray();
        assertEquals(text, new PacketReader(bytes).readString(64));
    }

    @Test
    void stringAtExactLimitIsAccepted() {
        String text = "a".repeat(16);
        byte[] bytes = new PacketWriter().writeString(text, 16).toByteArray();
        assertEquals(text, new PacketReader(bytes).readString(16));
    }

    @Test
    void stringLimitCountsBytesNotCharacters() {
        // "ü" takes two bytes in UTF-8.
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeString("ü".repeat(9), 16));
        byte[] bytes = new PacketWriter().writeString("ü".repeat(9), 18).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(bytes).readString(16));
    }

    @Test
    void writerRejectsUnpairedSurrogates() {
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeString("a\uD800b", 16));
    }

    @Test
    void readerRejectsNegativeOversizedAndTruncatedLengths() {
        byte[] negative = new PacketWriter().writeVarInt(-1).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(negative).readString(16));
        assertThrows(ProtocolException.class, () -> new PacketReader(negative).readByteArray(16));

        byte[] oversized = new PacketWriter().writeVarInt(17).writeBytes(new byte[17]).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(oversized).readString(16));

        byte[] truncated = new PacketWriter().writeVarInt(10).writeBytes(new byte[3]).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(truncated).readString(16));
        assertThrows(ProtocolException.class, () -> new PacketReader(truncated).readByteArray(16));
    }

    @Test
    void readerRejectsInvalidUtf8() {
        byte[][] invalid = {
            {2, (byte) 0xC0, (byte) 0x80},               // overlong NUL (modified UTF-8)
            {3, (byte) 0xED, (byte) 0xA0, (byte) 0x80},  // encoded surrogate (CESU-8)
            {1, (byte) 0xFF},                             // never valid in UTF-8
            {2, (byte) 0xE2, (byte) 0x82},                // sequence cut off by the length
            {1, (byte) 0x80},                             // lone continuation byte
        };
        for (byte[] bytes : invalid) {
            assertThrows(ProtocolException.class, () -> new PacketReader(bytes).readString(16));
        }
    }

    @Test
    void byteArraysRoundTripAndRespectLimits() {
        byte[] payload = {1, 2, 3, 4};
        byte[] bytes = new PacketWriter().writeByteArray(payload, 4).toByteArray();
        assertArrayEquals(payload, new PacketReader(bytes).readByteArray(4));
        assertThrows(ProtocolException.class, () -> new PacketReader(bytes).readByteArray(3));
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeByteArray(payload, 3));
    }

    @Test
    void listsRoundTripAndRespectLimits() {
        List<Integer> values = List.of(1, 200, 30_000);
        byte[] bytes = new PacketWriter().writeList(values, 3, PacketWriter::writeVarInt).toByteArray();
        assertEquals(values, new PacketReader(bytes).readList(3, PacketReader::readVarInt));
        assertThrows(ProtocolException.class, () -> new PacketReader(bytes).readList(2, PacketReader::readVarInt));
        assertThrows(IllegalArgumentException.class,
                () -> new PacketWriter().writeList(values, 2, PacketWriter::writeVarInt));

        byte[] negative = new PacketWriter().writeVarInt(-5).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(negative).readList(3, PacketReader::readVarInt));
    }

    @Test
    void booleansAreStrict() {
        assertThrows(ProtocolException.class, () -> new PacketReader(new byte[] {2}).readBoolean());
    }

    @Test
    void everyReadReportsUnderflowAsProtocolException() {
        PacketReader empty = new PacketReader(new byte[0]);
        assertThrows(ProtocolException.class, empty::readByte);
        assertThrows(ProtocolException.class, empty::readUnsignedByte);
        assertThrows(ProtocolException.class, empty::readBoolean);
        assertThrows(ProtocolException.class, empty::readUnsignedShort);
        assertThrows(ProtocolException.class, empty::readInt);
        assertThrows(ProtocolException.class, empty::readLong);
        assertThrows(ProtocolException.class, empty::readDouble);
        assertThrows(ProtocolException.class, empty::readVarInt);
        assertThrows(ProtocolException.class, empty::readVarLong);
        assertThrows(ProtocolException.class, empty::readUuid);
        assertThrows(ProtocolException.class, () -> new PacketReader(new byte[7]).readLong());
    }

    @Test
    void expectEndRejectsLeftoverBytes() {
        PacketReader in = new PacketReader(new byte[] {1, 2});
        in.readByte();
        assertThrows(ProtocolException.class, in::expectEnd);
    }

    @Test
    void validatedReadTurnsRejectionsIntoProtocolExceptions() {
        byte[] bytes = new PacketWriter().writeString("Bad Id!", 64).toByteArray();
        assertThrows(ProtocolException.class, () -> new PacketReader(bytes).readValidated(64, ServiceId::new));
    }

    @Test
    void writerRejectsPayloadsLargerThanAFrame() {
        PacketWriter out = new PacketWriter();
        assertThrows(ProtocolException.class, () -> out.writeBytes(new byte[Protocol.MAX_FRAME_SIZE + 1]));
    }

    @Test
    void writerRejectsOutOfRangeArguments() {
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeByte(256));
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeShort(-1));
        assertThrows(IllegalArgumentException.class, () -> new PacketWriter().writeShort(65_536));
    }
}
