package com.baggute.cheatclient.event;

import com.baggute.cheatclient.UtilityClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class EventBus {
    private final Map<Class<?>, List<Consumer<?>>> listeners = new HashMap<>();

    public <T> void subscribe(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, ignored -> new ArrayList<>()).add(listener);
    }

    public <T> void unsubscribe(Class<T> eventType, Consumer<T> listener) {
        List<Consumer<?>> eventListeners = listeners.get(eventType);
        if (eventListeners == null) {
            return;
        }
        eventListeners.remove(listener);
        if (eventListeners.isEmpty()) {
            listeners.remove(eventType);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void post(T event) {
        List<Consumer<?>> eventListeners = listeners.get(event.getClass());
        if (eventListeners == null) {
            return;
        }

        for (Consumer<?> listener : new ArrayList<>(eventListeners)) {
            try {
                ((Consumer<T>) listener).accept(event);
            } catch (Exception exception) {
                UtilityClient.LOGGER.error("Event listener failed for {}", event.getClass().getName(), exception);
            }
        }
    }
}