package com.chat99.server.wallet;

import java.math.BigInteger;
import java.security.MessageDigest;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

public final class TronTransactionSigner {

    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
        CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
    private static final BigInteger HALF_CURVE_ORDER = CURVE.getN().shiftRight(1);

    private TronTransactionSigner() {}

    public static String signRawDataHex(String rawDataHex, String privateKeyHex) {
        byte[] raw = Hex.decode(rawDataHex);
        byte[] hash = sha256(raw);
        BigInteger d = new BigInteger(privateKeyHex, 16);
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, new ECPrivateKeyParameters(d, DOMAIN));
        BigInteger[] sig = signer.generateSignature(hash);
        BigInteger r = sig[0];
        BigInteger s = sig[1];
        // Canonical low-S
        if (s.compareTo(HALF_CURVE_ORDER) > 0) {
            s = CURVE.getN().subtract(s);
        }
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECPoint expectedPub = spec.getG().multiply(d).normalize();
        int recId = -1;
        for (int i = 0; i < 4; i++) {
            ECPoint recovered = recoverFromSignature(i, r, s, hash);
            if (recovered != null && recovered.equals(expectedPub)) {
                recId = i;
                break;
            }
        }
        if (recId < 0) {
            throw new IllegalStateException("Unable to find ECDSA recovery id for TRON signature");
        }
        byte[] signature = new byte[65];
        System.arraycopy(toFixedLength(r, 32), 0, signature, 0, 32);
        System.arraycopy(toFixedLength(s, 32), 0, signature, 32, 32);
        signature[64] = (byte) recId;
        return Hex.toHexString(signature);
    }

    public static String privateKeyToBase58Address(String privateKeyHex) {
        BigInteger d = new BigInteger(privateKeyHex, 16);
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECPoint q = spec.getG().multiply(d).normalize();
        return TronAddressUtils.fromPublicKeyUncompressed(q.getEncoded(false));
    }

    /**
     * Recover public point from ECDSA signature (same approach as Ethereum/TRON clients).
     * Do NOT use public-key Y parity as recovery id — that causes SIGERROR (wrong recovered address).
     */
    static ECPoint recoverFromSignature(int recId, BigInteger r, BigInteger s, byte[] messageHash) {
        BigInteger n = CURVE.getN();
        BigInteger i = BigInteger.valueOf((long) recId / 2);
        BigInteger x = r.add(i.multiply(n));
        if (x.compareTo(CURVE.getCurve().getField().getCharacteristic()) >= 0) {
            return null;
        }
        ECPoint R = decompressKey(x, (recId & 1) == 1);
        if (!R.multiply(n).isInfinity()) {
            return null;
        }
        BigInteger e = new BigInteger(1, messageHash);
        BigInteger eInv = BigInteger.ZERO.subtract(e).mod(n);
        BigInteger rInv = r.modInverse(n);
        BigInteger srInv = rInv.multiply(s).mod(n);
        BigInteger eInvrInv = rInv.multiply(eInv).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(CURVE.getG(), eInvrInv, R, srInv).normalize();
    }

    private static ECPoint decompressKey(BigInteger xBN, boolean yBit) {
        byte[] compEnc = new byte[33];
        compEnc[0] = (byte) (yBit ? 0x03 : 0x02);
        byte[] xBytes = toFixedLength(xBN, 32);
        System.arraycopy(xBytes, 0, compEnc, 1, 32);
        return CURVE.getCurve().decodePoint(compEnc);
    }

    private static byte[] toFixedLength(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        if (raw.length > len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
