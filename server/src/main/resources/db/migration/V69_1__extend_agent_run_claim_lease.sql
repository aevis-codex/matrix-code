alter table matrixcode_agent_runs
    add column claimed_by_user_id varchar(64) comment '认领本次运行的用户或 Worker ID，用于队列消费责任归属；为空表示尚未被认领。';

alter table matrixcode_agent_runs
    add column claimed_at timestamp(6) comment '运行从 QUEUED 被认领为 RUNNING 的时间，排队状态下为空。';

alter table matrixcode_agent_runs
    add column claim_expires_at timestamp(6) comment '运行认领租约到期时间，用于后续恢复卡住的 RUNNING 运行；排队或终态下可以为空。';

alter table matrixcode_agent_runs
    add constraint fk_mc_agent_runs_claimed_by
        foreign key (claimed_by_user_id) references matrixcode_users (id);

create index idx_mc_agent_runs_queue_claim on matrixcode_agent_runs (project_id, status, created_at, id);

create index idx_mc_agent_runs_claim_lease on matrixcode_agent_runs (project_id, status, claim_expires_at);
