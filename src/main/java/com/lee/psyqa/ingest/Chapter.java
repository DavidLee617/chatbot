package com.lee.psyqa.ingest;

import java.util.List;

/** 一章：章名 + 清洗后的段落（每段一行）。 */
public record Chapter(String title, List<String> paragraphs) {}
