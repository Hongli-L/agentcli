package com.agentcli.plan;

import com.agentcli.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Execute 任务状态的 SQLite 持久化（对应参考实现的 {@code DurableTaskManager}）。
 *
 * <p>把 {@link ExecutionPlan} 与其 {@link Task} 的状态落盘，支持进程崩溃 / 重启后按 plan_id
 * 恢复并续跑未完成任务。持久化失败仅告警、不中断主流程。
 */
public class PlanStore {

    private final SqliteStore store;

    public PlanStore(SqliteStore store) {
        this.store = store;
    }

    /** 持久化整个计划（plan 元信息 + 所有任务）。 */
    public void savePlan(ExecutionPlan plan) {
        try (Connection conn = store.connect()) {
            long now = System.currentTimeMillis();
            upsertPlan(conn, plan, now);
            for (Task task : plan.getAllTasks()) {
                upsertTask(conn, plan.getId(), task, now);
            }
        } catch (SQLException e) {
            System.err.println("Plan 持久化失败: " + e.getMessage());
        }
    }

    /** 持久化单个任务状态变更。 */
    public void saveTask(String planId, Task task) {
        try (Connection conn = store.connect()) {
            upsertTask(conn, planId, task, System.currentTimeMillis());
        } catch (SQLException e) {
            System.err.println("Plan 任务持久化失败: " + e.getMessage());
        }
    }

    /** 从库重建计划（含任务状态）。无记录返回 null。 */
    public ExecutionPlan loadPlan(String planId) {
        try (Connection conn = store.connect()) {
            String goal;
            ExecutionPlan.PlanStatus status;
            String summary;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT goal, status, summary FROM plans WHERE plan_id = ?")) {
                ps.setString(1, planId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    goal = rs.getString("goal");
                    status = ExecutionPlan.PlanStatus.valueOf(rs.getString("status"));
                    summary = rs.getString("summary");
                }
            }
            ExecutionPlan plan = new ExecutionPlan(planId, goal);
            if (summary != null) {
                plan.setSummary(summary);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT task_id, description, type, status, dependencies, result_summary "
                            + "FROM plan_tasks WHERE plan_id = ?")) {
                ps.setString(1, planId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String taskId = rs.getString("task_id");
                        String description = rs.getString("description");
                        Task.TaskType type = Task.TaskType.valueOf(rs.getString("type"));
                        Task.TaskStatus taskStatus = Task.TaskStatus.valueOf(rs.getString("status"));
                        List<String> deps = parseDeps(rs.getString("dependencies"));
                        Task task = new Task(taskId, description, type, deps);
                        plan.addTask(task);
                        task.restoreState(taskStatus, rs.getString("result_summary"), null);
                    }
                }
            }
            plan.restoreStatus(status);
            return plan;
        } catch (SQLException e) {
            System.err.println("Plan 恢复失败: " + e.getMessage());
            return null;
        }
    }

    /** 查找最近一个可恢复的 plan（状态为 CREATED / RUNNING）。无则返回 null。 */
    public String findResumablePlanId() {
        try (Connection conn = store.connect();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT plan_id FROM plans WHERE status IN ('CREATED','RUNNING') "
                             + "ORDER BY updated_at DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("plan_id") : null;
            }
        } catch (SQLException e) {
            System.err.println("查找可恢复 plan 失败: " + e.getMessage());
            return null;
        }
    }

    private void upsertPlan(Connection conn, ExecutionPlan plan, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO plans(plan_id, goal, status, summary, created_at, updated_at) "
                        + "VALUES(?,?,?,?,?,?) "
                        + "ON CONFLICT(plan_id) DO UPDATE SET goal=excluded.goal, "
                        + "status=excluded.status, summary=excluded.summary, updated_at=excluded.updated_at")) {
            ps.setString(1, plan.getId());
            ps.setString(2, plan.getGoal());
            ps.setString(3, plan.getStatus().name());
            ps.setString(4, plan.getSummary());
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.executeUpdate();
        }
    }

    private void upsertTask(Connection conn, String planId, Task task, long now) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO plan_tasks(plan_id, task_id, description, type, status, dependencies, result_summary, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(plan_id, task_id) DO UPDATE SET description=excluded.description, "
                        + "type=excluded.type, status=excluded.status, dependencies=excluded.dependencies, "
                        + "result_summary=excluded.result_summary, updated_at=excluded.updated_at")) {
            ps.setString(1, planId);
            ps.setString(2, task.getId());
            ps.setString(3, task.getDescription());
            ps.setString(4, task.getType().name());
            ps.setString(5, task.getStatus().name());
            ps.setString(6, String.join(",", task.getDependencies()));
            ps.setString(7, task.getResult());
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    private static List<String> parseDeps(String raw) {
        List<String> deps = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String s : raw.split(",")) {
                if (!s.isBlank()) {
                    deps.add(s.trim());
                }
            }
        }
        return deps;
    }
}
