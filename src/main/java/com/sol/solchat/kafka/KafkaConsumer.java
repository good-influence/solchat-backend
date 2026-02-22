package com.sol.solchat.kafka;

import com.sol.solchat.dto.ChatMessage;
import com.sol.solchat.service.ChatMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaConsumer {

    private final ChatMessageService chatMessageService;

    @KafkaListener(topics = "${chat.kafka.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ChatMessage message) {
        chatMessageService.deliverToParticipants(message);
    }
}
