package io.translab.tantor.server.service;

import lombok.Getter;

import java.util.UUID;

@Getter
public class TopicRecreationException extends RuntimeException {

    public enum TopicPresence {
        ABSENT,
        PRESENT,
        UNKNOWN,
        UNCERTAIN
    }

    private final UUID operationId;
    private final String stage;
    private final TopicPresence presence;

    public TopicRecreationException(UUID operationId, String stage, TopicPresence presence, String message) {
        super(message);
        this.operationId = operationId;
        this.stage = stage;
        this.presence = presence;
    }

    public TopicRecreationException(UUID operationId, String stage, TopicPresence presence, String message, Throwable cause) {
        super(message, cause);
        this.operationId = operationId;
        this.stage = stage;
        this.presence = presence;
    }
}
