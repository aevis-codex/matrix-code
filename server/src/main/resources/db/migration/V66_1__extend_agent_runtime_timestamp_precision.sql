alter table matrixcode_agent_runs
    modify column created_at timestamp(6) not null comment '运行创建时间，保留微秒精度以稳定同一秒内的运行列表顺序。';

alter table matrixcode_agent_runs
    modify column started_at timestamp(6) comment '运行开始时间，排队状态下可以为空，保留微秒精度用于生命周期回放。';

alter table matrixcode_agent_runs
    modify column finished_at timestamp(6) comment '运行结束时间，未结束时为空，保留微秒精度用于生命周期回放。';

alter table matrixcode_agent_runs
    modify column updated_at timestamp(6) not null comment '运行记录最后更新时间，保留微秒精度用于审计排序。';

alter table matrixcode_agent_run_events
    modify column occurred_at timestamp(6) not null comment '事件发生时间，保留微秒精度以稳定同一秒内的运行时间线顺序。';
