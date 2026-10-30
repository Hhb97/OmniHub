package com.omnihub.iot.thingmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.thingmodel.ThingModelDefs.EnumValue;
import com.omnihub.iot.thingmodel.ThingModelDefs.Spec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValueValidatorTest {

    private static JsonNode j(String json) {
        return Jsons.parse(json);
    }

    @Test
    void intRequiresIntegralAndRange() {
        Spec spec = new Spec(0.0, 10.0, null, null, null);
        assertNull(ValueValidator.validate("INT", spec, j("5")));
        assertNotNull(ValueValidator.validate("INT", spec, j("5.5")));
        assertNotNull(ValueValidator.validate("INT", spec, j("11")));
        assertNotNull(ValueValidator.validate("INT", spec, j("-1")));
        assertNotNull(ValueValidator.validate("INT", spec, j("\"5\"")));
    }

    @Test
    void floatAcceptsNumbersOnly() {
        Spec spec = new Spec(-40.0, 125.0, null, null, null);
        assertNull(ValueValidator.validate("FLOAT", spec, j("23.5")));
        assertNull(ValueValidator.validate("FLOAT", spec, j("23")));
        assertNotNull(ValueValidator.validate("FLOAT", spec, j("126")));
        assertNotNull(ValueValidator.validate("FLOAT", spec, j("\"23.5\"")));
        assertNotNull(ValueValidator.validate("FLOAT", spec, j("true")));
    }

    @Test
    void boolIsStrict() {
        assertNull(ValueValidator.validate("BOOL", null, j("true")));
        assertNotNull(ValueValidator.validate("BOOL", null, j("1")));
        assertNotNull(ValueValidator.validate("BOOL", null, j("\"true\"")));
    }

    @Test
    void stringRespectsMaxLength() {
        Spec spec = new Spec(null, null, null, 3, null);
        assertNull(ValueValidator.validate("STRING", spec, j("\"abc\"")));
        assertNotNull(ValueValidator.validate("STRING", spec, j("\"abcd\"")));
        assertNotNull(ValueValidator.validate("STRING", spec, j("12")));
    }

    @Test
    void enumMustBeDeclared() {
        Spec spec = new Spec(null, null, null, null, List.of(new EnumValue("0", "off"), new EnumValue("1", "on")));
        assertNull(ValueValidator.validate("ENUM", spec, j("1")));
        assertNull(ValueValidator.validate("ENUM", spec, j("\"0\"")));
        assertNotNull(ValueValidator.validate("ENUM", spec, j("2")));
        assertNotNull(ValueValidator.validate("ENUM", null, j("1")));
    }

    @Test
    void jsonMustBeObjectOrArray() {
        assertNull(ValueValidator.validate("JSON", null, j("{\"a\":1}")));
        assertNull(ValueValidator.validate("JSON", null, j("[1,2]")));
        assertNotNull(ValueValidator.validate("JSON", null, j("5")));
    }

    @Test
    void nullAndUnknownTypeAreRejected() {
        assertNotNull(ValueValidator.validate("FLOAT", null, null));
        assertNotNull(ValueValidator.validate("FLOAT", null, j("null")));
        assertNotNull(ValueValidator.validate("BLOB", null, j("1")));
    }

    @Test
    void numericProjection() {
        assertEquals(2.5, ValueValidator.numeric(j("2.5")));
        assertEquals(1.0, ValueValidator.numeric(j("true")));
        assertNull(ValueValidator.numeric(j("\"x\"")));
    }
}
