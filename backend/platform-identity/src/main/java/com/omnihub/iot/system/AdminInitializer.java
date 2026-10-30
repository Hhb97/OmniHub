package com.omnihub.iot.system;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator when the user table is empty. The password comes only from configuration
 * (IOT_ADMIN_PASSWORD); nothing is hardcoded and the password is never logged.
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AdminProperties props;

    public AdminInitializer(JdbcTemplate jdbc, PasswordEncoder encoder, AdminProperties props) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer users = jdbc.queryForObject("SELECT count(*) FROM sys_user WHERE deleted = 0", Integer.class);
        if (users != null && users > 0) {
            return;
        }
        String password = props.getInitialPassword();
        if (password == null || password.length() < 8) {
            log.warn("No users exist and IOT_ADMIN_PASSWORD is not set (min 8 chars): no administrator was created");
            return;
        }
        Long tenantId = jdbc.queryForObject("SELECT id FROM sys_tenant WHERE code = 'default'", Long.class);
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password_hash, nickname) VALUES (?, ?, ?, ?)", tenantId,
                props.getUsername(), encoder.encode(password), "系统管理员");
        List<Long> ids = jdbc.queryForList("SELECT id FROM sys_user WHERE username = ?", Long.class,
                props.getUsername());
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = 'ADMIN'",
                ids.get(0));
        log.info("Initial administrator '{}' created", props.getUsername());
    }
}
