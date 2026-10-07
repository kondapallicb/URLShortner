package com.kondapallicb.urlshortener.infrastructure;

import com.kondapallicb.urlshortener.domain.SlugGenerator;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class Base62SlugGenerator implements SlugGenerator {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int SLUG_LENGTH = 7;

    private final SecureRandom random = new SecureRandom();

    @Override
    public String generate() {
        StringBuilder builder = new StringBuilder(SLUG_LENGTH);
        for (int index = 0; index < SLUG_LENGTH; index++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
