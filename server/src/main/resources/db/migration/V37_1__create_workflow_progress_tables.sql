create table matrixcode_workflow_items (
    id varchar(64) not null,
    project_id varchar(64) not null,
    title varchar(200) not null,
    state varchar(64) not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (id),
    constraint fk_mc_workflow_items_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_workflow_items_project on matrixcode_workflow_items (project_id, state);

create table matrixcode_workflow_events (
    id varchar(64) not null,
    project_id varchar(64) not null,
    item_id varchar(64) not null,
    event_type varchar(64) not null,
    from_state varchar(64) not null,
    to_state varchar(64) not null,
    actor_id varchar(120) not null,
    occurred_at timestamp not null,
    primary key (id),
    constraint fk_mc_workflow_events_project foreign key (project_id) references matrixcode_projects (id),
    constraint fk_mc_workflow_events_item foreign key (item_id) references matrixcode_workflow_items (id)
);

create index idx_mc_workflow_events_item_occurred on matrixcode_workflow_events (item_id, occurred_at);
create index idx_mc_workflow_events_project_occurred on matrixcode_workflow_events (project_id, occurred_at);

create table matrixcode_acceptance_states (
    project_id varchar(64) not null,
    document_id varchar(64) not null,
    accepted boolean not null,
    return_to_role varchar(64) not null,
    updated_at timestamp not null,
    primary key (project_id),
    constraint fk_mc_acceptance_project foreign key (project_id) references matrixcode_projects (id)
);

create index idx_mc_acceptance_updated on matrixcode_acceptance_states (updated_at);
