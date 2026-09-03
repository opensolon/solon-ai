/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package org.noear.solon.ai.ui.agui.event;

import java.util.List;

/**
 * 一个 RFC 6902 JSON Patch 操作。
 *
 * <p>仅 {@code op} 和 {@code path} 是所有操作共有的字段；{@code value}、
 * {@code from} 按操作类型使用。使用强类型对象可以避免调用方只能把 patch
 * 塞进 rawEvent，同时保留 AG-UI 要求的 JSON 结构。</p>
 */
public class JsonPatchOperation {
    private String op;
    private String path;
    private Object value;
    private String from;

    public JsonPatchOperation() {
    }

    public JsonPatchOperation(String op, String path) {
        this.op = op;
        this.path = path;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public static JsonPatchOperation add(String path, Object value) {
        JsonPatchOperation operation = new JsonPatchOperation("add", path);
        operation.setValue(value);
        return operation;
    }

    public static JsonPatchOperation replace(String path, Object value) {
        JsonPatchOperation operation = new JsonPatchOperation("replace", path);
        operation.setValue(value);
        return operation;
    }

    public static JsonPatchOperation remove(String path) {
        return new JsonPatchOperation("remove", path);
    }

    public static JsonPatchOperation move(String from, String path) {
        JsonPatchOperation operation = new JsonPatchOperation("move", path);
        operation.setFrom(from);
        return operation;
    }
}
