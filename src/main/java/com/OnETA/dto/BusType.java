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
            case 4, 14, 22 -> METROPOLITAN;
            case 3 -> VILLAGE;
            default -> OTHER;
        };
    }

    public static BusType fromKakao(String type) {
        if (type == null || type.isBlank()) return null;
        return switch (type.trim()) {
            case "BLUE", "간선" -> TRUNK;
            case "GREEN", "지선" -> BRANCH;
            case "YELLOW", "순환" -> CIRCULAR;
            case "RED", "광역", "SEAT", "직행좌석" -> METROPOLITAN;
            case "마을" -> VILLAGE;
            default -> OTHER;
        };
    }
}
