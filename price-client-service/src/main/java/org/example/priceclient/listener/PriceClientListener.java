package org.example.priceclient.listener;

import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.example.priceclient.config.RabbitMQConfig;
import org.example.reservationprice.PriceRequest;
import org.example.reservationprice.PriceResponse;
import org.example.reservationprice.ReservationPriceServiceGrpc;
import org.example.restaurant.events.ReservationCreatedEvent;
import org.example.restaurant.events.ReservationPricedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class PriceClientListener {

    private static final Logger log = LoggerFactory.getLogger(PriceClientListener.class);

    private final RabbitTemplate rabbitTemplate;

    @GrpcClient("reservation-price-service")
    private ReservationPriceServiceGrpc.ReservationPriceServiceBlockingStub priceServiceStub;

    public PriceClientListener(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Слушает события создания бронирования из restaurant-exchange
     * Вызывает gRPC Price Server для расчета цены
     * Публикует ReservationPricedEvent в Fanout Exchange
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "q.price-client.reservation.created", durable = "true"),
                    exchange = @Exchange(name = RabbitMQConfig.EXCHANGE_NAME, type = "topic", durable = "true"),
                    key = "reservation.created"
            )
    )
    public void handleReservationCreated(ReservationCreatedEvent event) {
        log.info("Price Client: Получено событие создания бронирования: reservationId={}, tableNumber={}", 
                event.reservationId(), event.tableNumber());

        try {
            // Вызываем gRPC Price Server
            PriceRequest priceRequest = PriceRequest.newBuilder()
                    .setReservationId(event.reservationId())
                    .setNumberOfGuests(event.numberOfGuests())
                    .setTableType(event.tableType() != null && !event.tableType().isEmpty() ? event.tableType() : "STANDARD")
                    .setDurationHours(2) // Фиксированная продолжительность
                    .build();

            PriceResponse priceResponse = priceServiceStub.calculatePrice(priceRequest);

            log.info("Price Client: Цена рассчитана для reservationId={}, price={}, verdict={}", 
                    event.reservationId(), priceResponse.getPrice(), priceResponse.getVerdict());

            // Публикуем событие с рассчитанной ценой
            ReservationPricedEvent pricedEvent = new ReservationPricedEvent(
                    event.reservationId(),
                    event.clientId(),
                    event.tableId(),
                    priceResponse.getPrice(),
                    priceResponse.getVerdict()
            );

            // Публикуем в Topic Exchange для demo (main service) - он будет обновлять цену в БД
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY_RESERVATION_PRICED,
                    pricedEvent
            );
            
            // Также публикуем в Fanout Exchange для других сервисов (notification, audit)
            rabbitTemplate.convertAndSend(RabbitMQConfig.FANOUT_EXCHANGE, "", pricedEvent);
            
            log.info("Price Client: Событие ReservationPricedEvent опубликовано для reservationId={}", 
                    event.reservationId());

        } catch (StatusRuntimeException e) {
            log.error("Price Client: Ошибка при вызове gRPC Price Server для reservationId={}: {}", 
                    event.reservationId(), e.getStatus(), e);
            
            // Можно отправить событие об ошибке или оставить без цены
            // В зависимости от требований бизнес-логики
        }
    }
}

