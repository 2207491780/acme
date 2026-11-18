package com.acme.platform.infra.application;

import com.acme.framework.excel.support.ExcelField;
import com.acme.framework.excel.support.ExcelSheet;

/**
 * Excel 通用范式 demo 行（REQ-20261114-071607 §API：DemoRow=名称/数量/备注，不落业务表）：
 * 映射声明归业务侧（BR-010 framework 无业务字段映射），列序 名称 → 数量 → 备注。
 */
@ExcelSheet("demo")
public class DemoRow {

    @ExcelField(value = "名称", order = 1)
    private String name;

    @ExcelField(value = "数量", order = 2)
    private Integer quantity;

    @ExcelField(value = "备注", order = 3)
    private String remark;

    public DemoRow() {
    }

    public DemoRow(String name, Integer quantity, String remark) {
        this.name = name;
        this.quantity = quantity;
        this.remark = remark;
    }

    public String getName() {
        return name;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getRemark() {
        return remark;
    }
}
