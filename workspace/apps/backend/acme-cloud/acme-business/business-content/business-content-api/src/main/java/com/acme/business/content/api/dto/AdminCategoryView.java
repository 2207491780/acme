package com.acme.business.content.api.dto;

/**
 * B 端分类项（openapi AdminCategoryView；与 AppCategoryView 分离命名，11.5.1）。
 */
public record AdminCategoryView(String code, String name) {
}
