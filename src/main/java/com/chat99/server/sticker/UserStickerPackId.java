package com.chat99.server.sticker;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class UserStickerPackId implements Serializable {

    private String userId;
    private String packId;
}
