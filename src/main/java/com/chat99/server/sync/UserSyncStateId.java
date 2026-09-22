package com.chat99.server.sync;

import com.chat99.server.sync.SyncEnums.SyncType;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserSyncStateId implements Serializable {

    private String userId;
    private SyncType syncType;
}
