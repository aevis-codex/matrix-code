create table matrixcode_local_file_operations (
    id varchar(64) not null comment '文件操作记录 ID，由服务生成 UUID，用于唯一标识一次本地文件访问行为。',
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id，用于项目级隔离本地文件操作历史。',
    workspace_id varchar(64) not null comment '本地工作区授权 ID，记录本次文件操作发生在哪个授权工作区。',
    operation_type varchar(32) not null comment '文件操作类型，例如 LIST、READ、WRITE。',
    relative_path varchar(1000) not null comment '相对工作区根目录的文件或目录路径。',
    operation_status varchar(32) not null comment '文件操作状态，例如 SUCCESS，用于后续扩展失败审计。',
    summary varchar(1000) not null comment '文件操作摘要，面向工作台展示本次操作的简要说明。',
    sort_order int not null comment '项目内最近操作排序，0 表示最新操作，用于恢复内存窗口顺序。',
    created_at timestamp not null comment '文件操作实际发生时间。',
    persisted_at timestamp not null comment '该记录写入正式表的时间。',
    primary key (id),
    constraint fk_mc_local_file_operations_project foreign key (project_id) references matrixcode_projects (id)
) comment='保存本地执行工作区最近文件操作记录，用于协作工作台恢复、审计和智能体上下文回看。';

create index idx_mc_local_file_operations_project_order
    on matrixcode_local_file_operations (project_id, sort_order, created_at);

create index idx_mc_local_file_operations_workspace
    on matrixcode_local_file_operations (workspace_id, created_at);

create table matrixcode_local_git_diff_summaries (
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id，每个项目只保留最近一次 Git Diff 摘要。',
    workspace_id varchar(64) not null comment '本地工作区授权 ID，记录本次 Git Diff 采集来源。',
    repository boolean not null comment '是否识别为 Git 仓库或 Git worktree。',
    changed_files_json text not null comment '变更文件列表的 JSON 文本，按 git diff --name-only 输出顺序保存。',
    diff_stat text not null comment 'git diff --stat 的摘要文本，用于交付审查和工作台展示。',
    captured_at timestamp not null comment 'Git Diff 实际采集时间。',
    updated_at timestamp not null comment '该摘要写入正式表的时间。',
    primary key (project_id),
    constraint fk_mc_local_git_diff_project foreign key (project_id) references matrixcode_projects (id)
) comment='保存每个项目最近一次本地 Git Diff 摘要，用于开发智能体交付前变更范围回看。';

create index idx_mc_local_git_diff_workspace
    on matrixcode_local_git_diff_summaries (workspace_id, captured_at);
