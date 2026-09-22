package com.chat99.sangong.service;

import com.chat99.sangong.domain.SangongRoundDraw;
import org.springframework.stereotype.Service;

/** 庄闲比牌：与 PHP CompareService 完全一致（庄闲相同庄大）。 */
@Service
public class CompareService {

    public boolean bankerWins(SangongRoundDraw banker, SangongRoundDraw player) {
        if (banker.getAmountHundredths() == player.getAmountHundredths()) {
            return true;
        }
        int bankerRank = handRank(banker.getHandType());
        int playerRank = handRank(player.getHandType());
        if (bankerRank != playerRank) {
            return bankerRank > playerRank;
        }
        return bankerWinsSameType(banker, player);
    }

    private int handRank(String handType) {
        if (handType == null) return 0;
        return switch (handType) {
            case SangongRoundDraw.HAND_ONE_YUAN -> 4;
            case SangongRoundDraw.HAND_PAIR -> 3;
            case SangongRoundDraw.HAND_POINT -> 2;
            case SangongRoundDraw.HAND_NIUNIU -> 1;
            default -> 0;
        };
    }

    private boolean bankerWinsSameType(SangongRoundDraw banker, SangongRoundDraw player) {
        String type = banker.getHandType() == null ? "" : banker.getHandType();
        switch (type) {
            case SangongRoundDraw.HAND_ONE_YUAN:
            case SangongRoundDraw.HAND_NIUNIU:
                return true;
            case SangongRoundDraw.HAND_PAIR: {
                int bankerPair = banker.getPairValue() == null ? 0 : banker.getPairValue();
                int playerPair = player.getPairValue() == null ? 0 : player.getPairValue();
                if (bankerPair != playerPair) {
                    return bankerPair > playerPair;
                }
                return true;
            }
            case SangongRoundDraw.HAND_POINT: {
                int bankerPoint = banker.getPointValue() == null ? 0 : banker.getPointValue();
                int playerPoint = player.getPointValue() == null ? 0 : player.getPointValue();
                if (bankerPoint != playerPoint) {
                    return bankerPoint > playerPoint;
                }
                int bankerCompare = banker.getCompareValue();
                int playerCompare = player.getCompareValue();
                if (bankerCompare != playerCompare) {
                    return bankerCompare > playerCompare;
                }
                return true;
            }
            default:
                return true;
        }
    }
}
