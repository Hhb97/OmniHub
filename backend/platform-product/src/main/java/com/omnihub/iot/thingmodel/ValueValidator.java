package com.omnihub.iot.thingmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.thingmodel.ThingModelDefs.EnumValue;
import com.omnihub.iot.thingmodel.ThingModelDefs.Spec;
import java.util.Set;

/** Validates a JSON value against a thing-model data type + spec. Returns an error message or null when valid. */
public final class ValueValidator {

    public static final Set<String> TYPES = Set.of("INT", "FLOAT", "BOOL", "STRING", "ENUM", "JSON");
    private static final int DEFAULT_MAX_STRING = 1024;
    private static final int MAX_JSON_BYTES = 8192;

    private ValueValidator() {
    }

    public static String validate(String dataType, Spec spec, JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "值不能为空";
        }
        switch (dataType) {
            case "INT" -> {
                if (!v.isIntegralNumber()) {
                    return "必须是整数";
                }
                return range(spec, v.asDouble());
            }
            case "FLOAT" -> {
                if (!v.isNumber()) {
                    return "必须是数字";
                }
                if (Double.isNaN(v.asDouble()) || Double.isInfinite(v.asDouble())) {
                    return "必须是有限数字";
                }
                return range(spec, v.asDouble());
            }
            case "BOOL" -> {
                return v.isBoolean() ? null : "必须是布尔值";
            }
            case "STRING" -> {
                if (!v.isTextual()) {
                    return "必须是字符串";
                }
                int max = spec != null && spec.maxLength() != null ? spec.maxLength() : DEFAULT_MAX_STRING;
                return v.asText().length() > max ? "长度不能超过 " + max : null;
            }
            case "ENUM" -> {
                if (spec == null || spec.enumValues() == null || spec.enumValues().isEmpty()) {
                    return "枚举未定义可选值";
                }
                String text = v.isValueNode() ? v.asText() : null;
                for (EnumValue e : spec.enumValues()) {
                    if (e.value().equals(text)) {
                        return null;
                    }
                }
                return "不在枚举范围内";
            }
            case "JSON" -> {
                if (!v.isObject() && !v.isArray()) {
                    return "必须是 JSON 对象或数组";
                }
                return Jsons.toJson(v).length() > MAX_JSON_BYTES ? "JSON 过大" : null;
            }
            default -> {
                return "不支持的数据类型 " + dataType;
            }
        }
    }

    private static String range(Spec spec, double d) {
        if (spec == null) {
            return null;
        }
        if (spec.min() != null && d < spec.min()) {
            return "不能小于 " + spec.min();
        }
        if (spec.max() != null && d > spec.max()) {
            return "不能大于 " + spec.max();
        }
        return null;
    }

    /** Numeric projection for aggregation (booleans become 0/1); null for non-numeric values. */
    public static Double numeric(JsonNode v) {
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean() ? 1.0 : 0.0;
        }
        return null;
    }
}
