package com.omnihub.iot.product;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.security.CurrentUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private final ProductMapper mapper;
    private final JdbcTemplate jdbc;

    public ProductService(ProductMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    public PageResult<ProductVO> page(Integer page, Integer size, String keyword, String category, String status) {
        LambdaQueryWrapper<Product> q = new LambdaQueryWrapper<Product>()
                .eq(Product::getTenantId, CurrentUser.tenantId()).orderByDesc(Product::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(Product::getName, keyword).or().like(Product::getProductKey, keyword));
        }
        if (category != null && !category.isBlank()) {
            q.eq(Product::getCategory, category);
        }
        if (status != null && !status.isBlank()) {
            q.eq(Product::getStatus, status);
        }
        Page<Product> p = mapper.selectPage(PageQuery.of(page, size), q);
        Map<Long, Long> counts = deviceCounts(p.getRecords().stream().map(Product::getId).toList());
        return PageResult.of(p, e -> toVO(e, counts.getOrDefault(e.getId(), 0L)));
    }

    public List<Map<String, Object>> options() {
        return mapper.selectList(new LambdaQueryWrapper<Product>().eq(Product::getTenantId, CurrentUser.tenantId())
                .orderByAsc(Product::getName)).stream()
                .map(p -> Map.<String, Object>of("id", p.getId(), "name", p.getName(), "productKey", p.getProductKey()))
                .toList();
    }

    public ProductVO get(Long id) {
        Product p = require(id);
        return toVO(p, deviceCounts(List.of(id)).getOrDefault(id, 0L));
    }

    public ProductVO create(ProductRequest req) {
        if (req.productKey() == null) {
            throw BizException.badRequest("产品标识不能为空");
        }
        if (mapper.selectCount(new LambdaQueryWrapper<Product>().eq(Product::getProductKey, req.productKey())) > 0) {
            throw BizException.conflict("产品标识已存在");
        }
        Product p = new Product();
        p.setTenantId(CurrentUser.tenantId());
        p.setName(req.name());
        p.setProductKey(req.productKey());
        p.setCategory(req.category());
        p.setProtocol(req.protocol() == null ? "MQTT" : req.protocol());
        p.setStatus(req.status() == null ? "ENABLED" : req.status());
        p.setDescription(req.description());
        p.setThingModelVersion(0);
        p.setCreatedBy(CurrentUser.username());
        mapper.insert(p);
        return toVO(p, 0L);
    }

    public ProductVO update(Long id, ProductRequest req) {
        Product p = require(id);
        p.setName(req.name());
        p.setCategory(req.category());
        if (req.protocol() != null) {
            p.setProtocol(req.protocol());
        }
        if (req.status() != null) {
            p.setStatus(req.status());
        }
        p.setDescription(req.description());
        mapper.updateById(p);
        return toVO(p, deviceCounts(List.of(id)).getOrDefault(id, 0L));
    }

    public void delete(Long id) {
        require(id);
        Long devices = deviceCounts(List.of(id)).getOrDefault(id, 0L);
        if (devices > 0) {
            throw BizException.conflict("该产品下仍有 " + devices + " 个设备，请先删除设备");
        }
        mapper.deleteById(id);
    }

    /** Tenant-checked lookup used by other modules. */
    public Product require(Long id) {
        Product p = mapper.selectById(id);
        if (p == null || !p.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("产品不存在");
        }
        return p;
    }

    private Map<Long, Long> deviceCounts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        String in = productIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Long> m = new HashMap<>();
        jdbc.query("SELECT product_id, count(*) FROM iot_device WHERE deleted = 0 AND product_id IN (" + in
                + ") GROUP BY product_id", rs -> {
                    m.put(rs.getLong(1), rs.getLong(2));
                });
        return m;
    }

    private static ProductVO toVO(Product p, long deviceCount) {
        return new ProductVO(p.getId(), p.getName(), p.getProductKey(), p.getCategory(), p.getProtocol(),
                p.getStatus(), p.getDescription(), deviceCount, p.getThingModelVersion(), p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
