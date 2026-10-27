package com.omnihub.iot.thingmodel;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.system.OpLog;
import com.omnihub.iot.thingmodel.ThingModelDefs.Content;
import com.omnihub.iot.thingmodel.ThingModelService.VersionInfo;
import java.time.Instant;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products/{productId}/thing-model")
public class ThingModelController {

    public record ThingModelVO(Long productId, int version, List<ThingModelDefs.Property> properties,
            List<ThingModelDefs.Event> events, List<ThingModelDefs.Service> services, Instant updatedAt) {
    }

    private final ThingModelService service;

    public ThingModelController(ThingModelService service) {
        this.service = service;
    }

    private static ThingModelVO vo(Long productId, ThingModelService.Versioned v) {
        return new ThingModelVO(productId, v.version(), v.content().properties(), v.content().events(),
                v.content().services(), v.updatedAt());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('thing:view')")
    public ApiResponse<ThingModelVO> get(@PathVariable Long productId) {
        return ApiResponse.ok(vo(productId, service.get(productId)));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('thing:edit')")
    @OpLog(module = "物模型", action = "保存物模型")
    public ApiResponse<ThingModelVO> save(@PathVariable Long productId, @RequestBody Content content) {
        return ApiResponse.ok(vo(productId, service.save(productId, content)));
    }

    @GetMapping("/versions")
    @PreAuthorize("hasAuthority('thing:view')")
    public ApiResponse<List<VersionInfo>> versions(@PathVariable Long productId) {
        return ApiResponse.ok(service.versions(productId));
    }
}
