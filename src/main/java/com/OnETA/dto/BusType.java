package com.OnETA.dto;

public enum BusType {
    TRUNK,
    BRANCH,
    CIRCULAR,
    METROPOLITAN,
    VILLAGE,
    OTHER;

    public static BusType fromOdsay(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 11 -> TRUNK;
            case 12 -> BRANCH;
            case 13 -> CIRCULAR;
            case 14 -> METROPOLITAN;
            case 3 -> VILLAGE;
            default -> OTHER;
        };
    }

    public static BusType fromKakao(String type) {
        if (type == null || type.isBlank()) return null;
        return switch (type.trim()) {
            case "간선" -> TRUNK;
            case "지선" -> BRANCH;
            case "순환" -> CIRCULAR;
            case "광역" -> METROPOLITAN;
            case "마을" -> VILLAGE;
            default -> OTHER;
        };
    }
}
