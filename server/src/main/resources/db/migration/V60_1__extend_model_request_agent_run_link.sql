alter table matrixcode_model_requests
    add column agent_run_id varchar(64) null comment '关联的 Agent 运行 ID；为空表示该模型请求暂未与具体 Agent 运行建立强关联，只保存低敏关联标识，不保存 prompt、模型响应正文、工具输出、向量召回正文或密钥。';

create index idx_mc_model_requests_agent_run on matrixcode_model_requests (project_id, agent_run_id, created_at);
