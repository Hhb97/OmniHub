package com.omnihub.iot.device;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.integration.mqtt.BrokerAdmin;
import com.omnihub.iot.product.Product;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.product.ProductService;
import com.omnihub.iot.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Device CRUD and credential management (HTTP side). MQTT-side state handling is in {@link DeviceRuntime}. */
@Service
public class DeviceService {

    private final DeviceMapper mapper;
    private final ProductService productService;
    private final ProductMapper productMapper;
    private final DeviceRuntime runtime;
    private final BrokerAdmin broker;

    public DeviceService(DeviceMapper mapper, ProductService productService, ProductMapper productMapper,
            DeviceRuntime runtime, BrokerAdmin broker) {
        this.mapper = mapper;
        this.productService = productService;
        this.productMapper = productMapper;
        this.runtime = runtime;
        this.broker = broker;
    }

    public PageResult<DeviceVO> page(Integer page, Integer size, String keyword, Long productId, String onlineStatus,
            Boolean enabled) {
        LambdaQueryWrapper<Device> q = new LambdaQueryWrapper<Device>().eq(Device::getTenantId, CurrentUser.tenantId())
                .orderByDesc(Device::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(Device::getName, keyword).or().like(Device::getDeviceKey, keyword));
        }
        if (productId != null) {
            q.eq(Device::getProductId, productId);
        }
        if (onlineStatus != null && !onlineStatus.isBlank()) {
            q.eq(Device::getOnlineStatus, onlineStatus);
        }
        if (enabled != null) {
            q.eq(Device::getEnabled, enabled);
        }
        return toPage(mapper.selectPage(PageQuery.of(page, size), q));
    }

    public PageResult<DeviceVO> pageByProduct(Long productId, Integer page, Integer size) {
        productService.require(productId);
        return toPage(mapper.selectPage(PageQuery.of(page, size), new LambdaQueryWrapper<Device>()
                .eq(Device::getTenantId, CurrentUser.tenantId()).eq(Device::getProductId, productId)
                .orderByDesc(Device::getId)));
    }

    /** Devices that came online most recently. */
    public List<DeviceVO> recentlyOnline(int limit) {
        List<Device> list = mapper.selectList(new LambdaQueryWrapper<Device>()
                .eq(Device::getTenantId, CurrentUser.tenantId()).isNotNull(Device::getLastOnlineAt)
                .orderByDesc(Device::getLastOnlineAt).last("LIMIT " + Math.max(1, Math.min(limit, 50))));
        return toVOs(list);
    }

    public DeviceVO get(Long id) {
        Device d = require(id);
        return toVOs(List.of(d)).get(0);
    }

    @Transactional
    public DeviceVO create(DeviceRequest req) {
        if (req.productId() == null) {
            throw BizException.badRequest("所属产品不能为空");
        }
        Product product = productService.require(req.productId());
        if (!"ENABLED".equals(product.getStatus())) {
            throw BizException.conflict("产品已停用，不能新增设备");
        }
        String key = req.deviceKey() != null ? req.deviceKey() : DeviceCredentials.newDeviceKey();
        if (mapper.selectCount(new LambdaQueryWrapper<Device>().eq(Device::getProductId, product.getId())
                .eq(Device::getDeviceKey, key)) > 0) {
            throw BizException.conflict("该产品下设备标识已存在");
        }
        String secret = DeviceCredentials.newSecret();
        Device d = new Device();
        d.setTenantId(CurrentUser.tenantId());
        d.setProductId(product.getId());
        d.setName(req.name());
        d.setDeviceKey(key);
        d.setDescription(req.description());
        d.setSecretSalt(DeviceCredentials.newSalt());
        d.setSecretHash(DeviceCredentials.hash(d.getSecretSalt(), secret));
        d.setEnabled(true);
        d.setMqttConnected(false);
        d.setOnlineStatus("INACTIVE");
        mapper.insert(d);
        return toVOs(List.of(d)).get(0).withSecret(secret);
    }

    /** All-or-nothing: one transaction. Secrets are returned once, in the response only. */
    @Transactional
    public List<DeviceVO> batchCreate(DeviceRequest.Batch req) {
        Product product = productService.require(req.productId());
        if (!"ENABLED".equals(product.getStatus())) {
            throw BizException.conflict("产品已停用，不能新增设备");
        }
        int width = Math.max(3, String.valueOf(req.count()).length());
        List<DeviceVO> out = new java.util.ArrayList<>();
        for (int i = 1; i <= req.count(); i++) {
            String key;
            do {
                key = DeviceCredentials.newDeviceKey();
            } while (mapper.selectCount(new LambdaQueryWrapper<Device>().eq(Device::getProductId, product.getId())
                    .eq(Device::getDeviceKey, key)) > 0);
            String secret = DeviceCredentials.newSecret();
            Device d = new Device();
            d.setTenantId(CurrentUser.tenantId());
            d.setProductId(product.getId());
            d.setName(req.namePrefix().trim() + "-" + String.format("%0" + width + "d", i));
            d.setDeviceKey(key);
            d.setDescription(req.description());
            d.setSecretSalt(DeviceCredentials.newSalt());
            d.setSecretHash(DeviceCredentials.hash(d.getSecretSalt(), secret));
            d.setEnabled(true);
            d.setMqttConnected(false);
            d.setOnlineStatus("INACTIVE");
            mapper.insert(d);
            out.add(toVOs(List.of(d)).get(0).withSecret(secret));
        }
        return out;
    }

    public DeviceVO update(Long id, DeviceRequest req) {
        Device d = require(id);
        d.setName(req.name());
        d.setDescription(req.description());
        mapper.updateById(d);
        runtime.invalidate(d);
        return toVOs(List.of(d)).get(0);
    }

    @Transactional
    public void delete(Long id) {
        Device d = require(id);
        mapper.deleteById(id);
        runtime.invalidate(d);
        broker.disconnect(runtime.clientId(d));
    }

    public String rotateSecret(Long id) {
        Device d = require(id);
        String secret = DeviceCredentials.newSecret();
        d.setSecretSalt(DeviceCredentials.newSalt());
        d.setSecretHash(DeviceCredentials.hash(d.getSecretSalt(), secret));
        mapper.updateById(d);
        runtime.invalidate(d);
        // the device is connected with the old secret: drop it so it must re-authenticate with the new one
        broker.disconnect(runtime.clientId(d));
        return secret;
    }

    public DeviceVO setEnabled(Long id, boolean enabled) {
        Device d = require(id);
        d.setEnabled(enabled);
        mapper.updateById(d);
        runtime.invalidate(d);
        if (!enabled) {
            broker.disconnect(runtime.clientId(d));
            runtime.markDisconnected(d.getId());
        }
        return get(id);
    }

    /** Tenant-checked lookup used by other modules. */
    public Device require(Long id) {
        Device d = mapper.selectById(id);
        if (d == null || !d.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("设备不存在");
        }
        return d;
    }

    /** id -> name for the given devices (no tenant filter; callers pass ids they already authorised). */
    public Map<Long, String> names(Set<Long> ids) {
        return mapper.selectBatchIds(ids).stream().collect(Collectors.toMap(Device::getId, Device::getName));
    }

    private PageResult<DeviceVO> toPage(Page<Device> p) {
        List<DeviceVO> vos = toVOs(p.getRecords());
        return PageResult.of(vos, p.getTotal(), p.getCurrent(), p.getSize());
    }

    private List<DeviceVO> toVOs(List<Device> devices) {
        Set<Long> pids = devices.stream().map(Device::getProductId).collect(Collectors.toSet());
        Map<Long, Product> products = pids.isEmpty() ? Map.of()
                : productMapper.selectBatchIds(pids).stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return devices.stream().map(d -> {
            Product p = products.get(d.getProductId());
            String pk = p == null ? null : p.getProductKey();
            return new DeviceVO(d.getId(), d.getName(), d.getDeviceKey(), d.getProductId(),
                    p == null ? null : p.getName(), pk, d.getDescription(), d.getEnabled(), d.getOnlineStatus(),
                    d.getMqttConnected(), pk == null ? null : pk + "." + d.getDeviceKey(), d.getLastOnlineAt(),
                    d.getLastOfflineAt(), d.getLastCommAt(), d.getCreatedAt(), null);
        }).toList();
    }
}
