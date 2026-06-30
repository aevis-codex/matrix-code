alter table matrixcode_model_requests
    add column actor_user_id varchar(64);

alter table matrixcode_model_requests
    add constraint fk_mc_model_requests_actor_user
        foreign key (actor_user_id) references matrixcode_users (id);

create index idx_mc_model_requests_actor_user on matrixcode_model_requests (actor_user_id, created_at);
