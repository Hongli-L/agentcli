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

    private final PathGuard pathGuard;
    private final Path projectDir;

    public MentionResolver(Path projectDir) {
        this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath().normalize();
        this.pathGuard = this.projectDir == null ? null : new PathGuard(this.projectDir.toString());
    }

    /**
     * 自由文本解析：扫描 {@code @path} 并内联替换；null / blank 原样返回。
     */
    public String resolve(String text) {
        if (text == null || text.isBlank() || pathGuard == null) {
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
        if (argumentsJson == null || argumentsJson.isBlank() || pathGuard == null) {
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

    /** 解析单个 @token：展开 ~ -> 路径安全校验 -> 读取内容 -> 内联块；失败返回标记。 */
    private String resolveMention(String token) {
        String candidate = expandTilde(token);
        try {
            Path safe = pathGuard.resolveSafe(candidate);
            byte[] raw = Files.readAllBytes(safe);
            // 归一化为正斜杠，保证内联块路径跨平台一致（Windows 默认分隔符为反斜杠）。
            String relPath = projectDir.relativize(safe).toString().replace('\\', '/');
            String content;
            if (raw.length > MentionPolicy.MAX_INLINE_BYTES) {
                content = new String(raw, 0, MentionPolicy.MAX_INLINE_BYTES, StandardCharsets.UTF_8);
                content = content + "\n" + MentionPolicy.truncatedMarker(raw.length);
            } else {
                content = new String(raw, StandardCharsets.UTF_8);
            }
            return MentionPolicy.inlineFormat(relPath, content);
        } catch (PolicyException e) {
            return MentionPolicy.mentionError(token, e.getMessage());
        } catch (IOException e) {
            return MentionPolicy.mentionError(token, "read failed: " + e.getMessage());
        } catch (Exception e) {
            return MentionPolicy.mentionError(token, e.getMessage());
        }
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
