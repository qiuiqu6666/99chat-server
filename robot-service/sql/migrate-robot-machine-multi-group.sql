-- Allow one machine_code to bind multiple IM groups.
-- Keep PRIMARY KEY (im_group_id): one group still has at most one machine.
-- FK must be dropped temporarily because InnoDB uses uk_robot_group_machine for it.
-- Usage: mysql ... jiqiren < sql/migrate-robot-machine-multi-group.sql

ALTER TABLE robot_group_binding
  DROP FOREIGN KEY fk_robot_group_machine;

ALTER TABLE robot_group_binding
  DROP INDEX uk_robot_group_machine;

ALTER TABLE robot_group_binding
  ADD KEY idx_robot_group_machine (machine_code);

ALTER TABLE robot_group_binding
  ADD CONSTRAINT fk_robot_group_machine
    FOREIGN KEY (machine_code) REFERENCES robot_machine (machine_code);
