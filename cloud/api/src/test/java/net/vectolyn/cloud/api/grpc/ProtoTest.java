// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The proto3 codec and the reader of the {@code .proto} subset: the wire format matches the
 * reference encoding byte for byte, and broken input fails with the documented exception only.
 */
class ProtoTest {

    private static final ProtoSchema SCHEMA = ProtoSchema.parse("""
            syntax = "proto3";
            package test.v1;
            option java_package = "x";
            enum Color { COLOR_UNSPECIFIED = 0; COLOR_RED = 1; }
            message Inner { string name = 1; Inner child = 2; }
            message All {
              string text = 1;
              bool flag = 2;
              int32 small = 3;
              int64 big = 4;
              Color color = 5;
              Inner inner = 6;
              repeated int32 numbers = 7;
              repeated string words = 8;
              optional int32 maybe = 9;
            }
            service Test {
              rpc One(All) returns (Inner);
              rpc Many(Inner) returns (stream All);
            }
            """);

    private static Proto.Message all() {
        return SCHEMA.messages().get("All");
    }

    @Test
    void theEncodingMatchesTheReferenceBytes() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "hi");
        value.put("flag", true);
        value.put("small", -1);
        value.put("big", 300L);
        value.put("color", "COLOR_RED");
        value.put("inner", Map.of("name", "a"));
        value.put("numbers", List.of(1, 2));
        value.put("words", List.of("x"));
        value.put("maybe", 0);
        // The proto3 wire format by hand; repeated numbers are written unpacked, which every reader accepts.
        String expected = "0a026869" + "1001" + "18ffffffffffffffffff01" + "20ac02" + "2801" + "32030a0161"
                + "38013802" + "420178" + "4800";
        byte[] bytes = Proto.encode(all(), value);
        assertEquals(expected, HexFormat.of().formatHex(bytes));
        assertEquals(value, Proto.decode(all(), bytes));
    }

    @Test
    void defaultsAreLeftOutUnlessOptional() throws Exception {
        assertArrayEquals(new byte[0], Proto.encode(all(), Map.of("text", "", "flag", false, "small", 0,
                "color", "COLOR_UNSPECIFIED")));
        assertEquals(Map.of(), Proto.decode(all(), new byte[0]));
    }

    @Test
    void packedRepeatedFieldsAndUnknownFieldsAreRead() throws Exception {
        // numbers packed [1, 150], then unknown fields of every wire type: varint, fixed64, bytes, fixed32
        byte[] bytes = HexFormat.of().parseHex("3a03019601" + "f80101" + "f9010102030405060708" + "fa010100"
                + "fd0101020304");
        assertEquals(Map.of("numbers", List.of(1, 150)), Proto.decode(all(), bytes));
    }

    @Test
    void aMessageFieldSeenTwiceIsMerged() throws Exception {
        byte[] bytes = HexFormat.of().parseHex("32030a0161" + "32021200");
        Map<?, ?> inner = (Map<?, ?>) Proto.decode(all(), bytes).get("inner");
        assertEquals("a", inner.get("name"));
        assertEquals(Map.of(), inner.get("child"));
    }

    @Test
    void unknownEnumNumbersAreKeptAsText() throws Exception {
        assertEquals(Map.of("color", "7"), Proto.decode(all(), new byte[] {0x28, 7}));
    }

    @Test
    void brokenInputFailsWithProtoException() {
        HexFormat hex = HexFormat.of();
        for (String broken : List.of(
                "0a05686921", // length beyond the end
                "0a02c328", // invalid UTF-8
                "10", // truncated varint
                "10ffffffffffffffffffff01", // varint longer than 10 bytes
                "0b", // group start on a known field
                "fb01", // group start on an unknown field
                "1202", // wrong wire type for bool
                "00", // field number 0
                "f9010102")) { // truncated fixed64
            assertThrows(Proto.ProtoException.class, () -> Proto.decode(all(), hex.parseHex(broken)), broken);
        }
    }

    @Test
    void nestingAndRepetitionAreBounded() {
        byte[] deep = new byte[0];
        for (int i = 0; i <= Proto.MAX_DEPTH + 1; i++) {
            byte[] outer = new byte[deep.length + 2];
            outer[0] = 0x12;
            outer[1] = (byte) deep.length;
            System.arraycopy(deep, 0, outer, 2, deep.length);
            deep = outer;
        }
        byte[] nested = deep;
        assertThrows(Proto.ProtoException.class, () -> Proto.decode(SCHEMA.messages().get("Inner"), nested));

        List<Integer> many = new ArrayList<>();
        for (int i = 0; i <= Proto.MAX_REPEATED; i++) {
            many.add(1);
        }
        byte[] repeated = Proto.encode(all(), Map.of("numbers", many));
        assertThrows(Proto.ProtoException.class, () -> Proto.decode(all(), repeated));
    }

    @Test
    void wrongJavaTypesAreRefusedWhenEncoding() {
        assertThrows(IllegalArgumentException.class, () -> Proto.encode(all(), Map.of("small", "1")));
        assertThrows(IllegalArgumentException.class, () -> Proto.encode(all(), Map.of("small", 1L << 40)));
        assertThrows(IllegalArgumentException.class, () -> Proto.encode(all(), Map.of("color", "COLOR_BLUE")));
        assertThrows(IllegalArgumentException.class, () -> Proto.encode(all(), Map.of("words", "x")));
    }

    @Test
    void theSchemaKnowsItsMethods() {
        assertEquals("/test.v1.Test/One", SCHEMA.path("One"));
        assertTrue(SCHEMA.methods().get("Many").streaming());
        assertEquals("All", SCHEMA.methods().get("One").request().name());
    }

    @Test
    void filesOutsideTheSubsetAreRefused() {
        for (String file : List.of(
                "syntax = \"proto2\";",
                "message A { string a = 1; } ",
                "message A { B b = 1; } service S {}",
                "message A { string a = 1; string a = 2; } service S {}",
                "message A { string a = 1; int32 b = 1; } service S {}",
                "message A { string a = 0; } service S {}",
                "enum E { E_A = 1; } service S {}",
                "message A {} service S { rpc X(A) returns (B); }",
                "message A {} service S {} service T {}",
                "message A { map<string, string> m = 1; } service S {}",
                "message A {} service S { rpc X(A) returns (A); rpc X(A) returns (A); }",
                "message A {")) {
            assertThrows(IllegalArgumentException.class, () -> ProtoSchema.parse(file), file);
        }
    }
}
