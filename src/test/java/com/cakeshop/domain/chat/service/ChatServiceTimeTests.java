package com.cakeshop.domain.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.cakeshop.domain.chat.entity.ChatRoom;
import com.cakeshop.domain.chat.mapper.ChatMapper;
import com.cakeshop.domain.member.service.MemberChatQueryService;
import com.cakeshop.domain.order.service.OrderChatQueryService;
import com.cakeshop.domain.product.service.ProductChatQueryService;
import com.cakeshop.global.infra.FileStorageClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatServiceTimeTests {
    @Test
    void createsRoomUsingSeoulClockRatherThanServerDefaultTimezone() {
        var mapper = mock(ChatMapper.class);
        var members = mock(MemberChatQueryService.class);
        var clock = Clock.fixed(Instant.parse("2026-10-28T15:05:00Z"), ZoneId.of("Asia/Seoul"));
        var service = new ChatService(mapper, clock, members, mock(OrderChatQueryService.class),
                mock(FileStorageClient.class), mock(ChatImageValidator.class),
                mock(ProductChatQueryService.class), mock(ChatRoomTxHelper.class));
        when(members.existsCustomer(1L)).thenReturn(true);
        service.getOrMakeChatRoom(1L);
        var saved = ArgumentCaptor.forClass(ChatRoom.class);
        verify(mapper).insertChatRoom(saved.capture());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(LocalDateTime.parse("2026-10-29T00:05:00"));
    }
}
