package com.example.demo.listeners;

import com.example.demo.config.RabbitMQConfig;
import com.example.demo.repository.ReservationRepository;
import com.example.demo.models.Reservation;
import org.example.restaurant.events.ReservationPricedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReservationPriceUpdateListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationPriceUpdateListener.class);
    private final ReservationRepository reservationRepository;

    public ReservationPriceUpdateListener(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    /**
     * Слушает события расчета цены из restaurant-exchange (Topic, не Fanout)
     * Обновляет цену в базе данных для соответствующей резервации
     * Это происходит асинхронно после того, как Price Client рассчитал цену через gRPC
     */
    @RabbitListener(
            bindings = @QueueBinding(
                    value = @Queue(name = "q.demo.reservation.price.update", durable = "true"),
                    exchange = @Exchange(name = RabbitMQConfig.EXCHANGE_NAME, type = "topic", durable = "true"),
                    key = RabbitMQConfig.ROUTING_KEY_RESERVATION_PRICED  // Topic routing key для обновления цены в БД
            )
    )
    public void handleReservationPriced(ReservationPricedEvent event) {
        log.info("Demo service: Получено событие расчета цены: reservationId={}, price={}, verdict={}", 
                event.reservationId(), event.price(), event.verdict());

        try {
            Reservation reservation = reservationRepository.findById(event.reservationId());
            if (reservation != null) {
                reservation.setPrice(event.price());
                reservationRepository.update(reservation);
                log.info("Demo service: Цена обновлена для reservationId={}, новая цена={}", 
                        event.reservationId(), event.price());
            } else {
                log.warn("Demo service: Резервация не найдена для обновления цены: reservationId={}", 
                        event.reservationId());
            }
        } catch (Exception e) {
            log.error("Demo service: Ошибка при обновлении цены для reservationId={}", 
                    event.reservationId(), e);
        }
    }
}

