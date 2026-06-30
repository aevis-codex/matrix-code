alter table matrixcode_deployment_targets
    add column ssh_address varchar(500);

alter table matrixcode_deployment_targets
    add column deploy_note text;

alter table matrixcode_deployment_targets
    add column health_check_url varchar(500);

alter table matrixcode_deployment_targets
    add column rollback_note text;

alter table matrixcode_deployment_targets
    add column remote_executed boolean;
