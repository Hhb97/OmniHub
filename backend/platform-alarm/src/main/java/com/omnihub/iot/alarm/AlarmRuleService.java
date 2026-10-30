package com.omnihub.iot.alarm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omnihub.iot.alarm.AlarmDtos.RuleRequest;
import com.omnihub.iot.alarm.AlarmDtos.RuleVO;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceService;
import com.omnihub.iot.identity.core.CurrentUser;
import com.omnihub.iot.product.Product;
import com.omnihub.iot.product.ProductMapper;
import com.omnihub.iot.product.ProductService;
import com.omnihub.iot.thingmodel.ThingModelDefs.Content;
import com.omnihub.iot.thingmodel.ThingModelService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlarmRuleService {

    private final AlarmRuleMapper mapper;
    private final ProductService productService;
    private final DeviceService deviceService;
    private final ProductMapper productMapper;
    private final ThingModelService thingModels;
    private final AlarmEngine engine;

    public AlarmRuleService(AlarmRuleMapper mapper, ProductService productService, DeviceService deviceService,
            ProductMapper productMapper, ThingModelService thingModels, AlarmEngine engine) {
        this.mapper = mapper;
        this.productService = productService;
        this.deviceService = deviceService;
        this.productMapper = productMapper;
        this.thingModels = thingModels;
        this.engine = engine;
    }

    public PageResult<RuleVO> page(Integer page, Integer size, String keyword, String ruleType, Boolean enabled) {
        LambdaQueryWrapper<AlarmRule> q = new LambdaQueryWrapper<AlarmRule>()
                .eq(AlarmRule::getTenantId, CurrentUser.tenantId()).orderByDesc(AlarmRule::getId);
        if (keyword != null && !keyword.isBlank()) {
            q.like(AlarmRule::getName, keyword);
        }
        if (ruleType != null && !ruleType.isBlank()) {
            q.eq(AlarmRule::getRuleType, ruleType);
        }
        if (enabled != null) {
            q.eq(AlarmRule::getEnabled, enabled);
        }
        Page<AlarmRule> p = mapper.selectPage(PageQuery.of(page, size), q);
        List<RuleVO> vos = toVOs(p.getRecords());
        return PageResult.of(vos, p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Transactional
    public RuleVO create(RuleRequest req) {
        AlarmRule r = new AlarmRule();
        r.setTenantId(CurrentUser.tenantId());
        apply(r, req);
        mapper.insert(r);
        engine.invalidateRules();
        return toVOs(List.of(r)).get(0);
    }

    @Transactional
    public RuleVO update(Long id, RuleRequest req) {
        AlarmRule r = require(id);
        apply(r, req);
        mapper.updateById(r);
        engine.invalidateRules();
        if (!Boolean.TRUE.equals(r.getEnabled())) {
            engine.recoverRule(r.getId(), "规则已停用");
        }
        return toVOs(List.of(r)).get(0);
    }

    @Transactional
    public void delete(Long id) {
        AlarmRule r = require(id);
        mapper.deleteById(id);
        engine.invalidateRules();
        engine.recoverRule(r.getId(), "规则已删除");
    }

    public RuleVO setEnabled(Long id, boolean enabled) {
        AlarmRule r = require(id);
        r.setEnabled(enabled);
        mapper.updateById(r);
        engine.invalidateRules();
        if (!enabled) {
            engine.recoverRule(r.getId(), "规则已停用");
        }
        return toVOs(List.of(r)).get(0);
    }

    private AlarmRule require(Long id) {
        AlarmRule r = mapper.selectById(id);
        if (r == null || !r.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("告警规则不存在");
        }
        return r;
    }

    private void apply(AlarmRule r, RuleRequest req) {
        Long productId;
        if ("DEVICE".equals(req.scope())) {
            if (req.deviceId() == null) {
                throw BizException.badRequest("设备范围的规则必须指定设备");
            }
            Device d = deviceService.require(req.deviceId());
            productId = d.getProductId();
            r.setDeviceId(d.getId());
            r.setProductId(productId);
        } else {
            if (req.productId() == null) {
                throw BizException.badRequest("产品范围的规则必须指定产品");
            }
            productId = productService.require(req.productId()).getId();
            r.setProductId(productId);
            r.setDeviceId(null);
        }
        Content model = thingModels.get(productId).content();
        switch (req.ruleType()) {
            case "THRESHOLD" -> {
                if (req.identifier() == null || model.property(req.identifier()) == null) {
                    throw BizException.badRequest("阈值规则必须选择物模型中已定义的属性");
                }
                String type = model.property(req.identifier()).dataType();
                if (!Set.of("INT", "FLOAT", "BOOL").contains(type)) {
                    throw BizException.badRequest("阈值规则只支持数值/布尔属性");
                }
                if (req.operator() == null || req.threshold() == null) {
                    throw BizException.badRequest("阈值规则必须配置比较符和阈值");
                }
            }
            case "EVENT" -> {
                if (req.identifier() == null || model.event(req.identifier()) == null) {
                    throw BizException.badRequest("事件规则必须选择物模型中已定义的事件");
                }
            }
            default -> {
                // OFFLINE needs no identifier
            }
        }
        r.setName(req.name());
        r.setScope(req.scope());
        r.setRuleType(req.ruleType());
        boolean threshold = "THRESHOLD".equals(req.ruleType());
        r.setIdentifier("OFFLINE".equals(req.ruleType()) ? null : req.identifier());
        r.setOperator(threshold ? req.operator() : null);
        r.setThreshold(threshold ? req.threshold() : null);
        r.setTriggerCount(threshold && req.triggerCount() != null ? req.triggerCount() : 1);
        r.setSilenceSeconds(req.silenceSeconds() == null ? 0 : req.silenceSeconds());
        r.setLevel(req.level());
        r.setEnabled(req.enabled() == null || req.enabled());
        r.setDescription(req.description());
    }

    private List<RuleVO> toVOs(List<AlarmRule> rules) {
        Set<Long> pids = rules.stream().map(AlarmRule::getProductId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> pnames = new java.util.HashMap<>();
        if (!pids.isEmpty()) {
            productMapper.selectBatchIds(pids).forEach(p -> pnames.put(p.getId(), p.getName()));
        }
        Set<Long> dids = rules.stream().map(AlarmRule::getDeviceId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> dnames = new java.util.HashMap<>();
        if (!dids.isEmpty()) {
            dnames.putAll(deviceService.names(dids));
        }
        return rules.stream().map(r -> new RuleVO(r.getId(), r.getName(), r.getScope(), r.getProductId(),
                r.getDeviceId(), pnames.get(r.getProductId()), dnames.get(r.getDeviceId()), r.getRuleType(),
                r.getIdentifier(), r.getOperator(), r.getThreshold(), r.getTriggerCount(), r.getSilenceSeconds(),
                r.getLevel(), r.getEnabled(), r.getDescription(), r.getCreatedAt())).toList();
    }
}
