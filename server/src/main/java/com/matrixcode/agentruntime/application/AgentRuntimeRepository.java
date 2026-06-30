package com.matrixcode.agentruntime.application;

import com.matrixcode.agentruntime.domain.AgentRunEventRecord;
import com.matrixcode.agentruntime.domain.AgentRunRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Agent 运行记录仓储接口。
 *
 * <p>作用域：应用层持久化边界。主要场景是 Agent Runtime 服务保存运行主记录、追加事件、
 * 查询运行时间线，以及在 Worker 调度时原子认领、续约和回收运行租约。实现类负责屏蔽 MySQL、
 * MyBatis-Plus 或其他正式存储细节。</p>
 */
public interface AgentRuntimeRepository {

    /**
     * 保存一次智能体运行的主记录。
     *
     * <p>同一个 {@code id} 再次保存时覆盖主状态、摘要和时间字段，但不删除既有运行事件。
     * 仓储实现必须确保项目和操作者用户存在，以满足正式表外键约束。</p>
     *
     * @param run 智能体运行主记录，必须包含项目、角色、操作者、模型和目标。
     */
    void saveRun(AgentRunRecord run);

    /**
     * 追加智能体运行事件。
     *
     * <p>该方法只追加事件，不重写运行主记录，也不清空历史事件。重复事件 ID 属于调用方错误，
     * 持久化层应抛出异常，以避免审计事件被静默覆盖。</p>
     *
     * @param event 单条运行事件，通常对应步骤开始、工具调用、模型请求或失败摘要。
     */
    void appendEvent(AgentRunEventRecord event);

    /**
     * 按运行 ID 查询单次智能体运行主记录。
     *
     * <p>该查询用于失败恢复计划、审计详情和后续调度器读取运行来源。仓储只返回主记录，
     * 不联表加载事件，避免恢复判断误读大体积事件 payload 或扩大敏感数据读取面。</p>
     *
     * @param runId 智能体运行 ID。
     * @return 存在时返回运行主记录，否则返回空。
     */
    Optional<AgentRunRecord> findRun(String runId);

    /**
     * 从项目队列中原子认领下一条排队运行。
     *
     * <p>实现必须保证同一条 {@code QUEUED} 运行在并发情况下只会被一个调用方推进到
     * {@code RUNNING}。队列为空或条件更新竞争失败时返回空，不抛业务异常。</p>
     *
     * @param projectId 项目 ID。
     * @param claimedByUserId 认领人或 Worker ID。
     * @param claimedAt 认领时间。
     * @param claimExpiresAt 认领租约到期时间。
     * @return 成功认领时返回更新后的运行记录。
     */
    default Optional<AgentRunRecord> claimNextQueuedRun(
            String projectId,
            String claimedByUserId,
            Instant claimedAt,
            Instant claimExpiresAt
    ) {
        return Optional.empty();
    }

    /**
     * 续期一次运行中的 Agent 运行租约。
     *
     * <p>实现必须通过项目 ID、运行 ID、{@code RUNNING} 状态和认领人共同限定更新条件。
     * 如果运行不存在、状态已经结束或认领人不匹配，应返回空而不是覆盖记录，避免其他 Worker 的租约被误刷新。</p>
     *
     * @param projectId 项目 ID。
     * @param runId 运行 ID。
     * @param claimedByUserId 当前认领人或 Worker ID。
     * @param renewedAt 续期发生时间。
     * @param claimExpiresAt 新的租约到期时间。
     * @return 成功续期时返回更新后的运行记录。
     */
    default Optional<AgentRunRecord> renewClaimLease(
            String projectId,
            String runId,
            String claimedByUserId,
            Instant renewedAt,
            Instant claimExpiresAt
    ) {
        return Optional.empty();
    }

    /**
     * 回收租约已过期的运行中 Agent 运行。
     *
     * <p>实现必须只更新 {@code RUNNING} 且 {@code claim_expires_at <= now} 的记录，并在更新条件里再次校验
     * 到期时间，避免并发续租成功后仍被回收。返回值只包含本次成功更新为失败态的运行。</p>
     *
     * @param projectId 项目 ID。
     * @param now 当前时间。
     * @param limit 单次最多回收数量。
     * @param failureSummary 写入失败摘要的低敏文本。
     * @return 本次成功回收的运行记录。
     */
    default List<AgentRunRecord> expireRunningLeases(
            String projectId,
            Instant now,
            int limit,
            String failureSummary
    ) {
        return List.of();
    }

    /**
     * 查询项目最近的智能体运行。
     *
     * <p>结果按创建时间倒序返回，用于工作台、审计视图和后续运行中心展示。
     * {@code limit} 小于 1 时返回空列表。</p>
     *
     * @param projectId 项目 ID。
     * @param limit 最大返回条数。
     * @return 不可变运行记录列表。
     */
    List<AgentRunRecord> recentRuns(String projectId, int limit);

    /**
     * 查询单次智能体运行的事件流。
     *
     * <p>结果按发生时间正序返回，保留同一时间下的 ID 排序，便于复盘 Agent 执行过程。</p>
     *
     * @param runId 智能体运行 ID。
     * @return 不可变事件列表。
     */
    List<AgentRunEventRecord> eventsForRun(String runId);
}
