package com.omnihub.iot.command;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.omnihub.iot.common.BizException;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.common.PageQuery;
import com.omnihub.iot.common.PageResult;
import com.omnihub.iot.device.Device;
import com.omnihub.iot.device.DeviceRuntime.DeviceRef;
import com.omnihub.iot.device.DeviceService;
import com.omnihub.iot.identity.core.CurrentUser;
import com.omnihub.iot.messaging.DownlinkPublisher;
import com.omnihub.iot.messaging.Topics;
import com.omnihub.iot.product.ProductService;
import com.omnihub.iot.system.service.ConfigService;
import com.omnihub.iot.telemetry.MessageLogger;
import com.omnihub.iot.thingmodel.ThingModelDefs;
import com.omnihub.iot.thingmodel.ThingModelDefs.Content;
import com.omnihub.iot.thingmodel.ThingModelService;
import com.omnihub.iot.thingmodel.ValueValidator;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Command lifecycle: PENDING -> SENT -> RECEIVED -> SUCCESS | FAILED, or TIMEOUT when no terminal result arrives.
 * All transitions are conditional UPDATEs so duplicate / late / out-of-order replies cannot corrupt the state.
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    private static final Set<String> OPEN = Set.of("PENDING", "SENT", "RECEIVED");

    private final DeviceCommandMapper mapper;
    private final DeviceService deviceService;
    private final ProductService productService;
    private final ThingModelService thingModels;
    private final DownlinkPublisher publisher;
    private final MessageLogger messageLogger;
    private final ConfigService config;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

    public CommandService(DeviceCommandMapper mapper, DeviceService deviceService, ProductService productService,
            ThingModelService thingModels, DownlinkPublisher publisher, MessageLogger messageLogger,
            ConfigService config, JdbcTemplate jdbc, ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.deviceService = deviceService;
        this.productService = productService;
        this.thingModels = thingModels;
        this.publisher = publisher;
        this.messageLogger = messageLogger;
        this.config = config;
        this.jdbc = jdbc;
        this.events = events;
    }

    public CommandVO send(Long deviceId, CommandRequest req) {
        Device d = deviceService.require(deviceId);
        if (!Boolean.TRUE.equals(d.getEnabled())) {
            throw BizException.conflict("设备已被禁用，无法下发指令");
        }
        if (!"ONLINE".equals(d.getOnlineStatus())) {
            throw BizException.conflict("设备当前离线，无法下发指令");
        }
        Content model = thingModels.current(d.getProductId()).content();
        JsonNode params = validate(model, req);
        String productKey = productService.require(d.getProductId()).getProductKey();

        Instant now = Instant.now();
        DeviceCommand c = new DeviceCommand();
        c.setCommandNo("cmd" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        c.setTenantId(d.getTenantId());
        c.setDeviceId(d.getId());
        c.setType(req.type());
        c.setIdentifier(req.identifier());
        c.setParams(Jsons.toJson(params));
        c.setStatus("PENDING");
        c.setCreatedBy(CurrentUser.username());
        c.setCreatedAt(now);
        c.setTimeoutAt(now.plusSeconds(config.getInt("command.timeout.seconds", 30)));
        mapper.insert(c);

        ObjectNode payload = Jsons.MAPPER.createObjectNode();
        payload.put("cmdId", c.getCommandNo());
        payload.put("type", req.type());
        if (req.identifier() != null) {
            payload.put("identifier", req.identifier());
        }
        payload.set("params", params);
        payload.put("ts", now.toEpochMilli());
        String topic = Topics.commandDown(productKey, d.getDeviceKey());
        String body = Jsons.toJson(payload);
        try {
            publisher.publish(topic, body.getBytes(StandardCharsets.UTF_8), 1);
            transition(c.getCommandNo(), d.getId(), Set.of("PENDING"), "SENT", null, null);
            messageLogger.log(d.getId(), "DOWN", "COMMAND", topic, body, "OK", null);
        } catch (Exception e) {
            log.warn("Publishing command {} failed: {}", c.getCommandNo(), e.getMessage());
            transition(c.getCommandNo(), d.getId(), Set.of("PENDING"), "FAILED", "平台消息发布失败: Broker 不可用", null);
            messageLogger.log(d.getId(), "DOWN", "COMMAND", topic, body, "REJECTED", "发布失败");
        }
        return CommandVO.of(byNo(c.getCommandNo()));
    }

    /** Handles a device reply. Returns false when it was ignored (unknown command, duplicate, already final). */
    public boolean handleReply(DeviceRef ref, String commandNo, String status, String message, JsonNode data) {
        String target = switch (status) {
            case "RECEIVED" -> "RECEIVED";
            case "SUCCESS" -> "SUCCESS";
            case "FAILED" -> "FAILED";
            default -> null;
        };
        if (target == null) {
            return false;
        }
        Set<String> from = "RECEIVED".equals(target) ? Set.of("SENT") : OPEN;
        return transition(commandNo, ref.id(), from, target, message, data);
    }

    private boolean transition(String commandNo, Long deviceId, Set<String> from, String to, String message,
            JsonNode data) {
        Timestamp now = Timestamp.from(Instant.now());
        boolean terminal = !OPEN.contains(to);
        String in = String.join(",", from.stream().map(s -> "'" + s + "'").toList());
        int n = jdbc.update("UPDATE iot_device_command SET status = ?, message = COALESCE(?, message), "
                + "result = COALESCE(?::jsonb, result), "
                + "sent_at = CASE WHEN ? = 'SENT' THEN ? ELSE sent_at END, "
                + "received_at = CASE WHEN ? = 'RECEIVED' THEN ? ELSE received_at END, "
                + "finished_at = CASE WHEN ? THEN ? ELSE finished_at END "
                + "WHERE command_no = ? AND device_id = ? AND status IN (" + in + ")", to, message,
                data == null ? null : Jsons.toJson(data), to, now, to, now, terminal, now, commandNo, deviceId);
        if (n > 0) {
            DeviceCommand c = byNo(commandNo);
            events.publishEvent(new CommandChangedEvent(c.getTenantId(), CommandVO.of(c)));
        }
        return n > 0;
    }

    /** Commands that did not reach a terminal state in time become TIMEOUT. */
    @Scheduled(fixedDelay = 5000)
    public void expire() {
        try {
            List<String> ids = jdbc.query("""
                    UPDATE iot_device_command SET status = 'TIMEOUT', finished_at = now(),
                           message = '设备未在规定时间内返回执行结果'
                    WHERE status IN ('PENDING', 'SENT', 'RECEIVED') AND timeout_at < now() RETURNING command_no""",
                    (rs, i) -> rs.getString(1));
            for (String no : ids) {
                DeviceCommand c = byNo(no);
                log.info("Command {} timed out", no);
                events.publishEvent(new CommandChangedEvent(c.getTenantId(), CommandVO.of(c)));
            }
        } catch (Exception e) {
            log.warn("Command expiry sweep failed: {}", e.getMessage());
        }
    }

    public PageResult<CommandVO> page(Long deviceId, Integer page, Integer size, String status) {
        deviceService.require(deviceId);
        LambdaQueryWrapper<DeviceCommand> q = new LambdaQueryWrapper<DeviceCommand>()
                .eq(DeviceCommand::getDeviceId, deviceId).orderByDesc(DeviceCommand::getId);
        if (status != null && !status.isBlank()) {
            q.eq(DeviceCommand::getStatus, status);
        }
        Page<DeviceCommand> p = mapper.selectPage(PageQuery.of(page, size), q);
        return PageResult.of(p, CommandVO::of);
    }

    public CommandVO get(Long id) {
        DeviceCommand c = mapper.selectById(id);
        if (c == null || !c.getTenantId().equals(CurrentUser.tenantId())) {
            throw BizException.notFound("指令不存在");
        }
        return CommandVO.of(c);
    }

    private DeviceCommand byNo(String no) {
        return mapper.selectOne(new LambdaQueryWrapper<DeviceCommand>().eq(DeviceCommand::getCommandNo, no));
    }

    // ------------------------------------------------------------------ validation against the thing model

    private static JsonNode validate(Content model, CommandRequest req) {
        JsonNode params = req.params() == null || req.params().isNull() ? Jsons.MAPPER.createObjectNode()
                : req.params();
        if (!params.isObject()) {
            throw BizException.badRequest("params 必须是 JSON 对象");
        }
        switch (req.type()) {
            case "SERVICE" -> {
                ThingModelDefs.Service s = req.identifier() == null ? null : model.service(req.identifier());
                if (s == null) {
                    throw BizException.badRequest("物模型中未定义该服务: " + req.identifier());
                }
                List<ThingModelDefs.Param> inputs = s.inputs() == null ? List.of() : s.inputs();
                for (ThingModelDefs.Param in : inputs) {
                    String err = ValueValidator.validate(in.dataType(), in.spec(), params.get(in.identifier()));
                    if (err != null) {
                        throw BizException.badRequest("参数 " + in.identifier() + " " + err);
                    }
                }
                params.fieldNames().forEachRemaining(n -> {
                    if (inputs.stream().noneMatch(i -> i.identifier().equals(n))) {
                        throw BizException.badRequest("未定义的参数: " + n);
                    }
                });
            }
            case "PROPERTY_SET" -> {
                ThingModelDefs.Property p = req.identifier() == null ? null : model.property(req.identifier());
                if (p == null) {
                    throw BizException.badRequest("物模型中未定义该属性: " + req.identifier());
                }
                if (!"RW".equals(p.accessMode())) {
                    throw BizException.badRequest("属性 " + p.identifier() + " 为只读，不能设置");
                }
                String err = ValueValidator.validate(p.dataType(), p.spec(), params.get("value"));
                if (err != null) {
                    throw BizException.badRequest("value " + err);
                }
            }
            case "PROPERTY_GET" -> {
                JsonNode ids = params.get("identifiers");
                if (ids == null || !ids.isArray() || ids.isEmpty()) {
                    throw BizException.badRequest("params.identifiers 必须是非空数组");
                }
                for (JsonNode id : ids) {
                    if (!id.isTextual() || model.property(id.asText()) == null) {
                        throw BizException.badRequest("物模型中未定义该属性: " + id.asText());
                    }
                }
            }
            default -> throw BizException.badRequest("不支持的指令类型");
        }
        return params;
    }
}
