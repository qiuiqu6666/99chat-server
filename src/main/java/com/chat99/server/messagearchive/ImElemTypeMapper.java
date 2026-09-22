package com.chat99.server.messagearchive;

final class ImElemTypeMapper {

    private ImElemTypeMapper() {}

    static Integer toImElemType(String timMsgType) {
        if (timMsgType == null || timMsgType.isBlank()) {
            return null;
        }
        return switch (timMsgType) {
            case "TIMTextElem" -> 1;
            case "TIMLocationElem" -> 2;
            case "TIMImageElem", "TIMFaceElem" -> 3;
            case "TIMSoundElem" -> 4;
            case "TIMVideoFileElem" -> 5;
            case "TIMFileElem" -> 6;
            case "TIMCustomElem" -> 8;
            case "TIMGroupTipElem", "TIMGroupSystemNoticeElem" -> 9;
            default -> null;
        };
    }
}
