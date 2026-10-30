package com.omnihub.iot.common;

import java.util.regex.Pattern;

/** Masks secrets before anything is written to logs or the operation audit table. */
public final class SensitiveMasker {

    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?i)(\"[a-z_]*(password|secret|token|credential|authorization)[a-z_]*\"\\s*:\\s*)\"[^\"]*\"");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");

    private SensitiveMasker() {
    }

    public static String maskJson(String s) {
        if (s == null) {
            return null;
        }
        String r = JSON_FIELD.matcher(s).replaceAll("$1\"******\"");
        return BEARER.matcher(r).replaceAll("Bearer ******");
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
