package com.example.agentic.urlshortener.repository;

import com.example.agentic.urlshortener.entity.UrlClick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UrlClickRepository extends JpaRepository<UrlClick, Long> {

    @Query("select c.referrer, count(c) from UrlClick c where c.shortUrl.id = :urlId "
            + "group by c.referrer order by count(c) desc")
    List<Object[]> referrerStats(@Param("urlId") Long urlId);

    @Query("select cast(c.accessedAt as LocalDate), count(c) from UrlClick c where c.shortUrl.id = :urlId "
            + "group by cast(c.accessedAt as LocalDate) order by cast(c.accessedAt as LocalDate)")
    List<Object[]> dailyStats(@Param("urlId") Long urlId);

    long countByShortUrlId(Long urlId);
}