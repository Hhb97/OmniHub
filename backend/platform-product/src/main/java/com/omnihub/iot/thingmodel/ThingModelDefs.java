package com.omnihub.iot.thingmodel;

import java.util.ArrayList;
import java.util.List;

/** Thing model definition types (stored as JSON in iot_thing_model.content). */
public final class ThingModelDefs {

    private ThingModelDefs() {
    }

    public record EnumValue(String value, String label) {
    }

    public record Spec(Double min, Double max, Double step, Integer maxLength, List<EnumValue> enumValues) {
    }

    public record Property(String identifier, String name, String dataType, String unit, String description,
            String accessMode, Spec spec) {
    }

    public record Param(String identifier, String name, String dataType, Spec spec) {
    }

    public record Event(String identifier, String name, String level, String description, List<Param> outputs) {
    }

    public record Service(String identifier, String name, String description, List<Param> inputs,
            List<Param> outputs) {
    }

    public record Content(List<Property> properties, List<Event> events, List<Service> services) {
        public Content {
            properties = properties == null ? new ArrayList<>() : properties;
            events = events == null ? new ArrayList<>() : events;
            services = services == null ? new ArrayList<>() : services;
        }

        public Property property(String id) {
            return properties.stream().filter(p -> p.identifier().equals(id)).findFirst().orElse(null);
        }

        public Event event(String id) {
            return events.stream().filter(p -> p.identifier().equals(id)).findFirst().orElse(null);
        }

        public Service service(String id) {
            return services.stream().filter(p -> p.identifier().equals(id)).findFirst().orElse(null);
        }
    }
}
