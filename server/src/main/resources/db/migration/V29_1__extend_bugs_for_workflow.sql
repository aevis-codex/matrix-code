alter table matrixcode_bugs
    add column reproduction_steps text;

alter table matrixcode_bugs
    add column expected_result text;

alter table matrixcode_bugs
    add column actual_result text;

alter table matrixcode_bugs
    add column last_note text;
