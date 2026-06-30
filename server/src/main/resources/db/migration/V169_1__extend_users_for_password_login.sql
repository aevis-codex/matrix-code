alter table matrixcode_users
    add column password_hash varchar(255) null comment '用户密码派生哈希，格式包含算法、迭代次数、盐和派生值；不保存明文密码。';

alter table matrixcode_users
    add column super_admin boolean not null default false comment '是否为全局超级管理员；true 表示可跨项目治理用户和权限。';

alter table matrixcode_users
    add column password_updated_at timestamp null comment '用户密码最近一次设置或重置时间，用于后续密码轮换和审计判断。';

create index idx_mc_users_super_admin on matrixcode_users (super_admin, status);
