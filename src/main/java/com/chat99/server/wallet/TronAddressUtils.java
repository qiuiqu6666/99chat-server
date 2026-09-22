package com.chat99.server.wallet;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.bitcoinj.base.Base58;
import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.encoders.Hex;

public final class TronAddressUtils {

    private static final byte MAINNET_PREFIX = 0x41;

    private TronAddressUtils() {}

    public static String fromPublicKeyUncompressed(byte[] pub65) {
        if (pub65.length != 65 || pub65[0] != 0x04) {
            throw new IllegalArgumentException("expected uncompressed 65-byte public key");
        }
        Keccak.Digest256 keccak = new Keccak.Digest256();
        keccak.update(pub65, 1, 64);
        byte[] hash = keccak.digest();
        byte[] addr20 = Arrays.copyOfRange(hash, 12, 32);
        byte[] payload = new byte[21];
        payload[0] = MAINNET_PREFIX;
        System.arraycopy(addr20, 0, payload, 1, 20);
        return base58CheckEncode(payload);
    }

    public static String base58CheckEncode(byte[] payload) {
        byte[] hash0 = sha256(payload);
        byte[] hash1 = sha256(hash0);
        byte[] checksummed = new byte[payload.length + 4];
        System.arraycopy(payload, 0, checksummed, 0, payload.length);
        System.arraycopy(hash1, 0, checksummed, payload.length, 4);
        return Base58.encode(checksummed);
    }

    public static byte[] decodeBase58ToBytes(String input) {
        try {
            byte[] decoded = Base58.decode(input);
            if (decoded.length < 5) {
                throw new IllegalArgumentException("invalid base58");
            }
            byte[] data = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
            byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
            byte[] hash0 = sha256(data);
            byte[] hash1 = sha256(hash0);
            for (int i = 0; i < 4; i++) {
                if (checksum[i] != hash1[i]) {
                    throw new IllegalArgumentException("checksum mismatch");
                }
            }
            return data;
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("invalid tron address", e);
        }
    }

    public static boolean isValidTronAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            byte[] raw = decodeBase58ToBytes(address);
            return raw.length == 21 && raw[0] == MAINNET_PREFIX;
        } catch (Exception e) {
            return false;
        }
    }

    /** 21-byte payload (0x41 + 20) -> hex address for contract params (41 + 40 hex, no 0x). */
    public static String toHexAddressParameter(String base58Address) {
        byte[] raw = decodeBase58ToBytes(base58Address);
        return Hex.toHexString(raw);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
