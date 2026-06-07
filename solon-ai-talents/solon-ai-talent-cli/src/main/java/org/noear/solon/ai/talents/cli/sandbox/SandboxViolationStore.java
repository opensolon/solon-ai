/*
 * Copyright 2017-2025 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.ai.talents.cli.sandbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 沙盒违规事件存储
 *
 * <p>在 macOS 上通过 log stream 监控 Seatbelt 违规事件，
 * 将 OS 级拦截通知到应用层。</p>
 *
 * @author noear
 * @since 3.9.1
 */
public class SandboxViolationStore {
    private final CopyOnWriteArrayList<ViolationEvent> violations = new CopyOnWriteArrayList<>();
    private final List<Consumer<List<ViolationEvent>>> listeners = new ArrayList<>();
    private static final int MAX_SIZE = 100;

    /**
     * 违规事件记录
     */
    public static class ViolationEvent {
        private final String message;
        private final String command;
        private final Instant timestamp;

        public ViolationEvent(String message, String command, Instant timestamp) {
            this.message = message;
            this.command = command;
            this.timestamp = timestamp;
        }

        public String getMessage() { return message; }
        public String getCommand() { return command; }
        public Instant getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return "[" + timestamp + "] " + message;
        }
    }

    public void addViolation(ViolationEvent event) {
        violations.add(event);
        if (violations.size() > MAX_SIZE) {
            violations.remove(0);
        }
        notifyListeners();
    }

    public List<ViolationEvent> getViolations() {
        return new ArrayList<>(violations);
    }

    public List<ViolationEvent> getViolationsForCommand(String command) {
        return violations.stream()
                .filter(v -> command != null && command.equals(v.getCommand()))
                .collect(java.util.stream.Collectors.toList());
    }

    public void clear() {
        violations.clear();
    }

    public int size() {
        return violations.size();
    }

    public void subscribe(Consumer<List<ViolationEvent>> listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    private void notifyListeners() {
        List<ViolationEvent> snapshot = getViolations();
        synchronized (listeners) {
            for (Consumer<List<ViolationEvent>> l : listeners) {
                try {
                    l.accept(snapshot);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
