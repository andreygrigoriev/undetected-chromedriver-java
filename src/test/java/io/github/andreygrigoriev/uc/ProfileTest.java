package io.github.andreygrigoriev.uc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProfileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private Path writePrefs(Path dir, String json) throws Exception {
        Path file = dir.resolve("Default").resolve("Preferences");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
        return file;
    }

    @Test
    void fixExitTypeNullsValueAndKeepsOtherKeys() throws Exception {
        Path file = writePrefs(root, "{\"profile\":{\"exit_type\":\"Crashed\",\"name\":\"x\"},\"other\":1}");
        Profile.of(root, true).fixExitType();
        JsonNode tree = MAPPER.readTree(file.toFile());
        assertThat(tree.get("profile").get("exit_type").isNull()).isTrue();
        assertThat(tree.get("profile").get("name").asText()).isEqualTo("x");
        assertThat(tree.get("other").asInt()).isEqualTo(1);
    }

    @Test
    void fixExitTypeLeavesFileWithoutExitTypeUntouched() throws Exception {
        String json = "{\"profile\":{\"name\":\"x\"}}";
        Path file = writePrefs(root, json);
        Profile.of(root, true).fixExitType();
        assertThat(Files.readString(file)).isEqualTo(json);
    }

    @Test
    void fixExitTypeWithMissingFileIsFine() {
        assertThatCode(() -> Profile.of(root, true).fixExitType()).doesNotThrowAnyException();
    }

    @Test
    void fixExitTypeWithCorruptJsonDoesNotThrow() throws Exception {
        Path file = writePrefs(root, "{not json");
        assertThatCode(() -> Profile.of(root, true).fixExitType()).doesNotThrowAnyException();
        assertThat(Files.readString(file)).isEqualTo("{not json");
    }

    @Test
    void keptProfileSurvivesDelete() throws Exception {
        Path dir = Files.createDirectory(root.resolve("mine"));
        Files.writeString(dir.resolve("f"), "x");
        Profile profile = Profile.of(dir, true);
        profile.delete();
        assertThat(dir.resolve("f")).exists();
        assertThat(profile.keep()).isTrue();
    }

    @Test
    void temporaryProfileIsRemoved() throws Exception {
        Profile profile = Profile.temporary();
        Files.createDirectories(profile.dir().resolve("Default"));
        Files.writeString(profile.dir().resolve("Default").resolve("f"), "x");
        assertThat(profile.keep()).isFalse();
        profile.delete();
        assertThat(profile.dir()).doesNotExist();
        assertThatCode(profile::delete).doesNotThrowAnyException();
    }

    @Test
    void deleteGivesUpQuietlyWhenDirectoryCannotBeRemoved() throws Exception {
        Path dir = Files.createDirectory(root.resolve("locked"));
        Path inner = Files.createDirectory(dir.resolve("inner"));
        Files.writeString(inner.resolve("f"), "x");
        assumeTrue(inner.toFile().setWritable(false) && !Files.isWritable(inner), "cannot make a dir read-only");
        try {
            assertThatCode(() -> Profile.of(dir, false).delete()).doesNotThrowAnyException();
            assertThat(inner.resolve("f")).exists();
        } finally {
            inner.toFile().setWritable(true);
        }
    }
}
