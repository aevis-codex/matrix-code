create table matrixcode_local_workspaces (
    id varchar(64) not null,
    project_id varchar(64) not null,
    name varchar(160) not null,
    root_path varchar(1000) not null,
    status varchar(32) not null,
    created_at timestamp not null,
    last_accessed_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_local_workspaces_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_local_workspaces_project on matrixcode_local_workspaces (project_id, status);

alter table matrixcode_local_execution_tasks
    add column tool_type varchar(64);

alter table matrixcode_local_execution_tasks
    add column approval_decision varchar(32);

alter table matrixcode_local_execution_tasks
    add column stdout_summary text;

alter table matrixcode_local_execution_tasks
    add column stderr_summary text;

alter table matrixcode_local_execution_tasks
    add column duration_millis bigint;

alter table matrixcode_local_execution_tasks
    add column approver_id varchar(120);

alter table matrixcode_local_execution_tasks
    add column approval_note text;

alter table matrixcode_local_execution_tasks
    add column decided_at timestamp;

alter table matrixcode_local_execution_tasks
    add column safety_rejection_reason text;

alter table matrixcode_local_execution_tasks
    add column canceled_by varchar(120);

alter table matrixcode_local_execution_tasks
    add column cancel_note text;

alter table matrixcode_local_execution_tasks
    add column canceled_at timestamp;

alter table matrixcode_local_execution_tasks
    add column sort_order int;

create table matrixcode_local_task_logs (
    id varchar(64) not null,
    project_id varchar(64) not null,
    task_id varchar(64) not null,
    stream varchar(32) not null,
    content text,
    sort_order int not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_task_logs_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_task_logs_task foreign key (task_id) references matrixcode_local_execution_tasks (id)
);

create index idx_mc_task_logs_task on matrixcode_local_task_logs (task_id, sort_order);
create index idx_mc_task_logs_project on matrixcode_local_task_logs (project_id, created_at);

alter table matrixcode_audit_records
    add column task_id varchar(64);

alter table matrixcode_audit_records
    add column tool_type varchar(64);

alter table matrixcode_audit_records
    add column workspace_path varchar(1000);

alter table matrixcode_audit_records
    add column occurred_at timestamp;

alter table matrixcode_audit_records
    add column sort_order int;
