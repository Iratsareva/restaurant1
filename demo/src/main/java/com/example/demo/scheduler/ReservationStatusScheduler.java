package com.example.demo.scheduler;

import com.example.demo.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Планировщик для автоматической отмены просроченных бронирований
 * Запускается каждые 6 часов и проверяет бронирования со статусом PENDING,
 * которые не были подтверждены за 24 часа
 */
@Component
public class ReservationStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationStatusScheduler.class);
    private final ReservationService reservationService;

    public ReservationStatusScheduler(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Запускается каждые 6 часов (в 00:00, 06:00, 12:00, 18:00)
     * Проверяет и отменяет просроченные бронирования
     */
    @Scheduled(cron = "0 0 */6 * * *") // Каждые 6 часов
    public void cancelExpiredReservations() {
        log.info("Запуск проверки просроченных бронирований...");
        try {
            int cancelledCount = reservationService.cancelExpiredPendingReservations();
            if (cancelledCount == 0) {
                log.debug("Просроченных бронирований не найдено");
            }
        } catch (Exception e) {
            log.error("Ошибка при выполнении задачи отмены просроченных бронирований", e);
        }
    }
}


