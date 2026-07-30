package com.agentcli.cli;

import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplCompleterTest {

    private static ReplCompleter completer() {
        return new ReplCompleter(
                () -> List.of("glm", "deepseek", "openai"),
                () -> List.of("skill-a", "skill-b"),
                () -> List.of("server-x", "server-y"));
    }

    private static List<String> complete(String line) {
        List<Candidate> candidates = new ArrayList<>();
        completer().complete(null, parsed(line), candidates);
        return candidates.stream().map(Candidate::value).toList();
    }

    @Test
    void slashPrefixCompletesToPlan() {
        assertTrue(complete("/pl").contains("/plan"), complete("/pl").toString());
    }

    @Test
    void slashRootListsAllCommands() {
        List<String> c = complete("/");
        assertTrue(c.contains("/plan"));
        assertTrue(c.contains("/memory"));
        assertTrue(c.contains("/model"));
    }

    @Test
    void memAliasCompletesToMemory() {
        assertTrue(complete("/mem").contains("/memory"));
    }

    @Test
    void doubleDashPrefixCompletesOptions() {
        List<String> c = complete("--pr");
        assertTrue(c.contains("--provider"));
        assertTrue(c.contains("--project"));
    }

    @Test
    void commandCompletionIsCaseInsensitive() {
        assertTrue(complete("/PL").contains("/plan"));
        assertTrue(complete("/Mem").contains("/memory"));
    }

    @Test
    void optionCompletionIsCaseSensitive() {
        // 小写 --pr 命中；--PR 不应命中（选项大小写敏感）
        assertFalse(complete("--PR").contains("--provider"));
    }

    @Test
    void modelValueUsesProviderSupplier() {
        List<String> c = complete("/model ");
        assertTrue(c.contains("glm"));
        assertTrue(c.contains("deepseek"));
        assertTrue(c.contains("openai"));
    }

    @Test
    void skillOnValueUsesSkillSupplier() {
        List<String> c = complete("/skill on ");
        assertTrue(c.contains("skill-a"));
        assertTrue(c.contains("skill-b"));
    }

    @Test
    void mcpLogsValueUsesServerSupplier() {
        List<String> c = complete("/mcp logs ");
        assertTrue(c.contains("server-x"));
        assertTrue(c.contains("server-y"));
    }

    @Test
    void missingSupplierFallsBackToNoCandidates() {
        ReplCompleter empty = new ReplCompleter(null, null, null);
        List<Candidate> candidates = new ArrayList<>();
        empty.complete(null, parsed("/model "), candidates);
        assertTrue(candidates.isEmpty());
    }

    @Test
    void completionDoesNotMutateInputBuffer() {
        String line = "/pl";
        List<Candidate> candidates = new ArrayList<>();
        completer().complete(null, parsed(line), candidates);
        assertEquals("/pl", line, "completer 不应修改用户输入");
        assertFalse(candidates.isEmpty());
    }

    /** 构造一个最小 ParsedLine：行按空格切分，最后一个 token 为当前 word。 */
    private static ParsedLine parsed(String line) {
        List<String> words = new ArrayList<>();
        if (line.isEmpty()) {
            words.add("");
        } else {
            for (String p : line.split(" ", -1)) {
                words.add(p);
            }
        }
        int wordIndex = words.size() - 1;
        String word = words.get(wordIndex);
        return new ParsedLine() {
            @Override
            public String line() {
                return line;
            }

            @Override
            public String word() {
                return word;
            }

            @Override
            public int wordIndex() {
                return wordIndex;
            }

            @Override
            public int wordCursor() {
                return word.length();
            }

            @Override
            public int cursor() {
                return line.length();
            }

            @Override
            public List<String> words() {
                return words;
            }
        };
    }
}
