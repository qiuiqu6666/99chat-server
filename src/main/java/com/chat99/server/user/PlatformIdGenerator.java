package com.chat99.server.user;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class PlatformIdGenerator {

    private final PlatformIdProperties props;
    private final UserRepository userRepository;
    private final SecureRandom rng = new SecureRandom();
    private final char[] letterSubset;

    public PlatformIdGenerator(PlatformIdProperties props, UserRepository userRepository) {
        this.props = props;
        this.userRepository = userRepository;
        char[] full = props.alphabet().toCharArray();
        char[] tmp = new char[full.length];
        int n = 0;
        for (char c : full) {
            if (Character.isLetter(c)) tmp[n++] = c;
        }
        if (n == 0) {
            throw new IllegalStateException("alphabet 必须包含至少一个字母");
        }
        char[] subset = new char[n];
        System.arraycopy(tmp, 0, subset, 0, n);
        this.letterSubset = subset;
    }

    public String allocate() {
        char[] alphabet = props.alphabet().toCharArray();
        for (int i = 0; i < props.maxRetry(); i++) {
            String candidate = randomString(alphabet);
            if (!userRepository.existsByUserId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("platformId allocation exhausted");
    }

    private String randomString(char[] alphabet) {
        char[] out = new char[props.length()];
        out[0] = letterSubset[rng.nextInt(letterSubset.length)];
        for (int i = 1; i < out.length; i++) {
            out[i] = alphabet[rng.nextInt(alphabet.length)];
        }
        return new String(out);
    }
}
