package com.example.agentic.urlshortener.service;

import com.example.agentic.config.ShortenerProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Base62 codes from a CSPRNG: 62^7 (about 3.5e12) possibilities; collisions are still handled by the DB constraint. */
@Component
public class ShortCodeGenerator {
    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();
    private final int length;

    public ShortCodeGenerator(ShortenerProperties props) {
        this.length = props.codeLength();
    }

    public String next() {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}