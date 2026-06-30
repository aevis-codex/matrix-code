alter table matrixcode_model_requests
    add column prompt_partition_policy_id varchar(80) not null default 'deepseek-reasonix-partitions-v1' comment '模型请求采用的 Prompt 分区策略版本，只保存低敏策略标识，不保存完整 prompt、用户指令、向量召回正文、工具输出或供应商密钥。';

alter table matrixcode_model_requests
    add column prompt_partition_fingerprint varchar(64) not null default '' comment 'Prompt 分区结构指纹，用于判断稳定前缀和动态后缀工程边界是否变化；不包含 prompt 正文或用户输入。';

alter table matrixcode_model_requests
    add column stable_partition_count int not null default 0 comment '模型请求 Prompt 编排中的稳定分区数量，稳定分区应尽量位于供应商可复用的前缀区域。';

alter table matrixcode_model_requests
    add column volatile_partition_count int not null default 0 comment '模型请求 Prompt 编排中的动态分区数量，动态分区包含角色提示词、上下文槽位和用户指令槽位等后置内容。';

create index idx_mc_model_req_prompt_partition on matrixcode_model_requests (project_id, prompt_partition_policy_id, prompt_partition_fingerprint);
