create table matrixcode_runtime_notification_reads (
    id varchar(64) not null comment '运行态提醒用户已读回执 ID，按项目、提醒和用户生成稳定唯一值。',
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id，用于按项目隔离提醒已读状态。',
    notification_id varchar(64) not null comment '运行态提醒 ID，关联 matrixcode_runtime_notifications.id。',
    user_id varchar(64) not null comment '已读用户 ID，关联 matrixcode_users.id；每个用户拥有独立已读状态。',
    read_at timestamp not null comment '该用户首次标记此提醒为已读的时间。',
    created_at timestamp not null comment '已读回执创建时间。',
    updated_at timestamp not null comment '已读回执最后更新时间。',
    primary key (id),
    constraint fk_mc_notification_reads_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_notification_reads_notification foreign key (notification_id) references matrixcode_runtime_notifications (id),
    constraint fk_mc_notification_reads_user foreign key (user_id) references matrixcode_users (id)
) comment='记录运行态提醒在不同用户维度下的已读回执，避免多人协作时互相影响未读状态。';

create unique index uk_mc_notification_reads_user
    on matrixcode_runtime_notification_reads (project_id, notification_id, user_id);

create index idx_mc_notification_reads_user_read
    on matrixcode_runtime_notification_reads (project_id, user_id, read_at);
