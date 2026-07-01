package com.agentcli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolParallelExecutionTest {

    @Test
    void emptyInvocationsReturnsEmptyList() {
        ToolRegistry registry = new ToolRegistry();
        assertEquals(List.of(), registry.executeTools(List.of()));
        assertEquals(List.of(), registry.executeTools(null));
    }

    @Test
    void singleInvocationUsesFastPath(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root.toString());
        ToolInvocation invocation = new ToolInvocation("tc1", "write_file",
                "{\"path\":\"solo.txt\",\"content\":\"only\"}");

        List<ToolExecutionResult> results = registry.executeTools(List.of(invocation));

        assertEquals(1, results.size());
        ToolExecutionResult result = results.get(0);
        assertEquals("tc1", result.id());
        assertEquals("write_file", result.name());
        assertFalse(result.timedOut());
        assertTrue(result.result().contains("文件已写入"), result.result());
    }

    @Test
    void parallelWritesPreserveInvocationOrder(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root.toString());
        List<ToolInvocation> invocations = List.of(
                new ToolInvocation("c1", "write_file", "{\"path\":\"a.txt\",\"content\":\"A\"}"),
                new ToolInvocation("c2", "write_file", "{\"path\":\"b.txt\",\"content\":\"B\"}"),
                new ToolInvocation("c3", "write_file", "{\"path\":\"c.txt\",\"content\":\"C\"}")
        );

        List<ToolExecutionResult> results = registry.executeTools(invocations);

        assertEquals(List.of("c1", "c2", "c3"), results.stream().map(ToolExecutionResult::id).toList());
        assertTrue(results.stream().allMatch(r -> !r.timedOut()));
        assertTrue(results.stream().allMatch(r -> r.result().contains("文件已写入")));
    }

    @Test
    void parallelReadsReturnResultsInInputOrder(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root.toString());
        registry.executeTool("write_file", "{\"path\":\"one.txt\",\"content\":\"FIRST\"}");
        registry.executeTool("write_file", "{\"path\":\"two.txt\",\"content\":\"SECOND\"}");

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("r2", "read_file", "{\"path\":\"two.txt\"}"),
                new ToolInvocation("r1", "read_file", "{\"path\":\"one.txt\"}")
        ));

        assertEquals(List.of("r2", "r1"), results.stream().map(ToolExecutionResult::id).toList());
        assertTrue(results.get(0).result().contains("SECOND"), results.get(0).result());
        assertTrue(results.get(1).result().contains("FIRST"), results.get(1).result());
    }

    @Test
    void slowToolsTimeOutWithinBatch(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root.toString(), 1L);
        registry.register(new SlowTool("slow_tool", 2_500));

        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("s1", "slow_tool", "{}"),
                new ToolInvocation("s2", "slow_tool", "{}")
        ));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ToolExecutionResult::timedOut));
        assertTrue(results.get(0).result().contains("超时"), results.get(0).result());
    }

    @Test
    void independentSlowToolsRunInParallel(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry(root.toString());
        registry.register(new SlowTool("slow_tool", 400));

        long started = System.nanoTime();
        List<ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolInvocation("p1", "slow_tool", "{}"),
                new ToolInvocation("p2", "slow_tool", "{}")
        ));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.result().equals("done")));
        // 顺序执行约 800ms+，并行应明显更短
        assertTrue(elapsedMs < 700, "expected parallel execution, took " + elapsedMs + "ms");
    }

    private static final class SlowTool extends AbstractTool {
        private final long sleepMillis;

        SlowTool(String name, long sleepMillis) {
            super(name, "test slow tool", JsonNodeFactory.instance.objectNode());
            this.sleepMillis = sleepMillis;
        }

        @Override
        public String execute(Map<String, String> args) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "interrupted";
            }
            return "done";
        }
    }
}
