package com.matrixcode.runtime.application;

public class InMemoryRuntimeNotificationStore implements RuntimeNotificationStore {

    @Override
    public RuntimeNotificationSnapshot load() {
        return RuntimeNotificationSnapshot.empty();
    }

    @Override
    public void save(RuntimeNotificationSnapshot snapshot) {
    }
}
