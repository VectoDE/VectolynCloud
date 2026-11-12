// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
package net.vectolyn.cloud.api.grpc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the subset of the proto3 language that {@code cloud.proto} uses, so the server encodes
 * exactly the messages the published file describes and no second copy of them can drift:
 * {@code syntax}, {@code package}, {@code option}, message definitions with scalar, enum and message
 * fields ({@code repeated}, {@code optional}), top-level {@code enum} and one {@code service} with
 * unary and server-streaming {@code rpc}s. Line comments are ignored.
 *
 * @param pkg      the package, for example {@code vectolyn.api.v1}
 * @param service  the service name
 * @param messages the messages by name
 * @param methods  the methods by name, in file order
 */
public record ProtoSchema(String pkg, String service, Map<String, Proto.Message> messages, Map<String, Method> methods) {

    /** Longest file read. */
    public static final int MAX_LENGTH = 256 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Pattern TOKEN = Pattern.compile("\\s*([A-Za-z0-9_.]+|\"[^\"\\n]*\"|[{}()=;])");
    private static final Map<String, Proto.Type> SCALARS = Map.of(
            "string", Proto.Type.STRING, "bool", Proto.Type.BOOL, "int32", Proto.Type.INT32, "int64", Proto.Type.INT64);

    /**
     * One method.
     *
     * @param name      the method name
     * @param request   the request message
     * @param response  the response message
     * @param streaming whether the server streams responses
     */
    public record Method(String name, Proto.Message request, Proto.Message response, boolean streaming) {
    }

    /** The full gRPC path of a method, {@code /package.Service/Method}. */
    public String path(String method) {
        return "/" + pkg + "." + service + "/" + method;
    }

