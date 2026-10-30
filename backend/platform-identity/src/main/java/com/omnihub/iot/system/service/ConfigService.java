package com.omnihub.iot.system.service;

import com.omnihub.iot.common.BizException;
import com.omnihub.iot.system.entity.SysConfig;
import com.omnihub.iot.system.mapper.SysConfigMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Key/value platform settings with a short-lived in-memory cache (hot paths read them per message). */
@Service
public class ConfigService {

    private static final long TTL_MS = 10_000;

    private record Cached(String value, long at) {
    }

    private final SysConfigMapper mapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ConfigService(SysConfigMapper mapper) {
        this.mapper = mapper;
    }

    public List<SysConfig> list() {
        return mapper.selectList(null);
    }

    public void update(String key, String value) {
        SysConfig c = mapper.selectById(key);
        if (c == null) {
            throw BizException.notFound("配置项不存在");
        }
        c.setConfigValue(value);
        mapper.updateById(c);
        cache.remove(key);
    }

    public int getInt(String key, int def) {
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c == null || now - c.at() > TTL_MS) {
            SysConfig cfg = mapper.selectById(key);
            c = new Cached(cfg == null ? null : cfg.getConfigValue(), now);
            cache.put(key, c);
        }
        try {
            return c.value() == null ? def : Integer.parseInt(c.value().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
