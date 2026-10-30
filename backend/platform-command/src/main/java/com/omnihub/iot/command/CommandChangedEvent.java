package com.omnihub.iot.command;

/** Published on every command state transition. */
public record CommandChangedEvent(Long tenantId, CommandVO command) {
}
