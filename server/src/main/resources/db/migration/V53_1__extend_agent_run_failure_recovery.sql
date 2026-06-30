alter table matrixcode_agent_runs
    add column failure_summary text comment '智能体运行失败摘要，保存可展示的短错误结论，不保存完整异常堆栈、完整 prompt 或工具输出全文。';

alter table matrixcode_agent_runs
    add column retryable boolean not null default false comment '本次失败是否允许基于相同目标和上下文进行恢复重试。';

alter table matrixcode_agent_runs
    add column retry_of_run_id varchar(64) not null default '' comment '如果本次运行是恢复重试，记录来源智能体运行 ID；原始运行为空字符串。';

create index idx_mc_agent_runs_failure_recovery on matrixcode_agent_runs (project_id, status, retryable, updated_at);
