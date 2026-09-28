package com.chat99.server.im;

/** The transport has not started. Retrying this failure cannot duplicate a message. */
public final class WalletCardNotSubmittedException extends RuntimeException {
    public WalletCardNotSubmittedException(String reason) { super(reason); }
}
