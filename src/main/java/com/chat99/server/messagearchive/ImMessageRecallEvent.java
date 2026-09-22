package com.chat99.server.messagearchive;

import java.util.List;

public record ImMessageRecallEvent(
    String callbackCommand,
    List<String> msgKeys,
    long eventTimeMs) {}
