package com.uyirhealthapp.payment_service.notify;

import com.uyirhealthapp.payment_service.config.PaymentProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookDispatcherTest {

    @Test
    void backoffDoublesUpToTheCap() {
        PaymentProperties props = mock(PaymentProperties.class);
        when(props.delivery()).thenReturn(new PaymentProperties.Delivery(12, Duration.ofSeconds(10),
                Duration.ofHours(1), 20, Duration.ofSeconds(3), Duration.ofSeconds(10)));
        WebhookDispatcher dispatcher = new WebhookDispatcher(props, null, null, null, Clock.systemUTC());

        assertThat(dispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(dispatcher.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(dispatcher.backoff(5)).isEqualTo(Duration.ofSeconds(160));
        assertThat(dispatcher.backoff(10)).isEqualTo(Duration.ofHours(1));
        assertThat(dispatcher.backoff(100)).isEqualTo(Duration.ofHours(1));
    }
}
