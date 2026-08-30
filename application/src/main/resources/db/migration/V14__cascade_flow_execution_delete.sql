ALTER TABLE flow_execution
    DROP FOREIGN KEY fk_flow_execution_flow;

ALTER TABLE flow_execution
    ADD CONSTRAINT fk_flow_execution_flow_cascade FOREIGN KEY (flow_id) REFERENCES flow (id) ON DELETE CASCADE;
