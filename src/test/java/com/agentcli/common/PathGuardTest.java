package com.agentcli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathGuardTest {

    @Test
    void resolvesPathInsideRoot(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("sub/file.txt");
        assertTrue(resolved.startsWith(guard.getRootPath()));
        assertTrue(resolved.endsWith(Path.of("sub", "file.txt")));
    }

    @Test
    void rejectsParentTraversal(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("../../etc/passwd"));
        assertTrue(ex.getMessage().contains("越界"));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root, @TempDir Path other) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe(other.resolve("secret.txt").toString()));
    }

    @Test
    void rejectsBlankInput(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe("  "));
    }

    @Test
    void policyExceptionIsAgentException(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        try {
            guard.resolveSafe("../escape");
        } catch (AgentException e) {
            assertEquals(PolicyException.class, e.getClass());
            return;
        }
        throw new AssertionError("应抛出 PolicyException");
    }
}
