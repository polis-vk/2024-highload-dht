package ru.vk.itmo.test.smirnovdmitrii.dao.state;

import ru.vk.itmo.test.smirnovdmitrii.dao.inmemory.Memtable;
import ru.vk.itmo.test.smirnovdmitrii.dao.outofmemory.sstable.SSTable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class StateService {
    private final AtomicReference<State> stateAtomicReference;

    public StateService() {
        stateAtomicReference = new AtomicReference<>(new State(List.of(), List.of()));
    }

    public List<Memtable> memtables() {
        return stateAtomicReference.get().memtables();
    }

    public List<SSTable> ssTables() {
        return stateAtomicReference.get().ssTables();
    }

    public State state() {
        return stateAtomicReference.get();
    }

    public void addMemtable(final Memtable memtable) {
        stateOperation(state -> {
            final List<Memtable> memtables = new ArrayList<>();
            memtables.add(memtable);
            memtables.addAll(state.memtables());
            return new State(memtables, state.ssTables());
        });
    }

    public void removeMemtable(final Memtable memtable) {
        stateOperation(state -> {
            final List<Memtable> memtables = new ArrayList<>(state.memtables());
            memtables.remove(memtable);
            return new State(memtables, state.ssTables());
        });
    }

    public void addSSTable(final SSTable ssTable) {
        stateOperation(state -> {
            final List<SSTable> ssTables = new ArrayList<>();
            ssTables.add(ssTable);
            ssTables.addAll(state.ssTables());
            return new State(state.memtables(), ssTables);
        });
    }

    public void compact(final List<SSTable> compactedSSTables, final SSTable compaction) {
        final long firstPriority = compactedSSTables.getFirst().priority();
        final long lastPriority = compactedSSTables.getLast().priority();
        stateOperation(state -> {
            int i = 0;
            final List<SSTable> ssTables = state.ssTables();
            final List<SSTable> newSSTables = new ArrayList<>();
            while (i < ssTables.size()) {
                final SSTable current = ssTables.get(i);
                if (current.priority() <= firstPriority) {
                    break;
                }
                newSSTables.add(current);
                i++;
            }
            while (i < ssTables.size()) {
                final SSTable current = ssTables.get(i);
                if (current.priority() < lastPriority) {
                    break;
                }
                i++;
            }
            if (compaction != null) {
                newSSTables.add(compaction);
            }
            newSSTables.addAll(ssTables.subList(i, ssTables.size()));
            return new State(state.memtables(), newSSTables);
        });
    }

    private void stateOperation(final Function<State, State> function) {
        while (true) {
            final State state = stateAtomicReference.get();
            final State newState = function.apply(state);
            if (stateAtomicReference.compareAndSet(state, newState)) {
                return;
            }
        }
    }

    public State currentState() {
        return stateAtomicReference.get();
    }

    public void setSSTables(final List<SSTable> ssTables) {
        stateOperation(state -> new State(state.memtables(), ssTables));
    }

    public void setMemtables(final List<Memtable> memtables) {
        stateOperation(state -> new State(memtables, state.ssTables()));
    }
}
