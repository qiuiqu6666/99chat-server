package com.chat99.server.favorite;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FavoriteUploadMetadata(
    @JsonAlias({"messageType", "favoriteType", "msgType"}) FavoriteType type,
    @JsonAlias({"elemType", "elementType"}) Integer elemType,
    @JsonAlias({"content", "body", "note"}) String text,
    @JsonAlias({"duration", "videoDurationSec"}) Integer durationSec,
    @JsonAlias({"remark", "label"}) String sourceConvLabel) {}
