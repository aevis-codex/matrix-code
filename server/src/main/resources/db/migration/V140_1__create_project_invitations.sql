create table matrixcode_project_invitations (
    id varchar(64) not null comment '项目邀请 ID，服务端生成的全局唯一标识。',
    project_id varchar(64) not null comment '所属项目 ID，关联 matrixcode_projects.id，用于项目级隔离邀请。',
    invitee_user_id varchar(64) not null comment '被邀请用户 ID，接受邀请时必须与当前登录用户一致。',
    display_name varchar(120) not null comment '被邀请用户显示名，用于接受邀请后初始化用户资料。',
    role_key varchar(64) not null comment '邀请授予的项目角色，例如 PRODUCT、DEVELOPER、TESTER、OPERATIONS。',
    status varchar(32) not null comment '邀请状态，PENDING 表示待接受，ACCEPTED 表示已接受，REVOKED 表示已撤销，EXPIRED 表示已过期。',
    token_hash varchar(128) not null comment '邀请令牌 SHA-256 哈希值；不保存明文 token，降低泄漏风险。',
    created_by_user_id varchar(64) not null comment '邀请创建人用户 ID，关联 matrixcode_users.id。',
    expires_at timestamp not null comment '邀请过期时间，过期后不可接受。',
    accepted_at timestamp null comment '邀请接受时间，未接受时为空。',
    created_at timestamp not null comment '邀请创建时间。',
    updated_at timestamp not null comment '邀请最后更新时间。',
    primary key (id),
    constraint fk_mc_project_invitations_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_project_invitations_invitee foreign key (invitee_user_id) references matrixcode_users (id),
    constraint fk_mc_project_invitations_creator foreign key (created_by_user_id) references matrixcode_users (id)
) comment='记录项目成员邀请生命周期，支持多人协作控制台的成员加入和邀请审计。';

create unique index uk_mc_project_invitations_token
    on matrixcode_project_invitations (token_hash);

create index idx_mc_project_invitations_project_status
    on matrixcode_project_invitations (project_id, status, created_at);

create index idx_mc_project_invitations_invitee
    on matrixcode_project_invitations (project_id, invitee_user_id, status);
