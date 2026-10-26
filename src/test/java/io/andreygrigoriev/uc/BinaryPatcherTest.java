package io.andreygrigoriev.uc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryPatcherTest {

    private static final String BLOCK = "{window.cdc_adoQpoasnfa76pfcZLmcfl_Array = window.Array;}";
    private static final String MARKER = "{console.log(\"undetected chromedriver 1337!\")}";

    @TempDir
    Path dir;

    private static byte[] randomBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        // keep random filler from accidentally containing the block start
        for (int i = 0; i < b.length; i++) {
            if (b[i] == '{') {
                b[i] = 0x01;
            }
        }
        return b;
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private Path write(byte[] content) throws IOException {
        Path file = dir.resolve("chromedriver");
        Files.write(file, content);
        return file;
    }

    @Test
    void patchesOnlyTheBlockAndKeepsSize() throws IOException {
        byte[] head = randomBytes(500, 1);
        byte[] tail = randomBytes(700, 2);
        byte[] original = concat(head, latin1(BLOCK), tail);
        Path exe = write(original);

        assertThat(BinaryPatcher.isPatched(exe)).isFalse();
        assertThat(BinaryPatcher.patch(exe)).isTrue();

        byte[] patched = Files.readAllBytes(exe);
        assertThat(patched).hasSize(original.length);
        assertThat(Arrays.copyOfRange(patched, 0, head.length)).isEqualTo(head);
        assertThat(Arrays.copyOfRange(patched, head.length + BLOCK.length(), patched.length)).isEqualTo(tail);
        String replaced = new String(patched, head.length, BLOCK.length(), StandardCharsets.ISO_8859_1);
        assertThat(replaced).isEqualTo(MARKER + " ".repeat(BLOCK.length() - MARKER.length()));
        assertThat(BinaryPatcher.isPatched(exe)).isTrue();
    }

    @Test
    void patchingTwiceChangesNothing() throws IOException {
        Path exe = write(concat(randomBytes(300, 3), latin1(BLOCK), randomBytes(300, 4)));
        BinaryPatcher.patch(exe);
        byte[] once = Files.readAllBytes(exe);

        assertThat(BinaryPatcher.patch(exe)).isFalse();

        assertThat(Files.readAllBytes(exe)).isEqualTo(once);
    }

    @Test
    void matchesBlockContainingCarriageReturnAndNel() throws IOException {
        String block = "{window.cdc_x\r= window.Array;\u0085y = window.Object;}";
        byte[] original = concat(randomBytes(50, 5), latin1(block + " padding padding padding"));
        Path exe = write(original);

        assertThat(BinaryPatcher.patch(exe)).isTrue();

        assertThat(BinaryPatcher.isPatched(exe)).isTrue();
        assertThat(Files.readAllBytes(exe)).hasSize(original.length);
    }

    @Test
    void doesNotMatchBlockContainingNewline() throws IOException {
        byte[] original = concat(randomBytes(50, 6), latin1("{window.cdc_x\n= window.Array;}"), randomBytes(50, 7));
        Path exe = write(original);

        assertThat(BinaryPatcher.patch(exe)).isFalse();

        assertThat(Files.readAllBytes(exe)).isEqualTo(original);
    }

    @Test
    void replacesEveryOccurrence() throws IOException {
        byte[] original = concat(latin1(BLOCK), randomBytes(100, 8), latin1(BLOCK), randomBytes(100, 9));
        Path exe = write(original);

        BinaryPatcher.patch(exe);

        String content = new String(Files.readAllBytes(exe), StandardCharsets.ISO_8859_1);
        assertThat(content).doesNotContain("window.cdc");
        assertThat(content.split("undetected chromedriver 1337!", -1)).hasSize(3);
        assertThat(content).hasSize(original.length);
    }

    @Test
    void leavesFileUntouchedWhenNoBlockFound() throws IOException {
        byte[] original = randomBytes(1000, 10);
        Path exe = write(original);

        assertThat(BinaryPatcher.patch(exe)).isFalse();

        assertThat(Files.readAllBytes(exe)).isEqualTo(original);
        assertThat(BinaryPatcher.isPatched(exe)).isFalse();
    }

    @Test
    void refusesBlockShorterThanMarker() throws IOException {
        byte[] original = concat(randomBytes(40, 11), latin1("{window.cdc_a;}"), randomBytes(40, 12));
        Path exe = write(original);

        assertThat(BinaryPatcher.patch(exe)).isFalse();

        assertThat(Files.readAllBytes(exe)).isEqualTo(original);
    }

    @Test
    void isPatchedIsFalseForMissingFile() {
        assertThat(BinaryPatcher.isPatched(dir.resolve("missing"))).isFalse();
    }

    @Test
    void parsesEmbeddedExeVersion() throws IOException {
        byte[] content = concat(randomBytes(30, 13), latin1("platform_handle\0content\u0000131.0.6778.85\u0000"),
                randomBytes(30, 14));
        Path exe = write(content);

        assertThat(BinaryPatcher.parseExeVersion(exe)).contains(ChromeVersion.parse("131.0.6778.85"));
    }

    @Test
    void parseExeVersionIsEmptyWhenAbsentOrBlank() throws IOException {
        assertThat(BinaryPatcher.parseExeVersion(write(randomBytes(200, 15)))).isEmpty();
        assertThat(BinaryPatcher.parseExeVersion(write(latin1("platform_handle\0content\0\0")))).isEmpty();
    }
}
