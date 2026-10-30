-- Default tenant, permission catalogue and the built-in ADMIN role.
-- No user (and therefore no password) is seeded here: the admin user is created at startup by
-- AdminInitializer from the IOT_ADMIN_PASSWORD environment variable.

INSERT INTO sys_tenant (code, name) VALUES ('default', '默认租户');

INSERT INTO sys_permission (code, name, type, parent_code, sort) VALUES
 ('dashboard', '数据总览', 'MENU', NULL, 1),
 ('dashboard:view', '查看总览', 'API', 'dashboard', 1),
 ('product', '产品管理', 'MENU', NULL, 2),
 ('product:view', '查看产品', 'API', 'product', 1),
 ('product:create', '新增产品', 'API', 'product', 2),
 ('product:update', '编辑产品', 'API', 'product', 3),
 ('product:delete', '删除产品', 'API', 'product', 4),
 ('thing', '物模型', 'MENU', NULL, 3),
 ('thing:view', '查看物模型', 'API', 'thing', 1),
 ('thing:edit', '编辑物模型', 'API', 'thing', 2),
 ('device', '设备管理', 'MENU', NULL, 4),
 ('device:view', '查看设备', 'API', 'device', 1),
 ('device:create', '新增设备', 'API', 'device', 2),
 ('device:update', '编辑/启停设备', 'API', 'device', 3),
 ('device:delete', '删除设备', 'API', 'device', 4),
 ('device:credential', '轮换设备密钥', 'API', 'device', 5),
 ('device:control', '设备控制', 'API', 'device', 6),
 ('telemetry', '设备监控', 'MENU', NULL, 5),
 ('telemetry:view', '查看监控数据', 'API', 'telemetry', 1),
 ('alarm', '告警管理', 'MENU', NULL, 6),
 ('alarm:view', '查看告警', 'API', 'alarm', 1),
 ('alarm:rule', '管理告警规则', 'API', 'alarm', 2),
 ('alarm:ack', '确认/处理告警', 'API', 'alarm', 3),
 ('system', '系统管理', 'MENU', NULL, 7),
 ('system:user', '用户管理', 'API', 'system', 1),
 ('system:role', '角色管理', 'API', 'system', 2),
 ('system:log', '日志查看', 'API', 'system', 3),
 ('system:config', '系统配置', 'API', 'system', 4);

INSERT INTO sys_role (tenant_id, code, name, description)
SELECT id, 'ADMIN', '管理员', '拥有全部权限' FROM sys_tenant WHERE code = 'default';

INSERT INTO sys_role (tenant_id, code, name, description)
SELECT id, 'OPERATOR', '运维人员', '设备/告警/监控的日常运维，无系统管理权限' FROM sys_tenant WHERE code = 'default';

INSERT INTO sys_role (tenant_id, code, name, description)
SELECT id, 'VIEWER', '只读用户', '只能查看数据' FROM sys_tenant WHERE code = 'default';

INSERT INTO sys_role_permission (role_id, permission_code)
SELECT r.id, p.code FROM sys_role r CROSS JOIN sys_permission p WHERE r.code = 'ADMIN';

INSERT INTO sys_role_permission (role_id, permission_code)
SELECT r.id, p.code FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'OPERATOR' AND p.type = 'API' AND p.code NOT LIKE 'system:%' AND p.code NOT IN ('product:delete', 'device:delete');

INSERT INTO sys_role_permission (role_id, permission_code)
SELECT r.id, p.code FROM sys_role r CROSS JOIN sys_permission p
WHERE r.code = 'VIEWER' AND p.code IN ('dashboard:view', 'product:view', 'thing:view', 'device:view', 'telemetry:view', 'alarm:view');

INSERT INTO sys_config (config_key, config_value, description) VALUES
 ('device.offline.timeout.seconds', '90', '设备超过该秒数无任何上行消息则判定为离线'),
 ('command.timeout.seconds', '30', '控制指令等待设备执行结果的超时时间'),
 ('retention.history.days', '90', '属性历史数据保留天数'),
 ('retention.message-log.days', '7', '设备通信记录保留天数');
