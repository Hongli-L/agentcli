package com.agentcli.cli;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 基于 JLine {@link Completer} 的 REPL 前缀补全器。
 *
 * <p>补全语义：只向候选列表追加 {@link Candidate}，从不修改用户输入缓冲区（提交文本始终是原始输入）。
 * <ul>
 *   <li>行首 token 以 {@code /} 开头：斜杠命令目录大小写不敏感前缀匹配；</li>
 *   <li>行首 token 以 {@code -} 开头：CliArgs 实际参数名大小写敏感前缀匹配；</li>
 *   <li>命令之后的取值 token：按首 token 选择对应运行期 Supplier 取候选。</li>
 * </ul>
 *
 * <p>运行期组件经 {@link Supplier} 注入，避免本类直接依赖业务包；任意 Supplier 为
 * {@code null} 或返回空列表时均安全回退为空候选。
 */
public final class ReplCompleter implements Completer {

    /** 启动参数名（与 CliArgs 实际字段保持一致，大小写敏感）。 */
    private static final List<String> OPTION_NAMES = List.of(
            "--help", "--version", "--hitl", "--lsp",
            "--prompt", "--provider", "--project", "--no-color",
            "-h", "-v", "-p", "-C");

    private final Supplier<List<String>> providers;
    private final Supplier<List<String>> skillNames;
    private final Supplier<List<String>> serverNames;

    /**
     * @param providers   {@code /model} 之后的 provider 候选来源（如 LlmClientFactory::knownProviders）
     * @param skillNames  {@code /skill show|on|off} 之后的 skill 名候选来源
     * @param serverNames {@code /mcp logs|status} 之后的 server 名候选来源
     */
    public ReplCompleter(Supplier<List<String>> providers,
                         Supplier<List<String>> skillNames,
                         Supplier<List<String>> serverNames) {
        this.providers = providers;
        this.skillNames = skillNames;
        this.serverNames = serverNames;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null || candidates == null) {
            return;
        }
        String word = line.word() == null ? "" : line.word();
        List<String> words = safeWords(line);
        int wordIndex = line.wordIndex();

        if (wordIndex <= 0) {
            // 行首 token：命令或启动参数补全
            if (word.startsWith("/")) {
                addCommandCandidates(candidates, word);
            } else if (word.startsWith("-")) {
                addOptionCandidates(candidates, word);
            }
            return;
        }

        // 取值 token：依据首 token（命令）与次 token（子命令）选择运行期 Supplier
        if (words.isEmpty()) {
            return;
        }
        String command = words.get(0).toLowerCase(Locale.ROOT);
        String sub = words.size() > 1 ? words.get(1).toLowerCase(Locale.ROOT) : "";

        if ("/model".equals(command)) {
            addSupplierCandidates(candidates, providers, word);
        } else if ("/skill".equals(command)
                && ("show".equals(sub) || "on".equals(sub) || "off".equals(sub))) {
            addSupplierCandidates(candidates, skillNames, word);
        } else if ("/mcp".equals(command)
                && ("logs".equals(sub) || "status".equals(sub))) {
            addSupplierCandidates(candidates, serverNames, word);
        }
    }

    private static void addCommandCandidates(List<Candidate> candidates, String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        for (String cmd : CliCommandParser.commandNames()) {
            if (cmd.toLowerCase(Locale.ROOT).startsWith(lower)) {
                candidates.add(new Candidate(cmd));
            }
        }
    }

    private static void addOptionCandidates(List<Candidate> candidates, String word) {
        for (String opt : OPTION_NAMES) {
            if (opt.startsWith(word)) {
                candidates.add(new Candidate(opt));
            }
        }
    }

    private static void addSupplierCandidates(List<Candidate> candidates,
                                             Supplier<List<String>> supplier, String word) {
        List<String> values = supplier == null ? List.of() : supplier.get();
        if (values == null || values.isEmpty()) {
            return;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                candidates.add(new Candidate(value));
            }
        }
    }

    private static List<String> safeWords(ParsedLine line) {
        List<String> words = line.words();
        return words == null ? List.of() : words;
    }
}
