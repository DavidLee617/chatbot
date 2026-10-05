package com.lee.psyqa.ingest;

/** data/raw/books.json 里的一条。 */
public record BookMeta(String file, String title, String author, String sourceUrl) {}
