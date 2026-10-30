package com.acme.business.content.api.dto;

/**
 * C 端分类项（openapi AppCategoryView；不含「全部」占位，需求 7.1）。
 */
public record AppCategoryView(String code, String name) {
}
