package kaptainwutax.seedcrackerX.finder;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class FinderControl {

    private final Map<Finder.Type, ConcurrentLinkedQueue<Finder>> activeFinders = new ConcurrentHashMap<>();

    public synchronized void deleteFinders() {
        this.activeFinders.clear();
    }

    public synchronized List<Finder> getActiveFinders() {
        this.activeFinders.values().forEach(finders -> {
            finders.removeIf(Finder::isUseless);
        });

        return this.activeFinders.values().stream()
                .flatMap(Queue::stream).collect(Collectors.toList());
    }

    public synchronized void addFinder(Finder.Type type, Finder finder) {
        if (finder.isUseless()) return;

        this.activeFinders.computeIfAbsent(type, key -> new ConcurrentLinkedQueue<>()).add(finder);
    }
}
