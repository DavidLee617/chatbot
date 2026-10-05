package com.lee.psyqa.model;

/** 一条引用来源，index 对应回答里的 [n]。 */
public record SourceRef(int index, String book, String author, String chapter, double score, String snippet) {}
