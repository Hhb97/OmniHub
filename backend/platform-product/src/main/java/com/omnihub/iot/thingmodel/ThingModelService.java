package com.omnihub.iot.thingmodel;

import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.identity.core.CurrentUser;
import com.omnihub.iot.product.Product;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.product.ProductService;
import com.omnihub.iot.thingmodel.ThingModelDefs.Content;
import com.omnihub.iot.thingmodel.ThingModelDefs.Param;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ThingModelService {

    private static final Pattern ID = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");
    private static final Set<String> LEVELS = Set.of("INFO", "WARNING", "ERROR");
    private static final long CACHE_TTL_MS = 30_000;

    public record Versioned(int version, Content content, Instant updatedAt) {
    }

    public record VersionInfo(int version, Instant createdAt, String createdBy) {
    }

    private record Cached(Versioned model, long at) {
    }

    private final JdbcTemplate jdbc;
    private final ProductService productService;
    private final ProductMapper productMapper;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public ThingModelService(JdbcTemplate jdbc, ProductService productService, ProductMapper productMapper) {
        this.jdbc = jdbc;
        this.productService = productService;
        this.productMapper = productMapper;
    }

    /** API read: tenant-checked. */
    public Versioned get(Long productId) {
        productService.require(productId);
        return load(productId);
    }

    /** Internal read without tenant check (device ingestion path); cached briefly. */
    public Versioned current(Long productId) {
        Cached c = cache.get(productId);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < CACHE_TTL_MS) {
            return c.model();
        }
        Versioned v = load(productId);
        cache.put(productId, new Cached(v, now));
        return v;
    }

    private Versioned load(Long productId) {
        List<Versioned> rows = jdbc.query(
                "SELECT version, content::text, created_at FROM iot_thing_model WHERE product_id = ? ORDER BY version DESC LIMIT 1",
                (rs, i) -> new Versioned(rs.getInt(1), Jsons.parse(rs.getString(2), Content.class),
                        rs.getTimestamp(3).toInstant()),
                productId);
        return rows.isEmpty() ? new Versioned(0, new Content(null, null, null), null) : rows.get(0);
    }

    @Transactional
    public Versioned save(Long productId, Content content) {
        Product product = productService.require(productId);
        validate(content);
        int next = product.getThingModelVersion() + 1;
        jdbc.update("INSERT INTO iot_thing_model (product_id, version, content, created_by) VALUES (?, ?, ?, ?)",
                productId, next, Jsons.toJson(content), CurrentUser.username());
        product.setThingModelVersion(next);
        productMapper.updateById(product);
        cache.remove(productId);
        return load(productId);
    }

    public List<VersionInfo> versions(Long productId) {
        productService.require(productId);
        return jdbc.query(
                "SELECT version, created_at, created_by FROM iot_thing_model WHERE product_id = ? ORDER BY version DESC",
                (rs, i) -> new VersionInfo(rs.getInt(1), rs.getTimestamp(2).toInstant(), rs.getString(3)), productId);
    }

    private void validate(Content c) {
        Set<String> seen = new HashSet<>();
        for (var p : c.properties()) {
            id(p.identifier(), seen, "属性");
            type(p.dataType(), "属性 " + p.identifier());
            if (p.name() == null || p.name().isBlank()) {
                throw BizException.badRequest("属性 " + p.identifier() + " 缺少名称");
            }
            if (p.accessMode() == null || !Set.of("R", "RW").contains(p.accessMode())) {
                throw BizException.badRequest("属性 " + p.identifier() + " 的读写权限必须为 R 或 RW");
            }
            spec(p.dataType(), p.spec(), "属性 " + p.identifier());
        }
        seen.clear();
        for (var e : c.events()) {
            id(e.identifier(), seen, "事件");
            if (e.level() == null || !LEVELS.contains(e.level())) {
                throw BizException.badRequest("事件 " + e.identifier() + " 级别必须为 INFO/WARNING/ERROR");
            }
            params(e.outputs(), "事件 " + e.identifier());
        }
        seen.clear();
        for (var s : c.services()) {
            id(s.identifier(), seen, "服务");
            params(s.inputs(), "服务 " + s.identifier());
            params(s.outputs(), "服务 " + s.identifier());
        }
    }

    private static void id(String id, Set<String> seen, String what) {
        if (id == null || !ID.matcher(id).matches()) {
            throw BizException.badRequest(what + "标识不合法: " + id);
        }
        if (!seen.add(id)) {
            throw BizException.badRequest(what + "标识重复: " + id);
        }
    }

    private static void type(String type, String where) {
        if (type == null || !ValueValidator.TYPES.contains(type)) {
            throw BizException.badRequest(where + " 的数据类型不合法");
        }
    }

    private static void spec(String type, ThingModelDefs.Spec spec, String where) {
        if (spec != null && spec.min() != null && spec.max() != null && spec.min() > spec.max()) {
            throw BizException.badRequest(where + " 的最小值不能大于最大值");
        }
        if ("ENUM".equals(type)) {
            if (spec == null || spec.enumValues() == null || spec.enumValues().isEmpty()) {
                throw BizException.badRequest(where + " 为枚举类型，必须配置枚举值");
            }
            Set<String> vals = new HashSet<>();
            for (var ev : spec.enumValues()) {
                if (ev.value() == null || !vals.add(ev.value())) {
                    throw BizException.badRequest(where + " 的枚举值为空或重复");
                }
            }
        }
    }

    private static void params(List<Param> params, String where) {
        if (params == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Param p : params) {
            id(p.identifier(), seen, where + " 参数");
            type(p.dataType(), where + " 参数 " + p.identifier());
            spec(p.dataType(), p.spec(), where + " 参数 " + p.identifier());
        }
    }
}
