package com.agentcli.mcp.mention;

import com.agentcli.image.ClipboardImage;
import com.agentcli.image.ImageProcessor;
import com.agentcli.llm.LlmClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片 @mention 解析：把自由文本里的 {@code @image:<path>} / {@code @clipboard} 转成多模态用户消息
 * （文本部件 + 图片部件），让 agent 能「看图」。这是对照 reference 人用体验补齐的最大短板。
 *
 * <p>设计要点：
 * <ul>
 *   <li>路径安全复用 {@link MentionResolver#resolvePathSafe}——与文本 @mention 遵守同一套「项目内 / 主目录允许根 /
 *       凭证子目录禁止」策略，单一真相源在 {@link MentionResolver}。</li>
 *   <li>无效引用（越界 / 敏感 / 文件不存在 / 非图片）不中断主流程，只插入一行可读 note，由模型决定是否追问。</li>
 *   <li>无 {@code @image} / {@code @clipboard} 时直接返回 {@link LlmClient.Message#user(String)}，行为与原纯文本一致。</li>
 *   <li>零额外依赖：图片解码/压缩走 JDK {@code javax.imageio}，剪贴板走 AWT（headless 时优雅降级为 note）。</li>
 * </ul>
 */
public class ImageMentionParser {

    // 同时识别 @image:<path> / @image:path 与 @clipboard。命中 image 时 group(1) 为路径；
    // 命中 @clipboard 时 group(1) 为 null。裸路径分支排除 CJK 符号 / 全角标点，避免 "@image:./a.png。这是什么" 吞掉句号。
    private static final Pattern IMAGE_REF = Pattern.compile(
            "@image:(<[^>]+>|[^\\s<>\\u2010-\\u206F\\u3000-\\u303F\\uFF00-\\uFFEF]+)"
                    + "|@clipboard(?![\\p{L}\\p{N}_])");
    private static final String CLIPBOARD_TOKEN = "@clipboard";

    private ImageMentionParser() {
    }

    /**
     * 把用户输入解析为多模态用户消息。
     *
     * @param input         原始用户输入（已做过文本 @mention 内联）
     * @param baseDir       相对图片路径的解析基目录（通常为项目根）
     * @param mentionResolver 文本 @mention 解析器，复用其路径安全逻辑；可为 null（退化为仅相对/绝对路径）
     */
    public static LlmClient.Message userMessage(String input, Path baseDir, MentionResolver mentionResolver) {
        String rawInput = input == null ? "" : input;
        List<ImageRef> refs = findRefs(rawInput);
        if (refs.isEmpty()) {
            return LlmClient.Message.user(rawInput);
        }

        String textWithoutRefs = stripRefs(rawInput).trim();
        if (textWithoutRefs.isBlank()) {
            textWithoutRefs = "请分析以下图片。";
        }

        List<String> notes = new ArrayList<>();
        List<LlmClient.ContentPart> imageParts = new ArrayList<>();

        for (ImageRef ref : refs) {
            ImagePayload payload = loadImage(ref.value(), baseDir, mentionResolver);
            String displayLabel = displayLabelFor(ref, payload);
            if (!payload.ok()) {
                notes.add("[图片引用无效: " + displayLabel + "，原因: " + payload.error() + "]");
                continue;
            }
            imageParts.add(ImageProcessor.toContentPart(payload.image()));
            String metadataText = ImageProcessor.createMetadataText(payload.image());
            if (metadataText != null) {
                notes.add(metadataText);
            }
        }

        List<LlmClient.ContentPart> parts = new ArrayList<>();
        StringBuilder textPart = new StringBuilder(textWithoutRefs)
                .append("\n\n")
                .append("[图片已作为图片附件附加。请直接观察本轮图片内容；除非用户明确要求结合历史，历史对话、历史工具结果、网页/仓库信息都只能作为背景，不能替代当前图片内容；如果当前图片与历史上下文冲突，以当前图片为准。不要调用 MCP、文件系统或浏览器工具重新读取图片；如果你无法直接观察附件，请明确说明无法看图，不要根据路径或历史上下文猜测。]");
        if (!notes.isEmpty()) {
            textPart.append("\n").append(String.join("\n", notes));
        }
        parts.add(LlmClient.ContentPart.text(textPart.toString()));
        parts.addAll(imageParts);

        boolean hasImage = parts.stream().anyMatch(LlmClient.ContentPart::isImage);
        if (hasImage) {
            return LlmClient.Message.user(parts);
        }
        return LlmClient.Message.user(textWithoutRefs + "\n\n" + String.join("\n", notes));
    }

    private static List<ImageRef> findRefs(String input) {
        Matcher matcher = IMAGE_REF.matcher(input);
        List<ImageRef> refs = new ArrayList<>();
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null) {
                refs.add(new ImageRef(CLIPBOARD_TOKEN));
                continue;
            }
            if (raw.startsWith("<") && raw.endsWith(">")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            refs.add(new ImageRef(raw));
        }
        return refs;
    }

    private static String stripRefs(String input) {
        return IMAGE_REF.matcher(input).replaceAll("").replaceAll("[ \\t]+\\n", "\n").trim();
    }

    private static ImagePayload loadImage(String rawPath, Path baseDir, MentionResolver mentionResolver) {
        try {
            Path resolvedPath = null;
            if (CLIPBOARD_TOKEN.equals(rawPath)) {
                ClipboardImage.GrabResult grab = ClipboardImage.grab();
                if (!grab.ok()) {
                    return ImagePayload.error(grab.error());
                }
                resolvedPath = grab.path();
                rawPath = resolvedPath.toString();
            } else {
                // 路径安全交给 MentionResolver（与文本 @mention 同策略）；无 resolver 时退化为相对/绝对路径解析。
                if (mentionResolver != null) {
                    MentionResolver.ResolvedPath r = mentionResolver.resolvePathSafe(rawPath);
                    if (r == null) {
                        return ImagePayload.error("路径需位于项目目录或主目录(~)内");
                    }
                    if (r.blockReason() != null) {
                        return ImagePayload.error(r.blockReason());
                    }
                    resolvedPath = r.safe();
                } else {
                    resolvedPath = resolvePath(rawPath, baseDir);
                }
            }
            if (!Files.exists(resolvedPath)) {
                return ImagePayload.error("文件不存在");
            }
            if (!Files.isRegularFile(resolvedPath)) {
                return ImagePayload.error("不是普通文件");
            }
            String mimeType = detectMimeType(resolvedPath);
            if (!mimeType.startsWith("image/")) {
                return ImagePayload.error("不是受支持的图片 MIME 类型: " + mimeType);
            }
            ImageProcessor.ProcessedImage image = ImageProcessor.fromPath(resolvedPath, mimeType);
            return ImagePayload.ok(image);
        } catch (Exception e) {
            return ImagePayload.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String displayLabelFor(ImageRef ref, ImagePayload payload) {
        if (CLIPBOARD_TOKEN.equals(ref.value())) {
            if (payload.ok() && payload.path() != null) {
                return "剪贴板 (" + payload.path().getFileName() + ")";
            }
            return "剪贴板";
        }
        return ref.value();
    }

    private static Path resolvePath(String rawPath, Path baseDir) {
        String value = rawPath == null ? "" : rawPath.trim();
        if (value.startsWith("file://")) {
            return Path.of(fileUriToLocalPath(value)).normalize();
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            Path root = baseDir == null ? Path.of(System.getProperty("user.dir")) : baseDir;
            path = root.resolve(path);
        }
        return path.normalize();
    }

    private static String fileUriToLocalPath(String value) {
        String afterScheme = value.substring("file://".length());
        String pathPart;
        if (afterScheme.startsWith("/")) {
            pathPart = afterScheme;
        } else {
            int slashIdx = afterScheme.indexOf('/');
            pathPart = slashIdx < 0 ? "/" + afterScheme : afterScheme.substring(slashIdx);
        }
        return percentDecodeUtf8(pathPart);
    }

    private static String percentDecodeUtf8(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            byte[] bytes = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeBytes(bytes);
            i++;
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String detectMimeType(Path path) throws IOException {
        String probed = Files.probeContentType(path);
        if (probed != null && !probed.isBlank()) {
            return probed;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private record ImageRef(String value) {
    }

    private record ImagePayload(boolean ok, ImageProcessor.ProcessedImage image, String error) {
        Path path() {
            return image == null ? null : image.sourcePath();
        }

        static ImagePayload ok(ImageProcessor.ProcessedImage image) {
            return new ImagePayload(true, image, null);
        }

        static ImagePayload error(String error) {
            return new ImagePayload(false, null, error);
        }
    }
}
