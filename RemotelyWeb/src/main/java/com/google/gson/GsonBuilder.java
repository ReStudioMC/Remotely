package com.google.gson;

public final class GsonBuilder {
    public GsonBuilder setPrettyPrinting() {
        return this;
    }

    public GsonBuilder disableHtmlEscaping() {
        return this;
    }

    public Gson create() {
        return new Gson();
    }
}