    /**
     * Reads a file.
     *
     * @param text the file
     * @return the schema
     * @throws IllegalArgumentException if the text is not in the supported subset, refers to an
     *                                  unknown type or uses a name or number twice
     */
    public static ProtoSchema parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("file too long");
        }
        return new Parser(tokens(text)).file();
    }

    private static List<String> tokens(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int comment = line.indexOf("//");
            clean.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(clean);
        int at = 0;
        while (at < clean.length()) {
            if (Character.isWhitespace(clean.charAt(at))) {
                at++;
                continue;
            }
            if (!m.find(at) || m.start(1) != at) {
                throw new IllegalArgumentException("unexpected character at offset " + at);
            }
            tokens.add(m.group(1));
            at = m.end();
        }
        return tokens;
    }

    private static final class Parser {

        private final List<String> tokens;
        private int at;
        private String pkg = "";
        private @Nullable String service;
        private final Map<String, Proto.Message> messages = new LinkedHashMap<>();
        private final Map<String, List<String>> enums = new LinkedHashMap<>();
        /** Fields whose type is resolved after the whole file is read: message, field tokens. */
        private final List<Object[]> pending = new ArrayList<>();
        private final List<String[]> rpcs = new ArrayList<>();

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        ProtoSchema file() {
            while (at < tokens.size()) {
                String word = next();
                switch (word) {
                    case "syntax" -> {
                        expect("=");
                        if (!next().equals("\"proto3\"")) {
                            throw new IllegalArgumentException("only proto3");
                        }
                        expect(";");
                    }
                    case "package" -> {
                        pkg = next();
                        if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")) {
                            throw new IllegalArgumentException("invalid package");
                        }
                        expect(";");
                    }
                    case "option" -> {
                        next();
                        expect("=");
                        next();
                        expect(";");
                    }
                    case "message" -> message();
                    case "enum" -> enumeration();
                    case "service" -> service();
                    default -> throw new IllegalArgumentException("unexpected " + word);
                }
            }
            for (Object[] p : pending) {
                Proto.Message owner = (Proto.Message) p[0];
                String type = (String) p[1];
                String name = (String) p[2];
                int number = (Integer) p[3];
                boolean repeated = (Boolean) p[4];
                boolean optional = (Boolean) p[5];
                Proto.Type scalar = SCALARS.get(type);
                if (scalar != null) {
                    owner.add(new Proto.Field(number, name, scalar, repeated, optional, null, null));
                } else if (enums.containsKey(type)) {
                    owner.add(new Proto.Field(number, name, Proto.Type.ENUM, repeated, optional, null, enums.get(type)));
                } else if (messages.containsKey(type)) {
                    owner.add(new Proto.Field(number, name, Proto.Type.MESSAGE, repeated, optional, messages.get(type),
                            null));
                } else {
                    throw new IllegalArgumentException("unknown type " + type);
                }
            }
            if (service == null) {
                throw new IllegalArgumentException("no service");
            }
            Map<String, Method> methods = new LinkedHashMap<>();
            for (String[] rpc : rpcs) {
                Proto.Message request = messages.get(rpc[1]);
                Proto.Message response = messages.get(rpc[2]);
                if (request == null || response == null) {
                    throw new IllegalArgumentException("unknown message in rpc " + rpc[0]);
                }
                if (methods.putIfAbsent(rpc[0], new Method(rpc[0], request, response, rpc[3] != null)) != null) {
                    throw new IllegalArgumentException("rpc twice: " + rpc[0]);
                }
            }
            return new ProtoSchema(pkg, service, Map.copyOf(messages), java.util.Collections.unmodifiableMap(methods));
        }

        private void message() {
            String name = name();
            if (messages.containsKey(name) || enums.containsKey(name)) {
                throw new IllegalArgumentException("type twice: " + name);
            }
            Proto.Message message = new Proto.Message(name);
            messages.put(name, message);
            expect("{");
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            java.util.Set<Integer> numbers = new java.util.HashSet<>();
            while (!peek().equals("}")) {
                String first = next();
                boolean repeated = first.equals("repeated");
                boolean optional = first.equals("optional");
                String type = repeated || optional ? next() : first;
                String field = name();
                expect("=");
                int number = number();
                expect(";");
                if (!fieldNames.add(field) || !numbers.add(number)) {
                    throw new IllegalArgumentException("field twice in " + name);
                }
                pending.add(new Object[] {message, type, field, number, repeated, optional});
            }
            expect("}");
        }

        private void enumeration() {
            String name = name();
            if (messages.containsKey(name) || enums.containsKey(name)) {
                throw new IllegalArgumentException("type twice: " + name);
            }
            expect("{");
            List<String> values = new ArrayList<>();
            while (!peek().equals("}")) {
                String value = name();
                expect("=");
                int number = number();
                expect(";");
                if (number != values.size() || values.contains(value)) {
                    throw new IllegalArgumentException("enum values must be numbered 0, 1, 2, ...");
                }
                values.add(value);
            }
            expect("}");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("empty enum");
            }
            enums.put(name, values);
        }

        private void service() {
            if (service != null) {
                throw new IllegalArgumentException("one service only");
            }
            service = name();
            expect("{");
            while (!peek().equals("}")) {
                expectWord("rpc");
                String method = name();
                expect("(");
                String request = name();
                expect(")");
                expectWord("returns");
                expect("(");
                String stream = null;
                String response = next();
                if (response.equals("stream")) {
                    stream = response;
                    response = next();
                }
                expect(")");
                expect(";");
                rpcs.add(new String[] {method, request, response, stream});
            }
            expect("}");
        }

        private String name() {
            String token = next();
            if (!NAME.matcher(token).matches()) {
                throw new IllegalArgumentException("invalid name");
            }
            return token;
        }

        private int number() {
            String token = next();
            if (!token.matches("[1-9][0-9]{0,8}|0")) {
                throw new IllegalArgumentException("invalid number");
            }
            return Integer.parseInt(token);
        }

        private void expect(String token) {
            if (!next().equals(token)) {
                throw new IllegalArgumentException("expected " + token);
            }
        }

        private void expectWord(String token) {
            expect(token);
        }

        private String peek() {
            if (at >= tokens.size()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return tokens.get(at);
        }

        private String next() {
            String token = peek();
            at++;
            return token;
        }
    }
}
