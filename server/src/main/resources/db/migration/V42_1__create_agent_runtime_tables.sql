create table matrixcode_agent_runs (
    id varchar(64) not null comment '智能体运行 ID，作为一次角色智能体运行的全局唯一标识。',
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id。',
    role_key varchar(64) not null comment '触发运行的角色键，例如 PRODUCT、DEVELOPER、TESTER、OPERATIONS。',
    agent_kind varchar(64) not null comment '智能体类型，例如 product、coding、tester、operations，用于区分运行器能力。',
    actor_user_id varchar(64) not null comment '触发本次智能体运行的用户 ID，用于责任归属和审计查询。',
    provider_id varchar(80) not null comment '本次运行使用的模型供应商 ID，例如 qwen、deepseek、kimi、doubao。',
    model_name varchar(160) not null comment '本次运行使用的模型名称，记录实际生效模型而非默认配置。',
    status varchar(32) not null comment '运行状态：QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELED。',
    goal text not null comment '本次智能体运行目标，来自用户指令或角色工作区任务。',
    summary text comment '运行摘要，保存最终结论、失败摘要或交付摘要。',
    created_at timestamp not null comment '运行创建时间。',
    started_at timestamp comment '运行开始时间，排队状态下可以为空。',
    finished_at timestamp comment '运行结束时间，未结束时为空。',
    updated_at timestamp not null comment '运行记录最后更新时间。',
    primary key (id),
    constraint fk_mc_agent_runs_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_agent_runs_actor foreign key (actor_user_id) references matrixcode_users (id)
) comment='记录每一次角色智能体运行的主状态、操作者、模型和目标。';

create index idx_mc_agent_runs_project_created on matrixcode_agent_runs (project_id, created_at);
create index idx_mc_agent_runs_actor_created on matrixcode_agent_runs (actor_user_id, created_at);
create index idx_mc_agent_runs_role_status on matrixcode_agent_runs (project_id, role_key, status);

create table matrixcode_agent_run_events (
    id varchar(64) not null comment '智能体运行事件 ID，作为事件流中单条事件的唯一标识。',
    run_id varchar(64) not null comment '所属智能体运行 ID，关联 matrixcode_agent_runs.id。',
    project_id varchar(64) not null comment '所属项目 ID，冗余保存便于按项目查询运行事件。',
    event_type varchar(64) not null comment '事件类型，例如 STEP_STARTED、TOOL_CALLED、MODEL_REQUESTED、RUN_FAILED。',
    event_title varchar(200) not null comment '事件标题，供工作台和审计视图直接展示。',
    event_payload text comment '事件结构化内容的 JSON 文本，保存工具输入输出摘要、错误体摘要或阶段结果。',
    occurred_at timestamp not null comment '事件发生时间。',
    primary key (id),
    constraint fk_mc_agent_events_run foreign key (run_id) references matrixcode_agent_runs (id),
    constraint fk_mc_agent_events_project foreign key (project_id) references matrixcode_projects (id)
) comment='记录智能体运行过程中的步骤事件、工具调用摘要和错误摘要。';

create index idx_mc_agent_events_run_occurred on matrixcode_agent_run_events (run_id, occurred_at);
create index idx_mc_agent_events_project_occurred on matrixcode_agent_run_events (project_id, occurred_at);
