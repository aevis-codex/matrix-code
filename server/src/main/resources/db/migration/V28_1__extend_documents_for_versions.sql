alter table matrixcode_documents
    add column parent_version_id varchar(64);

alter table matrixcode_documents
    add column frozen_by varchar(120);

alter table matrixcode_documents
    add column frozen_at timestamp;

create index idx_mc_documents_parent_version on matrixcode_documents (parent_version_id);
