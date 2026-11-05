package com.agentcli.mcp.mention;

import com.agentcli.common.PathGuard;
import com.agentcli.common.PolicyException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;

/**
 * @mention 解析的唯一入口：将自由文本或工具参数 JSON 中的 {@code @path} 替换为项目内联文本块。
 *
 * <p>设计要点：
 * <ul>
 *   <li>路径安全单一真相源是 {@link PathGuard#resolveSafe}；本类捕获 {@link PolicyException} 后
 *       转为 {@code [mention error: ...]} 标记，不向上抛，绝不中断主流程。</li>
 *   <li>两个入口（{@link #resolve} / {@link #resolveJson}）共用 {@link #resolveMention} 一套逻辑。</li>
 *   <li>{@code ~} 展开：仅 {@code ~/} 与裸 {@code ~} 受支持；展开后仍需落在 projectDir 内
 *       （多数用户 home 不在项目内，{@code ~/x} 几乎总被拒）——这是 PRD 默认行为，详见系统架构设计 §8.2。</li>
 * </ul>
 */
public class MentionResolver {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 主目录下被视为凭证、禁止内联的子目录（相对 home 的顶层段）。 */
    private static final java.util.Set<String> SENSITIVE_HOME_SEGMENTS =
            java.util.Set.of(".ssh", ".aws", ".gnupg", ".netrc");

    private final PathGuard projectGuard;
    private final Path projectDir;
    /** 额外允许根（如 user.home），用于放宽 @路径 的 ~ 引用；每个根各自独立校验，越不出其边界。 */
    private final java.util.List<Path> extraRoots;
    private final java.util.List<PathGuard> extraGuards;
    private final Path homeDirPath;

    public MentionResolver(Path projectDir) {
        this(projectDir, (Path[]) null);
    }

    public MentionResolver(Path projectDir, Path... extraRoots) {
        this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath().normalize();
        this.projectGuard = this.projectDir == null ? null : new PathGuard(this.projectDir.toString());
        String h = homeDir();
        this.homeDirPath = h.isEmpty() ? null : Paths.get(h).toAbsolutePath().normalize();
        this.extraRoots = new java.util.ArrayList<>();
        this.extraGuards = new java.util.ArrayList<>();
        if (extraRoots != null) {
            for (Path r : extraRoots) {
                if (r == null) {
                    continue;
                }
                Path normalized = r.toAbsolutePath().normalize();
                this.extraRoots.add(normalized);
                this.extraGuards.add(new PathGuard(normalized.toString()));
            }
        }
    }

