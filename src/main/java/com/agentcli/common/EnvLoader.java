package com.agentcli.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * 统一的配置值读取入口。
 *
 * 查找顺序：系统环境变量 -> JVM 系统属性 -> 项目根 {@code .env} -> 用户主目录 {@code ~/.env}。
 * 这样既支持容器/CI 用环境变量注入，也支持本地开发用 .env 文件。
 */
public final class EnvLoader {

    private EnvLoader() {
    }

    /** 按既定优先级读取配置值，全部缺失时返回 {@code null}。 */
    public static String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String fromEnv = System.getenv(key);
        if (isPresent(fromEnv)) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (isPresent(fromProp)) {
            return fromProp.trim();
        }
        return readFromDotEnv(key);
    }

    /** 读取配置值，缺失时返回给定默认值。 */
    public static String get(String key, String defaultValue) {
        String value = get(key);
        return isPresent(value) ? value : defaultValue;
    }

    private static String readFromDotEnv(String key) {
        File[] envFiles = {
                new File(".env"),
                new File(System.getProperty("user.home"), ".env")
        };
        for (File envFile : envFiles) {
            if (!envFile.exists()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith(key + "=")) {
                        return line.substring((key + "=").length()).trim();
                    }
                }
            } catch (IOException ignored) {
                // .env 读取失败时退回下一个候选文件，不阻塞启动。
            }
        }
        return null;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
