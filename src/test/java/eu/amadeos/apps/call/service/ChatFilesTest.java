package eu.amadeos.apps.call.service;

import eu.amadeos.apps.call.domain.ChatFile;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatFilesTest {
    @Test
    void namesFromTheOtherSideLoseFoldersAndHiddenDots() {
        assertEquals("passwd", ChatFiles.cleanName("../../etc/passwd"));
        assertEquals("evil.txt", ChatFiles.cleanName("C:\\Users\\x\\evil.txt"));
        assertEquals("bashrc", ChatFiles.cleanName(".bashrc"));
        assertEquals("ab.png", ChatFiles.cleanName("a\u0000b.png"));
        assertEquals("file", ChatFiles.cleanName(".."));
        assertEquals("file", ChatFiles.cleanName(null));
        String longName = ChatFiles.cleanName("x".repeat(300) + ".jpeg");
        assertEquals(120, longName.length());
        assertEquals(".jpeg", longName.substring(115));
    }

    @Test
    void sizesReadLikeAFileManager() {
        assertEquals("512 B", ChatFile.sizeText(512, Locale.ENGLISH));
        assertEquals("3.4 kB", ChatFile.sizeText(3400, Locale.ENGLISH));
        assertEquals("12,0 MB", ChatFile.sizeText(12_000_000, Locale.GERMAN));
        assertEquals("2.1 GB", ChatFile.sizeText(2L * 1024 * 1024 * 1024, Locale.ENGLISH));
    }
}
