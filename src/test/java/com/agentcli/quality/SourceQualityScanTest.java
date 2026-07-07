package com.agentcli.quality;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceQualityScanTest {

    private static final List<String> FORBIDDEN_IDENTIFIERS = List.of(
            "com.paicli",
            "paicli_",
            "~/.paicli",
            " paicli "
    );

    @Test
    void sourceTreeShouldNotContainLegacyIdentifiers() throws IOException {
        Path sourceRoot = Path.of("src");
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            List<String> violations = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".java") || name.endsWith(".md")
                                || name.endsWith(".xml") || name.endsWith(".yml")
                                || name.endsWith(".yaml") || name.endsWith(".properties");
                    })
                    .filter(path -> !path.endsWith(Path.of("src", "test", "java", "com", "agentcli",
                            "quality", "SourceQualityScanTest.java")))
                    .map(SourceQualityScanTest::scanOne)
                    .filter(result -> result != null && !result.isBlank())
                    .toList();

            assertTrue(violations.isEmpty(), "发现疑似遗留标识:\n" + String.join("\n", violations));
        }
    }

    private static String scanOne(Path path) {
        try {
            String content = Files.readString(path).toLowerCase(Locale.ROOT);
            for (String token : FORBIDDEN_IDENTIFIERS) {
                if (content.contains(token)) {
                    return path + " contains: " + token;
                }
            }
            return null;
        } catch (IOException e) {
            return path + " unreadable: " + e.getMessage();
        }
    }
}

