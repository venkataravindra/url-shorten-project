package com.example.agentic.urlshortener.service;

import com.example.agentic.urlshortener.entity.ShortUrl;
import com.example.agentic.urlshortener.repository.ShortUrlRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Own bean so each insert attempt gets its own transaction; a unique violation then rolls back only that attempt. */
@Component
public class ShortUrlWriter {
    private final ShortUrlRepository repository;

    public ShortUrlWriter(ShortUrlRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ShortUrl insert(ShortUrl url) {
        return repository.saveAndFlush(url);
    }
}