package eu.amadeos.apps.call.ui.main;

import eu.amadeos.apps.call.bootstrap.CallModule;
import eu.amadeos.apps.call.domain.EndReason;
import eu.amadeos.commons.i18n.Language;
import eu.amadeos.commons.i18n.Messages;
import eu.amadeos.commons.i18n.Mnemonics;
import eu.amadeos.commons.i18n.TranslationCheck;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationTest {
    private static Messages texts(Language language) {
        return Messages.load(CallModule.class, CallModule.MESSAGES, Locale.forLanguageTag(language.code()));
    }

    @Test
    void everyLanguageHasEveryText() {
        assertEquals(Map.of(), TranslationCheck.problems(CallModule.class, CallModule.MESSAGES));
    }

    @Test
    void everyEndHasAText() {
        for (Language language : Language.values()) {
            for (EndReason reason : EndReason.values()) {
                String key = "ended." + reason.name().toLowerCase(Locale.ROOT).replace('_', '-');
                assertTrue(!texts(language).get(key).equals(key), language + " " + key);
            }
        }
    }

    /** No two entries of one menu, window or dialog share a mnemonic letter. */
    @Test
    void mnemonicsAreUnique() {
        Map<String, List<String>> groups = Map.of(
                "bar", List.of("menu.call", "menu.person", "menu.options", "menu.help"),
                "call", List.of("menu.call.video", "menu.call.audio", "menu.call.chat", "menu.call.address",
                        "menu.call.settings", "menu.call.exit"),
                "person", List.of("menu.person.verify", "menu.person.add-contact", "menu.person.clear-chat",
                        "menu.person.forget"),
                "details", List.of("people.search", "details.video", "details.audio", "details.chat",
                        "details.verify"),
                "chat-window", List.of("call.switch.video", "call.switch.audio", "call.verify", "call.end-chat"),
                "call-window", List.of("call.microphone", "call.camera", "call.screen", "call.chat", "call.verify",
                        "call.hang-up"),
                "call-window-off", List.of("call.microphone.off", "call.camera.off", "call.screen", "call.chat",
                        "call.verify", "call.hang-up"),
                "incoming", List.of("incoming.accept", "incoming.audio-only", "incoming.decline"),
                "safety", List.of("safety.match", "safety.different", "safety.later"),
                "settings", List.of("settings.name", "settings.camera", "settings.microphone", "settings.speaker",
                        "settings.camera-on", "settings.echo", "settings.wait", "settings.keep-chats",
                        "settings.port"));
        for (Language language : Language.values()) {
            Messages texts = texts(language);
            for (Map.Entry<String, List<String>> group : groups.entrySet()) {
                Map<Integer, String> used = new HashMap<>();
                for (String key : group.getValue()) {
                    Mnemonics.Text text = Mnemonics.parse(texts.get(key));
                    assertTrue(text.index() >= 0, language + " " + key + " has no mnemonic");
                    String before = used.put(text.keyCode(), key);
                    assertNull(before, language + " " + group.getKey() + ": " + key + " and " + before
                            + " share a letter");
                }
            }
        }
    }

    @Test
    void germanNames() {
        assertEquals("Anrufe", texts(Language.GERMAN).get("window.title"));
        assertEquals("Auflegen", Mnemonics.parse(texts(Language.GERMAN).get("call.hang-up")).text());
    }
}
