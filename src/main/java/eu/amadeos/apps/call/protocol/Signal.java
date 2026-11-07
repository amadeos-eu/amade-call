package eu.amadeos.apps.call.protocol;

import eu.amadeos.apps.call.domain.CallKind;
import eu.amadeos.commons.json.Json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One message between two amade-call programs, sent as a small JSON object over the TLS connection of the call.
 * The connection carries the call set-up (who calls, accept, decline), the WebRTC negotiation (offer, answer,
 * ICE candidates), the chat and the switches the other side shows (camera, screen). Picture and sound do not
 * go through it; they travel in their own encrypted stream (DTLS-SRTP) that WebRTC sets up.
 */
public record Signal(Type type, Map<String, Object> fields) {
    /** The version of these messages; a newer program can still talk to this one while it sends version 1. */
    public static final int VERSION = 1;

    public enum Type {
        /** The caller: its name, the kind of call, whether its camera is on, and the version. */
        HELLO,
        /** The callee answers; {@code video} says whether its camera starts on. */
        ACCEPT,
        /** The callee does not take the call; {@code reason} is "declined" or "busy". */
        DECLINE,
        OFFER,
        ANSWER,
        /** An ICE candidate: {@code index} (m-line) and {@code candidate}. */
        ICE,
        CHAT,
        /** A picture or file follows: {@code id}, {@code name}, {@code size} in bytes. */
        FILE,
        /** A piece of it: {@code id} and {@code data} in Base64, in order. */
        FILE_DATA,
        /** All of it was sent: {@code id} and {@code sha256} of the whole file. */
        FILE_END,
        /** Either side gives up the file {@code id}. */
        FILE_CANCEL,
        /** The sender switched its camera on or off. */
        CAMERA,
        /** The sender started or stopped sharing its screen. */
        SCREEN,
        /** The sender mutes or unmutes its microphone. */
        MICROPHONE,
        /** The call ends. */
        BYE;

        String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Optional<Type> ofWire(String text) {
            for (Type t : values()) if (t.wire().equals(text)) return Optional.of(t);
            return Optional.empty();
        }
    }

    public Signal {
        Objects.requireNonNull(type, "type");
        fields = Map.copyOf(fields);
    }

    // ---------------------------------------------------------------- the messages

    /** {@code video} says whether the caller's camera starts on. */
    public static Signal hello(String name, CallKind kind, boolean video) {
        return new Signal(Type.HELLO, Map.of("name", name, "kind", kind.name().toLowerCase(Locale.ROOT),
                "video", video, "version", VERSION));
    }

    /** {@code name} is how the answering side calls itself, for callers who dialled an address. */
    public static Signal accept(String name, boolean video) {
        return new Signal(Type.ACCEPT, Map.of("name", name, "video", video));
    }

    public static Signal decline(String name, boolean busy) {
        return new Signal(Type.DECLINE, Map.of("name", name, "reason", busy ? "busy" : "declined"));
    }

    public static Signal offer(String sdp) {
        return new Signal(Type.OFFER, Map.of("sdp", sdp));
    }

    public static Signal answer(String sdp) {
        return new Signal(Type.ANSWER, Map.of("sdp", sdp));
    }

    public static Signal ice(int index, String candidate) {
        return new Signal(Type.ICE, Map.of("index", index, "candidate", candidate));
    }

    public static Signal chat(String text) {
        return new Signal(Type.CHAT, Map.of("text", text));
    }

    public static Signal file(String id, String name, long size) {
        return new Signal(Type.FILE, Map.of("id", id, "name", name, "size", size));
    }

    public static Signal fileData(String id, String base64) {
        return new Signal(Type.FILE_DATA, Map.of("id", id, "data", base64));
    }

    public static Signal fileEnd(String id, String sha256) {
        return new Signal(Type.FILE_END, Map.of("id", id, "sha256", sha256));
    }

    public static Signal fileCancel(String id) {
        return new Signal(Type.FILE_CANCEL, Map.of("id", id));
    }

    public static Signal camera(boolean on) {
        return new Signal(Type.CAMERA, Map.of("on", on));
    }

    public static Signal screen(boolean on) {
        return new Signal(Type.SCREEN, Map.of("on", on));
    }

    public static Signal microphone(boolean on) {
        return new Signal(Type.MICROPHONE, Map.of("on", on));
    }

    public static Signal bye() {
        return new Signal(Type.BYE, Map.of());
    }

    // ---------------------------------------------------------------- reading fields

    public String text(String key) {
        return Json.str(fields, key);
    }

    public int number(String key) {
        return Json.integer(fields, key);
    }

    /** A whole number that may be larger than an int; -1 when missing. */
    public long longNumber(String key) {
        return fields.get(key) instanceof Number n ? n.longValue() : -1;
    }

    public boolean flag(String key) {
        return Boolean.TRUE.equals(fields.get(key));
    }

    /** The kind of a HELLO; video when the field is missing or unknown. */
    public CallKind kind() {
        return switch (text("kind")) {
            case "audio" -> CallKind.AUDIO;
            case "chat" -> CallKind.CHAT;
            default -> CallKind.VIDEO;
        };
    }

    // ---------------------------------------------------------------- wire format

    public String encode() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.wire());
        map.putAll(fields);
        return Json.write(map);
    }

    /** Reads a message; empty for anything that is not one (unknown types are skipped, not fatal). */
    public static Optional<Signal> decode(String text) {
        Map<String, Object> map;
        try {
            map = Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Optional<Type> type = Type.ofWire(Json.str(map, "type"));
        if (type.isEmpty()) return Optional.empty();
        Map<String, Object> fields = new LinkedHashMap<>(map);
        fields.remove("type");
        fields.values().removeIf(Objects::isNull);
        return Optional.of(new Signal(type.get(), fields));
    }
}