    /**
     * 自由文本解析：扫描 {@code @path} 并内联替换；null / blank 原样返回。
     */
    public String resolve(String text) {
        if (text == null || text.isBlank() || projectGuard == null) {
            return text;
        }
        Matcher matcher = MentionPolicy.MENTION_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String replacement = resolveMention(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 工具参 JSON：解析后遍历字符串叶子节点做 resolve，再重序列化；
     * 解析 / 读取失败 -> 回退返回原始 argumentsJson（降级，不抛）。
     */
    public String resolveJson(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank() || projectGuard == null) {
            return argumentsJson;
        }
        try {
            JsonNode root = MAPPER.readTree(argumentsJson);
            JsonNode resolved = resolveNode(root);
            return MAPPER.writeValueAsString(resolved);
        } catch (Exception ignored) {
            return argumentsJson;
        }
    }

    private JsonNode resolveNode(JsonNode node) {
        if (node == null) {
            return node;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(resolve(node.asText()));
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> out.set(entry.getKey(), resolveNode(entry.getValue())));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> out.add(resolveNode(child)));
            return out;
        }
        return node;
    }

    /** 解析单个 @token：展开 ~ -> 多根路径安全校验 -> 读取内容 -> 内联块；失败返回友好标记。 */
    private String resolveMention(String token) {
        ResolvedPath r = resolvePathSafe(token);
        if (r == null) {
            return MentionPolicy.mentionError(token, friendlyReason(token));
        }
        if (r.blockReason() != null) {
            return MentionPolicy.mentionError(token, r.blockReason());
        }
        try {
            byte[] raw = Files.readAllBytes(r.safe());
            // 归一化为正斜杠，保证内联块路径跨平台一致（Windows 默认分隔符为反斜杠）。
            String content;
            if (raw.length > MentionPolicy.MAX_INLINE_BYTES) {
                content = new String(raw, 0, MentionPolicy.MAX_INLINE_BYTES, StandardCharsets.UTF_8);
                content = content + "\n" + MentionPolicy.truncatedMarker(raw.length);
            } else {
                content = new String(raw, StandardCharsets.UTF_8);
            }
            return MentionPolicy.inlineFormat(r.relPath(), content);
        } catch (IOException e) {
            return MentionPolicy.mentionError(token, "read failed: " + e.getMessage());
        } catch (Exception e) {
            return MentionPolicy.mentionError(token, e.getMessage());
        }
    }

    /**
     * 路径安全解析的单一真相源：展开 ~ -> 多根 {@link PathGuard#resolveSafe} 校验 -> 敏感子目录拦截。
     * 文本内联（{@link #resolveMention}）与图片引用（{@link ImageMentionParser}）共用，确保两者遵守
     * 完全相同的「项目内 / 主目录下允许根 / 凭证子目录禁止」策略。
     *
     * @return 解析结果；{@code safe == null} 表示越出所有允许根，{@code blockReason != null} 表示被策略拦截
     */
    ResolvedPath resolvePathSafe(String token) {
        String candidate = expandTilde(token);
        Path safe = null;
        String relPath = null;

        // 1) 先试项目根
        if (projectGuard != null) {
            try {
                Path s = projectGuard.resolveSafe(candidate);
                safe = s;
                relPath = projectDir.relativize(s).toString().replace('\\', '/');
            } catch (PolicyException ignored) {
                // 落到额外根再试
            }
        }
        // 2) 再试额外允许根（如 user.home）：命中后用 ~ 前缀显示更友好
        if (safe == null) {
            for (int i = 0; i < extraGuards.size(); i++) {
                PathGuard g = extraGuards.get(i);
                try {
                    Path s = g.resolveSafe(candidate);
                    safe = s;
                    Path root = extraRoots.get(i);
                    String rel = root.relativize(s).toString().replace('\\', '/');
                    relPath = root.equals(homeDirPath) ? "~/" + rel : rel;
                    break;
                } catch (PolicyException ignored) {
                    // 试下一个根
                }
            }
        }

        if (safe == null) {
            return null;
        }
        // home 下凭证子目录禁止读取，防 ~/.ssh / ~/.aws 等泄露
        if (relPath != null && relPath.startsWith("~/") && isSensitiveUnderHome(relPath)) {
            return new ResolvedPath(null, relPath, "sensitive path blocked: " + relPath);
        }
        return new ResolvedPath(safe, relPath, null);
    }

    /** 路径安全解析结果：{@code safe} 为 null 表示越界；{@code blockReason} 非 null 表示被策略拦截。 */
    record ResolvedPath(Path safe, String relPath, String blockReason) {
    }

    /** 全部根拒绝时给出可读原因，引导用户用项目内路径或 ~ 引用。 */
    private String friendlyReason(String candidate) {
        if (candidate.equals("~") || candidate.startsWith("~/") || candidate.startsWith(homeDir())) {
            return "展开后需位于项目目录或主目录(~)内";
        }
        if (!extraGuards.isEmpty()) {
            return "路径需位于项目目录或主目录(~)内";
        }
        return "路径需位于项目目录内";
    }

    /** 仅当 ~ 引用命中 home 下凭证子目录（.ssh/.aws/.gnupg/.netrc）时为真。 */
    private boolean isSensitiveUnderHome(String relPath) {
        String[] parts = relPath.substring(2).split("/");
        return parts.length > 0 && SENSITIVE_HOME_SEGMENTS.contains(parts[0]);
    }

    /** 仅展开领先的 {@code ~/} 与裸 {@code ~}；其余形式原样返回（交给 PathGuard 校验）。 */
    private String expandTilde(String p) {
        if (p == null) {
            return null;
        }
        if (p.equals("~")) {
            return homeDir();
        }
        if (p.startsWith("~/")) {
            return homeDir() + p.substring(1); // 把 ~ 换成 home，保留 / 之后部分
        }
        return p;
    }

    private static String homeDir() {
        String home = System.getProperty("user.home");
        return home == null ? "" : home;
    }
}
